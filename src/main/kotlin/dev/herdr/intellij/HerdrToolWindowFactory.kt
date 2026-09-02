package dev.herdr.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class HerdrToolWindowFactory :
    ToolWindowFactory,
    DumbAware {
    override fun createToolWindowContent(
        project: Project,
        toolWindow: ToolWindow,
    ) {
        val controller = ApplicationManager.getApplication().getService(HerdrController::class.java)
        val panel = HerdrToolWindowPanel(project, controller, HerdrSettings.getInstance())
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        if (controller.currentState() is HerdrUiState.NoServer) {
            controller.connect()
        }
    }
}
