package dev.herdr.intellij

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val HERDR_PROTOCOL_VERSION = 22

open class HerdrProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

class HerdrProtocolMismatch(
    val expected: Int,
    val actual: Int,
) : HerdrProtocolException("Herdr protocol mismatch: expected $expected, received $actual")

@Serializable
data class HerdrServerCapabilities(
    @SerialName("live_handoff") val liveHandoff: Boolean,
    @SerialName("detached_server_daemon") val detachedServerDaemon: Boolean = false,
)

@Serializable
data class HerdrPong(
    val version: String,
    val protocol: Int,
    val capabilities: HerdrServerCapabilities? = null,
)

@Serializable
data class AgentCapability(val kind: String, val label: String)

@Serializable
enum class AgentStatus {
    @SerialName("idle") IDLE,
    @SerialName("working") WORKING,
    @SerialName("blocked") BLOCKED,
    @SerialName("done") DONE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class ReadSource {
    @SerialName("visible") VISIBLE,
    @SerialName("recent") RECENT,
    @SerialName("recent_unwrapped") RECENT_UNWRAPPED,
    @SerialName("detection") DETECTION,
}

@Serializable
enum class ReadFormat {
    @SerialName("text") TEXT,
    @SerialName("ansi") ANSI,
}

@Serializable
data class HerdrWorktree(
    @SerialName("repo_key") val repoKey: String,
    @SerialName("repo_name") val repoName: String,
    @SerialName("repo_root") val repoRoot: String,
    @SerialName("checkout_path") val checkoutPath: String,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean,
)

@Serializable
data class HerdrWorkspace(
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    @SerialName("pane_count") val paneCount: Int,
    @SerialName("tab_count") val tabCount: Int,
    @SerialName("active_tab_id") val activeTabId: String,
    @SerialName("agent_status") val agentStatus: AgentStatus,
    val tokens: Map<String, String> = emptyMap(),
    val worktree: HerdrWorktree? = null,
)

@Serializable
data class HerdrTab(
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val number: Int,
    val label: String,
    val focused: Boolean,
    @SerialName("pane_count") val paneCount: Int,
    @SerialName("agent_status") val agentStatus: AgentStatus,
)

@Serializable
data class HerdrPane(
    @SerialName("pane_id") val paneId: String,
    @SerialName("terminal_id") val terminalId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val focused: Boolean,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val label: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    val tokens: Map<String, String> = emptyMap(),
    val revision: Long,
)

@Serializable
data class HerdrAgent(
    @SerialName("terminal_id") val terminalId: String,
    val name: String? = null,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("terminal_title") val terminalTitle: String? = null,
    @SerialName("terminal_title_stripped") val terminalTitleStripped: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("agent_status") val agentStatus: AgentStatus,
    @SerialName("screen_detection_skipped") val screenDetectionSkipped: Boolean = false,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
    val tokens: Map<String, String> = emptyMap(),
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean,
    @SerialName("launch_pending") val launchPending: Boolean = false,
    @SerialName("interactive_ready") val interactiveReady: Boolean = false,
    @SerialName("state_change_seq") val stateChangeSequence: Long = 0,
    val cwd: String? = null,
    @SerialName("foreground_cwd") val foregroundCwd: String? = null,
    val revision: Long,
)

@Serializable
data class HerdrSnapshot(
    val version: String,
    val protocol: Int,
    @SerialName("focused_workspace_id") val focusedWorkspaceId: String? = null,
    @SerialName("focused_tab_id") val focusedTabId: String? = null,
    @SerialName("focused_pane_id") val focusedPaneId: String? = null,
    val workspaces: List<HerdrWorkspace>,
    val tabs: List<HerdrTab>,
    val panes: List<HerdrPane>,
    val layouts: List<HerdrPaneLayout>,
    val agents: List<HerdrAgent>,
)

@Serializable
data class HerdrPaneRead(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val source: ReadSource,
    val format: ReadFormat,
    val text: String,
    val revision: Long,
    val truncated: Boolean,
)

@Serializable
data class HerdrPaneLayoutRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Serializable
data class HerdrPaneLayoutPane(
    @SerialName("pane_id") val paneId: String,
    val focused: Boolean,
    val rect: HerdrPaneLayoutRect,
)

@Serializable
enum class HerdrSplitDirection {
    @SerialName("right") RIGHT,
    @SerialName("down") DOWN,
}

@Serializable
data class HerdrPaneLayoutSplit(
    val id: String,
    val direction: HerdrSplitDirection,
    val ratio: Float,
    val rect: HerdrPaneLayoutRect,
)

@Serializable
data class HerdrPaneLayout(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("tab_id") val tabId: String,
    val zoomed: Boolean,
    val area: HerdrPaneLayoutRect,
    @SerialName("focused_pane_id") val focusedPaneId: String,
    val panes: List<HerdrPaneLayoutPane>,
    val splits: List<HerdrPaneLayoutSplit>,
)

@Serializable
data class HerdrWorktreeInfo(
    val path: String,
    val branch: String? = null,
    @SerialName("is_bare") val isBare: Boolean,
    @SerialName("is_detached") val isDetached: Boolean,
    @SerialName("is_prunable") val isPrunable: Boolean,
    @SerialName("is_linked_worktree") val isLinkedWorktree: Boolean,
    @SerialName("open_workspace_id") val openWorkspaceId: String? = null,
    val label: String,
)

sealed interface HerdrResult {
    data class Pong(val value: HerdrPong) : HerdrResult
    data class Capabilities(val values: List<AgentCapability>) : HerdrResult
    data class Snapshot(val value: HerdrSnapshot) : HerdrResult
    data class PaneRead(val value: HerdrPaneRead) : HerdrResult
    data object SubscriptionStarted : HerdrResult
    data class WorkspaceCreated(
        val workspace: HerdrWorkspace,
        val tab: HerdrTab,
        val rootPane: HerdrPane,
    ) : HerdrResult
    data class TabCreated(val tab: HerdrTab, val rootPane: HerdrPane) : HerdrResult
    data class WorktreeCreated(
        val workspace: HerdrWorkspace,
        val tab: HerdrTab,
        val rootPane: HerdrPane,
        val worktree: HerdrWorktreeInfo,
    ) : HerdrResult
    data class WorktreeOpened(
        val workspace: HerdrWorkspace,
        val tab: HerdrTab,
        val rootPane: HerdrPane,
        val worktree: HerdrWorktreeInfo,
        val alreadyOpen: Boolean,
    ) : HerdrResult
    data class AgentStarted(val agent: HerdrAgent, val arguments: List<String>) : HerdrResult
    data class AgentPrompted(val agent: HerdrAgent) : HerdrResult
    data class AgentInfo(val agent: HerdrAgent) : HerdrResult
    data object Ok : HerdrResult
}

sealed interface HerdrResponse {
    val id: String

    data class Success(override val id: String, val result: HerdrResult) : HerdrResponse
    data class Error(override val id: String, val code: String, val message: String) : HerdrResponse
}

sealed interface HerdrEvent {
    data class WorkspaceUpsert(val workspace: HerdrWorkspace) : HerdrEvent
    data class WorkspacesReplaced(val workspaces: List<HerdrWorkspace>) : HerdrEvent
    data class WorkspaceClosed(val workspaceId: String) : HerdrEvent
    data class WorkspaceRenamed(val workspaceId: String, val label: String) : HerdrEvent
    data class WorkspaceFocused(val workspaceId: String) : HerdrEvent
    data class TabUpsert(val tab: HerdrTab) : HerdrEvent
    data class TabsReplaced(val workspaceId: String, val tabs: List<HerdrTab>) : HerdrEvent
    data class TabClosed(val workspaceId: String, val tabId: String) : HerdrEvent
    data class TabRenamed(val workspaceId: String, val tabId: String, val label: String) : HerdrEvent
    data class TabFocused(val workspaceId: String, val tabId: String) : HerdrEvent
    data class PaneUpsert(val pane: HerdrPane) : HerdrEvent
    data class PaneClosed(val workspaceId: String, val paneId: String) : HerdrEvent
    data class PaneMoved(
        val previousPaneId: String,
        val pane: HerdrPane,
        val createdWorkspace: HerdrWorkspace?,
        val createdTab: HerdrTab?,
        val closedWorkspaceId: String?,
        val closedTabId: String?,
    ) : HerdrEvent
    data class PaneFocused(val workspaceId: String, val paneId: String) : HerdrEvent
    data class PaneOutputChanged(val workspaceId: String, val paneId: String, val revision: Long) : HerdrEvent
    data class PaneDetected(
        val workspaceId: String,
        val paneId: String,
        val agent: String?,
        val released: Boolean,
        val finalStatus: AgentStatus?,
    ) : HerdrEvent
    data class PaneStatusChanged(
        val workspaceId: String,
        val paneId: String,
        val status: AgentStatus,
        val agent: String?,
        val title: String?,
        val displayAgent: String?,
        val stateLabels: Map<String, String>,
    ) : HerdrEvent
    data class LayoutUpdated(val layout: HerdrPaneLayout) : HerdrEvent
}

class HerdrRequest private constructor(
    val id: String,
    val method: String,
    val params: JsonObject,
    val mutation: Boolean,
) {
    companion object {
        private val topologyEvents = listOf(
            "workspace.created",
            "workspace.updated",
            "workspace.metadata_updated",
            "workspace.renamed",
            "workspace.moved",
            "workspace.reordered",
            "workspace.closed",
            "workspace.focused",
            "worktree.created",
            "worktree.opened",
            "worktree.removed",
            "tab.created",
            "tab.closed",
            "tab.focused",
            "tab.renamed",
            "tab.moved",
            "pane.created",
            "pane.closed",
            "pane.updated",
            "pane.focused",
            "pane.moved",
            "pane.exited",
            "pane.agent_detected",
            "layout.updated",
        )

        fun ping(id: String) = HerdrRequest(id, "ping", JsonObject(emptyMap()), false)

        fun capabilities(id: String) = HerdrRequest(
            id,
            "server.agent_capabilities",
            JsonObject(emptyMap()),
            false,
        )

        fun snapshot(id: String) = HerdrRequest(id, "session.snapshot", JsonObject(emptyMap()), false)

        fun topologySubscription(id: String) = subscription(id, emptySet())

        fun combinedSubscription(id: String, paneIds: Set<String>) = subscription(id, paneIds)

        fun paneRead(id: String, paneId: String, lines: Int = 400): HerdrRequest {
            HerdrProtocol.requireIdentifier(paneId, "pane id")
            return HerdrRequest(
                id,
                "pane.read",
                buildJsonObject {
                    put("pane_id", paneId)
                    put("source", "recent_unwrapped")
                    put("lines", lines)
                    put("format", "text")
                    put("strip_ansi", true)
                },
                false,
            )
        }

        fun mutation(id: String, method: String, params: JsonObject): HerdrRequest {
            if (method !in setOf(
                    "workspace.create",
                    "tab.create",
                    "worktree.create",
                    "worktree.open",
                    "agent.start",
                    "agent.prompt",
                    "agent.send_keys",
                    "agent.focus",
                )
            ) {
                throw HerdrProtocolException("unsupported mutation method: $method")
            }
            return HerdrRequest(id, method, params, true)
        }

        private fun subscription(id: String, paneIds: Set<String>): HerdrRequest {
            paneIds.forEach { HerdrProtocol.requireIdentifier(it, "pane id") }
            val subscriptions = buildJsonArray {
                topologyEvents.forEach { type ->
                    add(buildJsonObject { put("type", type) })
                }
                paneIds.sorted().forEach { paneId ->
                    add(buildJsonObject {
                        put("type", "pane.agent_status_changed")
                        put("pane_id", paneId)
                    })
                }
            }
            return HerdrRequest(
                id,
                "events.subscribe",
                buildJsonObject { put("subscriptions", subscriptions) },
                false,
            )
        }
    }
}

private object HerdrProtocolJson {
    val codec = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}

object HerdrProtocol {
    fun encode(request: HerdrRequest): String = requestObject(request).toString()

    fun requestObject(request: HerdrRequest): JsonObject {
        requireIdentifier(request.id, "request id")
        requireIdentifier(request.method, "request method")
        return buildJsonObject {
            put("id", request.id)
            put("method", request.method)
            put("params", request.params)
        }
    }

    fun decodeCompatiblePing(line: String, expectedId: String): HerdrPong {
        val response = decodeResponse(line, expectedId)
        val pong = (response as? HerdrResponse.Success)?.result as? HerdrResult.Pong
            ?: throw HerdrProtocolException("ping did not return pong")
        if (pong.value.protocol != HERDR_PROTOCOL_VERSION) {
            throw HerdrProtocolMismatch(HERDR_PROTOCOL_VERSION, pong.value.protocol)
        }
        return pong.value
    }

    fun decodeCapabilities(line: String, expectedId: String): List<AgentCapability> {
        val response = decodeResponse(line, expectedId)
        return ((response as? HerdrResponse.Success)?.result as? HerdrResult.Capabilities)?.values
            ?: throw HerdrProtocolException("request did not return agent capabilities")
    }

    fun decodeSnapshot(line: String, expectedId: String): HerdrSnapshot {
        val response = decodeResponse(line, expectedId)
        return ((response as? HerdrResponse.Success)?.result as? HerdrResult.Snapshot)?.value
            ?: throw HerdrProtocolException("request did not return a session snapshot")
    }

    fun decodePaneRead(line: String, expectedId: String): HerdrPaneRead {
        val response = decodeResponse(line, expectedId)
        return ((response as? HerdrResponse.Success)?.result as? HerdrResult.PaneRead)?.value
            ?: throw HerdrProtocolException("request did not return a pane read")
    }

    fun decodeResponse(line: String, expectedId: String): HerdrResponse {
        val response = parseObject(line)
        val id = response.string("id", "response id")
        requireIdentifier(expectedId, "expected response id")
        if (id != expectedId) {
            throw HerdrProtocolException("response id mismatch: expected $expectedId, received $id")
        }

        val error = response["error"]
        val result = response["result"]
        if ((error == null) == (result == null)) {
            throw HerdrProtocolException("response must contain exactly one of result or error")
        }
        if (error != null) {
            val body = decode<ErrorBody>(error, "error body")
            requireIdentifier(body.code, "error code")
            if (body.message.isBlank()) {
                throw HerdrProtocolException("error message is blank")
            }
            return HerdrResponse.Error(id, body.code, body.message)
        }

        val resultObject = result!!.objectValue("result")
        val type = resultObject.string("type", "result type")
        val typed = when (type) {
            "pong" -> {
                val value = decode<PongResult>(resultObject, "pong result")
                if (value.version.isBlank()) {
                    throw HerdrProtocolException("pong version is blank")
                }
                HerdrResult.Pong(HerdrPong(value.version, value.protocol, value.capabilities))
            }
            "agent_capabilities" -> {
                val values = decode<CapabilitiesResult>(resultObject, "agent capabilities").capabilities
                values.forEach {
                    requireIdentifier(it.kind, "agent capability kind")
                    if (it.label.isBlank()) {
                        throw HerdrProtocolException("agent capability label is blank")
                    }
                }
                if (values.map(AgentCapability::kind).distinct().size != values.size) {
                    throw HerdrProtocolException("agent capability kinds are not unique")
                }
                HerdrResult.Capabilities(values)
            }
            "session_snapshot" -> {
                val snapshot = decode<SnapshotResult>(resultObject, "session snapshot").snapshot
                validateSnapshot(snapshot)
                HerdrResult.Snapshot(snapshot)
            }
            "pane_read" -> {
                val read = decode<PaneReadResult>(resultObject, "pane read").read
                validatePaneRead(read)
                HerdrResult.PaneRead(read)
            }
            "subscription_started" -> HerdrResult.SubscriptionStarted
            "workspace_created" -> {
                val value = decode<WorkspaceCreatedResult>(resultObject, "workspace created")
                validateAllocation(value.workspace, value.tab, value.rootPane)
                HerdrResult.WorkspaceCreated(value.workspace, value.tab, value.rootPane)
            }
            "tab_created" -> {
                val value = decode<TabCreatedResult>(resultObject, "tab created")
                validateTab(value.tab)
                validatePane(value.rootPane)
                HerdrResult.TabCreated(value.tab, value.rootPane)
            }
            "worktree_created" -> {
                val value = decode<WorktreeCreatedResult>(resultObject, "worktree created")
                validateAllocation(value.workspace, value.tab, value.rootPane)
                validateWorktreeInfo(value.worktree)
                HerdrResult.WorktreeCreated(value.workspace, value.tab, value.rootPane, value.worktree)
            }
            "worktree_opened" -> {
                val value = decode<WorktreeOpenedResult>(resultObject, "worktree opened")
                validateAllocation(value.workspace, value.tab, value.rootPane)
                validateWorktreeInfo(value.worktree)
                HerdrResult.WorktreeOpened(
                    value.workspace,
                    value.tab,
                    value.rootPane,
                    value.worktree,
                    value.alreadyOpen,
                )
            }
            "agent_started" -> {
                val value = decode<AgentStartedResult>(resultObject, "agent started")
                validateAgent(value.agent)
                HerdrResult.AgentStarted(value.agent, value.argv)
            }
            "agent_prompted" -> {
                val value = decode<AgentResult>(resultObject, "agent prompted")
                validateAgent(value.agent)
                HerdrResult.AgentPrompted(value.agent)
            }
            "agent_info" -> {
                val value = decode<AgentResult>(resultObject, "agent info")
                validateAgent(value.agent)
                HerdrResult.AgentInfo(value.agent)
            }
            "ok" -> HerdrResult.Ok
            else -> throw HerdrProtocolException("unknown result type: $type")
        }
        return HerdrResponse.Success(id, typed)
    }

    fun decodeEvent(line: String): HerdrEvent {
        val envelope = parseObject(line)
        val event = envelope.string("event", "event tag")
        val data = envelope["data"]?.objectValue("event data")
            ?: throw HerdrProtocolException("event is missing data")
        val value = when (event) {
            "workspace.created" -> HerdrEvent.WorkspaceUpsert(
                decodeTyped<WorkspacePayload>(data, "workspace_created", "workspace created").workspace,
            )
            "workspace.updated" -> HerdrEvent.WorkspaceUpsert(
                decodeTyped<WorkspacePayload>(data, "workspace_updated", "workspace updated").workspace,
            )
            "workspace.metadata_updated" -> HerdrEvent.WorkspaceUpsert(
                decodeTyped<WorkspacePayload>(data, "workspace_metadata_updated", "workspace metadata").workspace,
            )
            "workspace.closed" -> HerdrEvent.WorkspaceClosed(
                decodeTyped<WorkspaceClosedPayload>(data, "workspace_closed", "workspace closed").workspaceId,
            )
            "workspace.renamed" -> {
                val payload = decodeTyped<WorkspaceRenamedPayload>(data, "workspace_renamed", "workspace renamed")
                HerdrEvent.WorkspaceRenamed(payload.workspaceId, payload.label)
            }
            "workspace.moved" -> {
                val payload = decodeTyped<WorkspaceMovedPayload>(
                    data,
                    "workspace_moved",
                    "workspace moved",
                )
                requireIdentifier(payload.workspaceId, "moved workspace id")
                if (payload.insertIndex < 0) {
                    throw HerdrProtocolException("workspace insert index is negative")
                }
                HerdrEvent.WorkspacesReplaced(payload.workspaces)
            }
            "workspace.reordered" -> {
                val payload = decodeTyped<WorkspaceReorderedPayload>(
                    data,
                    "workspace_reordered",
                    "workspace reordered",
                )
                payload.workspaceIds.forEach { requireIdentifier(it, "reordered workspace id") }
                payload.beforeWorkspaceId?.let { requireIdentifier(it, "workspace reorder anchor") }
                HerdrEvent.WorkspacesReplaced(payload.workspaces)
            }
            "workspace.focused" -> HerdrEvent.WorkspaceFocused(
                decodeTyped<WorkspaceFocusedPayload>(data, "workspace_focused", "workspace focused").workspaceId,
            )
            "worktree.created" -> {
                val payload = decodeTyped<WorktreeCreatedPayload>(data, "worktree_created", "worktree created")
                validateWorktreeInfo(payload.worktree)
                HerdrEvent.WorkspaceUpsert(payload.workspace)
            }
            "worktree.opened" -> {
                val payload = decodeTyped<WorktreeOpenedPayload>(data, "worktree_opened", "worktree opened")
                validateWorktreeInfo(payload.worktree)
                HerdrEvent.WorkspaceUpsert(payload.workspace)
            }
            "worktree.removed" -> {
                val payload = decodeTyped<WorktreeRemovedPayload>(data, "worktree_removed", "worktree removed")
                validateWorktreeInfo(payload.worktree)
                payload.workspace?.let(HerdrEvent::WorkspaceUpsert)
                    ?: HerdrEvent.WorkspaceClosed(payload.workspaceId)
            }
            "tab.created" -> HerdrEvent.TabUpsert(
                decodeTyped<TabPayload>(data, "tab_created", "tab created").tab,
            )
            "tab.closed" -> {
                val payload = decodeTyped<TabIdentityPayload>(data, "tab_closed", "tab closed")
                HerdrEvent.TabClosed(payload.workspaceId, payload.tabId)
            }
            "tab.renamed" -> {
                val payload = decodeTyped<TabRenamedPayload>(data, "tab_renamed", "tab renamed")
                HerdrEvent.TabRenamed(payload.workspaceId, payload.tabId, payload.label)
            }
            "tab.moved" -> {
                val payload = decodeTyped<TabMovedPayload>(data, "tab_moved", "tab moved")
                requireIdentifier(payload.tabId, "moved tab id")
                if (payload.insertIndex < 0) {
                    throw HerdrProtocolException("tab insert index is negative")
                }
                HerdrEvent.TabsReplaced(payload.workspaceId, payload.tabs)
            }
            "tab.focused" -> {
                val payload = decodeTyped<TabIdentityPayload>(data, "tab_focused", "tab focused")
                HerdrEvent.TabFocused(payload.workspaceId, payload.tabId)
            }
            "pane.created" -> HerdrEvent.PaneUpsert(
                decodeTyped<PanePayload>(data, "pane_created", "pane created").pane,
            )
            "pane.updated" -> HerdrEvent.PaneUpsert(
                decodeTyped<PanePayload>(data, "pane_updated", "pane updated").pane,
            )
            "pane.closed" -> {
                val payload = decodeTyped<PaneIdentityPayload>(data, "pane_closed", "pane closed")
                HerdrEvent.PaneClosed(payload.workspaceId, payload.paneId)
            }
            "pane.exited" -> {
                val payload = decodeTyped<PaneIdentityPayload>(data, "pane_exited", "pane exited")
                HerdrEvent.PaneClosed(payload.workspaceId, payload.paneId)
            }
            "pane.focused" -> {
                val payload = decodeTyped<PaneIdentityPayload>(data, "pane_focused", "pane focused")
                HerdrEvent.PaneFocused(payload.workspaceId, payload.paneId)
            }
            "pane.moved" -> {
                val payload = decodeTyped<PaneMovedPayload>(data, "pane_moved", "pane moved")
                requireIdentifier(payload.previousWorkspaceId, "previous workspace id")
                requireIdentifier(payload.previousTabId, "previous tab id")
                HerdrEvent.PaneMoved(
                    payload.previousPaneId,
                    payload.pane,
                    payload.createdWorkspace,
                    payload.createdTab,
                    payload.closedWorkspaceId,
                    payload.closedTabId,
                )
            }
            "pane.output_changed" -> {
                val payload = decodeTyped<PaneOutputPayload>(data, "pane_output_changed", "pane output")
                HerdrEvent.PaneOutputChanged(payload.workspaceId, payload.paneId, payload.revision)
            }
            "pane.agent_detected" -> {
                val payload = decodeTyped<PaneDetectedPayload>(data, "pane_agent_detected", "pane detected")
                HerdrEvent.PaneDetected(
                    payload.workspaceId,
                    payload.paneId,
                    payload.agent,
                    payload.released,
                    payload.finalStatus,
                )
            }
            "pane.agent_status_changed" -> {
                val payload = decode<PaneStatusPayload>(data, "pane status")
                data["type"]?.jsonPrimitive?.content?.let { type ->
                    if (type != "pane_agent_status_changed") {
                        throw HerdrProtocolException("unexpected pane status data type: $type")
                    }
                }
                HerdrEvent.PaneStatusChanged(
                    payload.workspaceId,
                    payload.paneId,
                    payload.agentStatus,
                    payload.agent,
                    payload.title,
                    payload.displayAgent,
                    payload.stateLabels,
                )
            }
            "layout.updated" -> {
                val layout = decodeTyped<LayoutPayload>(data, "layout_updated", "layout updated").layout
                validateLayout(layout)
                HerdrEvent.LayoutUpdated(layout)
            }
            else -> throw HerdrProtocolException("unknown event tag: $event")
        }
        validateEvent(value)
        return value
    }

    internal fun requireIdentifier(value: String, description: String) {
        if (value.isBlank() || value.any(Char::isISOControl)) {
            throw HerdrProtocolException("invalid $description")
        }
    }

    private fun parseObject(line: String): JsonObject = try {
        HerdrProtocolJson.codec.parseToJsonElement(line).jsonObject
    } catch (failure: Exception) {
        throw HerdrProtocolException("message is not a JSON object", failure)
    }

    private inline fun <reified T> decode(element: JsonElement, description: String): T = try {
        HerdrProtocolJson.codec.decodeFromJsonElement<T>(element)
    } catch (failure: Exception) {
        throw HerdrProtocolException("malformed $description", failure)
    }

    private inline fun <reified T> decodeTyped(data: JsonObject, type: String, description: String): T {
        ensureType(data, type, description)
        return decode(data, description)
    }

    private fun ensureType(data: JsonObject, type: String, description: String) {
        val actual = data.string("type", "$description data type")
        if (actual != type) {
            throw HerdrProtocolException("unexpected $description data type: $actual")
        }
    }

    private fun validateSnapshot(snapshot: HerdrSnapshot) {
        if (snapshot.protocol != HERDR_PROTOCOL_VERSION) {
            throw HerdrProtocolMismatch(HERDR_PROTOCOL_VERSION, snapshot.protocol)
        }
        if (snapshot.version.isBlank()) {
            throw HerdrProtocolException("snapshot version is blank")
        }
        snapshot.focusedWorkspaceId?.let { requireIdentifier(it, "focused workspace id") }
        snapshot.focusedTabId?.let { requireIdentifier(it, "focused tab id") }
        snapshot.focusedPaneId?.let { requireIdentifier(it, "focused pane id") }
        snapshot.workspaces.forEach(::validateWorkspace)
        snapshot.tabs.forEach(::validateTab)
        snapshot.panes.forEach(::validatePane)
        snapshot.layouts.forEach(::validateLayout)
        snapshot.agents.forEach(::validateAgent)
        requireUnique(snapshot.workspaces.map(HerdrWorkspace::workspaceId), "workspace ids")
        requireUnique(snapshot.tabs.map(HerdrTab::tabId), "tab ids")
        requireUnique(snapshot.panes.map(HerdrPane::paneId), "pane ids")
        requireUnique(snapshot.agents.map(HerdrAgent::paneId), "agent pane ids")
        snapshot.tabs.forEach { tab ->
            if (snapshot.workspaces.none { it.workspaceId == tab.workspaceId }) {
                throw HerdrProtocolException("tab references an unknown workspace: ${tab.workspaceId}")
            }
        }
        snapshot.panes.forEach { pane ->
            if (snapshot.workspaces.none { it.workspaceId == pane.workspaceId } ||
                snapshot.tabs.none { it.tabId == pane.tabId && it.workspaceId == pane.workspaceId }
            ) {
                throw HerdrProtocolException("pane references unknown topology: ${pane.paneId}")
            }
        }
        snapshot.agents.forEach { agent ->
            if (snapshot.panes.none {
                    it.paneId == agent.paneId &&
                        it.workspaceId == agent.workspaceId &&
                        it.tabId == agent.tabId
                }
            ) {
                throw HerdrProtocolException("agent references an unknown pane: ${agent.paneId}")
            }
        }
    }

    private fun validateWorkspace(workspace: HerdrWorkspace) {
        requireIdentifier(workspace.workspaceId, "workspace id")
        requireIdentifier(workspace.activeTabId, "active tab id")
        if (workspace.label.isBlank()) {
            throw HerdrProtocolException("workspace label is blank")
        }
        workspace.worktree?.let {
            requireIdentifier(it.repoKey, "repository key")
            if (it.repoName.isBlank() || it.repoRoot.isBlank() || it.checkoutPath.isBlank()) {
                throw HerdrProtocolException("worktree path data is blank")
            }
        }
    }

    private fun validateTab(tab: HerdrTab) {
        requireIdentifier(tab.tabId, "tab id")
        requireIdentifier(tab.workspaceId, "tab workspace id")
        if (tab.label.isBlank()) {
            throw HerdrProtocolException("tab label is blank")
        }
    }

    private fun validatePane(pane: HerdrPane) {
        requireIdentifier(pane.paneId, "pane id")
        requireIdentifier(pane.terminalId, "pane terminal id")
        requireIdentifier(pane.workspaceId, "pane workspace id")
        requireIdentifier(pane.tabId, "pane tab id")
        pane.agent?.let { requireIdentifier(it, "pane agent kind") }
    }

    private fun validateAgent(agent: HerdrAgent) {
        requireIdentifier(agent.terminalId, "agent terminal id")
        requireIdentifier(agent.workspaceId, "agent workspace id")
        requireIdentifier(agent.tabId, "agent tab id")
        requireIdentifier(agent.paneId, "agent pane id")
        agent.name?.let { requireIdentifier(it, "agent name") }
        agent.agent?.let { requireIdentifier(it, "agent kind") }
    }

    private fun validatePaneRead(read: HerdrPaneRead) {
        requireIdentifier(read.paneId, "pane read pane id")
        requireIdentifier(read.workspaceId, "pane read workspace id")
        requireIdentifier(read.tabId, "pane read tab id")
        if (read.source != ReadSource.RECENT_UNWRAPPED) {
            throw HerdrProtocolException("pane read used unexpected source: ${read.source}")
        }
    }

    private fun validateAllocation(workspace: HerdrWorkspace, tab: HerdrTab, pane: HerdrPane) {
        validateWorkspace(workspace)
        validateTab(tab)
        validatePane(pane)
        if (tab.workspaceId != workspace.workspaceId ||
            pane.workspaceId != workspace.workspaceId ||
            pane.tabId != tab.tabId
        ) {
            throw HerdrProtocolException("allocation result contains inconsistent topology identifiers")
        }
    }

    private fun validateWorktreeInfo(worktree: HerdrWorktreeInfo) {
        if (worktree.path.isBlank() || worktree.label.isBlank()) {
            throw HerdrProtocolException("worktree result path or label is blank")
        }
        worktree.openWorkspaceId?.let { requireIdentifier(it, "open worktree workspace id") }
    }

    private fun validateLayout(layout: HerdrPaneLayout) {
        requireIdentifier(layout.workspaceId, "layout workspace id")
        requireIdentifier(layout.tabId, "layout tab id")
        requireIdentifier(layout.focusedPaneId, "layout focused pane id")
        layout.panes.forEach { pane -> requireIdentifier(pane.paneId, "layout pane id") }
        layout.splits.forEach { split -> requireIdentifier(split.id, "layout split id") }
        requireUnique(layout.panes.map(HerdrPaneLayoutPane::paneId), "layout pane ids")
        requireUnique(layout.splits.map(HerdrPaneLayoutSplit::id), "layout split ids")
    }

    private fun validateEvent(event: HerdrEvent) {
        when (event) {
            is HerdrEvent.WorkspaceUpsert -> validateWorkspace(event.workspace)
            is HerdrEvent.WorkspacesReplaced -> event.workspaces.forEach(::validateWorkspace)
            is HerdrEvent.WorkspaceClosed -> requireIdentifier(event.workspaceId, "workspace id")
            is HerdrEvent.WorkspaceRenamed -> requireIdentifier(event.workspaceId, "workspace id")
            is HerdrEvent.WorkspaceFocused -> requireIdentifier(event.workspaceId, "workspace id")
            is HerdrEvent.TabUpsert -> validateTab(event.tab)
            is HerdrEvent.TabsReplaced -> {
                requireIdentifier(event.workspaceId, "workspace id")
                event.tabs.forEach(::validateTab)
            }
            is HerdrEvent.TabClosed -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.tabId, "tab id")
            }
            is HerdrEvent.TabRenamed -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.tabId, "tab id")
            }
            is HerdrEvent.TabFocused -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.tabId, "tab id")
            }
            is HerdrEvent.PaneUpsert -> validatePane(event.pane)
            is HerdrEvent.PaneClosed -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.paneId, "pane id")
            }
            is HerdrEvent.PaneMoved -> {
                requireIdentifier(event.previousPaneId, "previous pane id")
                validatePane(event.pane)
                event.createdWorkspace?.let(::validateWorkspace)
                event.createdTab?.let(::validateTab)
            }
            is HerdrEvent.PaneFocused -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.paneId, "pane id")
            }
            is HerdrEvent.PaneOutputChanged -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.paneId, "pane id")
            }
            is HerdrEvent.PaneDetected -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.paneId, "pane id")
                event.agent?.let { requireIdentifier(it, "agent kind") }
            }
            is HerdrEvent.PaneStatusChanged -> {
                requireIdentifier(event.workspaceId, "workspace id")
                requireIdentifier(event.paneId, "pane id")
                event.agent?.let { requireIdentifier(it, "agent kind") }
            }
            is HerdrEvent.LayoutUpdated -> validateLayout(event.layout)
        }
    }

    private fun requireUnique(values: List<String>, description: String) {
        if (values.distinct().size != values.size) {
            throw HerdrProtocolException("duplicate $description")
        }
    }

    private fun JsonObject.string(key: String, description: String): String {
        val primitive = this[key]?.jsonPrimitive
            ?: throw HerdrProtocolException("message is missing $description")
        if (!primitive.isString) {
            throw HerdrProtocolException("$description must be a string")
        }
        val value = primitive.content
        requireIdentifier(value, description)
        return value
    }

    private fun JsonElement.objectValue(description: String): JsonObject = try {
        jsonObject
    } catch (failure: Exception) {
        throw HerdrProtocolException("$description is not an object", failure)
    }
}

@Serializable
private data class ErrorBody(val code: String, val message: String)

@Serializable
private data class PongResult(
    val version: String,
    val protocol: Int,
    val capabilities: HerdrServerCapabilities? = null,
)

@Serializable
private data class CapabilitiesResult(val capabilities: List<AgentCapability>)

@Serializable
private data class SnapshotResult(val snapshot: HerdrSnapshot)

@Serializable
private data class PaneReadResult(val read: HerdrPaneRead)

@Serializable
private data class WorkspaceCreatedResult(
    val workspace: HerdrWorkspace,
    val tab: HerdrTab,
    @SerialName("root_pane") val rootPane: HerdrPane,
)

@Serializable
private data class TabCreatedResult(
    val tab: HerdrTab,
    @SerialName("root_pane") val rootPane: HerdrPane,
)

@Serializable
private data class WorktreeCreatedResult(
    val workspace: HerdrWorkspace,
    val tab: HerdrTab,
    @SerialName("root_pane") val rootPane: HerdrPane,
    val worktree: HerdrWorktreeInfo,
)

@Serializable
private data class WorktreeOpenedResult(
    val workspace: HerdrWorkspace,
    val tab: HerdrTab,
    @SerialName("root_pane") val rootPane: HerdrPane,
    val worktree: HerdrWorktreeInfo,
    @SerialName("already_open") val alreadyOpen: Boolean,
)

@Serializable
private data class AgentStartedResult(val agent: HerdrAgent, val argv: List<String>)

@Serializable
private data class AgentResult(val agent: HerdrAgent)

@Serializable
private data class WorkspacePayload(val type: String, val workspace: HerdrWorkspace)

@Serializable
private data class WorkspaceClosedPayload(
    val type: String,
    @SerialName("workspace_id") val workspaceId: String,
)

@Serializable
private data class WorkspaceRenamedPayload(
    val type: String,
    @SerialName("workspace_id") val workspaceId: String,
    val label: String,
)

@Serializable
private data class WorkspaceFocusedPayload(
    val type: String,
    @SerialName("workspace_id") val workspaceId: String,
)

@Serializable
private data class WorkspaceMovedPayload(
    val type: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("insert_index") val insertIndex: Int,
    val workspaces: List<HerdrWorkspace>,
)

@Serializable
private data class WorkspaceReorderedPayload(
    val type: String,
    @SerialName("workspace_ids") val workspaceIds: List<String>,
    @SerialName("before_workspace_id") val beforeWorkspaceId: String? = null,
    val workspaces: List<HerdrWorkspace>,
)

@Serializable
private data class WorktreeCreatedPayload(
    val type: String,
    val workspace: HerdrWorkspace,
    val worktree: HerdrWorktreeInfo,
)

@Serializable
private data class WorktreeOpenedPayload(
    val type: String,
    val workspace: HerdrWorkspace,
    val worktree: HerdrWorktreeInfo,
    @SerialName("already_open") val alreadyOpen: Boolean,
)

@Serializable
private data class WorktreeRemovedPayload(
    val type: String,
    @SerialName("workspace_id") val workspaceId: String,
    val workspace: HerdrWorkspace? = null,
    val worktree: HerdrWorktreeInfo,
    val forced: Boolean,
)

@Serializable
private data class TabPayload(val type: String, val tab: HerdrTab)

@Serializable
private data class TabIdentityPayload(
    val type: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
)

@Serializable
private data class TabRenamedPayload(
    val type: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val label: String,
)

@Serializable
private data class TabMovedPayload(
    val type: String,
    @SerialName("tab_id") val tabId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("insert_index") val insertIndex: Int,
    val tabs: List<HerdrTab>,
)

@Serializable
private data class PanePayload(val type: String, val pane: HerdrPane)

@Serializable
private data class PaneIdentityPayload(
    val type: String,
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
)

@Serializable
private data class PaneMovedPayload(
    val type: String,
    @SerialName("previous_pane_id") val previousPaneId: String,
    @SerialName("previous_workspace_id") val previousWorkspaceId: String,
    @SerialName("previous_tab_id") val previousTabId: String,
    val pane: HerdrPane,
    @SerialName("created_workspace") val createdWorkspace: HerdrWorkspace? = null,
    @SerialName("created_tab") val createdTab: HerdrTab? = null,
    @SerialName("closed_workspace_id") val closedWorkspaceId: String? = null,
    @SerialName("closed_tab_id") val closedTabId: String? = null,
)

@Serializable
private data class PaneOutputPayload(
    val type: String,
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val revision: Long,
)

@Serializable
private data class PaneDetectedPayload(
    val type: String,
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    val agent: String? = null,
    val released: Boolean = false,
    @SerialName("final_status") val finalStatus: AgentStatus? = null,
)

@Serializable
private data class PaneStatusPayload(
    @SerialName("pane_id") val paneId: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("agent_status") val agentStatus: AgentStatus,
    val agent: String? = null,
    val title: String? = null,
    @SerialName("display_agent") val displayAgent: String? = null,
    @SerialName("state_labels") val stateLabels: Map<String, String> = emptyMap(),
)

@Serializable
private data class LayoutPayload(val type: String, val layout: HerdrPaneLayout)
