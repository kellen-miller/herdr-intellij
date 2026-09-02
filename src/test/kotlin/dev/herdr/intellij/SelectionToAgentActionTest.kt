package dev.herdr.intellij

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SelectionToAgentActionTest {
    @Test
    fun `selection review is root relative and ends on the final selected character line`() {
        val review =
            SelectionToAgentAction.selectionReview(
                navigationRoot = Path.of("/repo/worktree"),
                file = Path.of("/repo/worktree/src/App.kt"),
                documentText = "one\ntwo\nthree\n",
                selectionStart = 4,
                selectionEnd = 8,
            )

        assertEquals("src/App.kt", review?.relativePath)
        assertEquals(2, review?.startLine)
        assertEquals(2, review?.endLine)
        assertEquals("two\n", review?.selectedText)
        assertNull(
            SelectionToAgentAction.selectionReview(
                Path.of("/repo/worktree"),
                Path.of("/elsewhere/secret.txt"),
                "secret",
                0,
                6,
            ),
        )
    }
}
