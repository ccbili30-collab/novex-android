package com.openminis.app.ui.chat

internal enum class StreamingFollowEvent {
    UserDragStarted,
    UserDragStoppedAtBottom,
    UserDragStoppedAway,
    UserTurnStarted,
    ExplicitBottomRequested,
}

/**
 * Restoring or opening a conversation must not grant the application permission
 * to move the viewport. The transcript remains detached until the user performs
 * a direct navigation action.
 */
internal fun initialStreamingFollowEnabled(
    @Suppress("UNUSED_PARAMETER") isFreshConversation: Boolean,
): Boolean = false

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
 * Navigation buttons perform their own one-shot scroll. They deliberately do
 * not leave behind a live-follow latch: a later token, image decode, keyboard
 * resize, or LazyColumn remeasure must not move the reader again.
 */
internal fun reduceStreamingFollow(
    @Suppress("UNUSED_PARAMETER") current: Boolean,
    @Suppress("UNUSED_PARAMETER") event: StreamingFollowEvent,
): Boolean = false

/** Only a confirmed live-tail attachment may move the viewport during growth. */
internal fun shouldFollowStreamingGrowth(
    @Suppress("UNUSED_PARAMETER") isStreaming: Boolean,
    @Suppress("UNUSED_PARAMETER") streamingFollowEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") userScrolledAway: Boolean,
    @Suppress("UNUSED_PARAMETER") scrollInProgress: Boolean,
    @Suppress("UNUSED_PARAMETER") millisSinceUserInterrupt: Long,
): Boolean = false

/**
 * Short detached conversations grow from the visual top. Bottom alignment is
 * reserved for a reader-confirmed live-tail attachment.
 */
internal fun shouldAlignShortConversationToBottom(
    @Suppress("UNUSED_PARAMETER") streamingFollowEnabled: Boolean,
): Boolean = false

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
): Boolean = false
