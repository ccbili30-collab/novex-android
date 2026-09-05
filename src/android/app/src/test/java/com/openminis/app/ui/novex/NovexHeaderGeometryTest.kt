package com.openminis.app.ui.novex

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexHeaderGeometryTest {
    @Test fun titleKeepsScreenCenterWhenActionsFit() {
        assertEquals(130, novexHeaderTitleOffset(360, 48, 96, 100))
    }
    @Test fun longTitleCannotCoverActionsOnNarrowScreen() {
        assertEquals(48, novexHeaderTitleOffset(360, 48, 144, 168))
    }
    @Test fun largerLeadingSlotCannotBeCoveredEither() {
        assertEquals(144, novexHeaderTitleOffset(360, 144, 48, 168))
    }
}
