package com.openminis.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexInitialRouteBackPolicyTest {
    @Test
    fun toolbarBackFinishesTheTemporaryHostWhenTheInitialRouteHasNoParent() {
        assertEquals(NovexInitialRouteBackAction.POP, novexInitialRouteBackAction(canPop = true))
        assertEquals(NovexInitialRouteBackAction.FINISH_HOST, novexInitialRouteBackAction(canPop = false))
    }

    @Test
    fun settingsEntersFromTheLeftWhileContentEditorsKeepTheNormalDirection() {
        assertEquals(NovexRouteEntryEdge.LEFT, novexRouteEntryEdge("settings"))
        assertEquals(NovexRouteEntryEdge.RIGHT, novexRouteEntryEdge("characters/world/edit"))
    }
}
