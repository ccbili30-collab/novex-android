package com.openminis.app.novex.adapter

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

data class NovexContextReadReceipt(val result: JSONObject, val usage: ContextUsageRecord?)

/** Records only ranges returned by the scoped reader, before acknowledging the tool result. */
class NovexContextReadJournal(private val repository: ChatRepository) {
    suspend fun record(conversationId: String, requestMessageId: String, responseMessageId: String,
        activeMessageIds: Set<String>, answerIdentity: AnswerIdentity, effectiveWindowTokens: Int,
        operation: String, result: JSONObject, now: Long = System.currentTimeMillis()): NovexContextReadReceipt {
        require(requestMessageId.isNotBlank() && responseMessageId.isNotBlank()) { "阅读记录缺少当前请求或回复编号" }
        val reads = observations(operation, result)
        val previous = repository.novexContextUsage(conversationId)
        val ledger = NovexContextUsageLedger.open(NovexContextUsageLedgerSnapshot(conversationId, previous))
            .withSourceReads(requestMessageId, responseMessageId, reads, activeMessageIds, answerIdentity, effectiveWindowTokens, now)
        val previousById = previous.associateBy { it.id }
        ledger.snapshot.records.filter { previousById[it.id] != it }.forEach { repository.recordNovexContextUsage(conversationId, it) }
        val visible = activeMessageIds + responseMessageId
        val returnedSources = reads.mapTo(mutableSetOf()) { it.sourceId to it.revision }
        val coverage = ledger.readCoverageForActivePath(visible).filter { (it.sourceId to it.revision) in returnedSources }
        val payload = JSONObject(result.toString()).put("read_coverage", JSONArray(coverage.map {
            JSONObject().put("source_id", it.sourceId).put("label", it.label).put("revision", it.revision)
                .put("covered_characters", it.coveredCharacters).put("total_characters", it.totalCharacters).put("complete", it.complete)
        })).put("coverage_note", "覆盖范围累计当前分支上同一修订的正文读取；目录预览与搜索不算通读。曾经读过不代表全文仍在当前上下文中，需要时可重新定位读取。")
        return NovexContextReadReceipt(payload, ledger.latestByRequestForActivePath(visible)[requestMessageId])
    }

    private fun observations(operation: String, payload: JSONObject): List<NovexSourceRead> = when (operation) {
        "read" -> listOf(observation(payload, payload.getInt("read_start"), payload.getInt("read_end"),
            payload.getString("text"), NovexSourceReadMethod.READ))
        "inspect" -> payload.getJSONArray("sources").objects().map {
            val text = it.getString("preview")
            observation(it, 0, text.length, text, NovexSourceReadMethod.PREVIEW)
        }
        "search" -> payload.getJSONArray("matches").objects().map {
            observation(it, it.getInt("excerpt_start"), it.getInt("excerpt_end"), it.getString("excerpt"), NovexSourceReadMethod.SEARCH)
        }
        else -> error("未知的阅读记录类型")
    }

    private fun observation(source: JSONObject, start: Int, end: Int, text: String, method: NovexSourceReadMethod): NovexSourceRead {
        require(text.length == end - start) { "返回正文与阅读范围不一致，未记录为已读" }
        return NovexSourceRead(source.getString("source_id"), source.getString("label"), source.getString("revision"),
            start, end, source.getInt("total_characters"), method)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
}
