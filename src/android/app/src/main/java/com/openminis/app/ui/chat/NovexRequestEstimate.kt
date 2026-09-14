package com.openminis.app.ui.chat

import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage

/** Shared local estimate for retention, material allocation and the final send guard.
 * Tool schemas are counted in their request representation, never data-class debug text.
 * This remains an estimate; provider usage is authoritative after a request succeeds.
 */
internal object NovexRequestEstimate {
    fun total(history: List<LLMMessage>, prompt: String?, tools: List<AgentToolDefinition>): Int =
        (history.sumOf { message(it).toLong() } + BPETokenizer.countTokens(prompt.orEmpty()) +
            tools.sumOf { BPETokenizer.countTokens(it.toOpenAIJson().toString()).toLong() } + 512)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun message(message: LLMMessage): Int =
        (if (message.contentParts.isEmpty()) BPETokenizer.countTokens(message.content)
        else message.contentParts.sumOf(::part)) +
            message.imageParts.sumOf { BPETokenizer.countImageTokens(it.data) } +
            message.audioParts.sumOf { it.base64Data.length / 4 } + 8

    fun part(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> BPETokenizer.countTokens(part.content) +
            (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }
}
