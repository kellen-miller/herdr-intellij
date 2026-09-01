package dev.herdr.intellij

internal sealed interface LaunchRecord {
    val id: String
    val workspaceId: String
    val tabId: String
    val paneId: String
    val name: String
    val kind: String
}

internal data class ProvisioningRecord(
    override val id: String,
    override val workspaceId: String,
    override val tabId: String,
    override val paneId: String,
    override val name: String,
    override val kind: String,
) : LaunchRecord

internal data class FailedLaunch(
    override val id: String,
    override val workspaceId: String,
    override val tabId: String,
    override val paneId: String,
    override val name: String,
    override val kind: String,
    val message: String,
    val ambiguous: Boolean,
) : LaunchRecord

internal enum class HerdrAction {
    CONNECT,
    START,
    REFRESH,
    ALLOCATE,
    START_AGENT,
    PROMPT,
    RESPOND,
    FOCUS,
    WORKTREE,
}

internal data class ActionError(
    val action: HerdrAction,
    val message: String,
    val requiresRefresh: Boolean,
)

internal data class AgentSelection(val agentName: String, val paneId: String)

internal data class RecentOutput(
    val paneId: String,
    val text: String,
    val revision: Long,
    val truncated: Boolean,
)

internal data class AgentView(
    val name: String,
    val displayName: String,
    val kind: String,
    val workspaceId: String,
    val tabId: String,
    val paneId: String,
    val status: AgentStatus,
    val focused: Boolean,
    val interactiveReady: Boolean,
    val cwd: String?,
    val title: String?,
    val stateLabels: Map<String, String>,
)

internal data class WorkspaceView(
    val id: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    val navigationRoot: String?,
    val agents: List<AgentView>,
    val launchRecords: List<LaunchRecord>,
)

internal data class HerdrLiveView(
    val serverVersion: String,
    val capabilities: List<AgentCapability>,
    val workspaces: List<WorkspaceView>,
    val selection: AgentSelection?,
    val recentOutput: RecentOutput?,
    val provisioning: List<ProvisioningRecord>,
    val failedLaunches: List<FailedLaunch>,
    val actionErrors: List<ActionError>,
    val stale: Boolean,
    internal val topology: HerdrTopology,
) {
    val workspaceCount: Int = workspaces.size
    val agentCount: Int = workspaces.sumOf { it.agents.size }
    val paneIds: Set<String> = topology.panes.keys
    val empty: Boolean = workspaces.isEmpty()
}

internal sealed interface HerdrUiState {
    data class NoServer(val socketTarget: String, val diagnostic: String? = null) : HerdrUiState
    data class Starting(val socketTarget: String) : HerdrUiState
    data class Connecting(val socketTarget: String, val diagnostic: String? = null) : HerdrUiState
    data class Incompatible(
        val socketTarget: String,
        val expectedProtocol: Int,
        val actualProtocol: Int?,
        val diagnostic: String,
    ) : HerdrUiState
    data class Live(val view: HerdrLiveView) : HerdrUiState

    class Disconnected(lastView: HerdrLiveView, val diagnostic: String) : HerdrUiState {
        val stale: HerdrLiveView = lastView.copy(stale = true)

        override fun equals(other: Any?): Boolean = other is Disconnected &&
            stale == other.stale && diagnostic == other.diagnostic

        override fun hashCode(): Int = 31 * stale.hashCode() + diagnostic.hashCode()
    }
}

internal data class HerdrTopology(
    val version: String,
    val focusedWorkspaceId: String?,
    val focusedTabId: String?,
    val focusedPaneId: String?,
    val workspaces: Map<String, HerdrWorkspace>,
    val tabs: Map<String, HerdrTab>,
    val panes: Map<String, HerdrPane>,
    val agents: Map<String, HerdrAgent>,
)

internal object HerdrModel {
    fun fromSnapshot(
        snapshot: HerdrSnapshot,
        capabilities: List<AgentCapability>,
        provisioning: List<ProvisioningRecord> = emptyList(),
        failedLaunches: List<FailedLaunch> = emptyList(),
        actionErrors: List<ActionError> = emptyList(),
    ): HerdrLiveView = normalize(
        topology = HerdrTopology(
            version = snapshot.version,
            focusedWorkspaceId = snapshot.focusedWorkspaceId,
            focusedTabId = snapshot.focusedTabId,
            focusedPaneId = snapshot.focusedPaneId,
            workspaces = snapshot.workspaces.associateBy(HerdrWorkspace::workspaceId),
            tabs = snapshot.tabs.associateBy(HerdrTab::tabId),
            panes = snapshot.panes.associateBy(HerdrPane::paneId),
            agents = snapshot.agents
                .filter { it.agent != null }
                .associateBy(HerdrAgent::paneId),
        ),
        capabilities = capabilities,
        selection = null,
        recentOutput = null,
        provisioning = provisioning,
        failedLaunches = failedLaunches,
        actionErrors = actionErrors,
        stale = false,
    )

    fun reconcile(current: HerdrLiveView, snapshot: HerdrSnapshot): HerdrLiveView {
        val fresh = fromSnapshot(
            snapshot,
            current.capabilities,
            current.provisioning,
            current.failedLaunches,
            current.actionErrors,
        )
        val selection = current.selection?.takeIf { selected ->
            fresh.workspaces.any { workspace ->
                workspace.agents.any { it.name == selected.agentName && it.paneId == selected.paneId }
            }
        }
        val output = current.recentOutput?.takeIf { selection?.paneId == it.paneId }
        return fresh.copy(selection = selection, recentOutput = output)
    }

    fun reduceEvent(current: HerdrLiveView, event: HerdrEvent): HerdrLiveView {
        var topology = current.topology
        when (event) {
            is HerdrEvent.WorkspaceUpsert -> {
                topology = topology.copy(
                    workspaces = topology.workspaces + (event.workspace.workspaceId to event.workspace),
                )
            }
            is HerdrEvent.WorkspacesReplaced -> {
                val replacements = event.workspaces.associateBy(HerdrWorkspace::workspaceId)
                val removed = topology.workspaces.keys - replacements.keys
                topology = topology.copy(workspaces = replacements)
                topology = removeWorkspaces(topology, removed)
            }
            is HerdrEvent.WorkspaceClosed -> {
                topology = removeWorkspaces(
                    topology.copy(workspaces = topology.workspaces - event.workspaceId),
                    setOf(event.workspaceId),
                )
            }
            is HerdrEvent.WorkspaceRenamed -> {
                val workspace = topology.workspaces[event.workspaceId]
                if (workspace != null) {
                    topology = topology.copy(
                        workspaces = topology.workspaces + (event.workspaceId to workspace.copy(label = event.label)),
                    )
                }
            }
            is HerdrEvent.WorkspaceFocused -> {
                topology = topology.copy(
                    focusedWorkspaceId = event.workspaceId,
                    workspaces = topology.workspaces.mapValues { (id, workspace) ->
                        workspace.copy(focused = id == event.workspaceId)
                    },
                )
            }
            is HerdrEvent.TabUpsert -> {
                topology = topology.copy(tabs = topology.tabs + (event.tab.tabId to event.tab))
            }
            is HerdrEvent.TabsReplaced -> {
                topology = topology.copy(
                    tabs = topology.tabs.filterValues { it.workspaceId != event.workspaceId } +
                        event.tabs.associateBy(HerdrTab::tabId),
                )
            }
            is HerdrEvent.TabClosed -> {
                val paneIds = topology.panes.values
                    .filter { it.tabId == event.tabId }
                    .mapTo(mutableSetOf(), HerdrPane::paneId)
                topology = topology.copy(
                    tabs = topology.tabs - event.tabId,
                    panes = topology.panes - paneIds,
                    agents = topology.agents - paneIds,
                )
            }
            is HerdrEvent.TabRenamed -> {
                val tab = topology.tabs[event.tabId]
                if (tab != null) {
                    topology = topology.copy(tabs = topology.tabs + (event.tabId to tab.copy(label = event.label)))
                }
            }
            is HerdrEvent.TabFocused -> {
                topology = topology.copy(
                    focusedWorkspaceId = event.workspaceId,
                    focusedTabId = event.tabId,
                    tabs = topology.tabs.mapValues { (id, tab) ->
                        if (tab.workspaceId == event.workspaceId) tab.copy(focused = id == event.tabId) else tab
                    },
                )
            }
            is HerdrEvent.PaneUpsert -> {
                topology = upsertPane(topology, event.pane)
            }
            is HerdrEvent.PaneClosed -> {
                topology = topology.copy(
                    panes = topology.panes - event.paneId,
                    agents = topology.agents - event.paneId,
                    focusedPaneId = topology.focusedPaneId.takeUnless { it == event.paneId },
                )
            }
            is HerdrEvent.PaneMoved -> {
                val agent = topology.agents[event.previousPaneId]
                topology = topology.copy(
                    panes = topology.panes - event.previousPaneId,
                    agents = topology.agents - event.previousPaneId,
                )
                event.createdWorkspace?.let { workspace ->
                    topology = topology.copy(
                        workspaces = topology.workspaces + (workspace.workspaceId to workspace),
                    )
                }
                event.createdTab?.let { tab ->
                    topology = topology.copy(tabs = topology.tabs + (tab.tabId to tab))
                }
                topology = upsertPane(topology, event.pane)
                if (agent != null) {
                    val moved = agent.copy(
                        paneId = event.pane.paneId,
                        workspaceId = event.pane.workspaceId,
                        tabId = event.pane.tabId,
                    )
                    topology = topology.copy(agents = topology.agents + (moved.paneId to moved))
                }
                event.closedWorkspaceId?.let { closed ->
                    topology = removeWorkspaces(
                        topology.copy(workspaces = topology.workspaces - closed),
                        setOf(closed),
                    )
                }
                event.closedTabId?.let { closed ->
                    topology = topology.copy(tabs = topology.tabs - closed)
                }
            }
            is HerdrEvent.PaneFocused -> {
                topology = topology.copy(
                    focusedWorkspaceId = event.workspaceId,
                    focusedPaneId = event.paneId,
                    panes = topology.panes.mapValues { (id, pane) -> pane.copy(focused = id == event.paneId) },
                    agents = topology.agents.mapValues { (id, agent) -> agent.copy(focused = id == event.paneId) },
                )
            }
            is HerdrEvent.PaneOutputChanged -> {
                val pane = topology.panes[event.paneId]
                if (pane != null && event.revision > pane.revision) {
                    topology = topology.copy(
                        panes = topology.panes + (event.paneId to pane.copy(revision = event.revision)),
                    )
                }
            }
            is HerdrEvent.PaneDetected -> {
                val pane = topology.panes[event.paneId]
                if (event.released) {
                    topology = topology.copy(
                        panes = if (pane == null) topology.panes else topology.panes +
                            (event.paneId to pane.copy(agent = null, agentStatus = event.finalStatus ?: AgentStatus.UNKNOWN)),
                        agents = topology.agents - event.paneId,
                    )
                } else if (pane != null && event.agent != null) {
                    topology = upsertPane(topology, pane.copy(agent = event.agent))
                }
            }
            is HerdrEvent.PaneStatusChanged -> {
                val pane = topology.panes[event.paneId]
                if (pane != null) {
                    topology = upsertPane(
                        topology,
                        pane.copy(
                            agent = event.agent ?: pane.agent,
                            title = event.title,
                            displayAgent = event.displayAgent,
                            agentStatus = event.status,
                            stateLabels = event.stateLabels,
                        ),
                    )
                }
                val agent = topology.agents[event.paneId]
                if (agent != null) {
                    topology = topology.copy(
                        agents = topology.agents + (event.paneId to agent.copy(
                            agent = event.agent ?: agent.agent,
                            title = event.title,
                            displayAgent = event.displayAgent,
                            agentStatus = event.status,
                            stateLabels = event.stateLabels,
                        )),
                    )
                }
            }
            is HerdrEvent.LayoutUpdated -> Unit
        }

        return normalize(
            topology,
            current.capabilities,
            current.selection,
            current.recentOutput,
            current.provisioning,
            current.failedLaunches,
            current.actionErrors,
            current.stale,
        )
    }

    fun select(current: HerdrLiveView, agentName: String?): HerdrLiveView {
        val agent = agentName?.let { wanted ->
            current.workspaces.asSequence()
                .flatMap { it.agents.asSequence() }
                .firstOrNull { it.name == wanted }
        }
        val selection = agent?.let { AgentSelection(it.name, it.paneId) }
        return current.copy(
            selection = selection,
            recentOutput = current.recentOutput?.takeIf { it.paneId == selection?.paneId },
        )
    }

    fun withOutput(current: HerdrLiveView, read: HerdrPaneRead): HerdrLiveView {
        if (current.selection?.paneId != read.paneId || current.recentOutput?.revision == read.revision) {
            return current
        }
        return current.copy(
            recentOutput = RecentOutput(read.paneId, read.text, read.revision, read.truncated),
        )
    }

    private fun normalize(
        topology: HerdrTopology,
        capabilities: List<AgentCapability>,
        selection: AgentSelection?,
        recentOutput: RecentOutput?,
        provisioning: List<ProvisioningRecord>,
        failedLaunches: List<FailedLaunch>,
        actionErrors: List<ActionError>,
        stale: Boolean,
    ): HerdrLiveView {
        val agentsByWorkspace = topology.agents.values
            .filter { it.agent != null }
            .groupBy(HerdrAgent::workspaceId)
        val recordsByWorkspace = (provisioning + failedLaunches).groupBy(LaunchRecord::workspaceId)
        val workspaces = topology.workspaces.values
            .sortedWith(compareBy(HerdrWorkspace::number, HerdrWorkspace::workspaceId))
            .map { workspace ->
                val agents = agentsByWorkspace[workspace.workspaceId].orEmpty()
                    .sortedWith(compareBy<HerdrAgent>({ topology.tabs[it.tabId]?.number ?: Int.MAX_VALUE }, { it.paneId }))
                    .map { agent ->
                        val pane = topology.panes[agent.paneId]
                        val kind = requireNotNull(agent.agent)
                        AgentView(
                            name = agent.name ?: agent.paneId,
                            displayName = agent.name ?: agent.displayAgent ?: kind,
                            kind = kind,
                            workspaceId = agent.workspaceId,
                            tabId = agent.tabId,
                            paneId = agent.paneId,
                            status = agent.agentStatus,
                            focused = agent.focused,
                            interactiveReady = agent.interactiveReady,
                            cwd = agent.cwd ?: pane?.cwd,
                            title = agent.title ?: pane?.title,
                            stateLabels = agent.stateLabels,
                        )
                    }
                val navigationRoot = workspace.worktree?.checkoutPath
                    ?: agents.firstNotNullOfOrNull(AgentView::cwd)
                WorkspaceView(
                    id = workspace.workspaceId,
                    number = workspace.number,
                    label = workspace.label,
                    focused = workspace.focused,
                    navigationRoot = navigationRoot,
                    agents = agents,
                    launchRecords = recordsByWorkspace[workspace.workspaceId].orEmpty(),
                )
            }
        val validSelection = selection?.takeIf { selected ->
            workspaces.any { workspace -> workspace.agents.any { it.paneId == selected.paneId } }
        }
        return HerdrLiveView(
            serverVersion = topology.version,
            capabilities = capabilities.toList(),
            workspaces = workspaces,
            selection = validSelection,
            recentOutput = recentOutput?.takeIf { it.paneId == validSelection?.paneId },
            provisioning = provisioning.toList(),
            failedLaunches = failedLaunches.toList(),
            actionErrors = actionErrors.toList(),
            stale = stale,
            topology = topology.copy(
                workspaces = topology.workspaces.toMap(),
                tabs = topology.tabs.toMap(),
                panes = topology.panes.toMap(),
                agents = topology.agents.toMap(),
            ),
        )
    }

    private fun removeWorkspaces(topology: HerdrTopology, workspaceIds: Set<String>): HerdrTopology {
        val tabIds = topology.tabs.values
            .filter { it.workspaceId in workspaceIds }
            .mapTo(mutableSetOf(), HerdrTab::tabId)
        val paneIds = topology.panes.values
            .filter { it.workspaceId in workspaceIds }
            .mapTo(mutableSetOf(), HerdrPane::paneId)
        return topology.copy(
            tabs = topology.tabs - tabIds,
            panes = topology.panes - paneIds,
            agents = topology.agents - paneIds,
            focusedWorkspaceId = topology.focusedWorkspaceId.takeUnless { it in workspaceIds },
            focusedTabId = topology.focusedTabId.takeUnless { it in tabIds },
            focusedPaneId = topology.focusedPaneId.takeUnless { it in paneIds },
        )
    }

    private fun upsertPane(topology: HerdrTopology, pane: HerdrPane): HerdrTopology {
        var agents = topology.agents
        val existing = agents[pane.paneId]
        if (pane.agent == null) {
            agents = agents - pane.paneId
        } else if (existing == null) {
            agents = agents + (pane.paneId to HerdrAgent(
                terminalId = pane.terminalId,
                agent = pane.agent,
                title = pane.title,
                displayAgent = pane.displayAgent,
                agentStatus = pane.agentStatus,
                stateLabels = pane.stateLabels,
                tokens = pane.tokens,
                workspaceId = pane.workspaceId,
                tabId = pane.tabId,
                paneId = pane.paneId,
                focused = pane.focused,
                cwd = pane.cwd,
                revision = pane.revision,
            ))
        }
        return topology.copy(
            panes = topology.panes + (pane.paneId to pane),
            agents = agents,
        )
    }
}
