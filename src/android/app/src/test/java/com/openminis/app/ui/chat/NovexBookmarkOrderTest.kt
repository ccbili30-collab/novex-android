package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** 书签排序拖动的纯逻辑守护：只重排，不增删、不越界。 */
class NovexBookmarkOrderTest {
    @Test fun movesDraggedIdToTargetSlot() {
        assertEquals(
            listOf("b", "a", "c"),
            reorderBookmarkIds(listOf("a", "b", "c"), "a", 1),
        )
        assertEquals(
            listOf("b", "c", "a"),
            reorderBookmarkIds(listOf("a", "b", "c"), "a", 2),
        )
        assertEquals(
            listOf("c", "a", "b"),
            reorderBookmarkIds(listOf("a", "b", "c"), "c", 0),
        )
    }

    @Test fun clampsOutOfRangeIndexAndKeepsUnknownIdNoOp() {
        assertEquals(listOf("a", "b"), reorderBookmarkIds(listOf("a", "b"), "b", 99))
        assertEquals(listOf("a", "b"), reorderBookmarkIds(listOf("a", "b"), "missing", 0))
    }

    @Test fun singleAndEmptyListsStayStable() {
        assertEquals(listOf("only"), reorderBookmarkIds(listOf("only"), "only", 0))
        assertEquals(emptyList<String>(), reorderBookmarkIds(emptyList(), "x", 0))
    }
}
