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

    @Test
    fun builtInWorldArtworkUsesStablePublicIds() {
        assertEquals(
            listOf(
                "world.cover.mountain-gate.v1",
                "world.cover.future-city.v1",
                "world.cover.cosmic-ruins.v1",
                "world.cover.warm-daily.v1",
            ),
            NovexBuiltInWorldArtwork.coverIds,
        )
        assertEquals(
            listOf(
                "world.background.mountain.v1",
                "world.background.cosmic.v1",
                "world.background.daily.v1",
            ),
            NovexBuiltInWorldArtwork.backgroundIds,
        )

        val first = NovexBuiltInWorldArtwork.stableCoverId("world-42")
        assertEquals(first, NovexBuiltInWorldArtwork.stableCoverId("world-42"))
        assertTrue(first in NovexBuiltInWorldArtwork.coverIds)
    }

    @Test
    fun charactersWithoutUserMediaUseNeutralEmptyArtwork() {
        assertEquals(
            NovexArtworkFallback.NeutralEmpty,
            novexArtworkFallback(NovexArtworkKind.CHARACTER, "character-42"),
        )
        assertTrue(
            novexArtworkFallback(NovexArtworkKind.WORLD, "world-42") is
                NovexArtworkFallback.BuiltInWorldCover,
        )
    }
}
