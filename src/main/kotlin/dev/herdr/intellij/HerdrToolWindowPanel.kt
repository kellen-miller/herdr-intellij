package dev.herdr.intellij

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal enum class RootCard {
    NO_SERVER,
    CONNECTING,
    INCOMPATIBLE,
    LIVE_EMPTY,
    LIVE,
    DISCONNECTED,
}

internal enum class LivePresentation {
    SPLIT,
    COMPACT,
}

internal data class CreateWorktreeIntent(
    val branch: String,
    val base: String?,
)

private sealed interface HerdrTreeItem {
    data object Root : HerdrTreeItem

    data class Workspace(
        val value: WorkspaceView,
    ) : HerdrTreeItem

    data class Agent(
        val value: AgentView,
    ) : HerdrTreeItem

    data class Launch(
        val value: LaunchRecord,
    ) : HerdrTreeItem
}

internal class HerdrToolWindowPanel(
    private val project: Project,
    private val controller: HerdrController,
    private val settings: HerdrSettings,
    private val navigator: WorkspaceNavigator = WorkspaceNavigator(),
) : JPanel(BorderLayout()),
    Disposable {
    private val connectionLabel = JBLabel()
    private val countsLabel = JBLabel()
    private val startButton = JButton(HerdrBundle.message("action.start"))
    private val retryButton = JButton(HerdrBundle.message("action.retry"))
    private val refreshButton = JButton(HerdrBundle.message("action.refresh"))
    private val newAgentButton = JButton(HerdrBundle.message("action.newAgent"))
    private val cards = CardLayout()
    private val cardRoot = JPanel(cards)
    private val noServerBody = JBLabel()
    private val connectingBody = JBLabel()
    private val incompatibleBody = JBLabel()
    private val disconnectedBody = JBLabel()
    private val disconnectedTree = JTree()
    private val liveContainer = JPanel(BorderLayout())
    private val splitter = JBSplitter(false, 0.42f)
    private val compactCards = CardLayout()
    private val compactContainer = JPanel(compactCards)
    private val master = JPanel(BorderLayout())
    private val detail = JPanel(BorderLayout())
    private val treeRoot = DefaultMutableTreeNode(HerdrTreeItem.Root)
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = JTree(treeModel)
    private val backButton =
        JButton(HerdrBundle.message("action.backToAgents")).apply {
            isVisible = backToAgentsVisible(LivePresentation.SPLIT)
        }
    private val identityLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val contextLabel = JBLabel()
    private val errorLabel = JBLabel()
    private val focusButton = JButton(HerdrBundle.message("action.focus"))
    private val openProjectButton = JButton(HerdrBundle.message("action.openProject"))
    private val openFileButton = JButton(HerdrBundle.message("action.openFile"))
    private val changesButton = JButton(HerdrBundle.message("action.currentChanges"))
    private val createWorktreeButton = JButton(HerdrBundle.message("action.createWorktree"))
    private val openWorktreeButton = JButton(HerdrBundle.message("action.openWorktree"))
    private val retryLaunchButton = JButton(HerdrBundle.message("action.retryLaunch"))
    private val output = JBTextArea()
    private val composerCards = CardLayout()
    private val composer = JPanel(composerCards)
    private val prompt = JBTextArea(4, 20)
    private val promptButton = JButton(HerdrBundle.message("action.sendPrompt"))
    private val blockedResponse = JBTextField()
    private val blockedSendButton = JButton(HerdrBundle.message("action.sendResponse"))
    private val enterButton = JButton(HerdrBundle.message("action.enter"))
    private val escapeButton = JButton(HerdrBundle.message("action.escape"))
    private val reviewPath = JBLabel()
    private val reviewText = JBTextArea()
    private val reviewInstruction = JBTextField()
    private val reviewCancelButton = JButton(HerdrBundle.message("action.cancelReview"))
    private val reviewSendButton = JButton(HerdrBundle.message("action.sendReview"))
    private var latestState: HerdrUiState = controller.currentState()
    private var presentation = LivePresentation.SPLIT
    private var compactDetailVisible = false
    private var selectedLaunchId: String? = null
    private var renderedReviewKey: List<Any>? = null
    private var renderingTree = false
    private var disposed = false
    private val stateSubscription: AutoCloseable
    private val blockedSubscription: AutoCloseable

    init {
        border = JBUI.Borders.empty(8)
        minimumSize = Dimension(280, 300)

        val header =
            JPanel(BorderLayout(12, 0)).apply {
                border = JBUI.Borders.emptyBottom(8)
                add(
                    JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                        add(connectionLabel)
                        add(countsLabel)
                    },
                    BorderLayout.WEST,
                )
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                        add(startButton)
                        add(retryButton)
                        add(refreshButton)
                        add(newAgentButton)
                    },
                    BorderLayout.EAST,
                )
            }
        connectionLabel.accessibleContext.accessibleName = HerdrBundle.message("header.connection.accessible")
        countsLabel.accessibleContext.accessibleName = HerdrBundle.message("header.counts.accessible")
        add(header, BorderLayout.NORTH)

        cardRoot.add(statePanel(HerdrBundle.message("state.noServer"), noServerBody), RootCard.NO_SERVER.name)
        cardRoot.add(statePanel(HerdrBundle.message("state.connecting"), connectingBody), RootCard.CONNECTING.name)
        cardRoot.add(statePanel(HerdrBundle.message("state.incompatible"), incompatibleBody), RootCard.INCOMPATIBLE.name)
        cardRoot.add(
            statePanel(
                HerdrBundle.message("state.liveEmpty"),
                JBLabel(HerdrBundle.message("state.liveEmpty.body")),
            ),
            RootCard.LIVE_EMPTY.name,
        )
        cardRoot.add(liveContainer, RootCard.LIVE.name)
        cardRoot.add(
            JPanel(BorderLayout(0, 8)).apply {
                add(statePanel(HerdrBundle.message("state.disconnected"), disconnectedBody), BorderLayout.NORTH)
                disconnectedTree.isEnabled = false
                disconnectedTree.isRootVisible = false
                disconnectedTree.showsRootHandles = true
                add(JBScrollPane(disconnectedTree), BorderLayout.CENTER)
            },
            RootCard.DISCONNECTED.name,
        )
        add(cardRoot, BorderLayout.CENTER)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer =
            object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(
                    tree: JTree?,
                    value: Any?,
                    selected: Boolean,
                    expanded: Boolean,
                    leaf: Boolean,
                    row: Int,
                    hasFocus: Boolean,
                ): java.awt.Component {
                    super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)
                    val item = (value as? DefaultMutableTreeNode)?.userObject as? HerdrTreeItem
                    text =
                        when (item) {
                            is HerdrTreeItem.Workspace -> {
                                val root = item.value.navigationRoot ?: HerdrBundle.message("workspace.noRoot")
                                "${item.value.label} · ${item.value.agents.size} · $root"
                            }
                            is HerdrTreeItem.Agent -> {
                                val label = latestCapabilities()[item.value.kind] ?: item.value.kind
                                val name =
                                    if (item.value.targetResolved) {
                                        item.value.name
                                    } else {
                                        HerdrBundle.message("agent.resolving")
                                    }
                                "${statusShape(item.value.status)} $name · $label · ${statusText(item.value.status)}"
                            }
                            is HerdrTreeItem.Launch ->
                                when (val launch = item.value) {
                                    is ProvisioningRecord -> "◌ ${launch.name} · ${launch.kind} · ${HerdrBundle.message(
                                        "status.provisioning",
                                    )}"
                                    is FailedLaunch -> "! ${launch.name} · ${launch.kind} · ${HerdrBundle.message("status.failed")}"
                                }
                            else -> ""
                        }
                    return this
                }
            }
        tree.accessibleContext.accessibleName = HerdrBundle.message("tree.accessible")
        tree.addTreeSelectionListener(::treeSelectionChanged)
        master.add(
            JBLabel(HerdrBundle.message("tree.title")).apply {
                border = JBUI.Borders.emptyBottom(6)
            },
            BorderLayout.NORTH,
        )
        master.add(JBScrollPane(tree), BorderLayout.CENTER)

        val detailHeader =
            JPanel(BorderLayout()).apply {
                add(backButton, BorderLayout.WEST)
                add(
                    JPanel().apply {
                        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                        add(identityLabel)
                        add(statusLabel)
                        add(contextLabel)
                    },
                    BorderLayout.CENTER,
                )
            }
        val detailActions =
            JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                add(focusButton)
                add(openProjectButton)
                add(openFileButton)
                add(changesButton)
                add(createWorktreeButton)
                add(openWorktreeButton)
                add(retryLaunchButton)
            }
        output.isEditable = false
        output.lineWrap = false
        output.accessibleContext.accessibleName = HerdrBundle.message("output.accessible")
        val detailCenter =
            JPanel(BorderLayout(0, 8)).apply {
                add(detailActions, BorderLayout.NORTH)
                add(JBScrollPane(output), BorderLayout.CENTER)
                add(errorLabel, BorderLayout.SOUTH)
            }
        detail.add(detailHeader, BorderLayout.NORTH)
        detail.add(detailCenter, BorderLayout.CENTER)

        composer.add(JBLabel(HerdrBundle.message("composer.selectAgent")), "none")
        composer.add(
            JPanel(BorderLayout(0, 4)).apply {
                add(JBLabel(HerdrBundle.message("composer.prompt")), BorderLayout.NORTH)
                add(JBScrollPane(prompt), BorderLayout.CENTER)
                add(promptButton, BorderLayout.EAST)
            },
            "prompt",
        )
        composer.add(
            JPanel(BorderLayout(4, 4)).apply {
                add(JBLabel(HerdrBundle.message("composer.blocked")), BorderLayout.NORTH)
                add(blockedResponse, BorderLayout.CENTER)
                add(
                    JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                        add(blockedSendButton)
                        add(enterButton)
                        add(escapeButton)
                    },
                    BorderLayout.EAST,
                )
            },
            "blocked",
        )
        reviewText.isEditable = false
        reviewText.lineWrap = true
        reviewText.wrapStyleWord = true
        composer.add(
            JPanel(BorderLayout(0, 4)).apply {
                add(reviewPath, BorderLayout.NORTH)
                add(JBScrollPane(reviewText), BorderLayout.CENTER)
                add(
                    JPanel(BorderLayout(4, 0)).apply {
                        add(reviewInstruction, BorderLayout.CENTER)
                        add(
                            JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                                add(reviewCancelButton)
                                add(reviewSendButton)
                            },
                            BorderLayout.EAST,
                        )
                    },
                    BorderLayout.SOUTH,
                )
            },
            "review",
        )
        prompt.accessibleContext.accessibleName = HerdrBundle.message("composer.prompt.accessible")
        blockedResponse.accessibleContext.accessibleName = HerdrBundle.message("composer.blocked.accessible")
        reviewText.accessibleContext.accessibleName = HerdrBundle.message("composer.reviewText.accessible")
        reviewInstruction.accessibleContext.accessibleName = HerdrBundle.message("composer.reviewInstruction.accessible")
        detail.add(composer, BorderLayout.SOUTH)

        splitter.firstComponent = master
        splitter.secondComponent = detail
        splitter.proportion = settings.state.splitterProportion.coerceIn(0.25f, 0.75f)
        liveContainer.add(splitter, BorderLayout.CENTER)

        startButton.addActionListener { controller.startHerdr() }
        retryButton.addActionListener { controller.retry() }
        refreshButton.addActionListener { controller.reconcileNow() }
        newAgentButton.addActionListener {
            val live = (latestState as? HerdrUiState.Live)?.view ?: return@addActionListener
            NewAgentDialog(project, live).resultIntent()?.let(controller::launchAgent)
        }
        backButton.addActionListener {
            compactDetailVisible = false
            compactCards.show(compactContainer, "master")
        }
        focusButton.addActionListener { selectedAgent()?.let { controller.focusAgent(it.name) } }
        openProjectButton.addActionListener { selectedWorkspace()?.navigationRoot?.let(navigator::openProject) }
        openFileButton.addActionListener {
            val live = (latestState as? HerdrUiState.Live)?.view ?: return@addActionListener
            val review = live.selectionReview ?: return@addActionListener
            val root = selectedWorkspace()?.navigationRoot ?: return@addActionListener
            navigator.openFile(root, review.relativePath, review.startLine)
        }
        changesButton.addActionListener {
            selectedWorkspace()?.navigationRoot?.let(navigator::showCurrentChanges)
        }
        createWorktreeButton.addActionListener {
            val workspace = selectedWorkspace() ?: return@addActionListener
            val branchResult =
                Messages.showInputDialog(
                    project,
                    HerdrBundle.message("worktree.branch.prompt"),
                    HerdrBundle.message("action.createWorktree"),
                    null,
                )
            if (branchResult.isNullOrBlank()) {
                return@addActionListener
            }
            val baseResult =
                Messages.showInputDialog(
                    project,
                    HerdrBundle.message("worktree.base.prompt"),
                    HerdrBundle.message("action.createWorktree"),
                    null,
                )
            val intent = createWorktreeIntent(branchResult, baseResult) ?: return@addActionListener
            controller.createWorktree(workspace.id, intent.branch, intent.base).thenAccept { outcome ->
                if (outcome is WorktreeCommandOutcome.Opened) {
                    SwingUtilities.invokeLater { navigator.openProject(outcome.path) }
                }
            }
        }
        openWorktreeButton.addActionListener {
            val workspace = selectedWorkspace() ?: return@addActionListener
            FileChooser
                .chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                    project,
                    null,
                )?.let { directory ->
                    controller.openWorktree(workspace.id, directory.path).thenAccept { outcome ->
                        if (outcome is WorktreeCommandOutcome.Opened) {
                            SwingUtilities.invokeLater { navigator.openProject(outcome.path) }
                        }
                    }
                }
        }
        retryLaunchButton.addActionListener {
            val failed = selectedLaunch() as? FailedLaunch ?: return@addActionListener
            controller.retryFailedLaunch(failed.id)
        }
        promptButton.addActionListener {
            val text = prompt.text
            if (text.isNotBlank()) {
                controller.promptSelected(text).thenAccept { outcome ->
                    if (outcome is MutationOutcome.Applied) {
                        SwingUtilities.invokeLater { prompt.text = "" }
                    }
                }
            }
        }
        blockedSendButton.addActionListener {
            val text = blockedResponse.text
            if (text.isNotEmpty()) {
                controller.sendBlockedText(text).thenAccept { outcome ->
                    if (outcome is MutationOutcome.Applied) {
                        SwingUtilities.invokeLater { blockedResponse.text = "" }
                    }
                }
            }
        }
        enterButton.addActionListener { controller.sendBlockedKey("Enter") }
        escapeButton.addActionListener { controller.sendBlockedKey("Escape") }
        reviewCancelButton.addActionListener { controller.clearSelectionReview() }
        reviewSendButton.addActionListener {
            val live = (latestState as? HerdrUiState.Live)?.view ?: return@addActionListener
            val review = live.selectionReview ?: return@addActionListener
            val instruction = reviewInstruction.text
            if (instruction.isBlank()) {
                return@addActionListener
            }
            controller
                .updateReviewInstruction(instruction)
                .thenCompose {
                    controller.promptSelected(review.copy(instruction = instruction).promptText())
                }.thenAccept { outcome ->
                    if (outcome is MutationOutcome.Applied) {
                        controller.clearSelectionReview()
                    }
                }
        }
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent?) {
                    applyPresentation(livePresentation(width))
                }
            },
        )

        stateSubscription =
            controller.addStateListener { state ->
                if (SwingUtilities.isEventDispatchThread()) {
                    render(state)
                } else {
                    SwingUtilities.invokeLater { if (!disposed) render(state) }
                }
            }
        blockedSubscription =
            controller.addBlockedListener { agent ->
                SwingUtilities.invokeLater {
                    if (!disposed) {
                        NotificationGroupManager
                            .getInstance()
                            .getNotificationGroup("Herdr Agent Status")
                            .createNotification(
                                HerdrBundle.message("notification.blocked.title"),
                                HerdrBundle.message("notification.blocked.body", agent.name),
                                NotificationType.WARNING,
                            ).notify(project)
                    }
                }
            }
    }

    override fun dispose() {
        disposed = true
        settings.state.splitterProportion = splitter.proportion.coerceIn(0.25f, 0.75f)
        settings.state.compactPresentation = presentation == LivePresentation.COMPACT
        stateSubscription.close()
        blockedSubscription.close()
        navigator.close()
    }

    private fun render(state: HerdrUiState) {
        latestState = state
        val rootCard = rootCard(state)
        cards.show(cardRoot, rootCard.name)
        val live =
            when (state) {
                is HerdrUiState.Live -> state.view
                is HerdrUiState.Disconnected -> state.stale
                else -> null
            }
        connectionLabel.text =
            when (state) {
                is HerdrUiState.NoServer -> "○ ${HerdrBundle.message("state.noServer")}"
                is HerdrUiState.Starting -> "◌ ${HerdrBundle.message("state.starting")}"
                is HerdrUiState.Connecting -> "◌ ${HerdrBundle.message("state.connecting")}"
                is HerdrUiState.Incompatible -> "! ${HerdrBundle.message("state.incompatible")}"
                is HerdrUiState.Live -> "● ${HerdrBundle.message("state.live")}"
                is HerdrUiState.Disconnected -> "! ${HerdrBundle.message("state.disconnected")}"
            }
        countsLabel.text =
            if (live == null) {
                ""
            } else {
                HerdrBundle.message(
                    "header.counts",
                    live.workspaceCount,
                    live.agentCount,
                )
            }
        noServerBody.text =
            when (state) {
                is HerdrUiState.NoServer -> state.diagnostic ?: state.socketTarget
                else -> ""
            }
        connectingBody.text =
            when (state) {
                is HerdrUiState.Starting -> state.socketTarget
                is HerdrUiState.Connecting -> state.diagnostic ?: state.socketTarget
                else -> ""
            }
        incompatibleBody.text =
            (state as? HerdrUiState.Incompatible)
                ?.let {
                    HerdrBundle.message("state.incompatible.body", it.expectedProtocol, it.actualProtocol ?: "?", it.diagnostic)
                }.orEmpty()
        disconnectedBody.text = (state as? HerdrUiState.Disconnected)?.diagnostic.orEmpty()
        startButton.isVisible = state is HerdrUiState.NoServer
        retryButton.isVisible = state is HerdrUiState.NoServer ||
            state is HerdrUiState.Incompatible ||
            state is HerdrUiState.Disconnected ||
            (state is HerdrUiState.Connecting && state.diagnostic != null)
        refreshButton.isVisible = state is HerdrUiState.Live
        newAgentButton.isVisible = state is HerdrUiState.Live

        if (state is HerdrUiState.Disconnected) {
            disconnectedTree.model = treeModelFor(state.stale)
            for (row in 0 until disconnectedTree.rowCount) disconnectedTree.expandRow(row)
        }
        if (state is HerdrUiState.Live) {
            renderLive(state.view)
        }
    }

    private fun renderLive(view: HerdrLiveView) {
        if (selectedLaunchId != null && view.workspaces.flatMap(WorkspaceView::launchRecords).none { it.id == selectedLaunchId }) {
            selectedLaunchId = null
        }
        renderingTree = true
        treeRoot.removeAllChildren()
        var selectedPath: TreePath? = null
        view.workspaces.forEach { workspace ->
            val workspaceNode = DefaultMutableTreeNode(HerdrTreeItem.Workspace(workspace))
            treeRoot.add(workspaceNode)
            workspace.agents.forEach { agent ->
                val node = DefaultMutableTreeNode(HerdrTreeItem.Agent(agent))
                workspaceNode.add(node)
                if (selectedLaunchId == null && agent.paneId == view.selection?.paneId) {
                    selectedPath = TreePath(node.path)
                }
            }
            workspace.launchRecords.forEach { launch ->
                val node = DefaultMutableTreeNode(HerdrTreeItem.Launch(launch))
                workspaceNode.add(node)
                if (launch.id == selectedLaunchId) {
                    selectedPath = TreePath(node.path)
                }
            }
        }
        treeModel.reload()
        for (row in 0 until tree.rowCount) tree.expandRow(row)
        tree.selectionPath = selectedPath
        renderingTree = false

        val agent = selectedAgent()
        val launch = selectedLaunch()
        val workspace = selectedWorkspace()
        identityLabel.text = launch?.let { "${it.name} · ${it.kind}" } ?: agent?.let {
            val name = if (it.targetResolved) it.name else HerdrBundle.message("agent.resolving")
            "${it.displayName} · $name"
        }
            ?: HerdrBundle.message("detail.noSelection")
        statusLabel.text =
            when (launch) {
                is ProvisioningRecord -> "◌ ${HerdrBundle.message("status.provisioning")}"
                is FailedLaunch -> "! ${HerdrBundle.message("status.failed")}"
                null -> agent?.let { "${statusShape(it.status)} ${statusText(it.status)}" }.orEmpty()
            }
        contextLabel.text =
            if (agent == null || workspace?.navigationRoot == null) {
                ""
            } else {
                val root = Path.of(workspace.navigationRoot)
                val cwd = agent.cwd?.let(Path::of)
                val relative =
                    if (cwd != null && cwd.normalize().startsWith(root.normalize())) {
                        root
                            .normalize()
                            .relativize(cwd.normalize())
                            .toString()
                            .ifBlank { "." }
                    } else {
                        "."
                    }
                HerdrBundle.message("detail.context", workspace.label, relative)
            }
        output.text =
            if (launch == null) {
                view.recentOutput
                    ?.takeIf { it.paneId == agent?.paneId }
                    ?.text
                    .orEmpty()
            } else {
                ""
            }
        output.caretPosition = output.document.length
        val requiresRefresh = view.actionErrors.any(ActionError::requiresRefresh)
        errorLabel.text =
            when (launch) {
                is FailedLaunch ->
                    if (launch.retryConfirmed) {
                        "! ${launch.message}"
                    } else {
                        "! ${launch.message} · ${HerdrBundle.message("action.refreshRequired")}"
                    }
                else ->
                    view.actionErrors
                        .lastOrNull()
                        ?.let { error ->
                            if (error.requiresRefresh) {
                                "! ${error.message} · ${HerdrBundle.message("action.refreshRequired")}"
                            } else {
                                "! ${error.message}"
                            }
                        }.orEmpty()
            }
        val mutable = !view.stale && !requiresRefresh
        focusButton.isEnabled = mutable && agent?.targetResolved == true
        val navigationRoot = workspace?.navigationRoot
        val indexedProject = navigationRoot?.let(navigator::hasIndexedProject) == true
        openProjectButton.isVisible = navigationRoot != null && !indexedProject
        openProjectButton.isEnabled = navigationRoot != null
        changesButton.isVisible = indexedProject
        changesButton.isEnabled = indexedProject
        openFileButton.isVisible = view.selectionReview != null && navigationRoot != null
        openFileButton.isEnabled = view.selectionReview != null && indexedProject
        createWorktreeButton.isEnabled = mutable && workspace?.navigationRoot != null
        openWorktreeButton.isEnabled = mutable && workspace != null
        retryLaunchButton.isVisible = launch is FailedLaunch
        retryLaunchButton.isEnabled = mutable && launch is FailedLaunch && launch.retryConfirmed

        val review = view.selectionReview?.takeIf { launch == null }
        when {
            review != null -> {
                val reviewKey = listOf(review.relativePath, review.startLine, review.endLine, review.selectedText)
                if (renderedReviewKey != reviewKey) {
                    renderedReviewKey = reviewKey
                    reviewPath.text = "${review.relativePath}:${review.startLine}-${review.endLine}"
                    reviewText.text = review.selectedText
                    reviewInstruction.text = review.instruction
                }
                reviewSendButton.isEnabled = mutable &&
                    agent?.targetResolved == true &&
                    agent?.interactiveReady == true &&
                    agent.status != AgentStatus.BLOCKED &&
                    agent.status != AgentStatus.DONE
                reviewCancelButton.isEnabled = true
                composerCards.show(composer, "review")
            }
            agent?.status == AgentStatus.BLOCKED -> {
                renderedReviewKey = null
                val enabled = mutable && agent.targetResolved
                blockedResponse.isEnabled = enabled
                blockedSendButton.isEnabled = enabled
                enterButton.isEnabled = enabled
                escapeButton.isEnabled = enabled
                composerCards.show(composer, "blocked")
            }
            agent?.targetResolved == true && agent.interactiveReady && agent.status != AgentStatus.DONE -> {
                renderedReviewKey = null
                prompt.isEnabled = mutable
                promptButton.isEnabled = mutable
                composerCards.show(composer, "prompt")
            }
            else -> {
                renderedReviewKey = null
                composerCards.show(composer, "none")
            }
        }
    }

    private fun treeSelectionChanged(event: TreeSelectionEvent) {
        if (renderingTree) {
            return
        }
        val item = (event.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
        when (item) {
            is HerdrTreeItem.Agent -> {
                selectedLaunchId = null
                if (item.value.targetResolved) {
                    controller.focusAgent(item.value.name)
                } else {
                    controller.selectAgent(item.value.name)
                }
                if (presentation == LivePresentation.COMPACT) {
                    compactDetailVisible = true
                    compactCards.show(compactContainer, "detail")
                }
            }
            is HerdrTreeItem.Launch -> {
                selectedLaunchId = item.value.id
                (latestState as? HerdrUiState.Live)?.view?.let(::renderLive)
                if (presentation == LivePresentation.COMPACT) {
                    compactDetailVisible = true
                    compactCards.show(compactContainer, "detail")
                }
            }
        }
    }

    private fun applyPresentation(next: LivePresentation) {
        backButton.isVisible = backToAgentsVisible(next)
        if (next == presentation && liveContainer.componentCount > 0) {
            return
        }
        presentation = next
        settings.state.compactPresentation = next == LivePresentation.COMPACT
        master.parent?.remove(master)
        detail.parent?.remove(detail)
        liveContainer.removeAll()
        if (next == LivePresentation.SPLIT) {
            splitter.firstComponent = master
            splitter.secondComponent = detail
            liveContainer.add(splitter, BorderLayout.CENTER)
        } else {
            compactContainer.removeAll()
            compactContainer.add(master, "master")
            compactContainer.add(detail, "detail")
            compactCards.show(compactContainer, if (compactDetailVisible) "detail" else "master")
            liveContainer.add(compactContainer, BorderLayout.CENTER)
        }
        liveContainer.revalidate()
        liveContainer.repaint()
    }

    private fun selectedAgent(): AgentView? {
        if (selectedLaunchId != null) {
            return null
        }
        val live = (latestState as? HerdrUiState.Live)?.view ?: return null
        val selected = live.selection ?: return null
        return live.workspaces
            .flatMap(WorkspaceView::agents)
            .singleOrNull { it.paneId == selected.paneId && it.name == selected.agentName }
    }

    private fun selectedWorkspace(): WorkspaceView? {
        val live = (latestState as? HerdrUiState.Live)?.view ?: return null
        val launchId = selectedLaunchId
        if (launchId != null) {
            return live.workspaces.singleOrNull { workspace ->
                workspace.launchRecords.any { it.id == launchId }
            }
        }
        val selected = live.selection ?: return null
        return live.workspaces.singleOrNull { workspace ->
            workspace.agents.any { it.paneId == selected.paneId && it.name == selected.agentName }
        }
    }

    private fun selectedLaunch(): LaunchRecord? {
        val id = selectedLaunchId ?: return null
        val live = (latestState as? HerdrUiState.Live)?.view ?: return null
        return live.workspaces.flatMap(WorkspaceView::launchRecords).singleOrNull { it.id == id }
    }

    private fun latestCapabilities(): Map<String, String> =
        ((latestState as? HerdrUiState.Live)?.view?.capabilities ?: emptyList())
            .associate { it.kind to it.label }

    private fun statePanel(
        title: String,
        body: JComponent,
    ): JPanel =
        JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(24)
            add(JBLabel(title).apply { font = font.deriveFont(font.style or java.awt.Font.BOLD) }, BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
        }

    private fun treeModelFor(view: HerdrLiveView): DefaultTreeModel {
        val root = DefaultMutableTreeNode(HerdrTreeItem.Root)
        view.workspaces.forEach { workspace ->
            val workspaceNode = DefaultMutableTreeNode(workspace.label)
            root.add(workspaceNode)
            workspace.agents.forEach { agent ->
                workspaceNode.add(
                    DefaultMutableTreeNode(
                        "${statusShape(agent.status)} ${agent.name} · ${statusText(agent.status)}",
                    ),
                )
            }
            workspace.launchRecords.forEach { launch -> workspaceNode.add(DefaultMutableTreeNode(launch.name)) }
        }
        return DefaultTreeModel(root)
    }

    companion object {
        internal fun rootCard(state: HerdrUiState): RootCard =
            when (state) {
                is HerdrUiState.NoServer -> RootCard.NO_SERVER
                is HerdrUiState.Starting, is HerdrUiState.Connecting -> RootCard.CONNECTING
                is HerdrUiState.Incompatible -> RootCard.INCOMPATIBLE
                is HerdrUiState.Live -> if (state.view.empty) RootCard.LIVE_EMPTY else RootCard.LIVE
                is HerdrUiState.Disconnected -> RootCard.DISCONNECTED
            }

        internal fun livePresentation(width: Int): LivePresentation = if (width >= 640) LivePresentation.SPLIT else LivePresentation.COMPACT

        internal fun backToAgentsVisible(presentation: LivePresentation): Boolean = presentation == LivePresentation.COMPACT

        internal fun createWorktreeIntent(
            branchDialogResult: String?,
            baseDialogResult: String?,
        ): CreateWorktreeIntent? {
            if (branchDialogResult.isNullOrBlank() || baseDialogResult == null) {
                return null
            }
            return CreateWorktreeIntent(branchDialogResult, baseDialogResult.takeIf(String::isNotBlank))
        }

        private fun statusShape(status: AgentStatus): String =
            when (status) {
                AgentStatus.IDLE -> "○"
                AgentStatus.WORKING -> "▶"
                AgentStatus.BLOCKED -> "◆"
                AgentStatus.DONE -> "■"
                AgentStatus.UNKNOWN -> "?"
            }

        private fun statusText(status: AgentStatus): String =
            when (status) {
                AgentStatus.IDLE -> HerdrBundle.message("status.idle")
                AgentStatus.WORKING -> HerdrBundle.message("status.working")
                AgentStatus.BLOCKED -> HerdrBundle.message("status.blocked")
                AgentStatus.DONE -> HerdrBundle.message("status.done")
                AgentStatus.UNKNOWN -> HerdrBundle.message("status.unknown")
            }
    }
}
