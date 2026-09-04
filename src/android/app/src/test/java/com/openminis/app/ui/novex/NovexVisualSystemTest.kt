package com.openminis.app.ui.novex

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexVisualSystemTest {
    @Test
    fun rootAndDisplayPagesStayWhiteWhileManagementPagesStayGrouped() {
        val palette = NovexSurfacePalette(
            canvas = Color.White,
            grouped = Color(0xFFF2F2F7),
            section = Color.White,
            muted = Color(0xFFF7F7FA),
        )

        assertEquals(Color.White, NovexPageTone.CONVERSATION.resolve(palette))
        assertEquals(Color.White, NovexPageTone.CATALOG.resolve(palette))
        assertEquals(Color.White, NovexPageTone.DISPLAY.resolve(palette))
        assertEquals(Color(0xFFF2F2F7), NovexPageTone.EDITOR.resolve(palette))
        assertEquals(Color(0xFFF2F2F7), NovexPageTone.SETTINGS.resolve(palette))
    }

    @Test
    fun everyTopBarActionUsesOneSharedSquareMetric() {
        assertEquals(48.dp, NovexDimensions.HeaderActionSize)
        assertEquals(22.dp, NovexDimensions.HeaderActionIconSize)
    }

    @Test
    fun everyPrimaryPageUsesTheSameContentRail() {
        val metrics = NovexLayoutMetrics(
            pageHorizontal = 16.dp,
            overlayHorizontal = 24.dp,
        )

        val pageInsets = novexPagePadding(bottom = 104.dp)

        assertEquals(metrics.pageHorizontal, pageInsets.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(metrics.pageHorizontal, pageInsets.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(104.dp, pageInsets.calculateBottomPadding())
        assertEquals(24.dp, metrics.overlayHorizontal)
    }

    @Test
    fun novexTypographyInheritsTheApplicationTypographyScale() {
        val applicationTypography = Typography(
            headlineSmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp),
            titleLarge = TextStyle(fontSize = 27.sp, lineHeight = 34.sp),
            titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
            bodyMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
            bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        )

        val actual = resolveNovexTypography(applicationTypography)

        assertEquals(30.sp, actual.brand.fontSize)
        assertEquals(27.sp, actual.pageTitle.fontSize)
        assertEquals(20.sp, actual.sectionTitle.fontSize)
        assertEquals(18.sp, actual.body.fontSize)
        assertEquals(15.sp, actual.metadata.fontSize)
    }

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
