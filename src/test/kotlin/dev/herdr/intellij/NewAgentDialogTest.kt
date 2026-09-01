package dev.herdr.intellij

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NewAgentDialogTest {
    @Test
    fun `dialog validates all three sources exact names capabilities and argument list`() {
        val directory = Files.createTempDirectory("herdr-new-agent").toRealPath()
        val capabilities = listOf(AgentCapability("codex", "Codex"))

        val existing =
            NewAgentDialog.validateIntent(
                NewAgentSourceChoice.EXISTING_WORKSPACE,
                "w-1",
                null,
                null,
                "review_agent-2",
                "codex",
                listOf("--profile", "review mode"),
                setOf("w-1"),
                capabilities,
            )
        assertIs<NewAgentSource.ExistingWorkspace>(existing.source)
        assertEquals(listOf("--profile", "review mode"), existing.arguments)

        val project =
            NewAgentDialog.validateIntent(
                NewAgentSourceChoice.CURRENT_PROJECT,
                null,
                directory.toString(),
                null,
                "worker",
                "codex",
                emptyList(),
                setOf("w-1"),
                capabilities,
            )
        assertEquals(directory.toString(), assertIs<NewAgentSource.Directory>(project.source).path)

        val pickedDirectory =
            NewAgentDialog.validateIntent(
                NewAgentSourceChoice.DIRECTORY,
                null,
                null,
                directory.toString(),
                "directory_worker",
                "codex",
                emptyList(),
                setOf("w-1"),
                capabilities,
            )
        assertEquals(directory.toString(), assertIs<NewAgentSource.Directory>(pickedDirectory.source).path)

        assertFailsWith<IllegalArgumentException> {
            NewAgentDialog.validateIntent(
                NewAgentSourceChoice.DIRECTORY,
                null,
                null,
                directory.toString(),
                "Worker",
                "unknown",
                emptyList(),
                setOf("w-1"),
                capabilities,
            )
        }
    }
}
