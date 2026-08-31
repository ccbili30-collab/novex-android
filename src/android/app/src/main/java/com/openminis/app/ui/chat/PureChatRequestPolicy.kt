package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMMessage

internal fun hasSameToolMode(first: LLMModel, second: LLMModel): Boolean =
    (first.supportsTools != false) == (second.supportsTools != false)

/**
 * Build the model-facing history for a model explicitly configured for pure
 * chat. Stored messages remain untouched; only the request projection drops
 * structured tool plumbing and hidden reasoning.
 */
internal fun pureChatHistory(messages: List<LLMMessage>): List<LLMMessage> = buildList {
    for (message in messages) {
        val visibleParts = message.contentParts.filterNot { part ->
            part is AgentContentPart.ToolUse || part is AgentContentPart.ToolResult
        }
        val hadStructuredParts = message.contentParts.isNotEmpty()
        val hasVisiblePayload = message.content.isNotBlank() ||
            visibleParts.any { part ->
                when (part) {
                    is AgentContentPart.Text -> part.text.isNotBlank()
                    is AgentContentPart.ImageData -> true
                    is AgentContentPart.ToolUse, is AgentContentPart.ToolResult -> false
                }
            } || message.imageParts.isNotEmpty() || message.audioParts.isNotEmpty()

        if (!hasVisiblePayload && hadStructuredParts) continue
        add(
            message.copy(
                contentParts = visibleParts,
                reasoningContent = null,
            ),
        )
    }
}
