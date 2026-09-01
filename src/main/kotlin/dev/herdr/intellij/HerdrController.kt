package dev.herdr.intellij

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private var connection: HerdrConnection,
    private val settings: HerdrSettings?,
    private val environment: Map<String, String>,
) : Disposable {
    constructor() : this(defaults())

    internal constructor(connection: HerdrConnection) : this(connection, null, System.getenv())

    private constructor(defaults: Defaults) : this(defaults.connection, defaults.settings, defaults.environment)

    private val disposed = AtomicBoolean(false)
    private val outputPollPending = AtomicBoolean(false)
    private val requestSequence = AtomicLong()
    private val lifecycle =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "herdr-controller").apply { isDaemon = true }
        }
    private val timer =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "herdr-controller-timer").apply { isDaemon = true }
        }
    private val state =
        AtomicReference<HerdrUiState>(
            HerdrUiState.NoServer(connection.socketTarget.toString()),
        )
    private val listeners = CopyOnWriteArrayList<(HerdrUiState) -> Unit>()
    private val blockedListeners = CopyOnWriteArrayList<(AgentView) -> Unit>()
    private val blockedAgentPaneIds = mutableSetOf<String>()
    private var blockedBaselineEstablished = false
    private var executableOverride = settings?.state?.executableOverride?.takeIf(String::isNotBlank)
    private var subscription: HerdrSubscription? = null
    private var subscribedPaneIds: Set<String> = emptySet()
    private var reconcileTask: ScheduledFuture<*>? = null
    private var outputTask: ScheduledFuture<*>? = null
    private var outputPollingPaneId: String? = null

    fun currentState(): HerdrUiState = state.get()

    fun addStateListener(listener: (HerdrUiState) -> Unit): AutoCloseable {
        listeners += listener
        listener(state.get())
        return AutoCloseable { listeners -= listener }
    }

    fun addBlockedListener(listener: (AgentView) -> Unit): AutoCloseable {
        blockedListeners += listener
        return AutoCloseable { blockedListeners -= listener }
    }

    fun connect(): CompletableFuture<HerdrUiState> = submit { connectInternal() }

    fun retry(): CompletableFuture<HerdrUiState> = connect()

    fun reconfigure(overrides: HerdrConnectionOverrides): CompletableFuture<HerdrUiState> =
        submit {
            cancelLiveWork()
            connection.close()
            connection =
                HerdrConnection(
                    HerdrConnection.resolveSocketTarget(overrides.socket.takeIf(String::isNotBlank), environment),
                    environment,
                )
            executableOverride = overrides.executable.takeIf(String::isNotBlank)
            blockedAgentPaneIds.clear()
            blockedBaselineEstablished = false
            publish(HerdrUiState.NoServer(connection.socketTarget.toString()))
        }

    fun startHerdr(): CompletableFuture<HerdrUiState> =
        submit {
            cancelLiveWork()
            publish(HerdrUiState.Starting(connection.socketTarget.toString()))
            try {
                connection.startHerdr(executableOverride)
            } catch (failure: HerdrTransportException) {
                return@submit publish(
                    HerdrUiState.NoServer(
                        connection.socketTarget.toString(),
                        failure.message ?: "Herdr could not be started",
                    ),
                )
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
                    return@submit publish(
                        HerdrUiState.Incompatible(
                            connection.socketTarget.toString(),
                            mismatch.expected,
                            mismatch.actual,
                            mismatch.message ?: "Herdr protocol mismatch",
                        ),
                    )
                } catch (failure: HerdrProtocolException) {
                    return@submit publish(
                        HerdrUiState.Incompatible(
                            connection.socketTarget.toString(),
                            HERDR_PROTOCOL_VERSION,
                            null,
                            failure.message ?: "Malformed Herdr protocol data",
                        ),
                    )
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

    fun selectAgent(agentName: String?): CompletableFuture<HerdrUiState> =
        submit {
            val live = (state.get() as? HerdrUiState.Live)?.view ?: return@submit state.get()
            publish(HerdrUiState.Live(HerdrModel.select(live, agentName)))
        }

    fun focusAgent(agentName: String): CompletableFuture<MutationOutcome> =
        submit {
            val live = mutableLive() ?: return@submit MutationOutcome.DefinitelyNotSent("Herdr is not live")
            val matches = live.workspaces.flatMap(WorkspaceView::agents).filter { it.name == agentName }
            if (matches.size != 1) {
                return@submit MutationOutcome.DefinitelyNotSent("Agent name is absent or ambiguous")
            }
            val request =
                HerdrRequest.mutation(
                    nextId("focus"),
                    "agent.focus",
                    buildJsonObject { put("target", agentName) },
                )
            val outcome = performMutation(live, request, HerdrAction.FOCUS)
            if (outcome is MutationOutcome.Applied) {
                val current = mutableLive() ?: return@submit outcome
                publish(HerdrUiState.Live(HerdrModel.select(current, agentName)))
            }
            outcome
        }

    fun sendBlockedText(text: String): CompletableFuture<MutationOutcome> =
        submit {
            if (text.isEmpty()) {
                return@submit MutationOutcome.DefinitelyNotSent("Blocked response is empty")
            }
            val keys =
                buildList {
                    text.codePoints().forEach { codePoint ->
                        add(
                            when (codePoint) {
                                ' '.code -> "space"
                                '+'.code -> "plus"
                                else -> String(Character.toChars(codePoint))
                            },
                        )
                    }
                }
            sendBlockedKeys(keys)
        }

    fun sendBlockedKey(key: String): CompletableFuture<MutationOutcome> =
        submit {
            if (key != "Enter" && key != "Escape") {
                return@submit MutationOutcome.DefinitelyNotSent("Unsupported blocked-response key")
            }
            sendBlockedKeys(listOf(key))
        }

    fun launchAgent(intent: NewAgentIntent): CompletableFuture<AgentLaunchOutcome> =
        submit {
            val live =
                mutableLive()
                    ?: return@submit AgentLaunchOutcome.AllocationFailed(
                        ActionError(HerdrAction.ALLOCATE, "Herdr is not live", false),
                    )
            if (live.capabilities.none { it.kind == intent.kind }) {
                return@submit AgentLaunchOutcome.AllocationFailed(
                    ActionError(HerdrAction.ALLOCATE, "Herdr no longer offers that launch kind", false),
                )
            }
            val allocationRequest =
                when (val source = intent.source) {
                    is NewAgentSource.ExistingWorkspace -> {
                        if (live.workspaces.none { it.id == source.workspaceId }) {
                            return@submit AgentLaunchOutcome.AllocationFailed(
                                ActionError(HerdrAction.ALLOCATE, "Herdr workspace no longer exists", false),
                            )
                        }
                        HerdrRequest.mutation(
                            nextId("allocate"),
                            "tab.create",
                            buildJsonObject {
                                put("workspace_id", source.workspaceId)
                                put("focus", false)
                            },
                        )
                    }
                    is NewAgentSource.Directory ->
                        HerdrRequest.mutation(
                            nextId("allocate"),
                            "workspace.create",
                            buildJsonObject {
                                put("cwd", source.path)
                                put("focus", false)
                            },
                        )
                }
            val allocation = connection.mutate(allocationRequest)
            if (allocation !is MutationOutcome.Applied) {
                val error =
                    when (allocation) {
                        is MutationOutcome.DefinitelyNotSent ->
                            ActionError(
                                HerdrAction.ALLOCATE,
                                allocation.diagnostic,
                                false,
                            )
                        is MutationOutcome.Rejected ->
                            ActionError(
                                HerdrAction.ALLOCATE,
                                allocation.error.message,
                                false,
                            )
                        is MutationOutcome.UnknownAfterWrite ->
                            ActionError(
                                HerdrAction.ALLOCATE,
                                allocation.diagnostic,
                                true,
                            )
                        is MutationOutcome.Applied -> error("handled above")
                    }
                publish(HerdrUiState.Live(HerdrModel.withActionError(mutableLive() ?: live, error)))
                return@submit AgentLaunchOutcome.AllocationFailed(error)
            }
            val result = allocation.response.result
            val workspace: HerdrWorkspace?
            val tab: HerdrTab
            val rootPane: HerdrPane
            when (result) {
                is HerdrResult.WorkspaceCreated -> {
                    workspace = result.workspace
                    tab = result.tab
                    rootPane = result.rootPane
                }
                is HerdrResult.TabCreated -> {
                    workspace = null
                    tab = result.tab
                    rootPane = result.rootPane
                }
                else -> {
                    val error = ActionError(HerdrAction.ALLOCATE, "Allocation returned an unexpected result", true)
                    publish(HerdrUiState.Live(HerdrModel.withActionError(mutableLive() ?: live, error)))
                    return@submit AgentLaunchOutcome.AllocationFailed(error)
                }
            }
            val record =
                ProvisioningRecord(
                    id = "${rootPane.workspaceId}:${rootPane.paneId}:${intent.name}",
                    workspaceId = rootPane.workspaceId,
                    tabId = rootPane.tabId,
                    paneId = rootPane.paneId,
                    name = intent.name,
                    kind = intent.kind,
                    arguments = intent.arguments,
                )
            val provisioned =
                HerdrModel.withAllocation(
                    mutableLive() ?: live,
                    workspace,
                    tab,
                    rootPane,
                    record,
                )
            publish(HerdrUiState.Live(provisioned))

            startRetainedAgent(record, intent.arguments, provisioned)
        }

    fun retryFailedLaunch(recordId: String): CompletableFuture<AgentLaunchOutcome> =
        submit {
            val live =
                mutableLive()
                    ?: return@submit AgentLaunchOutcome.AllocationFailed(
                        ActionError(HerdrAction.START_AGENT, "Herdr is not live", false),
                    )
            val failed =
                live.failedLaunches.singleOrNull { it.id == recordId }
                    ?: return@submit AgentLaunchOutcome.AllocationFailed(
                        ActionError(HerdrAction.START_AGENT, "Failed allocation no longer exists", false),
                    )
            if (!failed.retryConfirmed || failed.paneId !in live.topology.panes || failed.paneId in live.topology.agents) {
                return@submit AgentLaunchOutcome.AllocationFailed(
                    ActionError(HerdrAction.START_AGENT, "Refresh must confirm an unused allocated pane", true),
                )
            }
            val provisioning =
                ProvisioningRecord(
                    failed.id,
                    failed.workspaceId,
                    failed.tabId,
                    failed.paneId,
                    failed.name,
                    failed.kind,
                    failed.arguments,
                )
            val provisioned =
                HerdrModel.withAllocation(
                    live,
                    null,
                    requireNotNull(live.topology.tabs[failed.tabId]),
                    requireNotNull(live.topology.panes[failed.paneId]),
                    provisioning,
                )
            publish(HerdrUiState.Live(provisioned))
            startRetainedAgent(provisioning, failed.arguments, provisioned)
        }

    fun promptSelected(text: String): CompletableFuture<MutationOutcome> =
        submit {
            if (text.isBlank()) {
                return@submit MutationOutcome.DefinitelyNotSent("Prompt is empty")
            }
            val live = mutableLive() ?: return@submit MutationOutcome.DefinitelyNotSent("Herdr is not live")
            val selected =
                selectedAgent(live)
                    ?: return@submit MutationOutcome.DefinitelyNotSent("No unambiguous agent is selected")
            if (!selected.interactiveReady || selected.status == AgentStatus.BLOCKED || selected.status == AgentStatus.DONE) {
                return@submit MutationOutcome.DefinitelyNotSent("Selected agent cannot accept an ordinary prompt")
            }
            performMutation(
                live,
                HerdrRequest.mutation(
                    nextId("prompt"),
                    "agent.prompt",
                    buildJsonObject {
                        put("target", selected.name)
                        put("text", text)
                    },
                ),
                HerdrAction.PROMPT,
            )
        }

    fun prepareSelectionReview(review: SelectionReview): CompletableFuture<HerdrUiState> =
        submit {
            val live = mutableLive() ?: return@submit state.get()
            publish(HerdrUiState.Live(HerdrModel.withSelectionReview(live, review)))
        }

    fun updateReviewInstruction(instruction: String): CompletableFuture<HerdrUiState> =
        submit {
            val live = mutableLive() ?: return@submit state.get()
            val review = live.selectionReview ?: return@submit state.get()
            publish(HerdrUiState.Live(HerdrModel.withSelectionReview(live, review.copy(instruction = instruction))))
        }

    fun clearSelectionReview(): CompletableFuture<HerdrUiState> =
        submit {
            val live = mutableLive() ?: return@submit state.get()
            publish(HerdrUiState.Live(HerdrModel.withSelectionReview(live, null)))
        }

    fun createWorktree(
        workspaceId: String,
        branch: String,
        base: String?,
    ): CompletableFuture<WorktreeCommandOutcome> =
        submit {
            if (branch.isBlank()) {
                return@submit WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, "Branch is required", false),
                )
            }
            val live =
                mutableLive() ?: return@submit WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, "Herdr is not live", false),
                )
            worktreeOutcome(
                live,
                HerdrRequest.mutation(
                    nextId("worktree-create"),
                    "worktree.create",
                    buildJsonObject {
                        put("workspace_id", workspaceId)
                        put("branch", branch)
                        base?.takeIf(String::isNotBlank)?.let { put("base", it) }
                        put("focus", false)
                    },
                ),
            )
        }

    fun openWorktree(
        workspaceId: String,
        path: String,
    ): CompletableFuture<WorktreeCommandOutcome> =
        submit {
            if (path.isBlank()) {
                return@submit WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, "Worktree path is required", false),
                )
            }
            val live =
                mutableLive() ?: return@submit WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, "Herdr is not live", false),
                )
            worktreeOutcome(
                live,
                HerdrRequest.mutation(
                    nextId("worktree-open"),
                    "worktree.open",
                    buildJsonObject {
                        put("workspace_id", workspaceId)
                        put("path", path)
                        put("focus", false)
                    },
                ),
            )
        }

    fun pollSelectedNow(): CompletableFuture<HerdrUiState> = submit { pollSelectedInternal() }

    fun mutate(request: HerdrRequest): CompletableFuture<MutationOutcome> =
        submit {
            val live = mutableLive()
            if (live == null) {
                return@submit MutationOutcome.DefinitelyNotSent("Herdr is not live")
            }
            val action =
                when (request.method) {
                    "workspace.create", "tab.create" -> HerdrAction.ALLOCATE
                    "worktree.create", "worktree.open" -> HerdrAction.WORKTREE
                    "agent.start" -> HerdrAction.START_AGENT
                    "agent.prompt" -> HerdrAction.PROMPT
                    "agent.send_keys" -> HerdrAction.RESPOND
                    "agent.focus" -> HerdrAction.FOCUS
                    else -> error("mutation request escaped its closed allowlist: ${request.method}")
                }
            performMutation(live, request, action)
        }

    private fun sendBlockedKeys(keys: List<String>): MutationOutcome {
        val live = mutableLive() ?: return MutationOutcome.DefinitelyNotSent("Herdr is not live")
        val selected =
            live.selection
                ?: return MutationOutcome.DefinitelyNotSent("No agent is selected")
        val matches = live.workspaces.flatMap(WorkspaceView::agents).filter { it.name == selected.agentName }
        if (matches.size != 1 ||
            matches.single().paneId != selected.paneId ||
            matches.single().status != AgentStatus.BLOCKED
        ) {
            return MutationOutcome.DefinitelyNotSent("Selected agent is not blocked")
        }
        val request =
            HerdrRequest.mutation(
                nextId("respond"),
                "agent.send_keys",
                buildJsonObject {
                    put("target", selected.agentName)
                    put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
                },
            )
        return performMutation(live, request, HerdrAction.RESPOND)
    }

    private fun startRetainedAgent(
        record: ProvisioningRecord,
        arguments: List<String>,
        provisioned: HerdrLiveView,
    ): AgentLaunchOutcome {
        val start =
            connection.mutate(
                HerdrRequest.mutation(
                    nextId("start-agent"),
                    "agent.start",
                    buildJsonObject {
                        put("name", record.name)
                        put("kind", record.kind)
                        put("pane_id", record.paneId)
                        put(
                            "args",
                            buildJsonArray {
                                arguments.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    },
                ),
            )
        if (start is MutationOutcome.Applied && start.response.result is HerdrResult.AgentStarted) {
            val agent = start.response.result.agent
            val current = mutableLive() ?: provisioned
            publish(HerdrUiState.Live(HerdrModel.withStartedAgent(current, record.id, agent)))
            return AgentLaunchOutcome.Started(agent)
        }
        val failed =
            when (start) {
                is MutationOutcome.Rejected ->
                    FailedLaunch(
                        record.id,
                        record.workspaceId,
                        record.tabId,
                        record.paneId,
                        record.name,
                        record.kind,
                        start.error.message,
                        false,
                        record.arguments,
                    )
                is MutationOutcome.DefinitelyNotSent ->
                    FailedLaunch(
                        record.id,
                        record.workspaceId,
                        record.tabId,
                        record.paneId,
                        record.name,
                        record.kind,
                        start.diagnostic,
                        false,
                        record.arguments,
                    )
                is MutationOutcome.UnknownAfterWrite ->
                    FailedLaunch(
                        record.id,
                        record.workspaceId,
                        record.tabId,
                        record.paneId,
                        record.name,
                        record.kind,
                        start.diagnostic,
                        true,
                        record.arguments,
                    )
                is MutationOutcome.Applied ->
                    FailedLaunch(
                        record.id,
                        record.workspaceId,
                        record.tabId,
                        record.paneId,
                        record.name,
                        record.kind,
                        "Agent start returned an unexpected result",
                        true,
                        record.arguments,
                    )
            }
        publish(HerdrUiState.Live(HerdrModel.withFailedLaunch(mutableLive() ?: provisioned, failed)))
        return AgentLaunchOutcome.Failed(failed)
    }

    private fun selectedAgent(live: HerdrLiveView): AgentView? {
        val selected = live.selection ?: return null
        return live.workspaces
            .flatMap(WorkspaceView::agents)
            .filter { it.name == selected.agentName }
            .singleOrNull()
            ?.takeIf { it.paneId == selected.paneId }
    }

    private fun worktreeOutcome(
        live: HerdrLiveView,
        request: HerdrRequest,
    ): WorktreeCommandOutcome =
        when (val outcome = performMutation(live, request, HerdrAction.WORKTREE)) {
            is MutationOutcome.Applied ->
                when (val result = outcome.response.result) {
                    is HerdrResult.WorktreeCreated -> WorktreeCommandOutcome.Opened(result.worktree.path, false)
                    is HerdrResult.WorktreeOpened ->
                        WorktreeCommandOutcome.Opened(
                            result.worktree.path,
                            result.alreadyOpen,
                        )
                    else ->
                        WorktreeCommandOutcome.Failed(
                            ActionError(HerdrAction.WORKTREE, "Worktree action returned an unexpected result", true),
                        )
                }
            is MutationOutcome.DefinitelyNotSent ->
                WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, outcome.diagnostic, false),
                )
            is MutationOutcome.Rejected ->
                WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, outcome.error.message, false),
                )
            is MutationOutcome.UnknownAfterWrite ->
                WorktreeCommandOutcome.Failed(
                    ActionError(HerdrAction.WORKTREE, outcome.diagnostic, true),
                )
        }

    private fun mutableLive(): HerdrLiveView? = (state.get() as? HerdrUiState.Live)?.view?.takeUnless { it.stale }

    private fun performMutation(
        live: HerdrLiveView,
        request: HerdrRequest,
        action: HerdrAction,
    ): MutationOutcome {
        val outcome = connection.mutate(request)
        when (outcome) {
            is MutationOutcome.Applied -> Unit
            is MutationOutcome.DefinitelyNotSent -> {
                val current = mutableLive() ?: live
                publish(
                    HerdrUiState.Live(
                        current.copy(
                            actionErrors =
                                current.actionErrors +
                                    ActionError(
                                        action,
                                        outcome.diagnostic,
                                        false,
                                    ),
                        ),
                    ),
                )
            }
            is MutationOutcome.Rejected -> {
                val current = mutableLive() ?: live
                publish(
                    HerdrUiState.Live(
                        current.copy(
                            actionErrors =
                                current.actionErrors +
                                    ActionError(
                                        action,
                                        outcome.error.message,
                                        false,
                                    ),
                        ),
                    ),
                )
            }
            is MutationOutcome.UnknownAfterWrite -> {
                val current = mutableLive() ?: live
                publish(
                    HerdrUiState.Live(
                        current.copy(
                            actionErrors =
                                current.actionErrors +
                                    ActionError(
                                        action,
                                        outcome.diagnostic,
                                        true,
                                    ),
                        ),
                    ),
                )
            }
        }
        return outcome
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
        blockedListeners.clear()
    }

    private fun connectInternal(): HerdrUiState {
        cancelLiveWork()
        publish(HerdrUiState.Connecting(connection.socketTarget.toString()))
        return try {
            establishLiveConnection()
        } catch (mismatch: HerdrProtocolMismatch) {
            publish(
                HerdrUiState.Incompatible(
                    connection.socketTarget.toString(),
                    mismatch.expected,
                    mismatch.actual,
                    mismatch.message ?: "Herdr protocol mismatch",
                ),
            )
        } catch (_: HerdrTransportException) {
            publish(HerdrUiState.NoServer(connection.socketTarget.toString()))
        } catch (failure: BootstrapException) {
            publish(HerdrUiState.Connecting(connection.socketTarget.toString(), failure.message))
        } catch (failure: HerdrProtocolException) {
            publish(
                HerdrUiState.Incompatible(
                    connection.socketTarget.toString(),
                    HERDR_PROTOCOL_VERSION,
                    null,
                    failure.message ?: "Malformed Herdr protocol data",
                ),
            )
        }
    }

    private fun establishLiveConnection(): HerdrUiState {
        connection.ping(nextId("ping"))
        val capabilities = connection.capabilities(nextId("capabilities"))
        return bootstrap(capabilities)
    }

    private fun bootstrap(capabilities: List<AgentCapability>): HerdrUiState {
        val topologyAttempt =
            connection.subscribe(
                HerdrRequest.topologySubscription(nextId("topology")),
                onEvent = { },
                onDisconnect = { diagnostic -> queueDisconnect(diagnostic) },
            )
        val topologySubscription =
            when (topologyAttempt) {
                is SubscriptionAttempt.Started -> topologyAttempt.subscription
                is SubscriptionAttempt.Rejected -> throw BootstrapException(topologyAttempt.error.message)
            }

        try {
            repeat(3) {
                val firstSnapshot = connection.snapshot(nextId("snapshot"))
                val paneIds = firstSnapshot.panes.mapTo(mutableSetOf(), HerdrPane::paneId)
                val bufferedEvents = mutableListOf<HerdrEvent>()
                val buffering = AtomicBoolean(true)
                val combinedAttempt =
                    connection.subscribe(
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
                val secondSnapshot =
                    try {
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

                val replay =
                    synchronized(bufferedEvents) {
                        buffering.set(false)
                        bufferedEvents.toList()
                    }
                topologySubscription.close()
                subscription?.close()
                subscription = combined
                subscribedPaneIds = secondPaneIds
                var live = HerdrModel.fromSnapshot(secondSnapshot, capabilities)
                establishBlockedBaseline(live)
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
        if (reduced.paneIds != subscribedPaneIds) {
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
            if (paneIds != subscribedPaneIds) {
                rebuildSubscription(HerdrModel.reconcile(live, snapshot), paneIds)
            } else {
                publish(HerdrUiState.Live(HerdrModel.reconcile(live, snapshot)))
            }
        } catch (failure: Throwable) {
            handleDisconnect(failure.message ?: "Herdr reconciliation failed")
        }
    }

    private fun rebuildSubscription(
        base: HerdrLiveView,
        initialPaneIds: Set<String>,
    ): HerdrUiState {
        var paneIds = initialPaneIds
        repeat(3) {
            val bufferedEvents = mutableListOf<HerdrEvent>()
            val buffering = AtomicBoolean(true)
            val attempt =
                connection.subscribe(
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
                    paneIds =
                        connection
                            .snapshot(nextId("snapshot"))
                            .panes
                            .mapTo(mutableSetOf(), HerdrPane::paneId)
                    return@repeat
                }
                throw BootstrapException(attempt.error.message)
            }

            val replacement = (attempt as SubscriptionAttempt.Started).subscription
            val snapshot =
                try {
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
            val replay =
                synchronized(bufferedEvents) {
                    buffering.set(false)
                    bufferedEvents.toList()
                }
            subscription?.close()
            subscription = replacement
            subscribedPaneIds = freshPaneIds
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
        reconcileTask =
            timer.scheduleAtFixedRate(
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
        subscribedPaneIds = emptySet()
    }

    private fun publish(next: HerdrUiState): HerdrUiState {
        state.set(next)
        if (next is HerdrUiState.Live) {
            val agents = next.view.workspaces.flatMap(WorkspaceView::agents)
            val nowBlocked =
                agents
                    .filter { it.status == AgentStatus.BLOCKED }
                    .mapTo(mutableSetOf(), AgentView::paneId)
            if (!blockedBaselineEstablished) {
                establishBlockedBaseline(next.view)
            } else {
                agents
                    .filter { it.paneId in nowBlocked && it.paneId !in blockedAgentPaneIds }
                    .forEach { agent -> blockedListeners.forEach { listener -> listener(agent) } }
                blockedAgentPaneIds.retainAll(nowBlocked)
                blockedAgentPaneIds += nowBlocked
            }
        }
        val selectedPaneId = (next as? HerdrUiState.Live)?.view?.selection?.paneId
        if (selectedPaneId != outputPollingPaneId) {
            outputTask?.cancel(false)
            outputTask = null
            outputPollingPaneId = selectedPaneId
            if (selectedPaneId != null) {
                outputTask =
                    timer.scheduleAtFixedRate(
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

    private fun establishBlockedBaseline(live: HerdrLiveView) {
        if (!blockedBaselineEstablished) {
            live.workspaces
                .flatMap(WorkspaceView::agents)
                .filter { it.status == AgentStatus.BLOCKED }
                .mapTo(blockedAgentPaneIds, AgentView::paneId)
            blockedBaselineEstablished = true
        }
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

    private class BootstrapException(
        message: String,
    ) : Exception(message)

    private data class Defaults(
        val connection: HerdrConnection,
        val settings: HerdrSettings,
        val environment: Map<String, String>,
    )

    companion object {
        private fun defaults(): Defaults {
            val environment = System.getenv()
            val settings = HerdrSettings.getInstance()
            val target = HerdrConnection.resolveSocketTarget(settings.state.socketOverride, environment)
            return Defaults(HerdrConnection(target, environment), settings, environment)
        }
    }
}
