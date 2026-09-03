package com.openminis.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeColorsTest {

    @Test
    fun `default preset preserves the existing Novex color contract`() {
        val colors = ThemeColorPresets.default.colors

        assertEquals(0xFF528AD2.toInt(), colors.light.accent)
        assertEquals(0xFFF2F2F7.toInt(), colors.light.background)
        assertEquals(0xFF171D1C.toInt(), colors.light.foreground)
        assertEquals(0xFF6A94CE.toInt(), colors.dark.accent)
        assertEquals(0xFF000000.toInt(), colors.dark.background)
        assertEquals(0xFFDEE4E2.toInt(), colors.dark.foreground)
    }

    @Test
    fun `hex colors accept six digit rgb and normalize uppercase`() {
        val parsed = parseOpaqueThemeColor("#52a8d2")

        assertEquals(0xFF52A8D2.toInt(), parsed)
        assertEquals("#52A8D2", parsed?.let(::formatOpaqueThemeColor))
        assertNull(parseOpaqueThemeColor("52A8D2"))
        assertNull(parseOpaqueThemeColor("#12345"))
        assertNull(parseOpaqueThemeColor("#GGGGGG"))
    }

    @Test
    fun `every bundled preset keeps text and accent readable in both modes`() {
        ThemeColorPresets.all.forEach { preset ->
            assertTrue(
                "${preset.id} should pass the contrast rules",
                validateThemeColors(preset.colors).isEmpty(),
            )
        }
    }

    @Test
    fun `validation rejects unreadable foreground and weak accent`() {
        val colors = AppThemeColors(
            presetId = ThemeColorPresets.CUSTOM_ID,
            light = ThemeVariantColors(
                accent = 0xFFF4F4F4.toInt(),
                background = 0xFFFFFFFF.toInt(),
                foreground = 0xFFF7F7F7.toInt(),
            ),
            dark = ThemeColorPresets.default.colors.dark,
        )

        val issues = validateThemeColors(colors)

        assertTrue(issues.any { it.mode == ThemeVariantMode.Light && it.field == ThemeColorField.Foreground })
        assertTrue(issues.any { it.mode == ThemeVariantMode.Light && it.field == ThemeColorField.Accent })
        assertFalse(issues.any { it.mode == ThemeVariantMode.Dark })
    }

    @Test
    fun `editing one variant turns a preset into custom without changing the other variant`() {
        val original = ThemeColorPresets.default.colors
        val edited = original.update(
            mode = ThemeVariantMode.Dark,
            colors = original.dark.copy(accent = 0xFFFFB020.toInt()),
        )

        assertEquals(ThemeColorPresets.CUSTOM_ID, edited.presetId)
        assertEquals(original.light, edited.light)
        assertEquals(0xFFFFB020.toInt(), edited.dark.accent)
    }

    @Test
    fun `on color selects the higher contrast neutral`() {
        assertEquals(0xFF000000.toInt(), bestOnColor(0xFFFFD54F.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), bestOnColor(0xFF243447.toInt()))
    }

    @Test
    fun `stored theme round trips atomically and malformed values are rejected`() {
        val colors = ThemeColorPresets.all.first { it.id == "purple" }.colors

        val encoded = AppThemeColorPreferences.encode(colors)

        assertEquals(colors, AppThemeColorPreferences.decode(encoded))
        assertNull(AppThemeColorPreferences.decode("1|purple|#123456"))
        assertNull(AppThemeColorPreferences.decode("2|purple|#123456|#FFFFFF|#000000|#123456|#000000|#FFFFFF"))
    }

    @Test
    fun `unknown stored preset is retained as custom colors`() {
        val encoded = AppThemeColorPreferences.encode(
            ThemeColorPresets.default.colors.copy(presetId = "removed-preset"),
        )

        val decoded = AppThemeColorPreferences.decode(encoded)

        assertEquals(ThemeColorPresets.CUSTOM_ID, decoded?.presetId)
        assertEquals(ThemeColorPresets.default.colors.light, decoded?.light)
        assertEquals(ThemeColorPresets.default.colors.dark, decoded?.dark)
    }
}
