package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Test

class NovexManualOrderTest {
    @Test
    fun savedIdsLeadAndNewIdsRemainVisibleInSourceOrder() {
        assertEquals(
            listOf("b", "a", "c", "d"),
            mergeNovexManualOrder(
                sourceIds = listOf("a", "b", "c", "d"),
                savedIds = listOf("b", "missing", "a"),
            ),
        )
    }

    @Test
    fun movingAnItemProducesAPersistableCompleteOrder() {
        assertEquals(
            listOf("b", "c", "a"),
            moveNovexOrderedId(listOf("a", "b", "c"), fromIndex = 0, toIndex = 2),
        )
        assertEquals(
            listOf("a", "b", "c"),
            moveNovexOrderedId(listOf("a", "b", "c"), fromIndex = -1, toIndex = 2),
        )
    }
}
