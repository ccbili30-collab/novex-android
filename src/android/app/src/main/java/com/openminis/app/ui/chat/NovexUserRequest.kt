package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ChatRepository

/** A persisted user turn may consist solely of a document receipt or audio.
 * Tool results use USER on the wire but never own a new user request. */
internal fun latestNovexUserRequest(history: List<LLMMessage>): LLMMessage? =
    history.lastOrNull { message ->
        message.role == LLMMessage.Role.USER && !message.dbMessageId.isNullOrBlank() &&
            message.contentParts.none { it is AgentContentPart.ToolResult || it is AgentContentPart.ToolUse } &&
            (ChatRepository.stripSystemReminders(message.content).isNotBlank() || message.imageParts.isNotEmpty() || message.audioParts.isNotEmpty() ||
                message.contentParts.any { it is AgentContentPart.Text && ChatRepository.stripSystemReminders(it.text).isNotBlank() || it is AgentContentPart.ImageData })
    }
