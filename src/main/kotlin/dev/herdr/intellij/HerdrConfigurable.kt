package dev.herdr.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JPanel

class HerdrConfigurable : Configurable {
    private var panel: JPanel? = null
    private var socketField: JBTextField? = null
    private var executableField: JBTextField? = null

    override fun getDisplayName(): String = HerdrBundle.message("settings.title")

    override fun createComponent(): JComponent {
        val socket = JBTextField()
        val executable = JBTextField()
        socket.accessibleContext.accessibleName = HerdrBundle.message("settings.socket.accessible")
        executable.accessibleContext.accessibleName = HerdrBundle.message("settings.executable.accessible")
        socketField = socket
        executableField = executable
        panel =
            JPanel(GridBagLayout()).apply {
                border = JBUI.Borders.empty(8)
                add(
                    JBLabel(HerdrBundle.message("settings.socket")),
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = 0
                        anchor = GridBagConstraints.LINE_START
                        insets = Insets(4, 0, 4, 12)
                    },
                )
                add(
                    socket,
                    GridBagConstraints().apply {
                        gridx = 1
                        gridy = 0
                        weightx = 1.0
                        fill = GridBagConstraints.HORIZONTAL
                        insets = Insets(4, 0, 4, 0)
                    },
                )
                add(
                    JBLabel(HerdrBundle.message("settings.executable")),
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = 1
                        anchor = GridBagConstraints.LINE_START
                        insets = Insets(4, 0, 4, 12)
                    },
                )
                add(
                    executable,
                    GridBagConstraints().apply {
                        gridx = 1
                        gridy = 1
                        weightx = 1.0
                        fill = GridBagConstraints.HORIZONTAL
                        insets = Insets(4, 0, 4, 0)
                    },
                )
                add(
                    JBLabel(HerdrBundle.message("settings.restartNote")),
                    GridBagConstraints().apply {
                        gridx = 0
                        gridy = 2
                        gridwidth = 2
                        weighty = 1.0
                        anchor = GridBagConstraints.FIRST_LINE_START
                        insets = Insets(8, 0, 0, 0)
                    },
                )
            }
        reset()
        return requireNotNull(panel)
    }

    override fun isModified(): Boolean {
        val state = HerdrSettings.getInstance().state
        return socketField?.text?.trim() != state.socketOverride ||
            executableField?.text?.trim() != state.executableOverride
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val settings = HerdrSettings.getInstance()
        val overrides =
            try {
                settings.applyOverrides(socketField?.text.orEmpty(), executableField?.text.orEmpty())
            } catch (failure: IllegalArgumentException) {
                throw ConfigurationException(failure.message ?: HerdrBundle.message("settings.invalid"))
            }
        ApplicationManager.getApplication().getService(HerdrController::class.java).reconfigure(overrides)
        reset()
    }

    override fun reset() {
        val state = HerdrSettings.getInstance().state
        socketField?.text = state.socketOverride
        executableField?.text = state.executableOverride
    }

    override fun disposeUIResources() {
        panel = null
        socketField = null
        executableField = null
    }
}
