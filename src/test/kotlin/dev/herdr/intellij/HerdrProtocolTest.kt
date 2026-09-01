package dev.herdr.intellij

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HerdrProtocolTest {
    @Test
    fun `protocol 22 ping accepts unknown fields`() {
        val pong = HerdrProtocol.decodeCompatiblePing(fixture("ping.json"), "ping-1")

        assertEquals("0.7.0", pong.version)
        assertEquals(22, pong.protocol)
        assertTrue(pong.capabilities?.detachedServerDaemon == true)
    }

    @Test
    fun `protocol 21 ping is rejected exactly`() {
        val failure = assertFailsWith<HerdrProtocolMismatch> {
            HerdrProtocol.decodeCompatiblePing(fixture("ping-21.json"), "ping-1")
        }

        assertEquals(22, failure.expected)
        assertEquals(21, failure.actual)
    }

    @Test
    fun `capability and snapshot fixtures decode to typed data`() {
        val capabilities = HerdrProtocol.decodeCapabilities(
            fixture("agent-capabilities.json"),
            "capabilities-1",
        )
        val snapshot = HerdrProtocol.decodeSnapshot(fixture("session-snapshot.json"), "snapshot-1")

        assertEquals(23, capabilities.size)
        assertEquals("qodercli", capabilities[19].kind)
        assertEquals(2, snapshot.panes.size)
        assertEquals("/repo/worktree", snapshot.workspaces.single().worktree?.checkoutPath)
    }

    @Test
    fun `malformed required identifiers and unknown result tags fail closed`() {
        assertFailsWith<HerdrProtocolException> {
            HerdrProtocol.decodeSnapshot(
                fixture("session-snapshot.json").replace("\"pane_id\": \"p-agent\"", "\"pane_id\": \"\""),
                "snapshot-1",
            )
        }
        assertFailsWith<HerdrProtocolException> {
            HerdrProtocol.decodeResponse(
                """{"id":"request-1","result":{"type":"future_result"}}""",
                "request-1",
            )
        }
        assertFailsWith<HerdrProtocolException> {
            HerdrProtocol.decodeResponse("{}", "request-1")
        }
    }

    @Test
    fun `request factories preserve protocol 22 method and parameter shapes`() {
        assertEquals(
            """{"id":"ping-1","method":"ping","params":{}}""",
            HerdrProtocol.encode(HerdrRequest.ping("ping-1")),
        )
        assertEquals(
            "recent_unwrapped",
            HerdrProtocol.requestObject(HerdrRequest.paneRead("read-1", "p-agent"))
                .getValue("params").jsonObject.getValue("source").jsonPrimitive.content,
        )
        val subscriptions = HerdrProtocol.requestObject(
            HerdrRequest.combinedSubscription("subscribe-1", setOf("p-agent", "p-shell")),
        ).getValue("params").jsonObject.getValue("subscriptions").jsonArray

        assertTrue(subscriptions.any { it.jsonObject["type"]?.jsonPrimitive?.content == "workspace.created" })
        assertEquals(2, subscriptions.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "pane.agent_status_changed"
        })
    }

    @Test
    fun `status event pane read and method errors decode`() {
        val event = HerdrProtocol.decodeEvent(fixture("pane-status-event.json"))
        val read = HerdrProtocol.decodePaneRead(fixture("pane-read.json"), "read-1")
        val error = HerdrProtocol.decodeResponse(fixture("error.json"), "subscribe-1")

        assertEquals(AgentStatus.BLOCKED, (event as HerdrEvent.PaneStatusChanged).status)
        assertEquals(8, read.revision)
        assertEquals("bounded output\n", read.text)
        assertEquals("pane_not_found", (error as HerdrResponse.Error).code)
    }

    @Test
    fun `mutation and layout payloads remain typed`() {
        val started = HerdrProtocol.decodeResponse(fixture("agent-started.json"), "start-1")
        val layout = HerdrProtocol.decodeEvent(fixture("layout-updated-event.json"))

        assertEquals(
            "reviewer",
            assertIs<HerdrResult.AgentStarted>((started as HerdrResponse.Success).result).agent.name,
        )
        assertEquals(
            "p-agent",
            assertIs<HerdrEvent.LayoutUpdated>(layout).layout.focusedPaneId,
        )
    }

    @Test
    fun `topology event variants require their complete tagged payload`() {
        assertFailsWith<HerdrProtocolException> {
            HerdrProtocol.decodeEvent(
                """{"event":"workspace.moved","data":{"type":"workspace_moved","workspace_id":"w-1","workspaces":[]}}""",
            )
        }
        assertFailsWith<HerdrProtocolException> {
            HerdrProtocol.decodeEvent(
                """{"event":"tab.moved","data":{"type":"tab_moved","tab_id":"t-1","workspace_id":"w-1","tabs":[]}}""",
            )
        }
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/protocol-22/$name")
    ).readText()
}
