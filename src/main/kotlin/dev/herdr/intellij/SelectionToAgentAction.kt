package dev.herdr.intellij

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class SelectionToAgentAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = review(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val review = review(event) ?: return
        val controller = ApplicationManager.getApplication().getService(HerdrController::class.java)
        ToolWindowManager.getInstance(project).getToolWindow("Herdr")?.activate({
            controller.prepareSelectionReview(review)
        }, true)
    }

    private fun review(event: AnActionEvent): SelectionReview? {
        val project = event.project ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val selection = editor.selectionModel
        if (!selection.hasSelection()) {
            return null
        }
        val controller = ApplicationManager.getApplication().getService(HerdrController::class.java)
        val live = (controller.currentState() as? HerdrUiState.Live)?.view?.takeUnless { it.stale } ?: return null
        val selected = live.selection ?: return null
        val workspace =
            live.workspaces.singleOrNull { workspace ->
                workspace.agents.any {
                    it.paneId == selected.paneId && it.name == selected.agentName && it.targetResolved
                }
            } ?: return null
        val root = workspace.navigationRoot ?: return null
        if (project.isDisposed) {
            return null
        }
        return selectionReview(
            Path.of(root),
            Path.of(file.path),
            editor.document.text,
            selection.selectionStart,
            selection.selectionEnd,
        )
    }

    companion object {
        internal fun selectionReview(
            navigationRoot: Path,
            file: Path,
            documentText: String,
            selectionStart: Int,
            selectionEnd: Int,
        ): SelectionReview? {
            if (selectionStart < 0 || selectionEnd <= selectionStart || selectionEnd > documentText.length) {
                return null
            }
            val root = canonicalPath(navigationRoot)
            val target = canonicalPath(file)
            if (!target.startsWith(root)) {
                return null
            }
            val startLine = 1 + documentText.take(selectionStart).count { it == '\n' }
            val endLine = 1 + documentText.take(selectionEnd - 1).count { it == '\n' }
            return SelectionReview(
                relativePath = root.relativize(target).toString().replace(File.separatorChar, '/'),
                startLine = startLine,
                endLine = endLine,
                selectedText = documentText.substring(selectionStart, selectionEnd),
            )
        }

        private fun canonicalPath(path: Path): Path =
            try {
                if (Files.exists(path)) path.toRealPath() else path.toAbsolutePath().normalize()
            } catch (_: Exception) {
                path.toAbsolutePath().normalize()
            }
    }
}
