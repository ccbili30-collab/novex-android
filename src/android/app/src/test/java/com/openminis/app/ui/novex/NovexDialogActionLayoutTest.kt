package com.openminis.app.ui.novex

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexDialogActionLayoutTest {
    @Test
    fun oneOrTwoActionsStayHorizontalWhileThreeActionsStack() {
        assertEquals(NovexDialogActionLayout.HORIZONTAL, novexDialogActionLayout(1))
        assertEquals(NovexDialogActionLayout.HORIZONTAL, novexDialogActionLayout(2))
        assertEquals(NovexDialogActionLayout.VERTICAL, novexDialogActionLayout(3))
    }
}
