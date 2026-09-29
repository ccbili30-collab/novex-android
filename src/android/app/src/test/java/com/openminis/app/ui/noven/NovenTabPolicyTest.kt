package com.openminis.app.ui.noven

import org.junit.Assert.assertEquals
import org.junit.Test

class NovenTabPolicyTest {

    @Test
    fun backOnHomeWithSearchQueryClearsSearch() {
        assertEquals(
            NovenBackAction.ClearHomeSearch,
            novenBackAction(NovenTab.HOME, "岛"),
        )
    }

    @Test
    fun backOnHomeWithoutQueryDefersToSystem() {
        assertEquals(NovenBackAction.System, novenBackAction(NovenTab.HOME, ""))
        assertEquals(NovenBackAction.System, novenBackAction(NovenTab.HOME, "  "))
    }

    @Test
    fun backOnAnyOtherTabGoesHome() {
        listOf(NovenTab.SESSIONS, NovenTab.CREATE, NovenTab.MESSAGES, NovenTab.ME).forEach { tab ->
            assertEquals(NovenBackAction.GoHome, novenBackAction(tab, ""))
            assertEquals(NovenBackAction.GoHome, novenBackAction(tab, "岛"))
        }
    }
}
