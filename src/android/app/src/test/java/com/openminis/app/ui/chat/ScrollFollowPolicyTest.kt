package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollFollowPolicyTest {
    @Test
    fun freshConversationStaysDetachedUntilBottomIsExplicitlyConfirmed() {
        var following = initialStreamingFollowEnabled(isFreshConversation = true)

        following = reduceStreamingFollow(
            following,
            StreamingFollowEvent.UserTurnStarted,
        )

        assertFalse(following)
        assertFalse(
            shouldFollowStreamingGrowth(
                isStreaming = true,
                streamingFollowEnabled = following,
                userScrolledAway = false,
                scrollInProgress = false,
                millisSinceUserInterrupt = 5_000L,
            ),
        )
        assertFalse(shouldAlignShortConversationToBottom(following))

        following = reduceStreamingFollow(
            following,
            StreamingFollowEvent.UserDragStoppedAtBottom,
        )

        assertTrue(following)
        assertTrue(shouldAlignShortConversationToBottom(following))
    }

    @Test
    fun sendingAnotherMessageDoesNotReattachAHistoryReader() {
        val following = reduceStreamingFollow(
            current = false,
            event = StreamingFollowEvent.UserTurnStarted,
        )

        assertFalse(following)
    }

    @Test
    fun existingConversationMayRestoreBottomFollow() {
        assertTrue(initialStreamingFollowEnabled(isFreshConversation = false))
    }

    @Test
    fun confirmedBottomFollowsOnlyActiveUninterruptedGrowth() {
        assertTrue(
            shouldFollowStreamingGrowth(
                isStreaming = true,
                streamingFollowEnabled = true,
                userScrolledAway = false,
                scrollInProgress = false,
                millisSinceUserInterrupt = 1_000L,
            ),
        )
        assertFalse(
            shouldFollowStreamingGrowth(true, true, true, false, 5_000L),
        )
        assertFalse(
            shouldFollowStreamingGrowth(true, true, false, true, 5_000L),
        )
        assertFalse(
            shouldFollowStreamingGrowth(true, true, false, false, 999L),
        )
        assertFalse(
            shouldFollowStreamingGrowth(false, true, false, false, 5_000L),
        )
    }

    @Test
    fun fingerDragImmediatelySuspendsStreamingFollow() {
        assertFalse(
            reduceStreamingFollow(
                current = true,
                event = StreamingFollowEvent.UserDragStarted,
            ),
        )
    }

    @Test
    fun followResumesOnlyAfterUserActuallyReturnsToBottom() {
        assertFalse(
            reduceStreamingFollow(
                current = false,
                event = StreamingFollowEvent.UserDragStoppedAway,
            ),
        )
        assertTrue(
            reduceStreamingFollow(
                current = false,
                event = StreamingFollowEvent.UserDragStoppedAtBottom,
            ),
        )
    }

    @Test
    fun explicitSendOrBottomRequestEnablesFollow() {
        assertTrue(
            reduceStreamingFollow(false, StreamingFollowEvent.ExplicitBottomRequested),
        )
    }

    @Test
    fun programmaticScrollCompletionCannotTriggerInteractionSettle() {
        assertFalse(
            shouldSettleAfterInteraction(
                scrollInProgress = false,
                userDragPending = false,
                userScrolledAway = false,
                isNearBottom = true,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun completedFingerDragMaySettleWhileFollowingLiveOutput() {
        assertTrue(
            shouldSettleAfterInteraction(
                scrollInProgress = false,
                userDragPending = true,
                userScrolledAway = false,
                isNearBottom = true,
                isStreaming = true,
            ),
        )
    }

    @Test
    fun activeScrollOrHistoryReadingNeverSettles() {
        assertFalse(
            shouldSettleAfterInteraction(true, true, false, true, true),
        )
        assertFalse(
            shouldSettleAfterInteraction(false, true, true, false, true),
        )
    }

    @Test
    fun smallUpwardPeekLeavesTheStreamingFollowZone() {
        assertFalse(
            isInsideStreamingFollowBottomZone(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 8,
                pixelsPerDp = 1f,
            ),
        )
    }

    @Test
    fun tinyRoundingOffsetStillCountsAsBottom() {
        assertTrue(
            isInsideStreamingFollowBottomZone(
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffsetPx = 2,
                pixelsPerDp = 1f,
            ),
        )
    }
}
