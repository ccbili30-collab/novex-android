package com.openminis.app.ui.chat

/**
 * Single source of truth for the main conversation list's physical direction.
 *
 * Kept separate from [ScrollFollowPolicy] because layout direction is geometry,
 * while follow permission is interaction state. Mixing the two was what let a
 * passive remeasure look like an authorized scroll.
 */
internal const val CHAT_TRANSCRIPT_REVERSE_LAYOUT = false

internal fun <T> transcriptRowsForLayout(rowsOldestFirst: List<T>): List<T> =
    if (CHAT_TRANSCRIPT_REVERSE_LAYOUT) rowsOldestFirst.asReversed() else rowsOldestFirst

/** Normal chronological lists are at the live tail only when no forward scroll remains. */
internal fun isAtTranscriptLatest(canScrollForward: Boolean): Boolean = !canScrollForward

internal fun latestTranscriptItemIndex(totalItemsCount: Int): Int? =
    (totalItemsCount - 1).takeIf { it >= 0 }

internal enum class TranscriptViewportMove {
    SessionOpened,
    UserSentMessage,
    UserRequestedLatest,
    UserRetriedTurn,
    PassiveStreamGrowth,
    StreamCompleted,
    ImageMeasured,
    ToolCardMeasured,
    KeyboardInsetChanged,
}

/** Only direct navigation intent may move the conversation viewport. */
internal fun allowsTranscriptViewportMove(reason: TranscriptViewportMove): Boolean = when (reason) {
    TranscriptViewportMove.SessionOpened,
    TranscriptViewportMove.UserSentMessage,
    TranscriptViewportMove.UserRequestedLatest,
    TranscriptViewportMove.UserRetriedTurn -> true
    TranscriptViewportMove.PassiveStreamGrowth,
    TranscriptViewportMove.StreamCompleted,
    TranscriptViewportMove.ImageMeasured,
    TranscriptViewportMove.ToolCardMeasured,
    TranscriptViewportMove.KeyboardInsetChanged -> false
}

internal enum class TranscriptFollowEvent {
    UserRequestedLatest,
    UserDragStarted,
    StreamCompleted,
}

/**
 * A tap on “latest” is a temporary follow request, not a distance threshold.
 * It follows passive layout growth until the stream ends or the user drags.
 */
internal data class TranscriptFollowState(
    val isFollowingLatest: Boolean = false,
) {
    fun after(event: TranscriptFollowEvent): TranscriptFollowState = when (event) {
        TranscriptFollowEvent.UserRequestedLatest -> copy(isFollowingLatest = true)
        TranscriptFollowEvent.UserDragStarted,
        TranscriptFollowEvent.StreamCompleted -> copy(isFollowingLatest = false)
    }

    fun shouldMoveFor(reason: TranscriptViewportMove): Boolean =
        allowsTranscriptViewportMove(reason) || isFollowingLatest && reason in followableGrowth

    private companion object {
        val followableGrowth = setOf(
            TranscriptViewportMove.PassiveStreamGrowth,
            TranscriptViewportMove.StreamCompleted,
            TranscriptViewportMove.ImageMeasured,
            TranscriptViewportMove.ToolCardMeasured,
            TranscriptViewportMove.KeyboardInsetChanged,
        )
    }
}
