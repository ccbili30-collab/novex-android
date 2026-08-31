package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTopBarPolicyTest {
    @Test
    fun normalScaleKeepsAllThreeRowsInsideTheBar() {
        assertEquals(76, chatTopBarExpandedHeightDp(1f))
    }

    @Test
    fun enlargedTextReceivesAdditionalVerticalBudget() {
        assertTrue(chatTopBarExpandedHeightDp(1.3f) > chatTopBarExpandedHeightDp(1f))
    }
}
