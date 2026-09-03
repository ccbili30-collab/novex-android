package com.openminis.app.ui.theme

import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val KEY_APP_THEME_COLORS = "app_theme_colors_v1"

enum class ThemeVariantMode {
    Light,
    Dark,
}

enum class ThemeColorField {
    Accent,
    Background,
    Foreground,
}

data class ThemeVariantColors(
    val accent: Int,
    val background: Int,
    val foreground: Int,
)

data class AppThemeColors(
    val presetId: String,
    val light: ThemeVariantColors,
    val dark: ThemeVariantColors,
) {
    fun variant(mode: ThemeVariantMode): ThemeVariantColors = when (mode) {
        ThemeVariantMode.Light -> light
        ThemeVariantMode.Dark -> dark
    }

    fun update(mode: ThemeVariantMode, colors: ThemeVariantColors): AppThemeColors = when (mode) {
        ThemeVariantMode.Light -> copy(presetId = ThemeColorPresets.CUSTOM_ID, light = colors)
        ThemeVariantMode.Dark -> copy(presetId = ThemeColorPresets.CUSTOM_ID, dark = colors)
    }
}

data class ThemeColorPreset(
    val id: String,
    val colors: AppThemeColors,
)

object ThemeColorPresets {
    const val CUSTOM_ID = "custom"

    val default = preset(
        id = "novex",
        lightAccent = 0xFF528AD2.toInt(),
        lightBackground = 0xFFF2F2F7.toInt(),
        lightForeground = 0xFF171D1C.toInt(),
        darkAccent = 0xFF6A94CE.toInt(),
        darkBackground = 0xFF000000.toInt(),
        darkForeground = 0xFFDEE4E2.toInt(),
    )

    val all: List<ThemeColorPreset> = listOf(
        default,
        preset(
            id = "blue",
            lightAccent = 0xFF2563EB.toInt(),
            lightBackground = 0xFFF5F7FB.toInt(),
            lightForeground = 0xFF171A21.toInt(),
            darkAccent = 0xFF8AB4F8.toInt(),
            darkBackground = 0xFF0C111B.toInt(),
            darkForeground = 0xFFF2F5F9.toInt(),
        ),
        preset(
            id = "purple",
            lightAccent = 0xFF7C3AED.toInt(),
            lightBackground = 0xFFF8F6FB.toInt(),
            lightForeground = 0xFF211A29.toInt(),
            darkAccent = 0xFFC4B5FD.toInt(),
            darkBackground = 0xFF15111B.toInt(),
            darkForeground = 0xFFF7F2FA.toInt(),
        ),
        preset(
            id = "pink",
            lightAccent = 0xFFBE185D.toInt(),
            lightBackground = 0xFFFCF6F9.toInt(),
            lightForeground = 0xFF281920.toInt(),
            darkAccent = 0xFFF9A8D4.toInt(),
            darkBackground = 0xFF1A1015.toInt(),
            darkForeground = 0xFFFFF4F9.toInt(),
        ),
        preset(
            id = "green",
            lightAccent = 0xFF047857.toInt(),
            lightBackground = 0xFFF4F9F6.toInt(),
            lightForeground = 0xFF15221B.toInt(),
            darkAccent = 0xFF6EE7B7.toInt(),
            darkBackground = 0xFF0E1612.toInt(),
            darkForeground = 0xFFF1F8F4.toInt(),
        ),
        preset(
            id = "amber",
            lightAccent = 0xFFB45309.toInt(),
            lightBackground = 0xFFFBF8F2.toInt(),
            lightForeground = 0xFF251D13.toInt(),
            darkAccent = 0xFFFBBF24.toInt(),
            darkBackground = 0xFF18140D.toInt(),
            darkForeground = 0xFFFFF8E8.toInt(),
        ),
        preset(
            id = "monochrome",
            lightAccent = 0xFF1A1C1F.toInt(),
            lightBackground = 0xFFF4F4F5.toInt(),
            lightForeground = 0xFF171719.toInt(),
            darkAccent = 0xFFE5E7EB.toInt(),
            darkBackground = 0xFF111113.toInt(),
            darkForeground = 0xFFF5F5F6.toInt(),
        ),
    )

    fun find(id: String): ThemeColorPreset? = all.firstOrNull { it.id == id }

    private fun preset(
        id: String,
        lightAccent: Int,
        lightBackground: Int,
        lightForeground: Int,
        darkAccent: Int,
        darkBackground: Int,
        darkForeground: Int,
    ): ThemeColorPreset {
        val colors = AppThemeColors(
            presetId = id,
            light = ThemeVariantColors(lightAccent, lightBackground, lightForeground),
            dark = ThemeVariantColors(darkAccent, darkBackground, darkForeground),
        )
        return ThemeColorPreset(id = id, colors = colors)
    }
}

data class ThemeColorIssue(
    val mode: ThemeVariantMode,
    val field: ThemeColorField,
    val actualRatio: Double,
    val requiredRatio: Double,
)

object AppThemeColorPreferences {
    fun read(prefs: SharedPreferences): AppThemeColors =
        decode(prefs.getString(KEY_APP_THEME_COLORS, null)) ?: ThemeColorPresets.default.colors

    fun write(prefs: SharedPreferences, colors: AppThemeColors): Boolean =
        prefs.edit().putString(KEY_APP_THEME_COLORS, encode(colors)).commit()

    fun encode(colors: AppThemeColors): String = listOf(
        "1",
        colors.presetId,
        formatOpaqueThemeColor(colors.light.accent),
        formatOpaqueThemeColor(colors.light.background),
        formatOpaqueThemeColor(colors.light.foreground),
        formatOpaqueThemeColor(colors.dark.accent),
        formatOpaqueThemeColor(colors.dark.background),
        formatOpaqueThemeColor(colors.dark.foreground),
    ).joinToString("|")

    fun decode(raw: String?): AppThemeColors? {
        val parts = raw?.split('|') ?: return null
        if (parts.size != 8 || parts[0] != "1") return null
        val values = parts.drop(2).map(::parseOpaqueThemeColor)
        if (values.any { it == null }) return null
        val presetId = parts[1].takeIf { candidate ->
            candidate == ThemeColorPresets.CUSTOM_ID || ThemeColorPresets.find(candidate) != null
        } ?: ThemeColorPresets.CUSTOM_ID
        return AppThemeColors(
            presetId = presetId,
            light = ThemeVariantColors(
                accent = values[0]!!,
                background = values[1]!!,
                foreground = values[2]!!,
            ),
            dark = ThemeVariantColors(
                accent = values[3]!!,
                background = values[4]!!,
                foreground = values[5]!!,
            ),
        )
    }
}

fun parseOpaqueThemeColor(value: String): Int? {
    if (!value.matches(Regex("^#[0-9a-fA-F]{6}$"))) return null
    return runCatching { (0xFF000000L or value.substring(1).toLong(16)).toInt() }.getOrNull()
}

fun formatOpaqueThemeColor(color: Int): String =
    String.format(Locale.ROOT, "#%06X", color and 0x00FFFFFF)

fun validateThemeColors(colors: AppThemeColors): List<ThemeColorIssue> = buildList {
    ThemeVariantMode.entries.forEach { mode ->
        val variant = colors.variant(mode)
        val textRatio = contrastRatio(variant.foreground, variant.background)
        if (textRatio < 4.5) {
            add(ThemeColorIssue(mode, ThemeColorField.Foreground, textRatio, 4.5))
        }
        val accentRatio = contrastRatio(variant.accent, variant.background)
        if (accentRatio < 3.0) {
            add(ThemeColorIssue(mode, ThemeColorField.Accent, accentRatio, 3.0))
        }
    }
}

fun contrastRatio(first: Int, second: Int): Double {
    val firstLuminance = relativeLuminance(first)
    val secondLuminance = relativeLuminance(second)
    return (max(firstLuminance, secondLuminance) + 0.05) /
        (min(firstLuminance, secondLuminance) + 0.05)
}

fun relativeLuminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val value = ((color ushr shift) and 0xFF) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

fun bestOnColor(background: Int): Int =
    if (contrastRatio(0xFF000000.toInt(), background) >=
        contrastRatio(0xFFFFFFFF.toInt(), background)
    ) {
        0xFF000000.toInt()
    } else {
        0xFFFFFFFF.toInt()
    }

fun blendOpaqueColors(foreground: Int, background: Int, foregroundFraction: Float): Int {
    val fraction = foregroundFraction.coerceIn(0f, 1f)
    fun channel(shift: Int): Int {
        val front = (foreground ushr shift) and 0xFF
        val back = (background ushr shift) and 0xFF
        return (front * fraction + back * (1f - fraction)).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
