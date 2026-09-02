package com.openminis.app.ui.novex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexVisualSystemTest {
    @Test
    fun artworkVariantIsStableAndAlwaysWithinBounds() {
        val first = novexArtworkVariant("云岚书院", 4)

        assertEquals(first, novexArtworkVariant("云岚书院", 4))
        assertTrue(first in 0..3)
        assertTrue(novexArtworkVariant("polygenelubricants", 4) in 0..3)
    }
}
