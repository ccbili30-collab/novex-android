package com.openminis.app.ui.chat

internal enum class StreamingFollowEvent {
    UserDragStarted,
    UserDragStoppedAtBottom,
    UserDragStoppedAway,
    ExplicitBottomRequested,
}

internal const val STREAMING_FOLLOW_BOTTOM_THRESHOLD_DP = 4

/** True only inside the zone where a completed drag may resume live follow. */
internal fun isInsideStreamingFollowBottomZone(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    pixelsPerDp: Float,
): Boolean = firstVisibleItemIndex == 0 &&
    firstVisibleItemScrollOffsetPx <=
    (STREAMING_FOLLOW_BOTTOM_THRESHOLD_DP * pixelsPerDp).toInt()

/**
 * Streaming follow is an explicit user-intent state, not a side effect of
 * transient LazyColumn geometry. A real finger drag suspends follow
 * immediately; only an actual return to the bottom (or an explicit send / jump
 * request) enables it again.
 */
internal fun reduceStreamingFollow(
    current: Boolean,
    event: StreamingFollowEvent,
): Boolean = when (event) {
    StreamingFollowEvent.UserDragStarted,
    StreamingFollowEvent.UserDragStoppedAway -> false
    StreamingFollowEvent.UserDragStoppedAtBottom,
    StreamingFollowEvent.ExplicitBottomRequested -> true
}

/**
 * A post-scroll settle belongs only to a completed finger drag. Compose also
 * reports programmatic scrolls through LazyListState.isScrollInProgress, so
 * that signal alone must never authorize a settle-to-bottom operation.
 */
internal fun shouldSettleAfterInteraction(
    scrollInProgress: Boolean,
    userDragPending: Boolean,
    userScrolledAway: Boolean,
    isNearBottom: Boolean,
    isStreaming: Boolean,
): Boolean = !scrollInProgress &&
    userDragPending &&
    !userScrolledAway &&
    isNearBottom &&
    isStreaming
