package dev.herdr.intellij

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HerdrModelTest {
    @Test
    fun `snapshot normalizes recognized agents and filters ordinary panes`() {
        val live = HerdrModel.fromSnapshot(snapshot(), capabilities())

        assertEquals(1, live.workspaceCount)
        assertEquals(1, live.agentCount)
        assertEquals(setOf("p-agent", "p-shell"), live.paneIds)
        assertEquals(
            listOf("reviewer"),
            live.workspaces
                .single()
                .agents
                .map(AgentView::name),
        )
        assertTrue(
            live.workspaces
                .single()
                .launchRecords
                .isEmpty(),
        )
    }

    @Test
    fun `plugin provisioning and failed allocations remain visible`() {
        val provisioning =
            ProvisioningRecord(
                id = "launch-1",
                workspaceId = "w-1",
                tabId = "t-1",
                paneId = "p-shell",
                name = "builder",
                kind = "codex",
            )
        val failed =
            FailedLaunch(
                id = "launch-2",
                workspaceId = "w-1",
                tabId = "t-1",
                paneId = "p-failed",
                name = "reviewer-2",
                kind = "claude",
                message = "agent.start rejected",
                ambiguous = false,
            )

        val live =
            HerdrModel.fromSnapshot(
                snapshot(),
                capabilities(),
                provisioning = listOf(provisioning),
                failedLaunches = listOf(failed),
            )

        assertEquals(listOf(provisioning, failed), live.workspaces.single().launchRecords)
    }

    @Test
    fun `event reduction is idempotent and preserves volatile selection`() {
        val base = HerdrModel.select(HerdrModel.fromSnapshot(snapshot(), capabilities()), "reviewer")
        val blocked = HerdrProtocol.decodeEvent(fixture("pane-status-event.json"))

        val once = HerdrModel.reduceEvent(base, blocked)
        val twice = HerdrModel.reduceEvent(once, blocked)

        assertEquals(once, twice)
        assertEquals(
            AgentStatus.BLOCKED,
            once.workspaces
                .single()
                .agents
                .single()
                .status,
        )
        assertEquals("reviewer", once.selection?.agentName)
    }

    @Test
    fun `snapshot reconciliation repairs omitted topology and retains matching output`() {
        val selected =
            HerdrModel.withOutput(
                HerdrModel.select(HerdrModel.fromSnapshot(snapshot(), capabilities()), "reviewer"),
                HerdrPaneRead(
                    "p-agent",
                    "w-1",
                    "t-1",
                    ReadSource.RECENT_UNWRAPPED,
                    ReadFormat.TEXT,
                    "latest",
                    10,
                    false,
                ),
            )
        val repairedSnapshot =
            snapshot().copy(
                workspaces = emptyList(),
                tabs = emptyList(),
                panes = emptyList(),
                agents = emptyList(),
                focusedWorkspaceId = null,
                focusedTabId = null,
                focusedPaneId = null,
            )

        val repaired = HerdrModel.reconcile(selected, repairedSnapshot)

        assertTrue(repaired.workspaces.isEmpty())
        assertNull(repaired.selection)
        assertNull(repaired.recentOutput)
    }

    @Test
    fun `root states expose live stale mismatch and retry semantics`() {
        val live = HerdrModel.fromSnapshot(snapshot(), capabilities())
        val states: List<HerdrUiState> =
            listOf(
                HerdrUiState.NoServer("/tmp/herdr.sock"),
                HerdrUiState.Starting("/tmp/herdr.sock"),
                HerdrUiState.Connecting("/tmp/herdr.sock"),
                HerdrUiState.Incompatible("/tmp/herdr.sock", 22, 21, "wrong protocol"),
                HerdrUiState.Live(live),
                HerdrUiState.Disconnected(live, "socket closed"),
            )

        assertIs<HerdrUiState.NoServer>(states[0])
        assertIs<HerdrUiState.Starting>(states[1])
        assertIs<HerdrUiState.Connecting>(states[2])
        assertIs<HerdrUiState.Incompatible>(states[3])
        assertIs<HerdrUiState.Live>(states[4])
        assertIs<HerdrUiState.Disconnected>(states[5])
        assertTrue((states[5] as HerdrUiState.Disconnected).stale.stale)
    }

    @Test
    fun `refresh resolves ambiguous retained pane and clears uncertainty gate`() {
        val failed =
            FailedLaunch(
                id = "launch-unknown",
                workspaceId = "w-1",
                tabId = "t-1",
                paneId = "p-shell",
                name = "builder",
                kind = "codex",
                message = "connection closed",
                ambiguous = true,
            )
        val current =
            HerdrModel.fromSnapshot(
                snapshot(),
                capabilities(),
                failedLaunches = listOf(failed),
                actionErrors = listOf(ActionError(HerdrAction.START_AGENT, "unknown", true)),
            )

        val refreshed = HerdrModel.reconcile(current, snapshot())

        assertEquals(false, refreshed.failedLaunches.single().ambiguous)
        assertTrue(refreshed.failedLaunches.single().retryConfirmed)
        assertTrue(refreshed.actionErrors.isEmpty())
        assertTrue("p-shell" !in refreshed.topology.agents)
    }

    private fun snapshot(): HerdrSnapshot =
        HerdrProtocol.decodeSnapshot(
            fixture("session-snapshot.json"),
            "snapshot-1",
        )

    private fun capabilities(): List<AgentCapability> =
        HerdrProtocol.decodeCapabilities(
            fixture("agent-capabilities.json"),
            "capabilities-1",
        )

    private fun fixture(name: String): String =
        requireNotNull(
            javaClass.getResource("/protocol-22/$name"),
        ).readText()
}
