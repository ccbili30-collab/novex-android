package com.openminis.app.ui.noven

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NovenSessionGroupRowShapeTest {

    @Test
    fun singleRowGroupRoundsAllFourCorners() {
        assertEquals(
            RoundedCornerShape(14.dp),
            novenSessionGroupRowShape(index = 0, count = 1),
        )
    }

    @Test
    fun firstRowRoundsOnlyTopCorners() {
        assertEquals(
            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            novenSessionGroupRowShape(index = 0, count = 3),
        )
    }

    @Test
    fun middleRowsAreSquare() {
        listOf(1, 2).forEach { index ->
            assertEquals(
                RoundedCornerShape(0.dp),
                novenSessionGroupRowShape(index = index, count = 4),
            )
        }
    }

    @Test
    fun lastRowRoundsOnlyBottomCorners() {
        assertEquals(
            RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
            novenSessionGroupRowShape(index = 2, count = 3),
        )
    }
}
