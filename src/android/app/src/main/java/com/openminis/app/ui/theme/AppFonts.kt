package com.openminis.app.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.openminis.app.R

/** Shared application font families; feature pages must not create private copies. */
val AppJetBrainsMonoFontFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

internal data class AppTypographyFamilies(
    val ui: FontFamily,
    val code: FontFamily,
)

internal fun typographyFamilies(preset: ThemeTypographyPreset): AppTypographyFamilies = when (preset) {
    ThemeTypographyPreset.SYSTEM_SANS -> AppTypographyFamilies(
        ui = FontFamily.Default,
        code = FontFamily.Monospace,
    )
    ThemeTypographyPreset.CLOUDE_SERIF -> AppTypographyFamilies(
        // Android's generic serif family is the platform equivalent of the supplied
        // CSS chain: ui-serif, Georgia, Cambria, Times, Noto Serif SC, serif.
        ui = FontFamily.Serif,
        code = AppJetBrainsMonoFontFamily,
    )
}

internal val LocalAppCodeFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

internal data class AppSemanticPalette(
    val diffAdded: Color,
    val diffRemoved: Color,
    val skill: Color,
)

private val DefaultAppSemanticPalette = AppSemanticPalette(
    diffAdded = Color(0xFF1A991A),
    diffRemoved = Color(0xFFCC1A1A),
    skill = Color(0xFF8E8E93),
)

internal fun semanticPalette(preset: ThemeColorPreset?): AppSemanticPalette =
    preset?.sourceContract?.semanticColors?.let {
        AppSemanticPalette(Color(it.diffAdded), Color(it.diffRemoved), Color(it.skill))
    } ?: DefaultAppSemanticPalette

internal val LocalAppSemanticPalette = staticCompositionLocalOf { DefaultAppSemanticPalette }
