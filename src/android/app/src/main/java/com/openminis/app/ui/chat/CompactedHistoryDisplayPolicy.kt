package com.openminis.app.ui.chat

/**
 * Keeps compacted rows in storage while deciding whether they belong on screen.
 * The compact divider itself is not marked as compacted history, so it remains
 * visible as the single affordance for reopening the archived transcript.
 */
internal fun conversationMessagesForDisplay(
    messages: List<ChatMessage>,
    compactedHistoryExpanded: Boolean,
): List<ChatMessage> = if (compactedHistoryExpanded) {
    messages
} else {
    messages.filterNot(ChatMessage::isCompactedHistory)
}
