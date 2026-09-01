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
        val missing = java.nio.file.Files.createTempDirectory("herdr-controller-start")
            .resolve("herdr.sock")
        val controller = HerdrController(HerdrConnection(missing, mapOf("PATH" to "")))

        val state = assertIs<HerdrUiState.NoServer>(
            controller.startHerdr().get(3, TimeUnit.SECONDS),
        )

        assertEquals("could not find an executable herdr binary", state.diagnostic)
        controller.dispose()
    }

    @Test
    fun `missing socket remains no server`() {
        val missing = java.nio.file.Files.createTempDirectory("herdr-controller-missing")
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
                "server.agent_capabilities" -> channel.writeLine(
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
                live?.workspaces?.singleOrNull()?.agents?.singleOrNull()?.status == AgentStatus.BLOCKED
            }
            val live = assertIs<HerdrUiState.Live>(controller.currentState()).view

            assertEquals(AgentStatus.BLOCKED, live.workspaces.single().agents.single().status)
            assertEquals(2, server.requests.count { it.method() == "session.snapshot" })
            assertEquals(2, server.requests.count { it.method() == "events.subscribe" })
            combinedRelease.countDown()
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
                "server.agent_capabilities" -> channel.writeLine(
                    responseFixture("agent-capabilities.json", request.id()),
                )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> if (!request.hasPaneStatusSubscriptions()) {
                    channel.writeLine(subscriptionStarted(request.id()))
                    topologyRelease.await(5, TimeUnit.SECONDS)
                    combinedRelease.await(5, TimeUnit.SECONDS)
                } else if (attempts.incrementAndGet() < 3) {
                    channel.writeLine(errorResponse(request.id(), "pane_not_found", "pane vanished"))
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
                "server.agent_capabilities" -> channel.writeLine(
                    responseFixture("agent-capabilities.json", request.id()),
                )
                "session.snapshot" -> channel.writeLine(snapshotResponse(request.id()))
                "events.subscribe" -> if (!request.hasPaneStatusSubscriptions()) {
                    channel.writeLine(subscriptionStarted(request.id()))
                    topologyRelease.await(5, TimeUnit.SECONDS)
                    finish.await(5, TimeUnit.SECONDS)
                } else {
                    attempts.incrementAndGet()
                    channel.writeLine(errorResponse(request.id(), "pane_not_found", "pane vanished"))
                }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))

            val state = assertIs<HerdrUiState.Connecting>(
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
                "server.agent_capabilities" -> channel.writeLine(
                    responseFixture("agent-capabilities.json", request.id()),
                )
                "session.snapshot" -> {
                    val count = snapshots.incrementAndGet()
                    val paneIds = if (count >= 4) setOf("p-agent") else setOf("p-agent", "p-shell")
                    val label = if (count >= 3) "Renamed without event" else "Herdr"
                    channel.writeLine(snapshotResponse(request.id(), label, paneIds))
                }
                "events.subscribe" -> if (!request.hasPaneStatusSubscriptions()) {
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

            val reconciled = assertIs<HerdrUiState.Live>(
                controller.reconcileNow().get(3, TimeUnit.SECONDS),
            ).view
            assertEquals("Renamed without event", reconciled.workspaces.single().label)

            sendPaneClose.countDown()
            waitUntil { combinedSubscriptions.get() == 2 }
            val rebuilt = assertIs<HerdrUiState.Live>(controller.currentState()).view
            assertEquals(setOf("p-agent"), rebuilt.paneIds)
            val lastCombined = server.requests.last { it.hasPaneStatusSubscriptions() }
            assertEquals(1, lastCombined.paneStatusSubscriptionCount())
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `selected output publishes only changed revisions`() {
        val reads = AtomicInteger()
        val topologyRelease = CountDownLatch(1)
        val combinedRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" -> channel.writeLine(
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
                    val revision = if (reads.incrementAndGet() < 3) 8 else 9
                    channel.writeLine(paneReadResponse(request.id(), revision))
                }
            }
        }.use { server ->
            val revisions = CopyOnWriteArrayList<Long>()
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.addStateListener { state ->
                if (state is HerdrUiState.Live) {
                    state.view.recentOutput?.revision?.let(revisions::add)
                }
            }
            controller.connect().get(3, TimeUnit.SECONDS)
            controller.selectAgent("reviewer").get(3, TimeUnit.SECONDS)

            controller.pollSelectedNow().get(3, TimeUnit.SECONDS)
            controller.pollSelectedNow().get(3, TimeUnit.SECONDS)
            controller.pollSelectedNow().get(3, TimeUnit.SECONDS)

            assertEquals(listOf(8L, 9L), revisions.distinct())
            combinedRelease.countDown()
            controller.dispose()
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
                "server.agent_capabilities" -> channel.writeLine(
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
                "server.agent_capabilities" -> channel.writeLine(
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
            val disabled = controller.mutate(HerdrRequest.mutation(
                "mutate-while-stale",
                "agent.prompt",
                JsonObject(mapOf(
                    "target" to JsonPrimitive("reviewer"),
                    "text" to JsonPrimitive("must not send"),
                )),
            )).get(3, TimeUnit.SECONDS)
            assertIs<MutationOutcome.DefinitelyNotSent>(disabled)
            assertEquals(requestsBeforeMutation, server.requests.size)

            assertIs<HerdrUiState.Live>(controller.retry().get(3, TimeUnit.SECONDS))
            finalRelease.countDown()
            controller.dispose()
        }
    }

    private fun responseFixture(name: String, id: String): String {
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
        val workspaces = snapshot.getValue("workspaces").jsonArray.map { workspaceElement ->
            val workspace = workspaceElement.jsonObject
            JsonObject(workspace +
                ("label" to JsonPrimitive(label)) +
                ("pane_count" to JsonPrimitive(paneIds.size)))
        }
        val panes = snapshot.getValue("panes").jsonArray.filter {
            it.jsonObject.getValue("pane_id").jsonPrimitive.content in paneIds
        }
        val agents = snapshot.getValue("agents").jsonArray.filter {
            it.jsonObject.getValue("pane_id").jsonPrimitive.content in paneIds
        }
        val updatedSnapshot = JsonObject(snapshot + mapOf(
            "workspaces" to JsonArray(workspaces),
            "panes" to JsonArray(panes),
            "agents" to JsonArray(agents),
        ))
        val updatedResult = JsonObject(result + ("snapshot" to updatedSnapshot))
        return JsonObject(response + mapOf("id" to JsonPrimitive(id), "result" to updatedResult)).toString()
    }

    private fun subscriptionStarted(id: String): String =
        """{"id":"$id","result":{"type":"subscription_started"}}"""

    private fun errorResponse(id: String, code: String, message: String): String =
        """{"id":"$id","error":{"code":"$code","message":"$message"}}"""

    private fun paneReadResponse(id: String, revision: Int): String =
        """{"id":"$id","result":{"type":"pane_read","read":{"pane_id":"p-agent","workspace_id":"w-1","tab_id":"t-1","source":"recent_unwrapped","format":"text","text":"revision $revision","revision":$revision,"truncated":false}}}"""

    private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content

    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    private fun JsonObject.hasPaneStatusSubscriptions(): Boolean =
        method() == "events.subscribe" && paneStatusSubscriptionCount() > 0

    private fun JsonObject.paneStatusSubscriptionCount(): Int =
        getValue("params").jsonObject.getValue("subscriptions").jsonArray.count {
            it.jsonObject.getValue("type").jsonPrimitive.content == "pane.agent_status_changed"
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition did not become true" }
            Thread.sleep(10)
        }
    }

    companion object {
        private fun fixture(name: String): String = requireNotNull(
            HerdrControllerTest::class.java.getResource("/protocol-22/$name")
        ).readText()
    }
}
