package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** A persisted, auditable replacement for older messages in a model context window. */
data class NovexDistillationRecord(
    val id: String,
    val conversationId: String,
    val branchId: String,
    val summary: String,
    val sourceMessageRefs: List<NovexResourceRef>,
    val durableFactRefs: List<NovexResourceRef>,
    val createdAtMillis: Long,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "蒸馏记录编号无效" }
        require(conversationId.isNotBlank() && branchId.isNotBlank()) { "蒸馏来源不能为空" }
        require(summary.isNotBlank()) { "蒸馏摘要不能为空" }
        require(sourceMessageRefs.isNotEmpty()) { "蒸馏记录必须引用原始消息" }
        require(sourceMessageRefs.distinct().size == sourceMessageRefs.size) { "蒸馏消息引用不能重复" }
        require(durableFactRefs.distinct().size == durableFactRefs.size) { "蒸馏事实引用不能重复" }
        require(createdAtMillis >= 0) { "蒸馏时间无效" }
    }
}

object NovexDistillationRecordCodec {
    fun encode(record: NovexDistillationRecord): String = JSONObject()
        .put("version", 1)
        .put("id", record.id)
        .put("conversation_id", record.conversationId)
        .put("branch_id", record.branchId)
        .put("summary", record.summary)
        .put("source_message_refs", JSONArray(record.sourceMessageRefs.map(NovexResourceRef::value)))
        .put("durable_fact_refs", JSONArray(record.durableFactRefs.map(NovexResourceRef::value)))
        .put("created_at_millis", record.createdAtMillis)
        .toString()
}

class NovexDistillationRecordWriter(
    private val store: NovexConversationWorkspaceStore,
) {
    fun save(
        scope: NovexConversationWorkspaceScope,
        record: NovexDistillationRecord,
        provenance: NovexWorkspaceProvenance,
    ): NovexWorkspaceEntry {
        require(scope.conversationId == record.conversationId) { "蒸馏记录不属于当前对话" }
        require(scope.writeBranchId == record.branchId) { "蒸馏记录不属于当前写入分支" }
        val content = NovexDistillationRecordCodec.encode(record).toByteArray(Charsets.UTF_8)
        return store.importArtifact(
            scope = scope,
            area = NovexWorkspaceArea.DERIVED,
            relativePath = "distillation-${record.id}.json",
            bytes = content,
            mimeType = "application/json",
            provenance = provenance.copy(
                sourceRefs = (provenance.sourceRefs + record.sourceMessageRefs + record.durableFactRefs).distinct(),
            ),
        )
    }
}
