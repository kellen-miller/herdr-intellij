package dev.herdr.intellij

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

internal enum class NewAgentSourceChoice {
    EXISTING_WORKSPACE,
    CURRENT_PROJECT,
    DIRECTORY,
}

internal sealed interface NewAgentSource {
    data class ExistingWorkspace(
        val workspaceId: String,
    ) : NewAgentSource

    data class Directory(
        val path: String,
    ) : NewAgentSource
}

internal data class NewAgentIntent(
    val source: NewAgentSource,
    val name: String,
    val kind: String,
    val arguments: List<String>,
) {
    init {
        require(NAME_PATTERN.matches(name)) { "Agent name must match [a-z][a-z0-9_-]{0,31}" }
        require(kind.isNotBlank()) { "Agent kind is required" }
    }

    companion object {
        val NAME_PATTERN = Regex("[a-z][a-z0-9_-]{0,31}")
    }
}

internal sealed interface AgentLaunchOutcome {
    data class Started(
        val agent: HerdrAgent,
    ) : AgentLaunchOutcome

    data class Failed(
        val record: FailedLaunch,
    ) : AgentLaunchOutcome

    data class AllocationFailed(
        val error: ActionError,
    ) : AgentLaunchOutcome
}

internal class NewAgentDialog(
    private val project: Project,
    private val view: HerdrLiveView,
) : DialogWrapper(project) {
    private val source = JComboBox(NewAgentSourceChoice.entries.toTypedArray())
    private val workspace = JComboBox(view.workspaces.toTypedArray())
    private val directory = TextFieldWithBrowseButton()
    private val kind = JComboBox(view.capabilities.toTypedArray())
    private val name = JBTextField()
    private val argumentsModel = CollectionListModel<String>()
    private val arguments = JBList(argumentsModel)

    init {
        title = HerdrBundle.message("newAgent.title")
        source.renderer =
            labelRenderer { value ->
                when (value as? NewAgentSourceChoice) {
                    NewAgentSourceChoice.EXISTING_WORKSPACE -> HerdrBundle.message("newAgent.source.existing")
                    NewAgentSourceChoice.CURRENT_PROJECT -> HerdrBundle.message("newAgent.source.project")
                    NewAgentSourceChoice.DIRECTORY -> HerdrBundle.message("newAgent.source.directory")
                    null -> ""
                }
            }
        workspace.renderer = labelRenderer { value -> (value as? WorkspaceView)?.label.orEmpty() }
        kind.renderer =
            labelRenderer { value ->
                (value as? AgentCapability)?.let { "${it.label} (${it.kind})" }.orEmpty()
            }
        source.addActionListener { updateSourceFields() }
        directory.addActionListener {
            FileChooser
                .chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                    project,
                    null,
                )?.let { directory.text = it.path }
        }
        arguments.visibleRowCount = 4
        name.emptyText.text = HerdrBundle.message("newAgent.name.placeholder")
        source.accessibleContext.accessibleName = HerdrBundle.message("newAgent.source.accessible")
        workspace.accessibleContext.accessibleName = HerdrBundle.message("newAgent.workspace.accessible")
        directory.accessibleContext.accessibleName = HerdrBundle.message("newAgent.directory.accessible")
        kind.accessibleContext.accessibleName = HerdrBundle.message("newAgent.kind.accessible")
        name.accessibleContext.accessibleName = HerdrBundle.message("newAgent.name.accessible")
        arguments.accessibleContext.accessibleName = HerdrBundle.message("newAgent.arguments.accessible")
        if (view.workspaces.isEmpty()) {
            source.selectedItem = NewAgentSourceChoice.CURRENT_PROJECT
        }
        updateSourceFields()
        init()
    }

    fun resultIntent(): NewAgentIntent? = if (showAndGet()) currentIntent() else null

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        var row = 0

        fun addRow(
            label: String,
            component: JComponent,
        ) {
            panel.add(
                JBLabel(label),
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = row
                    anchor = GridBagConstraints.LINE_START
                    insets = Insets(4, 0, 4, 12)
                },
            )
            panel.add(
                component,
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = row++
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    insets = Insets(4, 0, 4, 0)
                },
            )
        }

        addRow(HerdrBundle.message("newAgent.source"), source)
        addRow(HerdrBundle.message("newAgent.workspace"), workspace)
        addRow(HerdrBundle.message("newAgent.directory"), directory)
        addRow(HerdrBundle.message("newAgent.kind"), kind)
        addRow(HerdrBundle.message("newAgent.name"), name)

        val argumentButtons =
            JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                add(
                    JButton(HerdrBundle.message("newAgent.argument.add")).apply {
                        addActionListener {
                            Messages
                                .showInputDialog(
                                    project,
                                    HerdrBundle.message("newAgent.argument.prompt"),
                                    HerdrBundle.message("newAgent.argument.add"),
                                    null,
                                )?.let(argumentsModel::add)
                        }
                    },
                )
                add(
                    JButton(HerdrBundle.message("newAgent.argument.remove")).apply {
                        addActionListener {
                            arguments.selectedIndices.sortedDescending().forEach(argumentsModel::remove)
                        }
                    },
                )
            }
        val argumentPanel =
            JPanel(BorderLayout(8, 0)).apply {
                add(JBScrollPane(arguments), BorderLayout.CENTER)
                add(argumentButtons, BorderLayout.EAST)
            }
        addRow(HerdrBundle.message("newAgent.arguments"), argumentPanel)
        return panel
    }

    override fun doValidate(): ValidationInfo? =
        try {
            currentIntent()
            null
        } catch (failure: IllegalArgumentException) {
            ValidationInfo(failure.message ?: HerdrBundle.message("newAgent.invalid"), name)
        }

    private fun currentIntent(): NewAgentIntent =
        validateIntent(
            source.selectedItem as NewAgentSourceChoice,
            (workspace.selectedItem as? WorkspaceView)?.id,
            project.basePath,
            directory.text,
            name.text,
            (kind.selectedItem as? AgentCapability)?.kind,
            argumentsModel.items.toList(),
            view.workspaces.mapTo(mutableSetOf(), WorkspaceView::id),
            view.capabilities,
        )

    private fun updateSourceFields() {
        val selected = source.selectedItem as NewAgentSourceChoice
        workspace.isEnabled = selected == NewAgentSourceChoice.EXISTING_WORKSPACE
        directory.isEnabled = selected == NewAgentSourceChoice.DIRECTORY
    }

    companion object {
        internal fun validateIntent(
            source: NewAgentSourceChoice,
            workspaceId: String?,
            currentProjectPath: String?,
            directoryPath: String?,
            name: String,
            kind: String?,
            arguments: List<String>,
            workspaceIds: Set<String>,
            capabilities: List<AgentCapability>,
        ): NewAgentIntent {
            require(NewAgentIntent.NAME_PATTERN.matches(name)) {
                "Agent name must match [a-z][a-z0-9_-]{0,31}"
            }
            val selectedKind = requireNotNull(kind) { "Launch kind is required" }
            require(capabilities.any { it.kind == selectedKind }) { "Launch kind is no longer available" }
            val selectedSource =
                when (source) {
                    NewAgentSourceChoice.EXISTING_WORKSPACE -> {
                        require(workspaceId != null && workspaceId in workspaceIds) {
                            "Select an existing Herdr workspace"
                        }
                        NewAgentSource.ExistingWorkspace(workspaceId)
                    }
                    NewAgentSourceChoice.CURRENT_PROJECT,
                    NewAgentSourceChoice.DIRECTORY,
                    -> {
                        val raw =
                            if (source == NewAgentSourceChoice.CURRENT_PROJECT) {
                                currentProjectPath
                            } else {
                                directoryPath
                            }
                        require(!raw.isNullOrBlank()) { "Select a directory" }
                        val path =
                            try {
                                Path.of(raw).toRealPath()
                            } catch (_: Exception) {
                                throw IllegalArgumentException("Directory does not exist")
                            }
                        require(Files.isDirectory(path)) { "Select a directory" }
                        NewAgentSource.Directory(path.toString())
                    }
                }
            return NewAgentIntent(selectedSource, name, selectedKind, arguments.toList())
        }

        private fun labelRenderer(text: (Any?) -> String): DefaultListCellRenderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean,
                ): java.awt.Component =
                    super.getListCellRendererComponent(
                        list,
                        text(value),
                        index,
                        isSelected,
                        cellHasFocus,
                    )
            }
    }
}
