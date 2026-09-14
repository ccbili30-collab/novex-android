package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

data class NovexCheckpointRecord(val entry: NovexWorkspaceEntry, val checkpoint: NovexPlaythroughCheckpoint?, val error: String? = null)

/** Reads the real saved object. Neither an old summary nor a tool's success sentence is recovery evidence. */
class NovexCheckpointContinuation(private val store: NovexConversationWorkspaceStore) {
    private fun records(scope: NovexConversationWorkspaceScope): List<NovexCheckpointRecord> {
        val failed = NovexLegacyCheckpointRecovery.recover(store, scope)
        return (store.inspect(scope).entries
            .filter { it.workspaceRef.area == NovexWorkspaceArea.SAVES && it.workspaceRef.relativePath.startsWith("checkpoint-") && it.workspaceRef.relativePath.endsWith(".json") }
            .map { read(scope, it) } + failed).sortedByDescending { it.entry.createdAtMillis }
    }

    fun inspect(scope: NovexConversationWorkspaceScope): List<NovexCheckpointRecord> = records(scope)

    private fun read(scope: NovexConversationWorkspaceScope, entry: NovexWorkspaceEntry): NovexCheckpointRecord =
        try {
                val raw = store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8)
                require(NovexFrozenContextCodec.digest(raw) == entry.sha256) { "存档文件与登记的校验值不符" }
                val checkpoint = NovexPlaythroughCheckpointCodec.decode(raw)
                require(checkpoint.conversationId == scope.conversationId && checkpoint.branchId == entry.workspaceRef.branchId) { "存档归属不符" }
                NovexCheckpointRecord(entry, checkpoint)
        } catch (failure: Exception) { NovexCheckpointRecord(entry, null, failure.message ?: "存档无法读取") }

    fun prepare(configuration: NovexConversationConfigurationSnapshot, scope: NovexConversationWorkspaceScope): NovexContextCandidate? {
        if (configuration.activeInteractiveFiction == null && configuration.cardBindingJson==null) return null
        val examined = mutableListOf<NovexCheckpointRecord>()
        val compatible = records(scope).asSequence().onEach(examined::add).firstOrNull { row -> row.checkpoint?.let {
            it.playthroughId != null && it.playthroughId == configuration.effectivePlaythroughId
        } == true }
        if (compatible == null) return examined.firstOrNull { it.error != null || it.checkpoint?.playthroughId == null }?.let { failed ->
            NovexContextCandidate("checkpoint-unavailable:${failed.entry.sha256}", "存档读取或局次归属待核对",
                "本分支存在无法读取或缺少本局编号的存档：${failed.entry.workspaceRef.value}。${failed.error ?: "旧存档不能确定属于当前局次，未自动采用其前情"}。不能用旧摘要冒充已恢复前情。",
                ContextSourceKind.PLAYTHROUGH_STATE, alwaysInclude = true)
        }
        val checkpoint = requireNotNull(compatible.checkpoint)
        val selected = NovexCheckpointReadProjection.publicEvents(checkpoint).takeLast(8)
        val original = JSONArray(selected.map { event ->
            // Exact bounded payloads, not a second model extraction. A cut payload is explicitly labelled.
            JSONObject(event.toString()).put("parts_json", event.getString("parts_json").take(1600))
                .put("parts_truncated", event.getString("parts_json").length > 1600)
        })
        val content = buildString {
            appendLine("续接读取的是已保存存档“${checkpoint.name}”，不执行回滚，也不覆盖当前本局数值。")
            appendLine("存档引用：${compatible.entry.workspaceRef.value}；修订：${compatible.entry.sha256}")
            appendLine("这里只读取用户话语和正式回答；存档原件保留的环境、执行过程及工具结果不随续接发送。保存原话不证明其中的推断已经成立。工具调用不是人物亲历。")
            appendLine("原始依据：${if (checkpoint.sourceCaptureRecorded) "已保存 ${checkpoint.sourceEvents.size} 条" else "旧存档没有原始消息依据"}；缺失编号：${checkpoint.missingSourceMessageIds.joinToString().ifBlank { "无已知缺项" }}。")
            appendLine("下面仅展示末尾 ${selected.size} 条可用对话，每条最多 1600 字符；其余公开对话用工作区读取工具按此引用回查。${NovexCheckpointReadProjection.LABEL}。未返回部分不代表已经读过。")
            appendLine(original.toString())
            appendLine("模型摘要及补充状态仅为未核验辅助整理，保留在用户的存档原件中；本次不把它们直接装配为确定前情。优先核对上述原始事件和软件当前本局状态；冲突或无依据处保留未知。")
            if (examined.any { it.error != null }) appendLine("在此存档之前另有 ${examined.count { it.error != null }} 份较新存档无法读取，不视为已恢复。")
        }
        return NovexContextCandidate("checkpoint:${checkpoint.id}:${compatible.entry.sha256}", "对话 · 已保存续接依据", content,
            ContextSourceKind.PLAYTHROUGH_STATE, alwaysInclude = true, position = Int.MIN_VALUE + 2)
    }
}
