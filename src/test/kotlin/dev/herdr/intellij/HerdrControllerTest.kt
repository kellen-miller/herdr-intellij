package dev.herdr.intellij

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HerdrControllerTest {
    @Test
    fun `start failure preserves a safe no server diagnostic`() {
        val missing =
            java.nio.file.Files
                .createTempDirectory("herdr-controller-start")
                .resolve("herdr.sock")
        val controller = HerdrController(HerdrConnection(missing, mapOf("PATH" to "")))

        val state =
            assertIs<HerdrUiState.NoServer>(
                controller.startHerdr().get(3, TimeUnit.SECONDS),
            )

        assertEquals("could not find an executable herdr binary", state.diagnostic)
        controller.dispose()
    }

    @Test
    fun `missing socket remains no server`() {
        val missing =
            java.nio.file.Files
                .createTempDirectory("herdr-controller-missing")
                .resolve("herdr.sock")
        val controller = HerdrController(HerdrConnection(missing))

        assertIs<HerdrUiState.NoServer>(controller.currentState())
        assertIs<HerdrUiState.NoServer>(controller.connect().get(3, TimeUnit.SECONDS))
        controller.dispose()
    }

    @Test
    fun `protocol mismatch stops before capabilities`() {
        ScriptedHerdrServer { _, request, channel ->
            channel.writeLine(responseFixture("ping-21.json", request.id()))
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            val state = controller.connect().get(3, TimeUnit.SECONDS)

            assertEquals(21, assertIs<HerdrUiState.Incompatible>(state).actualProtocol)
            assertEquals(listOf("ping"), server.requests.map { it.method() })
            controller.dispose()
        }
    }

    @Test
    fun `malformed framing becomes incompatible instead of partially live`() {
        ScriptedHerdrServer { _, _, channel ->
            channel.writeUtf8("""{"id":"intellij:ping:1","result":{"type":"pong"}}""")
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            val state = controller.connect().get(3, TimeUnit.SECONDS)

            assertIs<HerdrUiState.Incompatible>(state)
            assertEquals(listOf("ping"), server.requests.map { it.method() })
            controller.dispose()
        }
    }

    @Test
    fun `bootstrap ignores old topology and replays combined overlap after fresh snapshot`() {
        val topologyRelease = CountDownLatch(1)
        val combinedRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(fixture("pane-status-event.json"))
                        topologyRelease.countDown()
                        combinedRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        channel.writeLine(
                            """{"event":"workspace.closed","data":{"type":"workspace_closed","workspace_id":"w-1"}}""",
                        )
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        combinedRelease.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            controller.connect().get(3, TimeUnit.SECONDS)
            waitUntil {
                val live = (controller.currentState() as? HerdrUiState.Live)?.view
                live
                    ?.workspaces
                    ?.singleOrNull()
                    ?.agents
                    ?.singleOrNull()
                    ?.status == AgentStatus.BLOCKED
            }
            val live = assertIs<HerdrUiState.Live>(controller.currentState()).view

            assertEquals(
                AgentStatus.BLOCKED,
                live.workspaces
                    .single()
                    .agents
                    .single()
                    .status,
            )
            assertEquals(2, server.requests.count { it.method() == "session.snapshot" })
            assertEquals(2, server.requests.count { it.method() == "events.subscribe" })
            combinedRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `bootstrap rebuilds subscriptions after replay adds a pane`() {
        val snapshots = AtomicInteger()
        val combinedSubscriptions = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> {
                    if (snapshots.incrementAndGet() < 3) {
                        channel.writeLine(snapshotResponse(request.id()))
                    } else {
                        channel.writeLine(snapshotWithAllocationResponse(request.id()))
                    }
                }
                "events.subscribe" ->
                    if (!request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(subscriptionStarted(request.id()))
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        val count = combinedSubscriptions.incrementAndGet()
                        channel.writeLine(subscriptionStarted(request.id()))
                        if (count == 1) {
                            channel.writeLine(paneCreatedEvent())
                        }
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            val live = assertIs<HerdrUiState.Live>(controller.connect().get(3, TimeUnit.SECONDS)).view

            assertEquals(2, combinedSubscriptions.get())
            assertEquals(setOf("p-agent", "p-shell", "p-new"), live.paneIds)
            assertEquals(3, server.requests.last { it.hasPaneStatusSubscriptions() }.paneStatusSubscriptionCount())
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `vanished pane subscribe rejection retries at most three times`() {
        val attempts = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val combinedRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" ->
                    if (!request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(subscriptionStarted(request.id()))
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        combinedRelease.await(5, TimeUnit.SECONDS)
                    } else if (attempts.incrementAndGet() < 3) {
                        channel.writeLine(
                            errorResponse("${request.id()}:sub:0:probe", "pane_not_found", "pane vanished"),
                        )
                    } else {
                        channel.writeLine(subscriptionStarted(request.id()))
                        topologyRelease.countDown()
                        combinedRelease.await(5, TimeUnit.SECONDS)
                    }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            assertIs<HerdrUiState.Live>(controller.connect().get(3, TimeUnit.SECONDS))
            assertEquals(3, attempts.get())
            combinedRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `three vanished pane rejections stop with reconnectable bootstrap error`() {
        val attempts = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val finish = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" ->
                    if (!request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(subscriptionStarted(request.id()))
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        finish.await(5, TimeUnit.SECONDS)
                    } else {
                        attempts.incrementAndGet()
                        channel.writeLine(
                            errorResponse("${request.id()}:sub:0:probe", "pane_not_found", "pane vanished"),
                        )
                    }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            val state =
                assertIs<HerdrUiState.Connecting>(
                    controller.connect().get(3, TimeUnit.SECONDS),
                )
            assertTrue(state.diagnostic?.contains("three subscription attempts") == true)
            assertEquals(3, attempts.get())
            topologyRelease.countDown()
            finish.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `reconciliation repairs omitted event and pane topology triggers subscription rebuild`() {
        val snapshots = AtomicInteger()
        val combinedSubscriptions = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val sendPaneClose = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> {
                    val count = snapshots.incrementAndGet()
                    val paneIds = if (count >= 4) setOf("p-agent") else setOf("p-agent", "p-shell")
                    val label = if (count >= 3) "Renamed without event" else "Herdr"
                    channel.writeLine(snapshotResponse(request.id(), label, paneIds))
                }
                "events.subscribe" ->
                    if (!request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(subscriptionStarted(request.id()))
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        val count = combinedSubscriptions.incrementAndGet()
                        channel.writeLine(subscriptionStarted(request.id()))
                        topologyRelease.countDown()
                        if (count == 1) {
                            sendPaneClose.await(5, TimeUnit.SECONDS)
                            channel.writeLine(
                                """{"event":"pane.closed","data":{"type":"pane_closed","pane_id":"p-shell","workspace_id":"w-1"}}""",
                            )
                        }
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)

            val reconciled =
                assertIs<HerdrUiState.Live>(
                    controller.reconcileNow().get(3, TimeUnit.SECONDS),
                ).view
            assertEquals("Renamed without event", reconciled.workspaces.single().label)

            sendPaneClose.countDown()
            waitUntil { combinedSubscriptions.get() == 2 }
            waitUntil {
                (controller.currentState() as? HerdrUiState.Live)?.view?.paneIds == setOf("p-agent")
            }
            val rebuilt = assertIs<HerdrUiState.Live>(controller.currentState()).view
            assertEquals(setOf("p-agent"), rebuilt.paneIds)
            val lastCombined = server.requests.last { it.hasPaneStatusSubscriptions() }
            assertEquals(1, lastCombined.paneStatusSubscriptionCount())
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `selected output follows real Herdr constant revisions`() {
        val reads = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val combinedRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        combinedRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        combinedRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "pane.read" -> {
                    val read = reads.incrementAndGet()
                    val text = if (read == 1) "first output" else "second output"
                    channel.writeLine(paneReadResponse(request.id(), 0, text))
                }
            }
        }.use { server ->
            val outputs = CopyOnWriteArrayList<String>()
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.addStateListener { state ->
                if (state is HerdrUiState.Live) {
                    state.view.recentOutput
                        ?.text
                        ?.let(outputs::add)
                }
            }
            controller.connect().get(3, TimeUnit.SECONDS)
            controller.selectAgent("reviewer").get(3, TimeUnit.SECONDS)

            controller.pollSelectedNow().get(3, TimeUnit.SECONDS)
            controller.pollSelectedNow().get(3, TimeUnit.SECONDS)
            controller.pollSelectedNow().get(3, TimeUnit.SECONDS)

            assertEquals(listOf("first output", "second output"), outputs.distinct())
            combinedRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `vanished selected pane does not disconnect a healthy runtime`() {
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "pane.read" -> channel.writeLine(errorResponse(request.id(), "pane_not_found", "pane vanished"))
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)
            controller.selectAgent("reviewer").get(3, TimeUnit.SECONDS)

            val state = controller.pollSelectedNow().get(3, TimeUnit.SECONDS)

            assertIs<HerdrUiState.Live>(state)
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `deliberate selection focuses exactly once and blocked responses preserve unicode code points`() {
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(fixture("pane-status-event.json"))
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "agent.focus", "agent.send_keys" ->
                    channel.writeLine(
                        """{"id":"${request.id()}","result":{"type":"ok"}}""",
                    )
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)

            assertIs<MutationOutcome.Applied>(controller.focusAgent("reviewer").get(3, TimeUnit.SECONDS))
            val selected = assertIs<HerdrUiState.Live>(controller.currentState()).view.selection
            assertEquals("reviewer", selected?.agentName)

            assertIs<MutationOutcome.Applied>(
                controller.sendBlockedText("a +🚀").get(3, TimeUnit.SECONDS),
            )
            assertIs<MutationOutcome.Applied>(controller.sendBlockedKey("Enter").get(3, TimeUnit.SECONDS))

            val mutations = server.requests.filter { it.method().startsWith("agent.") }
            assertEquals(listOf("agent.focus", "agent.send_keys", "agent.send_keys"), mutations.map { it.method() })
            assertEquals("reviewer", mutations[0].params()["target"]?.jsonPrimitive?.content)
            assertEquals(
                listOf("a", "space", "plus", "🚀"),
                mutations[1]
                    .params()
                    .getValue("keys")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertEquals(
                listOf("Enter"),
                mutations[2]
                    .params()
                    .getValue("keys")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertEquals(1, mutations.count { it.method() == "agent.focus" })
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `event discovered agent cannot mutate before its name resolves`() {
        val topologyRelease = CountDownLatch(1)
        val sendDetected = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        sendDetected.await(5, TimeUnit.SECONDS)
                        channel.writeLine(paneStatusEvent("working", "p-shell"))
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "agent.focus" -> channel.writeLine("""{"id":"${request.id()}","result":{"type":"ok"}}""")
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)

            sendDetected.countDown()
            waitUntil {
                (controller.currentState() as? HerdrUiState.Live)
                    ?.view
                    ?.workspaces
                    ?.singleOrNull()
                    ?.agents
                    ?.any { it.paneId == "p-shell" } == true
            }
            val outcome = controller.focusAgent("p-shell").get(3, TimeUnit.SECONDS)

            assertIs<MutationOutcome.DefinitelyNotSent>(outcome)
            assertTrue(server.requests.none { it.method() == "agent.focus" })
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `agent launch retains allocation as provisioning then failed evidence`() {
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        val sawProvisioning = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "tab.create" -> channel.writeLine(tabCreatedResponse(request.id()))
                "agent.start" -> channel.writeLine(errorResponse(request.id(), "launch_failed", "binary missing"))
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.addStateListener { state ->
                if ((state as? HerdrUiState.Live)?.view?.provisioning?.isNotEmpty() == true) {
                    sawProvisioning.countDown()
                }
            }
            controller.connect().get(3, TimeUnit.SECONDS)

            val outcome =
                controller
                    .launchAgent(
                        NewAgentIntent(
                            source = NewAgentSource.ExistingWorkspace("w-1"),
                            name = "worker_2",
                            kind = "codex",
                            arguments = listOf("--profile", "review"),
                        ),
                    ).get(3, TimeUnit.SECONDS)

            val failed = assertIs<AgentLaunchOutcome.Failed>(outcome).record
            assertTrue(sawProvisioning.await(1, TimeUnit.SECONDS))
            assertEquals("p-new", failed.paneId)
            assertEquals("binary missing", failed.message)
            assertEquals(false, failed.ambiguous)
            val live = assertIs<HerdrUiState.Live>(controller.currentState()).view
            assertTrue(live.provisioning.isEmpty())
            assertEquals(failed, live.failedLaunches.single())
            assertIs<AgentLaunchOutcome.AllocationFailed>(
                controller.retryFailedLaunch(failed.id).get(3, TimeUnit.SECONDS),
            )
            assertEquals(listOf("tab.create", "agent.start"), server.requests.map { it.method() }.takeLast(2))
            val allocate = server.requests.first { it.method() == "tab.create" }.params()
            assertEquals("w-1", allocate["workspace_id"]?.jsonPrimitive?.content)
            assertEquals(false, allocate["focus"]?.jsonPrimitive?.content?.toBoolean())
            val start = server.requests.first { it.method() == "agent.start" }.params()
            assertEquals("p-new", start["pane_id"]?.jsonPrimitive?.content)
            assertEquals(listOf("--profile", "review"), start.getValue("args").jsonArray.map { it.jsonPrimitive.content })
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `agent launch unknown after write retains ambiguous allocation without retry`() {
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "tab.create" -> channel.writeLine(tabCreatedResponse(request.id()))
                "agent.start" -> Unit
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)

            val outcome =
                controller
                    .launchAgent(
                        NewAgentIntent(
                            NewAgentSource.ExistingWorkspace("w-1"),
                            "worker_3",
                            "codex",
                            emptyList(),
                        ),
                    ).get(3, TimeUnit.SECONDS)

            val failed = assertIs<AgentLaunchOutcome.Failed>(outcome).record
            assertTrue(failed.ambiguous)
            val retry = controller.retryFailedLaunch(failed.id).get(3, TimeUnit.SECONDS)
            assertIs<AgentLaunchOutcome.AllocationFailed>(retry)
            assertEquals(1, server.requests.count { it.method() == "agent.start" })
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `refresh proves ambiguous pane then retry starts without another allocation`() {
        val snapshots = AtomicInteger()
        val starts = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> {
                    if (snapshots.incrementAndGet() <= 2) {
                        channel.writeLine(snapshotResponse(request.id()))
                    } else {
                        channel.writeLine(snapshotWithAllocationResponse(request.id()))
                    }
                }
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "tab.create" -> channel.writeLine(tabCreatedResponse(request.id()))
                "agent.start" ->
                    if (starts.incrementAndGet() > 1) {
                        channel.writeLine(agentStartedResponse(request.id(), "worker_4"))
                    }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)
            val first =
                controller
                    .launchAgent(
                        NewAgentIntent(
                            NewAgentSource.ExistingWorkspace("w-1"),
                            "worker_4",
                            "codex",
                            listOf("--review"),
                        ),
                    ).get(3, TimeUnit.SECONDS)
            val failed = assertIs<AgentLaunchOutcome.Failed>(first).record
            assertTrue(failed.ambiguous)

            val refreshed =
                assertIs<HerdrUiState.Live>(
                    controller.reconcileNow().get(3, TimeUnit.SECONDS),
                ).view
            assertEquals(false, refreshed.failedLaunches.single().ambiguous)
            val started = controller.retryFailedLaunch(failed.id).get(3, TimeUnit.SECONDS)

            assertEquals("worker_4", assertIs<AgentLaunchOutcome.Started>(started).agent.name)
            assertEquals(1, server.requests.count { it.method() == "tab.create" })
            assertEquals(2, server.requests.count { it.method() == "agent.start" })
            val startRequests = server.requests.filter { it.method() == "agent.start" }
            assertEquals(
                startRequests[0].params().getValue("pane_id"),
                startRequests[1].params().getValue("pane_id"),
            )
            assertEquals(
                startRequests[0].params().getValue("args"),
                startRequests[1].params().getValue("args"),
            )
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `blocked notifications fire only on transitions`() {
        val topologyRelease = CountDownLatch(1)
        val firstBlocked = CountDownLatch(1)
        val leaveBlocked = CountDownLatch(1)
        val blockAgain = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        firstBlocked.await(5, TimeUnit.SECONDS)
                        channel.writeLine(fixture("pane-status-event.json"))
                        channel.writeLine(fixture("pane-status-event.json"))
                        leaveBlocked.await(5, TimeUnit.SECONDS)
                        channel.writeLine(paneStatusEvent("working"))
                        blockAgain.await(5, TimeUnit.SECONDS)
                        channel.writeLine(fixture("pane-status-event.json"))
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        }.use { server ->
            val notifications = AtomicInteger()
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.addBlockedListener { notifications.incrementAndGet() }
            controller.connect().get(3, TimeUnit.SECONDS)

            firstBlocked.countDown()
            waitUntil { notifications.get() == 1 }
            Thread.sleep(50)
            assertEquals(1, notifications.get())
            leaveBlocked.countDown()
            waitUntil {
                val agent =
                    (controller.currentState() as? HerdrUiState.Live)
                        ?.view
                        ?.workspaces
                        ?.singleOrNull()
                        ?.agents
                        ?.singleOrNull()
                agent?.status == AgentStatus.WORKING
            }
            blockAgain.countDown()
            waitUntil { notifications.get() == 2 }
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `prompt and worktree commands use structured targets and open returned roots`() {
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "agent.prompt" -> channel.writeLine("""{"id":"${request.id()}","result":{"type":"ok"}}""")
                "worktree.create" -> channel.writeLine(worktreeResponse(request.id(), false))
                "worktree.open" -> channel.writeLine(worktreeResponse(request.id(), true))
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)
            controller.selectAgent("reviewer").get(3, TimeUnit.SECONDS)

            assertIs<MutationOutcome.Applied>(controller.promptSelected("Review this change").get(3, TimeUnit.SECONDS))
            assertEquals(
                WorktreeCommandOutcome.Opened("/repo/worktrees/topic", false),
                controller.createWorktree("w-1", "topic", "").get(3, TimeUnit.SECONDS),
            )
            assertEquals(
                WorktreeCommandOutcome.Opened("/repo/worktrees/topic", true),
                controller.openWorktree("w-1", "/repo/worktrees/topic").get(3, TimeUnit.SECONDS),
            )

            val prompt = server.requests.first { it.method() == "agent.prompt" }.params()
            assertEquals("reviewer", prompt["target"]?.jsonPrimitive?.content)
            assertEquals("Review this change", prompt["text"]?.jsonPrimitive?.content)
            val create = server.requests.first { it.method() == "worktree.create" }.params()
            assertEquals("topic", create["branch"]?.jsonPrimitive?.content)
            assertTrue("base" !in create)
            val open = server.requests.first { it.method() == "worktree.open" }.params()
            assertEquals("/repo/worktrees/topic", open["path"]?.jsonPrimitive?.content)
            assertEquals(false, open["focus"]?.jsonPrimitive?.content?.toBoolean())
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `settings reconfigure connection target without stopping either runtime`() {
        val firstTopology = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val secondTopology = CountDownLatch(1)
        val secondRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            answerBootstrap(request, channel, firstTopology, firstRelease)
        }.use { first ->
            ScriptedHerdrServer { _, request, channel ->
                answerBootstrap(request, channel, secondTopology, secondRelease)
            }.use { second ->
                val controller = HerdrController(HerdrConnection(first.socketPath))
                assertIs<HerdrUiState.Live>(controller.connect().get(3, TimeUnit.SECONDS))

                val reconfigured =
                    controller
                        .reconfigure(
                            HerdrConnectionOverrides(second.socketPath.toString(), "/opt/herdr"),
                        ).get(3, TimeUnit.SECONDS)

                assertEquals(
                    second.socketPath,
                    java.nio.file.Path
                        .of(assertIs<HerdrUiState.NoServer>(reconfigured).socketTarget),
                )
                assertIs<HerdrUiState.Live>(controller.connect().get(3, TimeUnit.SECONDS))
                assertTrue(first.requests.none { it.method() == "server.stop" })
                assertTrue(second.requests.none { it.method() == "server.stop" })
                firstRelease.countDown()
                secondRelease.countDown()
                controller.dispose()
            }
        }
    }

    @Test
    fun `slow selected output read does not accumulate scheduled polls`() {
        val reads = AtomicInteger()
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
                "pane.read" -> {
                    val read = reads.incrementAndGet()
                    if (read == 1) {
                        firstReadStarted.countDown()
                        releaseFirstRead.await(5, TimeUnit.SECONDS)
                    }
                    channel.writeLine(paneReadResponse(request.id(), read))
                }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)
            controller.selectAgent("reviewer").get(3, TimeUnit.SECONDS)

            assertTrue(firstReadStarted.await(2, TimeUnit.SECONDS))
            Thread.sleep(800)
            releaseFirstRead.countDown()
            Thread.sleep(200)

            assertTrue(reads.get() <= 2, "scheduled pane.read backlog burst to ${reads.get()} requests")
            waitUntil { reads.get() >= 2 }
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `subscription loss freezes stale state and retry bootstraps fresh`() {
        val combinedCount = AtomicInteger()
        val topologyRelease = CountDownLatch(2)
        val disconnectFirst = CountDownLatch(1)
        val finalRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" ->
                    channel.writeLine(
                        responseFixture("agent-capabilities.json", request.id()),
                    )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine(subscriptionStarted(request.id()))
                    if (request.hasPaneStatusSubscriptions()) {
                        topologyRelease.countDown()
                        if (combinedCount.incrementAndGet() == 1) {
                            disconnectFirst.await(5, TimeUnit.SECONDS)
                        } else {
                            finalRelease.await(5, TimeUnit.SECONDS)
                        }
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        finalRelease.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)

            disconnectFirst.countDown()
            waitUntil { controller.currentState() is HerdrUiState.Disconnected }
            val stale = assertIs<HerdrUiState.Disconnected>(controller.currentState()).stale
            assertTrue(stale.stale)
            assertEquals(1, stale.agentCount)

            val requestsBeforeMutation = server.requests.size
            val disabled =
                controller
                    .mutate(
                        HerdrRequest.mutation(
                            "mutate-while-stale",
                            "agent.prompt",
                            JsonObject(
                                mapOf(
                                    "target" to JsonPrimitive("reviewer"),
                                    "text" to JsonPrimitive("must not send"),
                                ),
                            ),
                        ),
                    ).get(3, TimeUnit.SECONDS)
            assertIs<MutationOutcome.DefinitelyNotSent>(disabled)
            assertEquals(requestsBeforeMutation, server.requests.size)

            assertIs<HerdrUiState.Live>(controller.retry().get(3, TimeUnit.SECONDS))
            finalRelease.countDown()
            controller.dispose()
        }
    }

    private fun responseFixture(
        name: String,
        id: String,
    ): String {
        val response = Json.parseToJsonElement(fixture(name)).jsonObject
        return JsonObject(response + ("id" to JsonPrimitive(id))).toString()
    }

    private fun snapshotResponse(
        id: String,
        label: String = "Herdr",
        paneIds: Set<String> = setOf("p-agent", "p-shell"),
    ): String {
        val response = Json.parseToJsonElement(fixture("session-snapshot.json")).jsonObject
        val result = response.getValue("result").jsonObject
        val snapshot = result.getValue("snapshot").jsonObject
        val workspaces =
            snapshot.getValue("workspaces").jsonArray.map { workspaceElement ->
                val workspace = workspaceElement.jsonObject
                JsonObject(
                    workspace +
                        ("label" to JsonPrimitive(label)) +
                        ("pane_count" to JsonPrimitive(paneIds.size)),
                )
            }
        val panes =
            snapshot.getValue("panes").jsonArray.filter {
                it.jsonObject
                    .getValue("pane_id")
                    .jsonPrimitive.content in paneIds
            }
        val agents =
            snapshot.getValue("agents").jsonArray.filter {
                it.jsonObject
                    .getValue("pane_id")
                    .jsonPrimitive.content in paneIds
            }
        val updatedSnapshot =
            JsonObject(
                snapshot +
                    mapOf(
                        "workspaces" to JsonArray(workspaces),
                        "panes" to JsonArray(panes),
                        "agents" to JsonArray(agents),
                    ),
            )
        val updatedResult = JsonObject(result + ("snapshot" to updatedSnapshot))
        return JsonObject(response + mapOf("id" to JsonPrimitive(id), "result" to updatedResult)).toString()
    }

    private fun subscriptionStarted(id: String): String = """{"id":"$id","result":{"type":"subscription_started"}}"""

    private fun errorResponse(
        id: String,
        code: String,
        message: String,
    ): String = """{"id":"$id","error":{"code":"$code","message":"$message"}}"""

    private fun paneReadResponse(
        id: String,
        revision: Int,
        text: String = "revision $revision",
    ): String =
        """{"id":"$id","result":{"type":"pane_read","read":{"pane_id":"p-agent","workspace_id":"w-1","tab_id":"t-1","source":"recent_unwrapped","format":"text","text":"$text","revision":$revision,"truncated":false}}}"""

    private fun tabCreatedResponse(id: String): String =
        """{"id":"$id","result":{"type":"tab_created","tab":{"tab_id":"t-new","workspace_id":"w-1","number":2,"label":"worker_2","focused":false,"pane_count":1,"agent_status":"unknown"},"root_pane":{"pane_id":"p-new","terminal_id":"term-new","workspace_id":"w-1","tab_id":"t-new","focused":false,"cwd":"/repo/worktree","agent_status":"unknown","revision":0}}}"""

    private fun paneStatusEvent(
        status: String,
        paneId: String = "p-agent",
    ): String =
        """{"event":"pane.agent_status_changed","data":{"workspace_id":"w-1","pane_id":"$paneId","agent_status":"$status","agent":"codex","title":"Reviewer","display_agent":"Codex","state_labels":{}}}"""

    private fun paneCreatedEvent(): String =
        """{"event":"pane.created","data":{"type":"pane_created","pane":{"pane_id":"p-new","terminal_id":"term-new","workspace_id":"w-1","tab_id":"t-new","focused":false,"cwd":"/repo/worktree","agent_status":"unknown","revision":0}}}"""

    private fun worktreeResponse(
        id: String,
        alreadyOpen: Boolean,
    ): String {
        val type = if (alreadyOpen) "worktree_opened" else "worktree_created"
        val alreadyOpenField = if (alreadyOpen) ",\"already_open\":true" else ""
        val response =
            """
            {
              "id": "$id",
              "result": {
                "type": "$type",
                "workspace": {
                  "workspace_id": "w-wt",
                  "number": 2,
                  "label": "topic",
                  "focused": false,
                  "pane_count": 1,
                  "tab_count": 1,
                  "active_tab_id": "t-wt",
                  "agent_status": "unknown",
                  "worktree": {
                    "repo_key": "repo-key",
                    "repo_name": "herdr",
                    "repo_root": "/repo",
                    "checkout_path": "/repo/worktrees/topic",
                    "is_linked_worktree": true
                  }
                },
                "tab": {
                  "tab_id": "t-wt",
                  "workspace_id": "w-wt",
                  "number": 1,
                  "label": "topic",
                  "focused": false,
                  "pane_count": 1,
                  "agent_status": "unknown"
                },
                "root_pane": {
                  "pane_id": "p-wt",
                  "terminal_id": "term-wt",
                  "workspace_id": "w-wt",
                  "tab_id": "t-wt",
                  "focused": false,
                  "cwd": "/repo/worktrees/topic",
                  "agent_status": "unknown",
                  "revision": 0
                },
                "worktree": {
                  "path": "/repo/worktrees/topic",
                  "branch": "topic",
                  "is_bare": false,
                  "is_detached": false,
                  "is_prunable": false,
                  "is_linked_worktree": true,
                  "label": "topic"
                }$alreadyOpenField
              }
            }
            """.trimIndent()
        return Json.parseToJsonElement(response).toString()
    }

    private fun answerBootstrap(
        request: JsonObject,
        channel: java.nio.channels.SocketChannel,
        topologyRelease: CountDownLatch,
        streamsRelease: CountDownLatch,
    ) {
        when (request.method()) {
            "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
            "server.agent_capabilities" ->
                channel.writeLine(
                    responseFixture("agent-capabilities.json", request.id()),
                )
            "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
            "events.subscribe" -> {
                channel.writeLine(subscriptionStarted(request.id()))
                if (request.hasPaneStatusSubscriptions()) {
                    topologyRelease.countDown()
                    streamsRelease.await(5, TimeUnit.SECONDS)
                } else {
                    topologyRelease.await(5, TimeUnit.SECONDS)
                    streamsRelease.await(5, TimeUnit.SECONDS)
                }
            }
        }
    }

    private fun snapshotWithAllocationResponse(id: String): String {
        val response = Json.parseToJsonElement(snapshotResponse(id)).jsonObject
        val result = response.getValue("result").jsonObject
        val snapshot = result.getValue("snapshot").jsonObject
        val tabs =
            snapshot.getValue("tabs").jsonArray +
                Json.parseToJsonElement(
                    """{"tab_id":"t-new","workspace_id":"w-1","number":2,"label":"worker_4","focused":false,"pane_count":1,"agent_status":"unknown"}""",
                )
        val panes =
            snapshot.getValue("panes").jsonArray +
                Json.parseToJsonElement(
                    """{"pane_id":"p-new","terminal_id":"term-new","workspace_id":"w-1","tab_id":"t-new","focused":false,"cwd":"/repo/worktree","agent_status":"unknown","revision":0}""",
                )
        val updated =
            JsonObject(
                snapshot +
                    mapOf(
                        "tabs" to JsonArray(tabs),
                        "panes" to JsonArray(panes),
                    ),
            )
        return JsonObject(response + ("result" to JsonObject(result + ("snapshot" to updated)))).toString()
    }

    private fun agentStartedResponse(
        id: String,
        name: String,
    ): String =
        """{"id":"$id","result":{"type":"agent_started","agent":{"terminal_id":"term-new","name":"$name","agent":"codex","display_agent":"Codex","agent_status":"working","workspace_id":"w-1","tab_id":"t-new","pane_id":"p-new","focused":false,"interactive_ready":true,"revision":1},"argv":["codex","--review"]}}"""

    private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content

    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    private fun JsonObject.params(): JsonObject = getValue("params").jsonObject

    private fun JsonObject.hasPaneStatusSubscriptions(): Boolean = method() == "events.subscribe" && paneStatusSubscriptionCount() > 0

    private fun JsonObject.paneStatusSubscriptionCount(): Int =
        getValue("params").jsonObject.getValue("subscriptions").jsonArray.count {
            it.jsonObject
                .getValue("type")
                .jsonPrimitive.content == "pane.agent_status_changed"
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition did not become true" }
            Thread.sleep(10)
        }
    }

    companion object {
        private fun fixture(name: String): String =
            requireNotNull(
                HerdrControllerTest::class.java.getResource("/protocol-22/$name"),
            ).readText()
    }
}
