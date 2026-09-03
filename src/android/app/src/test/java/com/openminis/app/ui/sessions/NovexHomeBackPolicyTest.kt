package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexHomeBackPolicyTest {
    @Test
    fun searchClosesBeforeTheRootPageCanExit() {
        assertEquals(
            NovexHomeBackAction.CLOSE_SEARCH,
            novexHomeBackAction(searchActive = true),
        )
        assertEquals(
            NovexHomeBackAction.LEAVE_HOME,
            novexHomeBackAction(searchActive = false),
        )
    }
}
