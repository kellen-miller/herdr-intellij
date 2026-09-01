package dev.herdr.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
internal class HerdrController private constructor(
    private val connection: HerdrConnection,
    private val settings: HerdrSettings?,
) : Disposable {
    constructor() : this(defaults())

    internal constructor(connection: HerdrConnection) : this(connection, null)

    private constructor(defaults: Defaults) : this(defaults.connection, defaults.settings)

    private val disposed = AtomicBoolean(false)
    private val outputPollPending = AtomicBoolean(false)
    private val requestSequence = AtomicLong()
    private val lifecycle = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "herdr-controller").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "herdr-controller-timer").apply { isDaemon = true }
    }
    private val state = AtomicReference<HerdrUiState>(
        HerdrUiState.NoServer(connection.socketTarget.toString()),
    )
    private val listeners = CopyOnWriteArrayList<(HerdrUiState) -> Unit>()
    private var subscription: HerdrSubscription? = null
    private var reconcileTask: ScheduledFuture<*>? = null
    private var outputTask: ScheduledFuture<*>? = null
    private var outputPollingPaneId: String? = null

    fun currentState(): HerdrUiState = state.get()

    fun addStateListener(listener: (HerdrUiState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state.get())
        return AutoCloseable { listeners -= listener }
    }

    fun connect(): CompletableFuture<HerdrUiState> = submit { connectInternal() }

    fun retry(): CompletableFuture<HerdrUiState> = connect()

    fun startHerdr(): CompletableFuture<HerdrUiState> = submit {
        cancelLiveWork()
        publish(HerdrUiState.Starting(connection.socketTarget.toString()))
        try {
            connection.startHerdr(settings?.state?.executableOverride?.takeIf(String::isNotBlank))
        } catch (failure: HerdrTransportException) {
            return@submit publish(HerdrUiState.NoServer(
                connection.socketTarget.toString(),
                failure.message ?: "Herdr could not be started",
            ))
        }

        var lastFailure: Throwable? = null
        repeat(10) { attempt ->
            if (attempt > 0) {
                Thread.sleep((50L shl attempt.coerceAtMost(3)).coerceAtMost(500L))
            }
            publish(HerdrUiState.Connecting(connection.socketTarget.toString()))
            try {
                return@submit establishLiveConnection()
            } catch (failure: HerdrTransportException) {
                lastFailure = failure
            } catch (mismatch: HerdrProtocolMismatch) {
                return@submit publish(HerdrUiState.Incompatible(
                    connection.socketTarget.toString(),
                    mismatch.expected,
                    mismatch.actual,
                    mismatch.message ?: "Herdr protocol mismatch",
                ))
            } catch (failure: HerdrProtocolException) {
                return@submit publish(HerdrUiState.Incompatible(
                    connection.socketTarget.toString(),
                    HERDR_PROTOCOL_VERSION,
                    null,
                    failure.message ?: "Malformed Herdr protocol data",
                ))
            } catch (failure: BootstrapException) {
                return@submit publish(
                    HerdrUiState.Connecting(connection.socketTarget.toString(), failure.message),
                )
            }
        }
        publish(
            HerdrUiState.Connecting(
                connection.socketTarget.toString(),
                lastFailure?.message ?: "Herdr did not begin answering ping",
            ),
        )
    }

    fun reconcileNow(): CompletableFuture<HerdrUiState> = submit { reconcileInternal() }

    fun selectAgent(agentName: String?): CompletableFuture<HerdrUiState> = submit {
        val live = (state.get() as? HerdrUiState.Live)?.view ?: return@submit state.get()
        publish(HerdrUiState.Live(HerdrModel.select(live, agentName)))
    }

    fun pollSelectedNow(): CompletableFuture<HerdrUiState> = submit { pollSelectedInternal() }

    fun mutate(request: HerdrRequest): CompletableFuture<MutationOutcome> = submit {
        val live = (state.get() as? HerdrUiState.Live)?.view
        if (live == null || live.stale) {
            return@submit MutationOutcome.DefinitelyNotSent("Herdr is not live")
        }
        val action = when (request.method) {
            "workspace.create", "tab.create" -> HerdrAction.ALLOCATE
            "worktree.create", "worktree.open" -> HerdrAction.WORKTREE
            "agent.start" -> HerdrAction.START_AGENT
            "agent.prompt" -> HerdrAction.PROMPT
            "agent.send_keys" -> HerdrAction.RESPOND
            "agent.focus" -> HerdrAction.FOCUS
            else -> error("mutation request escaped its closed allowlist: ${request.method}")
        }
        val outcome = connection.mutate(request)
        when (outcome) {
            is MutationOutcome.Applied -> Unit
            is MutationOutcome.DefinitelyNotSent -> publish(HerdrUiState.Live(live.copy(
                actionErrors = live.actionErrors + ActionError(
                    action,
                    outcome.diagnostic,
                    false,
                ),
            )))
            is MutationOutcome.Rejected -> publish(HerdrUiState.Live(live.copy(
                actionErrors = live.actionErrors + ActionError(
                    action,
                    outcome.error.message,
                    false,
                ),
            )))
            is MutationOutcome.UnknownAfterWrite -> publish(HerdrUiState.Live(live.copy(
                actionErrors = live.actionErrors + ActionError(
                    action,
                    outcome.diagnostic,
                    true,
                ),
            )))
        }
        outcome
    }

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return
        }
        cancelLiveWork()
        connection.close()
        lifecycle.shutdownNow()
        timer.shutdownNow()
        listeners.clear()
    }

    private fun connectInternal(): HerdrUiState {
        cancelLiveWork()
        publish(HerdrUiState.Connecting(connection.socketTarget.toString()))
        return try {
            establishLiveConnection()
        } catch (mismatch: HerdrProtocolMismatch) {
            publish(HerdrUiState.Incompatible(
                connection.socketTarget.toString(),
                mismatch.expected,
                mismatch.actual,
                mismatch.message ?: "Herdr protocol mismatch",
            ))
        } catch (_: HerdrTransportException) {
            publish(HerdrUiState.NoServer(connection.socketTarget.toString()))
        } catch (failure: BootstrapException) {
            publish(HerdrUiState.Connecting(connection.socketTarget.toString(), failure.message))
        } catch (failure: HerdrProtocolException) {
            publish(HerdrUiState.Incompatible(
                connection.socketTarget.toString(),
                HERDR_PROTOCOL_VERSION,
                null,
                failure.message ?: "Malformed Herdr protocol data",
            ))
        }
    }

    private fun establishLiveConnection(): HerdrUiState {
        connection.ping(nextId("ping"))
        val capabilities = connection.capabilities(nextId("capabilities"))
        return bootstrap(capabilities)
    }

    private fun bootstrap(capabilities: List<AgentCapability>): HerdrUiState {
        val topologyAttempt = connection.subscribe(
            HerdrRequest.topologySubscription(nextId("topology")),
            onEvent = { },
            onDisconnect = { diagnostic -> queueDisconnect(diagnostic) },
        )
        val topologySubscription = when (topologyAttempt) {
            is SubscriptionAttempt.Started -> topologyAttempt.subscription
            is SubscriptionAttempt.Rejected -> throw BootstrapException(topologyAttempt.error.message)
        }

        try {
            repeat(3) {
                val firstSnapshot = connection.snapshot(nextId("snapshot"))
                val paneIds = firstSnapshot.panes.mapTo(mutableSetOf(), HerdrPane::paneId)
                val bufferedEvents = mutableListOf<HerdrEvent>()
                val buffering = AtomicBoolean(true)
                val combinedAttempt = connection.subscribe(
                    HerdrRequest.combinedSubscription(nextId("subscription"), paneIds),
                    onEvent = { event ->
                        var dispatch = false
                        synchronized(bufferedEvents) {
                            if (buffering.get()) bufferedEvents += event else dispatch = true
                        }
                        if (dispatch) queueEvent(event)
                    },
                    onDisconnect = { diagnostic -> queueDisconnect(diagnostic) },
                )
                if (combinedAttempt is SubscriptionAttempt.Rejected) {
                    if (combinedAttempt.error.code == "pane_not_found") {
                        return@repeat
                    }
                    throw BootstrapException(combinedAttempt.error.message)
                }

                val combined = (combinedAttempt as SubscriptionAttempt.Started).subscription
                val secondSnapshot = try {
                    connection.snapshot(nextId("snapshot"))
                } catch (failure: Throwable) {
                    combined.close()
                    throw failure
                }
                val secondPaneIds = secondSnapshot.panes.mapTo(mutableSetOf(), HerdrPane::paneId)
                if (secondPaneIds != paneIds) {
                    combined.close()
                    return@repeat
                }

                val replay = synchronized(bufferedEvents) {
                    buffering.set(false)
                    bufferedEvents.toList()
                }
                topologySubscription.close()
                subscription?.close()
                subscription = combined
                var live = HerdrModel.fromSnapshot(secondSnapshot, capabilities)
                replay.forEach { event -> live = HerdrModel.reduceEvent(live, event) }
                val published = publish(HerdrUiState.Live(live))
                startLiveWork()
                return published
            }
            throw BootstrapException("pane topology changed during three subscription attempts")
        } finally {
            topologySubscription.close()
        }
    }

    private fun handleEvent(event: HerdrEvent) {
        val live = (state.get() as? HerdrUiState.Live)?.view ?: return
        val reduced = HerdrModel.reduceEvent(live, event)
        if (reduced.paneIds != live.paneIds) {
            try {
                rebuildSubscription(reduced, reduced.paneIds)
            } catch (failure: Throwable) {
                handleDisconnect(failure.message ?: "failed to rebuild Herdr subscription")
            }
        } else {
            publish(HerdrUiState.Live(reduced))
        }
    }

    private fun reconcileInternal(): HerdrUiState {
        val live = (state.get() as? HerdrUiState.Live)?.view ?: return state.get()
        return try {
            val snapshot = connection.snapshot(nextId("reconcile"))
            val paneIds = snapshot.panes.mapTo(mutableSetOf(), HerdrPane::paneId)
            if (paneIds != live.paneIds) {
                rebuildSubscription(HerdrModel.reconcile(live, snapshot), paneIds)
            } else {
                publish(HerdrUiState.Live(HerdrModel.reconcile(live, snapshot)))
            }
        } catch (failure: Throwable) {
            handleDisconnect(failure.message ?: "Herdr reconciliation failed")
        }
    }

    private fun rebuildSubscription(base: HerdrLiveView, initialPaneIds: Set<String>): HerdrUiState {
        var paneIds = initialPaneIds
        repeat(3) {
            val bufferedEvents = mutableListOf<HerdrEvent>()
            val buffering = AtomicBoolean(true)
            val attempt = connection.subscribe(
                HerdrRequest.combinedSubscription(nextId("subscription"), paneIds),
                onEvent = { event ->
                    var dispatch = false
                    synchronized(bufferedEvents) {
                        if (buffering.get()) bufferedEvents += event else dispatch = true
                    }
                    if (dispatch) queueEvent(event)
                },
                onDisconnect = { diagnostic -> queueDisconnect(diagnostic) },
            )
            if (attempt is SubscriptionAttempt.Rejected) {
                if (attempt.error.code == "pane_not_found") {
                    paneIds = connection.snapshot(nextId("snapshot")).panes
                        .mapTo(mutableSetOf(), HerdrPane::paneId)
                    return@repeat
                }
                throw BootstrapException(attempt.error.message)
            }

            val replacement = (attempt as SubscriptionAttempt.Started).subscription
            val snapshot = try {
                connection.snapshot(nextId("snapshot"))
            } catch (failure: Throwable) {
                replacement.close()
                throw failure
            }
            val freshPaneIds = snapshot.panes.mapTo(mutableSetOf(), HerdrPane::paneId)
            if (freshPaneIds != paneIds) {
                replacement.close()
                paneIds = freshPaneIds
                return@repeat
            }
            val replay = synchronized(bufferedEvents) {
                buffering.set(false)
                bufferedEvents.toList()
            }
            subscription?.close()
            subscription = replacement
            var live = HerdrModel.reconcile(base, snapshot)
            replay.forEach { event -> live = HerdrModel.reduceEvent(live, event) }
            return publish(HerdrUiState.Live(live))
        }
        throw BootstrapException("pane topology changed during three subscription rebuild attempts")
    }

    private fun pollSelectedInternal(): HerdrUiState {
        val live = (state.get() as? HerdrUiState.Live)?.view ?: return state.get()
        val selected = live.selection ?: return state.get()
        return try {
            val read = connection.paneRead(nextId("read"), selected.paneId)
            val updated = HerdrModel.withOutput(live, read)
            if (updated == live) state.get() else publish(HerdrUiState.Live(updated))
        } catch (failure: Throwable) {
            handleDisconnect(failure.message ?: "selected pane read failed")
        }
    }

    private fun handleDisconnect(diagnostic: String): HerdrUiState {
        cancelLiveWork()
        val current = state.get()
        return when (current) {
            is HerdrUiState.Live -> publish(HerdrUiState.Disconnected(current.view, diagnostic))
            is HerdrUiState.Disconnected -> current
            else -> publish(HerdrUiState.Connecting(connection.socketTarget.toString(), diagnostic))
        }
    }

    private fun queueEvent(event: HerdrEvent) {
        if (!disposed.get()) {
            lifecycle.execute { if (!disposed.get()) handleEvent(event) }
        }
    }

    private fun queueDisconnect(diagnostic: String) {
        if (!disposed.get()) {
            lifecycle.execute { if (!disposed.get()) handleDisconnect(diagnostic) }
        }
    }

    private fun startLiveWork() {
        reconcileTask?.cancel(false)
        outputTask?.cancel(false)
        reconcileTask = timer.scheduleAtFixedRate(
            { queueReconcile() },
            30,
            30,
            TimeUnit.SECONDS,
        )
    }

    private fun queueReconcile() {
        if (!disposed.get()) {
            lifecycle.execute { if (!disposed.get()) reconcileInternal() }
        }
    }

    private fun queuePoll() {
        if (disposed.get() || !outputPollPending.compareAndSet(false, true)) {
            return
        }
        try {
            lifecycle.execute {
                try {
                    if (!disposed.get()) pollSelectedInternal()
                } finally {
                    outputPollPending.set(false)
                }
            }
        } catch (failure: RejectedExecutionException) {
            outputPollPending.set(false)
            if (!disposed.get()) {
                throw failure
            }
        }
    }

    private fun cancelLiveWork() {
        reconcileTask?.cancel(false)
        reconcileTask = null
        outputTask?.cancel(false)
        outputTask = null
        outputPollingPaneId = null
        subscription?.close()
        subscription = null
    }

    private fun publish(next: HerdrUiState): HerdrUiState {
        state.set(next)
        val selectedPaneId = (next as? HerdrUiState.Live)?.view?.selection?.paneId
        if (selectedPaneId != outputPollingPaneId) {
            outputTask?.cancel(false)
            outputTask = null
            outputPollingPaneId = selectedPaneId
            if (selectedPaneId != null) {
                outputTask = timer.scheduleAtFixedRate(
                    { queuePoll() },
                    250,
                    250,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
        listeners.forEach { it(next) }
        return next
    }

    private fun nextId(prefix: String): String = "intellij:$prefix:${requestSequence.incrementAndGet()}"

    private fun <T> submit(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        if (disposed.get()) {
            future.completeExceptionally(IllegalStateException("Herdr controller is disposed"))
            return future
        }
        lifecycle.execute {
            try {
                if (disposed.get()) {
                    future.completeExceptionally(IllegalStateException("Herdr controller is disposed"))
                } else {
                    future.complete(action())
                }
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return future
    }

    private class BootstrapException(message: String) : Exception(message)

    private data class Defaults(val connection: HerdrConnection, val settings: HerdrSettings)

    companion object {
        private fun defaults(): Defaults {
            val environment = System.getenv()
            val settings = HerdrSettings.getInstance()
            val target = HerdrConnection.resolveSocketTarget(settings.state.socketOverride, environment)
            return Defaults(HerdrConnection(target, environment), settings)
        }
    }
}
