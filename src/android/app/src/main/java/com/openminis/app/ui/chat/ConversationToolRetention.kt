package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.json.JSONObject

/** A per-request projection. Stored and in-memory originals stay untouched, so
 * increasing the window restores the full results and history recall stays exact.
 */
internal object ConversationToolRetention {
    private const val PREFIX = "[历史工具结果按需回读]"

    fun project(history: List<LLMMessage>, window: Int, occupied: Int, canRecall: Boolean,
                count: (AgentContentPart) -> Int): List<LLMMessage> {
        if (!canRecall || !ConversationRetention.needsCompact(occupied, window)) return history
        val target = (window - ConversationRetention.reserve(window) - window / 20 - 1024).coerceAtLeast(0)
        val projected = history.toMutableList()
        data class Candidate(val row: Int, val part: Int, val cost: Int)
        val candidates = history.take((history.size - 4).coerceAtLeast(0)).flatMapIndexed { row, message ->
            if (message.dbMessageId == null) emptyList() else message.contentParts.mapIndexedNotNull { index, part ->
                if (part is AgentContentPart.ToolResult && part.imageData == null &&
                    part.content.length > 500 && !part.content.startsWith(PREFIX)) Candidate(row, index, count(part)) else null
            }
        }.sortedByDescending { it.cost }
        var remaining = occupied.toLong()
        for (candidate in candidates) {
            if (remaining <= target) break
            val message = projected[candidate.row]
            val parts = message.contentParts.toMutableList()
            val original = parts[candidate.part] as AgentContentPart.ToolResult
            val pointer = JSONObject().put("message_id", message.dbMessageId).put("offset", 0)
            val excerptEnd = original.content.offsetByCodePoints(0, minOf(160, original.content.codePointCount(0, original.content.length)))
            val replacement = original.copy(content = "$PREFIX\n原始结果仍保存在当前对话。需要细节时调用 read_conversation_history（读取历史原文）：$pointer，按 next_offset（下一页位置）继续。\n片段（不是完整结果）：\n" + original.content.substring(0, excerptEnd))
            val saved = candidate.cost - count(replacement)
            if (saved <= 0) continue
            parts[candidate.part] = replacement
            projected[candidate.row] = message.copy(contentParts = parts)
            remaining -= saved
        }
        return projected
    }
}
