package com.openminis.app.ui.chat

/**
 * Builds an inclusive conversation rewind from a visible message.
 *
 * A visible assistant bubble can merge several persisted assistant rows. Its
 * first source row is therefore the only safe database cutoff: starting at the
 * last row would leave an earlier fragment of the same reply in model history.
 */
internal object ConversationTimelineMutation {
    data class Plan(
        val cutoffDbMessageId: String,
        val retainedMessages: List<ChatMessage>,
        val deletedMessages: List<ChatMessage>,
        val invalidateCompactMarkers: Boolean,
    )

    fun inclusive(messages: List<ChatMessage>, messageId: String): Plan? {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val target = messages[index]
        return Plan(
            cutoffDbMessageId = target.sourceDbIds.firstOrNull() ?: target.id,
            retainedMessages = messages.subList(0, index).toList(),
            deletedMessages = messages.subList(index, messages.size).toList(),
            invalidateCompactMarkers = target.isCompactedHistory,
        )
    }
}
