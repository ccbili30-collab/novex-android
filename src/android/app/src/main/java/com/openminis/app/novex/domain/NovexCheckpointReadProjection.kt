package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Archives retain exact source and environment for the user. Model continuation only receives
 * public dialogue and software values; private tool reads must use current scoped card tools. */
object NovexCheckpointReadProjection {
    const val LABEL = "存档续接视图；原始环境、执行过程与工具结果未发送，不能视为已通读原始存档"

    fun projectArchive(entry: NovexWorkspaceEntry, raw: String, historyScopeKey: String?): NovexWorkspaceTextProjection? {
        if (entry.workspaceRef.area == NovexWorkspaceArea.DERIVED &&
            entry.workspaceRef.relativePath.startsWith("distillation-") && entry.workspaceRef.relativePath.endsWith(".json")) {
            val source = JSONObject(raw)
            require(source.getString("conversation_id") == entry.workspaceRef.conversationId &&
                source.getString("branch_id") == entry.workspaceRef.branchId) { "整理摘要归属不符" }
            if (historyScopeKey != null && com.openminis.app.novex.domain.NovexHistoryAccessScope.canReplay(source.optString("history_scope_key"), historyScopeKey)) return null
            val note = "旧整理摘要的身份或访问范围不符；摘要正文未提供。公开对话和已保存成果可按当前范围重新读取。"
            source.remove("summary")
            source.put("reading_note", note).put("original_sha256", entry.sha256)
            return NovexWorkspaceTextProjection(source.toString(), note)
        }
        return project(entry, raw)
    }

    fun project(entry: NovexWorkspaceEntry, raw: String): NovexWorkspaceTextProjection? {
        if (entry.workspaceRef.area != NovexWorkspaceArea.SAVES ||
            !entry.workspaceRef.relativePath.startsWith("checkpoint-") || !entry.workspaceRef.relativePath.endsWith(".json")) return null
        val saved = NovexPlaythroughCheckpointCodec.decode(raw)
        require(saved.conversationId == entry.workspaceRef.conversationId && saved.branchId == entry.workspaceRef.branchId) { "存档归属不符" }
        val events = publicEvents(saved)
        val source = JSONObject(raw)
        val view = JSONObject().put("document_type", "novex.checkpoint_read_view").put("view_version", 1)
            .put("name", saved.name).put("playthrough_id", saved.playthroughId)
            .put("original_sha256", entry.sha256).put("reading_note", LABEL)
            .put("source_capture_recorded", saved.sourceCaptureRecorded)
            .put("source_events", JSONArray(events))
            .put("stored_source_event_count", saved.sourceEvents.size)
            .put("visible_source_event_count", events.size)
            .put("missing_source_message_ids", JSONArray(saved.missingSourceMessageIds))
            .put("playthrough_state", source.getJSONObject("playthrough_state"))
            .put("auxiliary_note", "模型摘要不是确定前情；历史工具输出也不是人物亲历。需要卡片专属资料时按当前身份或管理范围重新读取。")
        return NovexWorkspaceTextProjection(view.toString(), LABEL)
    }

    fun publicEvents(saved: NovexPlaythroughCheckpoint): List<JSONObject> = saved.sourceEvents.mapNotNull { event ->
        if (event.role !in setOf("user", "assistant")) return@mapNotNull null
        val parts = runCatching { JSONArray(event.partsJson) }.getOrNull() ?: return@mapNotNull null
        val hasWork = (0 until parts.length()).any { parts.optJSONObject(it)?.optString("type") in setOf("toolUse", "toolResult", "thinking") }
        val visible = JSONArray()
        repeat(parts.length()) { index ->
            val part = parts.optJSONObject(index) ?: return@repeat
            if (part.optString("type") == "text" && !part.optBoolean("execution", event.role == "assistant" && hasWork)) {
                visible.put(JSONObject().put("type", "text").put("value", part.optString("value")))
            }
        }
        if (visible.length() == 0) return@mapNotNull null
        JSONObject().put("message_id", event.messageId).put("role", event.role)
            .put("parts_json", visible.toString()).put("source_revision", event.revision)
            .put("created_at_millis", event.createdAtMillis).put("filtered", visible.length() != parts.length())
    }
}
