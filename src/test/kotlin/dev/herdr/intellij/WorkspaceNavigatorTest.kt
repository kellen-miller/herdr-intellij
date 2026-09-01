package dev.herdr.intellij

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceNavigatorTest {
    @Test
    fun `project ownership prefers exact base then longest containing content root`() {
        val nested =
            listOf(
                ProjectRoots("outer", Path.of("/repo"), listOf(Path.of("/repo"))),
                ProjectRoots("inner", Path.of("/other"), listOf(Path.of("/repo/worktree"))),
                ProjectRoots("exact", Path.of("/repo/worktree/src"), listOf(Path.of("/unrelated"))),
            )

        assertEquals(
            "exact",
            WorkspaceNavigator.owningProject(Path.of("/repo/worktree/src"), nested)?.id,
        )
        assertEquals(
            "inner",
            WorkspaceNavigator.owningProject(Path.of("/repo/worktree/src/App.kt"), nested)?.id,
        )
        assertEquals(
            "a-exact",
            WorkspaceNavigator
                .owningProject(
                    Path.of("/repo/worktree/src"),
                    listOf(
                        ProjectRoots("z-exact", Path.of("/repo/worktree/src"), emptyList()),
                        ProjectRoots("a-exact", Path.of("/repo/worktree/src"), emptyList()),
                    ),
                )?.id,
        )
    }
}
