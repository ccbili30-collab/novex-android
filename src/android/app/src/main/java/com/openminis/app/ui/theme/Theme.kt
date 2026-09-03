package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Accent: iOS blue, desaturated. [T-android-accent-blue-parity]
//
// Was a teal (#2E8B8B / #4DD9D9) that predated iOS settling on blue. The hue
// now comes from iOS Assets.xcassets/AccentColor.colorset (sRGB components
// r0.212 g0.525 b0.933 -> #3686EE light, r0.329 g0.565 b0.894 -> #5490E4 dark),
// but iOS's saturation (84% / 73%) read as glaring on Android's darker
// surfaces, so SATURATION is dialled back ~30% with hue and lightness kept:
//   light  #3686EE  S84% L57%  ->  #528AD2  S59% L57%
//   dark   #5490E4  S73% L61%  ->  #6A94CE  S51% L61%
//
// Lightness is deliberately NOT raised, which is the other way to "lighten".
// It would have softened dark mode further but pushed light-mode contrast on
// white from 3.62 to 2.62 — below WCAG AA's 4.5 for text. Desaturating keeps
// dark mode at 5.93 (passing) and leaves light mode where it was.
//
// Names keep the `Teal` prefix only to avoid churning 90+ call sites; the
// value is the contract, not the name.
private val TealPrimary = Color(0xFF528AD2)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFB2DFDB)
private val TealOnPrimaryContainer = Color(0xFF00332F)
private val TealSecondary = Color(0xFF4A6360)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFCCE8E4)
private val TealOnSecondaryContainer = Color(0xFF05201D)
private val TealTertiary = Color(0xFF46617A)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFCDE5FF)
private val TealOnTertiaryContainer = Color(0xFF001D32)
private val TealBackground = Color(0xFFF5FAFA)
private val TealOnBackground = Color(0xFF171D1C)
private val TealSurface = Color(0xFFF5FAFA)
private val TealOnSurface = Color(0xFF171D1C)
private val TealSurfaceVariant = Color(0xFFDAE5E2)
private val TealOnSurfaceVariant = Color(0xFF3F4947)
private val TealOutline = Color(0xFF6F7977)

private val TealDarkPrimary = Color(0xFF6A94CE)
private val TealDarkOnPrimary = Color(0xFF003737)
private val TealDarkPrimaryContainer = Color(0xFF1A6B6B)
private val TealDarkOnPrimaryContainer = Color(0xFFB2DFDB)
private val TealDarkSecondary = Color(0xFFB1CCC8)
private val TealDarkOnSecondary = Color(0xFF1C3532)
private val TealDarkSecondaryContainer = Color(0xFF334B48)
private val TealDarkOnSecondaryContainer = Color(0xFFCCE8E4)
private val TealDarkBackground = Color(0xFF0E1514)
private val TealDarkOnBackground = Color(0xFFDEE4E2)
private val TealDarkSurface = Color(0xFF0E1514)
private val TealDarkOnSurface = Color(0xFFDEE4E2)
private val TealDarkSurfaceVariant = Color(0xFF3F4947)
private val TealDarkOnSurfaceVariant = Color(0xFFBEC9C6)
private val TealDarkOutline = Color(0xFF899390)

// Neutral grouped-card surfaces (iOS-style system-grouped background).
// Override Material3's tonal `surfaceContainer*` so cards don't pick up the
// teal primary tint.
// Light: page = #F2F2F7 gray, card = white
// Dark:  page = #000, card = #1C1C1E
private val NeutralGroupedBg = Color(0xFFF2F2F7)
private val NeutralGroupedCard = Color(0xFFFFFFFF)
private val NeutralGroupedCardElevated = Color(0xFFF7F7FA)
private val NeutralOutline = Color(0xFFD1D1D6)

private val NeutralDarkGroupedBg = Color(0xFF000000)
private val NeutralDarkGroupedCard = Color(0xFF1C1C1E)
private val NeutralDarkGroupedCardElevated = Color(0xFF2C2C2E)
private val NeutralDarkOutline = Color(0xFF38383A)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedBg,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    themeColors: AppThemeColors = ThemeColorPresets.default.colors,
    content: @Composable () -> Unit,
) {
    val variant = themeColors.variant(
        if (darkTheme) ThemeVariantMode.Dark else ThemeVariantMode.Light
    )
    val usesNovexDefaults = themeColors == ThemeColorPresets.default.colors
    val colorScheme = if (usesNovexDefaults) {
        if (darkTheme) DarkColorScheme else LightColorScheme
    } else {
        themedColorScheme(variant)
    }
    val typography = scaledTypography(fontScale)
    val chatPalette = if (usesNovexDefaults) {
        if (darkTheme) DarkChatPalette else LightChatPalette
    } else {
        themedChatPalette(variant)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(LocalChatPalette provides chatPalette, content = content)
    }
}

/** Applies draft colors without replacing the caller's typography or shapes. */
@Composable
fun MinisThemePreview(
    darkTheme: Boolean,
    themeColors: AppThemeColors,
    content: @Composable () -> Unit,
) {
    val variant = themeColors.variant(
        if (darkTheme) ThemeVariantMode.Dark else ThemeVariantMode.Light
    )
    val usesNovexDefaults = themeColors == ThemeColorPresets.default.colors
    val parentTypography = MaterialTheme.typography
    val parentShapes = MaterialTheme.shapes
    MaterialTheme(
        colorScheme = if (usesNovexDefaults) {
            if (darkTheme) DarkColorScheme else LightColorScheme
        } else {
            themedColorScheme(variant)
        },
        typography = parentTypography,
        shapes = parentShapes,
    ) {
        CompositionLocalProvider(
            LocalChatPalette provides if (usesNovexDefaults) {
                if (darkTheme) DarkChatPalette else LightChatPalette
            } else {
                themedChatPalette(variant)
            },
            content = content,
        )
    }
}

private fun themedColorScheme(
    colors: ThemeVariantColors,
) = run {
    val darkSurface = relativeLuminance(colors.background) < 0.35
    val accent = Color(colors.accent)
    val background = Color(colors.background)
    val foreground = Color(colors.foreground)
    val onAccent = Color(bestOnColor(colors.accent))
    val primaryContainerInt = blendOpaqueColors(
        colors.accent,
        colors.background,
        if (darkSurface) 0.30f else 0.16f,
    )
    val secondaryInt = blendOpaqueColors(colors.accent, colors.foreground, 0.68f)
    val secondaryContainerInt = blendOpaqueColors(
        colors.accent,
        colors.background,
        if (darkSurface) 0.22f else 0.11f,
    )
    val surfaceLowInt = blendOpaqueColors(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.10f else 0.025f,
    )
    val surfaceInt = blendOpaqueColors(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.14f else 0.045f,
    )
    val surfaceHighInt = blendOpaqueColors(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.19f else 0.075f,
    )
    val onSurfaceVariantInt = blendOpaqueColors(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.76f else 0.72f,
    )
    val outlineInt = blendOpaqueColors(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.38f else 0.22f,
    )

    val commonPrimaryContainer = Color(primaryContainerInt)
    val commonSecondary = Color(secondaryInt)
    val commonSecondaryContainer = Color(secondaryContainerInt)
    val commonSurfaceLow = Color(surfaceLowInt)
    val commonSurface = Color(surfaceInt)
    val commonSurfaceHigh = Color(surfaceHighInt)
    val commonOnSurfaceVariant = Color(onSurfaceVariantInt)
    val commonOutline = Color(outlineInt)

    if (darkSurface) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = commonPrimaryContainer,
            onPrimaryContainer = Color(bestOnColor(primaryContainerInt)),
            secondary = commonSecondary,
            onSecondary = Color(bestOnColor(secondaryInt)),
            secondaryContainer = commonSecondaryContainer,
            onSecondaryContainer = Color(bestOnColor(secondaryContainerInt)),
            tertiary = commonSecondary,
            onTertiary = Color(bestOnColor(secondaryInt)),
            tertiaryContainer = commonSecondaryContainer,
            onTertiaryContainer = Color(bestOnColor(secondaryContainerInt)),
            background = background,
            onBackground = foreground,
            surface = background,
            onSurface = foreground,
            surfaceVariant = commonSurface,
            onSurfaceVariant = commonOnSurfaceVariant,
            surfaceContainerLowest = background,
            surfaceContainerLow = commonSurfaceLow,
            surfaceContainer = commonSurface,
            surfaceContainerHigh = commonSurfaceHigh,
            surfaceContainerHighest = commonSurfaceHigh,
            outline = commonOutline,
            outlineVariant = commonOutline,
            error = DarkColorScheme.error,
            onError = DarkColorScheme.onError,
            errorContainer = DarkColorScheme.errorContainer,
            onErrorContainer = DarkColorScheme.onErrorContainer,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = commonPrimaryContainer,
            onPrimaryContainer = Color(bestOnColor(primaryContainerInt)),
            secondary = commonSecondary,
            onSecondary = Color(bestOnColor(secondaryInt)),
            secondaryContainer = commonSecondaryContainer,
            onSecondaryContainer = Color(bestOnColor(secondaryContainerInt)),
            tertiary = commonSecondary,
            onTertiary = Color(bestOnColor(secondaryInt)),
            tertiaryContainer = commonSecondaryContainer,
            onTertiaryContainer = Color(bestOnColor(secondaryContainerInt)),
            background = background,
            onBackground = foreground,
            surface = background,
            onSurface = foreground,
            surfaceVariant = commonSurface,
            onSurfaceVariant = commonOnSurfaceVariant,
            surfaceContainerLowest = background,
            surfaceContainerLow = commonSurfaceLow,
            surfaceContainer = commonSurface,
            surfaceContainerHigh = commonSurfaceHigh,
            surfaceContainerHighest = commonSurfaceHigh,
            outline = commonOutline,
            outlineVariant = commonOutline,
            error = LightColorScheme.error,
            onError = LightColorScheme.onError,
            errorContainer = LightColorScheme.errorContainer,
            onErrorContainer = LightColorScheme.onErrorContainer,
        )
    }
}

private fun themedChatPalette(
    colors: ThemeVariantColors,
): ChatPalette {
    val darkSurface = relativeLuminance(colors.background) < 0.35
    val base = if (darkSurface) DarkChatPalette else LightChatPalette
    fun themedColor(foreground: Int, background: Int, fraction: Float): Color =
        Color(blendOpaqueColors(foreground, background, fraction))

    val background = Color(colors.background)
    val foreground = Color(colors.foreground)
    val accent = Color(colors.accent)
    val secondary = themedColor(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.14f else 0.045f,
    )
    val elevated = themedColor(
        colors.foreground,
        colors.background,
        if (darkSurface) 0.20f else 0.08f,
    )
    val subtle = themedColor(colors.foreground, colors.background, if (darkSurface) 0.76f else 0.68f)
    val faint = themedColor(colors.foreground, colors.background, if (darkSurface) 0.42f else 0.34f)
    val border = themedColor(colors.foreground, colors.background, if (darkSurface) 0.35f else 0.20f)
    val userBubble = themedColor(
        colors.accent,
        colors.background,
        if (darkSurface) 0.34f else 0.14f,
    )

    return base.copy(
        background = background,
        secondaryBg = secondary,
        inputBg = elevated,
        inputIconBg = secondary,
        inputIconBorder = border,
        inputBorder = border,
        primaryText = foreground,
        secondaryText = subtle,
        tertiaryText = faint,
        disabledText = faint.copy(alpha = 0.55f),
        userBubble = userBubble,
        toolBg = secondary,
        toolBorder = border,
        toolCapsuleBg = elevated,
        separator = border,
        sendButton = accent,
        sendButtonDisabled = faint,
        inlineCodeBg = elevated,
        link = accent,
        thinking = accent,
        tableBorder = border,
        toastBg = userBubble,
        thumbnailBorder = border,
        sheetHeaderBg = elevated,
        sheetHeaderBorder = border,
        fabAccent = themedColor(colors.accent, colors.background, if (darkSurface) 0.30f else 0.18f),
    )
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight == TextUnit.Unspecified) lineHeight else lineHeight * factor,
    )

internal fun scaledTypography(factor: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
