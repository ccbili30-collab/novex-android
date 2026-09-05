package com.openminis.app.ui.novex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovexActionMenuDispatchTest {
    @Test
    fun selectedActionWaitsUntilTheMenuIsActuallyDismissed() {
        var calls = 0
        val coordinator = NovexMenuActionCoordinator()

        coordinator.request { calls += 1 }

        assertNull(coordinator.takeReadyAction(menuExpanded = true))
        assertEquals(0, calls)
        coordinator.takeReadyAction(menuExpanded = false)?.invoke()
        assertEquals(1, calls)
        assertNull(coordinator.takeReadyAction(menuExpanded = false))
    }
}
