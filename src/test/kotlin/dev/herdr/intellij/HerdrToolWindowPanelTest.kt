package dev.herdr.intellij

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Container
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HerdrToolWindowPanelTest {
    @Test
    fun `six root cards and responsive boundary are deterministic`() {
        val empty =
            HerdrModel.fromSnapshot(
                HerdrSnapshot(
                    version = "0.7.0",
                    protocol = 22,
                    workspaces = emptyList(),
                    tabs = emptyList(),
                    panes = emptyList(),
                    layouts = emptyList(),
                    agents = emptyList(),
                ),
                emptyList(),
            )
        val live =
            empty.copy(
                workspaces =
                    listOf(
                        WorkspaceView("w-1", 1, "Herdr", false, "/repo", emptyList(), emptyList()),
                    ),
            )

        assertEquals(RootCard.NO_SERVER, HerdrToolWindowPanel.rootCard(HerdrUiState.NoServer("/tmp/s")))
        assertEquals(RootCard.CONNECTING, HerdrToolWindowPanel.rootCard(HerdrUiState.Starting("/tmp/s")))
        assertEquals(RootCard.CONNECTING, HerdrToolWindowPanel.rootCard(HerdrUiState.Connecting("/tmp/s")))
        assertEquals(
            RootCard.INCOMPATIBLE,
            HerdrToolWindowPanel.rootCard(HerdrUiState.Incompatible("/tmp/s", 22, 21, "mismatch")),
        )
        assertEquals(RootCard.LIVE_EMPTY, HerdrToolWindowPanel.rootCard(HerdrUiState.Live(empty)))
        assertEquals(RootCard.LIVE, HerdrToolWindowPanel.rootCard(HerdrUiState.Live(live)))
        assertEquals(RootCard.DISCONNECTED, HerdrToolWindowPanel.rootCard(HerdrUiState.Disconnected(live, "lost")))
        assertEquals(LivePresentation.COMPACT, HerdrToolWindowPanel.livePresentation(639))
        assertEquals(LivePresentation.SPLIT, HerdrToolWindowPanel.livePresentation(640))
    }

    @Test
    fun `back to agents is visible only in compact presentation`() {
        assertFalse(HerdrToolWindowPanel.backToAgentsVisible(LivePresentation.SPLIT))
        assertTrue(HerdrToolWindowPanel.backToAgentsVisible(LivePresentation.COMPACT))
    }

    @Test
    fun `create worktree cancel aborts while an accepted blank base means head`() {
        assertNull(HerdrToolWindowPanel.createWorktreeIntent("topic", null))

        val intent = assertNotNull(HerdrToolWindowPanel.createWorktreeIntent("topic", ""))
        assertEquals("topic", intent.branch)
        assertNull(intent.base)
    }

    @Test
    fun `pending review can be dismissed while the agent is blocked`() {
        val topologyRelease = CountDownLatch(1)
        val streamsRelease = CountDownLatch(1)
        ScriptedHerdrServer { _, request, channel ->
            when (request.method()) {
                "ping" -> channel.writeLine(responseFixture("ping.json", request.id()))
                "server.agent_capabilities" -> channel.writeLine(responseFixture("agent-capabilities.json", request.id()))
                "session.snapshot" -> channel.writeLine(snapshotWithoutRootResponse(request.id()))
                "events.subscribe" -> {
                    channel.writeLine("""{"id":"${request.id()}","result":{"type":"subscription_started"}}""")
                    if (request.hasPaneStatusSubscriptions()) {
                        channel.writeLine(fixture("pane-status-event.json"))
                        topologyRelease.countDown()
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    } else {
                        topologyRelease.await(5, TimeUnit.SECONDS)
                        streamsRelease.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        }.use { server ->
            val controller = HerdrController(HerdrConnection(server.socketPath))
            controller.connect().get(3, TimeUnit.SECONDS)
            controller.selectAgent("reviewer").get(3, TimeUnit.SECONDS)
            controller
                .prepareSelectionReview(SelectionReview("src/Auth.kt", 4, 6, "authorize()"))
                .get(3, TimeUnit.SECONDS)
            var panel: HerdrToolWindowPanel? = null
            SwingUtilities.invokeAndWait {
                panel =
                    HerdrToolWindowPanel(
                        testProject(),
                        controller,
                        HerdrSettings(),
                    )
            }

            val dismiss = panel!!.findButton("Cancel review")
            assertNotNull(dismiss)
            SwingUtilities.invokeAndWait(dismiss::doClick)
            waitUntil {
                (controller.currentState() as? HerdrUiState.Live)?.view?.selectionReview == null
            }

            SwingUtilities.invokeAndWait(panel!!::dispose)
            streamsRelease.countDown()
            controller.dispose()
        }
    }

    @Test
    fun `plugin registers only the editor review action from the command allowlist`() {
        val pluginXml = requireNotNull(javaClass.getResource("/META-INF/plugin.xml")).readText()

        assertEquals(1, Regex("<action\\s").findAll(pluginXml).count())
        assertTrue("dev.herdr.SelectionToAgent" in pluginXml)
        assertTrue("Herdr Agent Status" in pluginXml)
        assertTrue("dev.herdr.settings" in pluginXml)
        listOf("server.stop", "delete", "rename", "reorder", "pane.split", "layout.apply", "raw").forEach {
            assertFalse(it in pluginXml)
        }
    }

    private fun Container.findButton(text: String): JButton? {
        components.forEach { component ->
            if (component is JButton && component.text == text) {
                return component
            }
            if (component is Container) {
                component.findButton(text)?.let { return it }
            }
        }
        return null
    }

    private fun responseFixture(
        name: String,
        id: String,
    ): String {
        val response = Json.parseToJsonElement(fixture(name)).jsonObject
        return JsonObject(response + ("id" to JsonPrimitive(id))).toString()
    }

    private fun snapshotWithoutRootResponse(id: String): String {
        val response = Json.parseToJsonElement(fixture("session-snapshot.json")).jsonObject
        val result = response.getValue("result").jsonObject
        val snapshot = result.getValue("snapshot").jsonObject
        val workspaces =
            snapshot.getValue("workspaces").jsonArray.map { workspace ->
                JsonObject(workspace.jsonObject - "worktree")
            }
        val panes =
            snapshot.getValue("panes").jsonArray.map { pane ->
                JsonObject(pane.jsonObject - "cwd")
            }
        val agents =
            snapshot.getValue("agents").jsonArray.map { agent ->
                JsonObject(agent.jsonObject - "cwd")
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
        return JsonObject(
            response +
                mapOf(
                    "id" to JsonPrimitive(id),
                    "result" to JsonObject(result + ("snapshot" to updatedSnapshot)),
                ),
        ).toString()
    }

    private fun testProject(): Project =
        Proxy.newProxyInstance(
            Project::class.java.classLoader,
            arrayOf(Project::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isDisposed" -> false
                "getName" -> "Herdr test"
                "getLocationHash" -> "herdr-test"
                else -> null
            }
        } as Project

    private fun fixture(name: String): String =
        requireNotNull(
            javaClass.getResource("/protocol-22/$name"),
        ).readText()

    private fun JsonObject.id(): String = getValue("id").jsonPrimitive.content

    private fun JsonObject.method(): String = getValue("method").jsonPrimitive.content

    private fun JsonObject.hasPaneStatusSubscriptions(): Boolean =
        method() == "events.subscribe" &&
            getValue("params").jsonObject.getValue("subscriptions").jsonArray.any {
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
}
