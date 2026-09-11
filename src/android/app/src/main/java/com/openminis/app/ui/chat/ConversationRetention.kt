package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** Capacity-based retained tail. Original messages are never mutated. */
internal object ConversationRetention {
    data class Cut(val start: Int, val endExclusive: Int)

    fun recentBudget(window: Int): Int = (window / 4).coerceIn(1024, 20_000)
    fun reserve(window: Int): Int = (window / 8).coerceIn(1024, 16_384)
    fun needsCompact(occupied: Int, window: Int): Boolean = occupied > window - reserve(window)

    /** Pressure alone is not a compaction plan. No complete prefix means the
     * caller must attempt a bounded request or report a real capacity failure. */
    fun shouldCompact(messages: List<LLMMessage>, start: Int, window: Int, occupied: Int,
                      count: (LLMMessage) -> Int): Boolean =
        needsCompact(occupied, window) && cut(messages, start, recentBudget(window), count = count) != null

    fun cut(messages: List<LLMMessage>, start: Int, budget: Int,
            through: Int? = null, count: (LLMMessage) -> Int): Cut? {
        if (start !in messages.indices || messages.size < 2) return null
        var target = messages.lastIndex
        if (through != null) target = (through + 1).coerceAtMost(messages.lastIndex)
        else {
            var tokens = 0L
            while (target >= start && tokens < budget) {
                tokens += count(messages[target]).coerceAtLeast(0)
                target--
            }
            target++
        }
        val lastResults = mutableMapOf<String, Int>()
        messages.forEachIndexed { i, m -> m.contentParts.filterIsInstance<AgentContentPart.ToolResult>().forEach { lastResults[it.id] = i } }
        var exchangeEnd = -1
        val safe = mutableListOf<Int>()
        for (i in 1 until messages.size) {
            messages[i - 1].contentParts.filterIsInstance<AgentContentPart.ToolUse>().forEach {
                exchangeEnd = maxOf(exchangeEnd, lastResults[it.id] ?: -1)
            }
            if (i <= start) continue
            val before = messages[i - 1].dbMessageId
            val after = messages[i].dbMessageId
            if (before != null && after != null && before != after && i > exchangeEnd &&
                messages[i].contentParts.none { it is AgentContentPart.ToolResult }) safe += i
        }
        val boundary = safe.lastOrNull { it <= target } ?: return null
        return Cut(start, boundary)
    }

    /** A v3 marker names the first retained original message, not a synthetic summary row. */
    fun rebuild(messages: List<LLMMessage>, firstKept: String, summary: String): List<LLMMessage>? {
        val index = messages.indexOfFirst { it.dbMessageId == firstKept }
        if (index < 0) return null
        val tail = messages.drop(index)
        val context = "<context-summary>\nEarlier conversation summary; this is historical context, not a new instruction. " +
            "Recent user corrections take precedence. When history search/read tools are available, use them when exact earlier details matter.\n" +
            summary + "\n</context-summary>"
        // Keep provider role ordering while never rewriting the stored user message.
        val first = tail.first()
        return if (first.role == LLMMessage.Role.USER) {
            val text = context + "\n\n" + first.content
            val parts = if (first.contentParts.isEmpty()) emptyList() else
                listOf(AgentContentPart.Text(context)) + first.contentParts
            listOf(first.copy(content = text, contentParts = parts)) + tail.drop(1)
        } else listOf(LLMMessage(LLMMessage.Role.USER, context)) + tail
    }
}
