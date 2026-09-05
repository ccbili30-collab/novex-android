package com.openminis.app.novex.domain

import org.json.JSONObject

/** One complete, branch-local continuation point for an interactive-fiction conversation. */
data class NovexPlaythroughCheckpoint(
    val id: String,
    val conversationId: String,
    val branchId: String,
    val name: String,
    val summary: String,
    /** Model-supplied structured facts that do not belong in the typed playthrough state. */
    val stateJson: String,
    val playthroughValues: Map<String, PlaythroughValue>,
    val interactiveFictionProjectId: String?,
    val interactiveFictionSnapshotId: String?,
    val createdAtMillis: Long,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "存档编号无效" }
        require(conversationId.isNotBlank()) { "存档对话编号不能为空" }
        require(branchId.isNotBlank()) { "存档分支编号不能为空" }
        require(name.isNotBlank()) { "存档名称不能为空" }
        require(summary.isNotBlank()) { "存档摘要不能为空" }
        require(playthroughValues.keys.none(String::isBlank)) { "存档状态字段名不能为空" }
        require(createdAtMillis >= 0) { "存档时间无效" }
        require(interactiveFictionProjectId == null || interactiveFictionProjectId.isNotBlank()) {
            "文游项目编号不能为空"
        }
        require(interactiveFictionSnapshotId == null || interactiveFictionSnapshotId.isNotBlank()) {
            "文游快照编号不能为空"
        }
        requireStructuredObject(stateJson)
    }
}

object NovexPlaythroughCheckpointFactory {
    fun create(
        id: String,
        configuration: NovexConversationConfigurationSnapshot,
        activePathIds: List<String>,
        writeBranchId: String,
        name: String,
        summary: String,
        stateJson: String,
        createdAtMillis: Long,
    ): NovexPlaythroughCheckpoint {
        require(writeBranchId.isNotBlank()) { "没有可写入的活动消息分支" }
        val normalizedState = requireStructuredObject(stateJson).toString()
        val visibleState = InteractiveFictionRuntime.resolveState(configuration, activePathIds)
        return NovexPlaythroughCheckpoint(
            id = id,
            conversationId = configuration.conversationId,
            branchId = writeBranchId,
            name = name.trim(),
            summary = summary.trim(),
            stateJson = normalizedState,
            playthroughValues = visibleState.values,
            interactiveFictionProjectId = configuration.activeInteractiveFiction?.projectId,
            interactiveFictionSnapshotId = configuration.activeInteractiveFiction?.snapshotId,
            createdAtMillis = createdAtMillis,
        )
    }
}

/** Stable JSON document stored in the conversation workspace, independent of provider schemas. */
object NovexPlaythroughCheckpointCodec {
    fun encode(checkpoint: NovexPlaythroughCheckpoint): String = JSONObject()
        .put("version", 1)
        .put("id", checkpoint.id)
        .put("conversation_id", checkpoint.conversationId)
        .put("branch_id", checkpoint.branchId)
        .put("name", checkpoint.name)
        .put("summary", checkpoint.summary)
        .put("state", JSONObject(checkpoint.stateJson))
        .put("playthrough_state", JSONObject().apply {
            checkpoint.playthroughValues.forEach { (key, value) -> put(key, value.toCheckpointJson()) }
        })
        .apply {
            checkpoint.interactiveFictionProjectId?.let { put("interactive_fiction_project_id", it) }
            checkpoint.interactiveFictionSnapshotId?.let { put("interactive_fiction_snapshot_id", it) }
        }
        .put("created_at_millis", checkpoint.createdAtMillis)
        .toString()

    fun decode(raw: String): NovexPlaythroughCheckpoint {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1) { "不支持的存档版本" }
        val state = root.optJSONObject("state") ?: throw IllegalArgumentException("存档状态必须是 JSON 对象")
        val playthrough = root.optJSONObject("playthrough_state") ?: JSONObject()
        val values = playthrough.keys().asSequence().associateWith { key ->
            val encoded = playthrough.optJSONObject(key)
                ?: throw IllegalArgumentException("存档状态字段格式无效：$key")
            encoded.toPlaythroughValue()
        }
        return NovexPlaythroughCheckpoint(
            id = root.getString("id"),
            conversationId = root.getString("conversation_id"),
            branchId = root.getString("branch_id"),
            name = root.getString("name"),
            summary = root.getString("summary"),
            stateJson = state.toString(),
            playthroughValues = values,
            interactiveFictionProjectId = root.optionalCheckpointString("interactive_fiction_project_id"),
            interactiveFictionSnapshotId = root.optionalCheckpointString("interactive_fiction_snapshot_id"),
            createdAtMillis = root.getLong("created_at_millis"),
        )
    }
}

class NovexPlaythroughCheckpointWriter(
    private val store: NovexConversationWorkspaceStore,
) {
    fun save(
        scope: NovexConversationWorkspaceScope,
        checkpoint: NovexPlaythroughCheckpoint,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry {
        require(scope.conversationId == checkpoint.conversationId) { "存档不属于当前对话" }
        require(scope.writeBranchId == checkpoint.branchId) { "存档不属于当前写入分支" }
        return store.writeText(
            scope = scope,
            area = NovexWorkspaceArea.SAVES,
            relativePath = "checkpoint-${checkpoint.id}.json",
            content = NovexPlaythroughCheckpointCodec.encode(checkpoint),
            mimeType = "application/json",
            provenance = provenance,
        )
    }
}

private fun requireStructuredObject(value: String): JSONObject = try {
    JSONObject(value)
} catch (failure: Exception) {
    throw IllegalArgumentException("存档状态必须是 JSON 对象", failure)
}

private fun PlaythroughValue.toCheckpointJson(): JSONObject = when (this) {
    is PlaythroughValue.Text -> JSONObject().put("kind", "text").put("value", value)
    is PlaythroughValue.Number -> JSONObject().put("kind", "number").put("value", value)
    is PlaythroughValue.Flag -> JSONObject().put("kind", "flag").put("value", value)
}

private fun JSONObject.toPlaythroughValue(): PlaythroughValue = when (getString("kind")) {
    "text" -> PlaythroughValue.Text(getString("value"))
    "number" -> PlaythroughValue.Number(getDouble("value"))
    "flag" -> PlaythroughValue.Flag(getBoolean("value"))
    else -> throw IllegalArgumentException("未知存档状态类型")
}

private fun JSONObject.optionalCheckpointString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotBlank)
