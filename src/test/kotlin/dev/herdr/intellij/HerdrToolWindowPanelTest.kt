package dev.herdr.intellij

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
}
