package com.openminis.app.novex.domain

import org.json.JSONObject
import org.json.JSONArray

/** Exact persisted message parts, not model-extracted story facts. */
data class NovexCheckpointSourceEvent(val messageId: String, val role: String, val partsJson: String,
    val parentMessageId: String?, val createdAtMillis: Long, val updatedAtMillis: Long?, val error: String? = null) {
    fun toJson(): JSONObject = JSONObject().put("message_id", messageId).put("role", role).put("parts_json", partsJson)
        .put("parent_message_id", parentMessageId).put("created_at_millis", createdAtMillis).put("updated_at_millis", updatedAtMillis).put("error", error)
    val revision: String get() = NovexFrozenContextCodec.digest(toJson().toString())
}

/** One complete, branch-local continuation point for an interactive-fiction conversation. */
data class NovexPlaythroughCheckpoint(
    val id: String,
    val conversationId: String,
    val branchId: String,
    val name: String,
    val summary: String,
    /** Unverified model organization, never promoted to established story facts. */
    val stateJson: String,
    val playthroughValues: Map<String, PlaythroughValue>,
    val interactiveFictionProjectId: String?,
    val interactiveFictionSnapshotId: String?,
    val createdAtMillis: Long,
    val playthroughId: String? = null,
    val sourceEvents: List<NovexCheckpointSourceEvent> = emptyList(),
    val missingSourceMessageIds: List<String> = emptyList(),
    val sourceCaptureRecorded: Boolean = false,
    val adoptedConfigurationJson: String? = null,
    val legacyPayloadJson: String? = null,
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
        sourceEvents: List<NovexCheckpointSourceEvent>? = null,
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
            playthroughId = configuration.effectivePlaythroughId,
            sourceEvents = sourceEvents.orEmpty().filter { it.messageId in activePathIds },
            missingSourceMessageIds = activePathIds.filter { id -> sourceEvents.orEmpty().none { it.messageId == id } },
            sourceCaptureRecorded = sourceEvents != null,
            adoptedConfigurationJson = NovexConversationConfigurationCodec.encode(configuration),
        )
    }
}

/** Stable JSON document stored in the conversation workspace, independent of provider schemas. */
object NovexPlaythroughCheckpointCodec {
    fun encode(checkpoint: NovexPlaythroughCheckpoint): String = JSONObject()
        .put("version", 2)
        .put("id", checkpoint.id)
        .put("conversation_id", checkpoint.conversationId)
        .put("branch_id", checkpoint.branchId)
        .put("name", checkpoint.name)
        .put("summary", checkpoint.summary)
        .put("state", JSONObject(checkpoint.stateJson))
        .put("model_summary_status", "unverified_auxiliary")
        .put("playthrough_id", checkpoint.playthroughId)
        .put("source_capture_recorded", checkpoint.sourceCaptureRecorded)
        .put("source_events", JSONArray(checkpoint.sourceEvents.map { it.toJson().put("revision", it.revision) }))
        .put("missing_source_message_ids", JSONArray(checkpoint.missingSourceMessageIds))
        .put("adopted_configuration_json", checkpoint.adoptedConfigurationJson)
        .put("legacy_payload_json", checkpoint.legacyPayloadJson)
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
        require(root.getInt("version") in 1..2) { "不支持的存档版本" }
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
            playthroughId = root.optionalCheckpointString("playthrough_id"),
            sourceEvents = root.optJSONArray("source_events")?.let { array -> (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                NovexCheckpointSourceEvent(row.getString("message_id"), row.getString("role"), row.getString("parts_json"),
                    row.optionalCheckpointString("parent_message_id"), row.getLong("created_at_millis"),
                    if (row.has("updated_at_millis") && !row.isNull("updated_at_millis")) row.getLong("updated_at_millis") else null,
                    row.optionalCheckpointString("error")).also { require(it.revision == row.getString("revision")) { "存档原始消息修订校验不符" } }
            } }.orEmpty(),
            missingSourceMessageIds = root.optJSONArray("missing_source_message_ids")?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
            sourceCaptureRecorded = root.optBoolean("source_capture_recorded", false),
            adoptedConfigurationJson = root.optionalCheckpointString("adopted_configuration_json"),
            legacyPayloadJson = if (root.getInt("version") == 1) raw else root.optionalCheckpointString("legacy_payload_json"),
        )
    }
}

class NovexPlaythroughCheckpointWriter(
    private val store: NovexConversationWorkspaceStore,
) {
    companion object { private val writeLock = Any() }
    fun save(
        scope: NovexConversationWorkspaceScope,
        checkpoint: NovexPlaythroughCheckpoint,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry = synchronized(writeLock) {
        require(scope.conversationId == checkpoint.conversationId) { "存档不属于当前对话" }
        require(scope.writeBranchId == checkpoint.branchId) { "存档不属于当前写入分支" }
        val ref = NovexWorkspaceFileRef.create(scope, NovexWorkspaceArea.SAVES, "checkpoint-${checkpoint.id}.json")
        store.find(scope, ref)?.let { entry ->
            val existing = NovexPlaythroughCheckpointCodec.decode(store.readBytes(scope, ref).toString(Charsets.UTF_8))
            require(entry.provenance.toolCallId == provenance.toolCallId && existing.name == checkpoint.name &&
                existing.summary == checkpoint.summary && JSONObject(existing.stateJson).toString() == JSONObject(checkpoint.stateJson).toString()) {
                "同一存档操作已保存不同内容，请读取已保存存档，不能覆盖重试"
            }
            return@synchronized entry
        }
        store.writeText(
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
