package com.openminis.app.ui.theme

import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.fontScaleForLevel
import com.openminis.app.ui.settings.getAppearancePrefs

internal data class AppAppearance(
    val darkTheme: Boolean,
    val fontScale: Float,
    val themeColors: AppThemeColors,
)

internal fun resolveAppAppearance(
    systemDarkTheme: Boolean,
    themeMode: Int,
    appBaseLevel: Int,
    themeColors: AppThemeColors,
): AppAppearance = AppAppearance(
    darkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDarkTheme
    },
    fontScale = fontScaleForLevel(appBaseLevel),
    themeColors = themeColors,
)

/**
 * The only application appearance entry point. Both the lightweight Novex home
 * and the full runtime use this module so preference changes cannot split the UI.
 */
@Composable
internal fun NovexAppTheme(
    content: @Composable (AppAppearance) -> Unit,
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val prefs = remember(context) { getAppearancePrefs(context) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
    var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
    var themeColors by remember { mutableStateOf(AppThemeColorPreferences.read(prefs)) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            when (key) {
                KEY_THEME_MODE -> themeMode = changed.getInt(KEY_THEME_MODE, 0)
                KEY_FONT_APP_BASE -> appBaseLevel = changed.getInt(KEY_FONT_APP_BASE, 0)
                KEY_APP_THEME_COLORS -> themeColors = AppThemeColorPreferences.read(changed)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val appearance = resolveAppAppearance(
        systemDarkTheme = systemDarkTheme,
        themeMode = themeMode,
        appBaseLevel = appBaseLevel,
        themeColors = themeColors,
    )
    MinisTheme(
        darkTheme = appearance.darkTheme,
        fontScale = appearance.fontScale,
        themeColors = appearance.themeColors,
    ) {
        content(appearance)
    }
}
