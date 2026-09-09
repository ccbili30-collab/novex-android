package com.openminis.app.novex.adapter

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.novex.domain.*
import org.json.JSONArray
import org.json.JSONObject

/** A read projection only. Stored dialogue, execution evidence and exports are never rewritten. */
object NovexScopedConversationHistory {
    data class Projection(val messages: List<LLMMessage>, val restrictedMessageIds: Set<String>)

    fun project(history: List<LLMMessage>, rows: List<MessageEntity>, records: List<ContextUsageRecord>,
        scopeKey: String): Projection {
        val byResponse = records.filter { it.responseMessageId != null }.associateBy { it.responseMessageId }
        val byId = rows.associateBy { it.id }
        val scopes = mutableMapOf<String, String?>()
        var inherited: String? = null
        rows.forEach { row ->
            val parts = runCatching { JSONArray(row.partsJson) }.getOrNull()
            val hasUserText = row.role == "user" && parts != null && (0 until parts.length()).any {
                parts.optJSONObject(it)?.optString("type") == "text"
            }
            if (hasUserText) inherited = null
            byResponse[row.id]?.let { inherited = it.historyScopeKey }
            scopes[row.id] = inherited
        }
        // Replay only verifiable call/result pairs. A filtered result is still tool
        // evidence, never an assistant statement or a new user instruction.
        fun restrictedScope(message: LLMMessage) =
            !NovexHistoryAccessScope.canReplay(scopes[message.dbMessageId], scopeKey)
        val uses = history.flatMapIndexed { index, message ->
            message.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { Triple(index, message, it) }
        }.groupBy { it.third.id }
        val results = history.flatMapIndexed { index, message ->
            message.contentParts.filterIsInstance<AgentContentPart.ToolResult>().map { Triple(index, message, it) }
        }.groupBy { it.third.id }
        val safeResults = results.mapNotNull { (id, candidates) ->
            val result = candidates.singleOrNull() ?: return@mapNotNull null
            val use = uses[id]?.singleOrNull() ?: return@mapNotNull null
            if (use.first >= result.first || use.third.name != result.third.name ||
                use.second.role != LLMMessage.Role.ASSISTANT || result.second.role != LLMMessage.Role.USER ||
                !restrictedScope(use.second) || !restrictedScope(result.second)) return@mapNotNull null
            val facts = NovexPublicWriteReceipt.project(result.third, scopeKey)
            if (facts.isEmpty()) return@mapNotNull null
            id to AgentContentPart.ToolResult(id, result.third.name,
                "历史执行回执；原始参数因访问范围改变已隐藏，不是新的执行请求。\n" + facts.joinToString("\n"),
                isError = result.third.isError)
        }.toMap()
        val restricted = linkedSetOf<String>()
        val messages = history.map { message ->
            val row = byId[message.dbMessageId]
            if (NovexHistoryAccessScope.canReplay(scopes[message.dbMessageId], scopeKey)) return@map message
            val hasTools = message.contentParts.any { it is AgentContentPart.ToolUse || it is AgentContentPart.ToolResult }
            val parts = row?.let { runCatching { JSONArray(it.partsJson) }.getOrNull() }
            val publicUser = message.role == LLMMessage.Role.USER && !hasTools &&
                ((row?.role == "user" && parts != null && (0 until parts.length()).any {
                    parts.optJSONObject(it)?.optString("type") in setOf("text", "mediaRef")
                }) || message.publicHistoryText != null)
            if (publicUser && row != null && message.reasoningContent.isNullOrEmpty()) return@map message
            val publicText = if (parts != null) buildList {
                repeat(parts.length()) { index ->
                    val part = parts.optJSONObject(index) ?: return@repeat
                    if (part.optString("type") == "text" && !part.optBoolean("execution", hasTools && message.role == LLMMessage.Role.ASSISTANT)) {
                        add(part.optString("value"))
                    }
                }
            } else listOfNotNull(message.publicHistoryText)
            if (hasTools || !message.reasoningContent.isNullOrEmpty() || publicText.joinToString("") != message.content) {
                message.dbMessageId?.let(restricted::add)
            }
            val safeTools = message.contentParts.mapNotNull { part -> when (part) {
                is AgentContentPart.ToolUse -> safeResults[part.id]?.let {
                    AgentContentPart.ToolUse(part.id, part.name,
                        JSONObject().put("_history_arguments_omitted", true))
                }
                is AgentContentPart.ToolResult -> safeResults[part.id]
                else -> null
            } }
            // Empty rows retain their anchors for compaction; the provider sweep drops them later.
            message.copy(content = publicText.joinToString("\n"),
                contentParts = publicText.map { AgentContentPart.Text(it) } + safeTools,
                reasoningContent = null,
                imageParts = if (publicUser) message.imageParts else emptyList(),
                audioParts = if (publicUser) message.audioParts else emptyList())
        }
        return Projection(messages, restricted)
    }
}
