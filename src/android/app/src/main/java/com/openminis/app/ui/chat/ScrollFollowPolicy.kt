package com.openminis.app.ui.chat

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
