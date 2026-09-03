package com.openminis.app.ui.theme

import androidx.compose.material3.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearanceTest {
    @Test
    fun `application font scaling keeps font size and line height together`() {
        val base = Typography().bodyMedium

        val scaled = scaledTypography(1.12f).bodyMedium

        assertEquals(base.fontSize * 1.12f, scaled.fontSize)
        assertEquals(base.lineHeight * 1.12f, scaled.lineHeight)
    }

    @Test
    fun `one appearance resolver serves both lightweight home and full app`() {
        val colors = ThemeColorPresets.all.first { it.id == "purple" }.colors

        val system = resolveAppAppearance(
            systemDarkTheme = true,
            themeMode = 0,
            appBaseLevel = 2,
            themeColors = colors,
        )
        val forcedLight = resolveAppAppearance(
            systemDarkTheme = true,
            themeMode = 1,
            appBaseLevel = -1,
            themeColors = colors,
        )
        val forcedDark = resolveAppAppearance(
            systemDarkTheme = false,
            themeMode = 2,
            appBaseLevel = 0,
            themeColors = colors,
        )

        assertTrue(system.darkTheme)
        assertEquals(1.12f, system.fontScale)
        assertEquals(colors, system.themeColors)
        assertFalse(forcedLight.darkTheme)
        assertEquals(0.94f, forcedLight.fontScale)
        assertTrue(forcedDark.darkTheme)
    }
}
