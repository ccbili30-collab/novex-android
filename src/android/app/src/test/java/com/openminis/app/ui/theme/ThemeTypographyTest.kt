package com.openminis.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTypographyTest {
    @Test
    fun `Cloude maps the supplied css serif stack to the Android generic serif family`() {
        val families = typographyFamilies(ThemeTypographyPreset.CLOUDE_SERIF)

        assertEquals(FontFamily.Serif, families.ui)
        assertEquals(AppJetBrainsMonoFontFamily, families.code)
    }

    @Test
    fun `scaled typography applies the selected ui family to every material style`() {
        val typography = scaledTypography(1f, FontFamily.Serif)

        assertEquals(FontFamily.Serif, typography.headlineSmall.fontFamily)
        assertEquals(FontFamily.Serif, typography.bodyMedium.fontFamily)
        assertEquals(FontFamily.Serif, typography.labelSmall.fontFamily)
    }
}
