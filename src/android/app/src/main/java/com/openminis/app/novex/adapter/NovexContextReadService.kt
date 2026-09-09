package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.NovexConversationConfigurationSnapshot
import com.openminis.app.novex.domain.NovexWorkspace
import com.openminis.app.novex.domain.ContextSourceKind
import com.openminis.app.novex.domain.NovexContextCandidate
import com.openminis.app.novex.domain.NovexFrozenContextCodec
import org.json.JSONArray
import org.json.JSONObject

/** Read-only source access shares exactly the same identity and background projection as requests. */
class NovexContextReadService(
    private val workspace: NovexWorkspace,
    private val legacy: NovexLegacyContext = NovexLegacyContext(),
    private val visibleMessages: List<String> = emptyList(),
) {
    suspend fun inspect(configuration: NovexConversationConfigurationSnapshot, offset: Int = 0, limit: Int = 80): JSONObject {
        require(offset >= 0 && limit in 1..100) { "目录偏移不能为负，每页数量须为一到一百" }
        val candidates = candidates(configuration)
        require(offset <= candidates.size) { "目录偏移超出范围，请重新查看目录" }
        val page = candidates.drop(offset).take(limit)
        return JSONObject().put("sources", JSONArray(page.map { candidate ->
            candidate.descriptor().put("preview", candidate.content.take(240)).put("preview_is_excerpt", candidate.content.length > 240)
        })).put("total_sources", candidates.size).put("next_offset", (offset + page.size).takeIf { it < candidates.size })
    }

    suspend fun search(configuration: NovexConversationConfigurationSnapshot, query: String, offset: Int = 0, limit: Int = 20): JSONObject {
        val term = query.trim()
        require(term.isNotEmpty() && term.length <= 300) { "搜索内容须为一到三百个字符" }
        require(offset >= 0 && limit in 1..50) { "搜索偏移不能为负，每页数量须为一到五十" }
        val matches = candidates(configuration).mapNotNull { candidate ->
            val index = candidate.content.indexOf(term, ignoreCase = true)
            if (index < 0) null else candidate to index
        }
        require(offset <= matches.size) { "搜索偏移超出范围，请重新搜索" }
        val page = matches.drop(offset).take(limit)
        return JSONObject().put("matches", JSONArray(page.map { (candidate, index) ->
            val start = (index - 120).coerceAtLeast(0)
            val end = (index + term.length + 240).coerceAtMost(candidate.content.length)
            candidate.descriptor().put("excerpt", candidate.content.substring(start, end))
                .put("excerpt_start", start).put("excerpt_end", end).put("read_all", false)
        })).put("total_sources_matched", matches.size).put("next_offset", (offset + page.size).takeIf { it < matches.size })
    }

    suspend fun read(configuration: NovexConversationConfigurationSnapshot, sourceId: String,
        offset: Int = 0, limit: Int = 12_000, revision: String? = null): JSONObject {
        require(offset >= 0 && limit in 1..24_000) { "读取偏移不能为负，单次长度须为一到两万四千个字符" }
        val candidate = requireNotNull(candidates(configuration).singleOrNull {
            (it.sourceId == sourceId || (revision != null && it.sourceId == "$sourceId@revision:$revision")) &&
                (revision == null || NovexFrozenContextCodec.digest(it.content) == revision)
        }) {
            "来源不可用、修订不符或存在并列修订；请按当前目录的来源编号和修订读取。私有身份模块不能通过猜测编号读取"
        }
        val currentRevision = NovexFrozenContextCodec.digest(candidate.content)
        require(offset == 0 || !revision.isNullOrBlank()) { "继续读取必须使用上次返回的修订摘要，不能拼接不同版本" }
        require(revision == null || revision == currentRevision) { "资料修订已经变化，请重新查看并从头读取" }
        require(offset <= candidate.content.length) { "读取偏移超出来源长度" }
        var end = offset + limit.coerceAtMost(candidate.content.length - offset)
        if (end in 1 until candidate.content.length && candidate.content[end - 1].isHighSurrogate() && candidate.content[end].isLowSurrogate()) end++
        return candidate.descriptor().put("text", candidate.content.substring(offset, end))
            .put("read_start", offset).put("read_end", end).put("next_offset", end.takeIf { it < candidate.content.length })
            .put("read_all", offset == 0 && end == candidate.content.length)
    }

    private suspend fun candidates(configuration: NovexConversationConfigurationSnapshot): List<NovexContextCandidate> =
        WorkspaceNovexContextLoader(workspace, legacy).load(configuration)
            .filter { it.kind != ContextSourceKind.TOOL_DEFINITION && it.content.isNotBlank() &&
                com.openminis.app.novex.domain.NovexWorldbookConditions.omission(it.worldbookConditions, visibleMessages) == null }

    private fun NovexContextCandidate.descriptor(): JSONObject = JSONObject().put("source_id", sourceId)
        .put("label", label).put("revision", NovexFrozenContextCodec.digest(content)).put("total_characters", content.length)
}
