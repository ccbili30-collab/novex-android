package com.openminis.app.ui.theme

import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import com.openminis.app.ui.settings.KEY_FONT_APP_BASE
import com.openminis.app.ui.settings.KEY_THEME_MODE
import com.openminis.app.ui.settings.getAppearancePrefs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real preferences and Compose recomposition, without an Activity, device or clicks. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppAppearanceLifecycleTest {
    private data class RenderedTheme(
        val appearance: AppAppearance,
        val background: Color,
        val font: FontFamily?,
        val bodySize: TextUnit,
    )

    @Test
    fun `Cloude preview cancel save reset and recreation reach the rendered theme`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val prefs = getAppearancePrefs(context)
        prefs.edit().putInt(KEY_THEME_MODE, 1).putInt(KEY_FONT_APP_BASE, 0).commit()
        assertTrue(AppThemeColorPreferences.write(prefs, ThemeColorPresets.default.colors))
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val preview = mutableStateOf<AppThemeColors?>(null)
        var rendered: RenderedTheme? = null
        var previewBackground: Color? = null
        var frame = 0L

        fun settle() {
            repeat(3) {
                shadowOf(Looper.getMainLooper()).idle()
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameClock.sendFrame(++frame * 16_000_000L)
                runCurrent()
            }
        }

        fun open(): Composition = Composition(NoNodes(), recomposer).also { composition ->
            composition.setContent {
                CompositionLocalProvider(
                    LocalContext provides context,
                    LocalConfiguration provides Configuration(context.resources.configuration),
                ) {
                    NovexAppTheme { appearance ->
                        val current = RenderedTheme(appearance, MaterialTheme.colorScheme.background,
                            MaterialTheme.typography.bodyLarge.fontFamily, MaterialTheme.typography.bodyLarge.fontSize)
                        SideEffect { rendered = current }
                        preview.value?.let { draft ->
                            MinisThemePreview(darkTheme = false, themeColors = draft) {
                                val background = MaterialTheme.colorScheme.background
                                SideEffect { previewBackground = background }
                            }
                        }
                    }
                }
            }
        }

        var composition = open()
        try {
            settle()
            val original = requireNotNull(rendered)
            assertEquals(Color.White, original.background)
            assertTrue(AppThemeColorPreferences.write(prefs, ThemeColorPresets.cloude.colors))
            settle()
            val cloude = requireNotNull(rendered)
            assertEquals(Color(0xFFF5F4EE), cloude.background)
            assertEquals(FontFamily.Serif, cloude.font)

            preview.value = ThemeColorPresets.default.colors
            settle()
            assertEquals(Color.White, previewBackground)
            assertEquals(cloude, rendered)
            preview.value = null // Cancel the local preview, without a preference write.
            settle()
            assertEquals(cloude, rendered)

            assertTrue(AppThemeColorPreferences.write(prefs, ThemeColorPresets.default.colors))
            settle()
            assertEquals(original, rendered)
            composition.dispose()
            composition = open()
            settle()
            assertEquals(original, rendered)

            // Each selection must replace the previous rendered palette, including
            // transitions away from Cloude, rather than merely updating preferences.
            for (preset in ThemeColorPresets.all.reversed()) {
                assertTrue(AppThemeColorPreferences.write(prefs, preset.colors))
                settle()
                assertEquals(preset.colors, requireNotNull(rendered).appearance.themeColors)
                assertEquals(Color(preset.colors.light.background), requireNotNull(rendered).background)
            }
            val custom = ThemeColorPresets.cloude.colors.update(
                ThemeVariantMode.Dark,
                ThemeColorPresets.cloude.colors.dark.copy(foreground = 0xFFFFFFFF.toInt()),
            )
            assertTrue(validateThemeColors(custom).isEmpty())
            assertTrue(AppThemeColorPreferences.write(prefs, custom))
            settle()
            composition.dispose()
            composition = open()
            settle()
            assertEquals(custom, requireNotNull(rendered).appearance.themeColors)
            assertEquals(Color(0xFFF5F4EE), requireNotNull(rendered).background)
            assertTrue(AppThemeColorPreferences.write(prefs, ThemeColorPresets.default.colors))
            settle()
            assertEquals(original, rendered)

            prefs.edit().putInt(KEY_THEME_MODE, 2).putInt(KEY_FONT_APP_BASE, 2).commit()
            settle()
            assertTrue(requireNotNull(rendered).appearance.darkTheme)
            assertEquals(1.12f, requireNotNull(rendered).appearance.fontScale)
            assertEquals(original.bodySize * 1.12f, requireNotNull(rendered).bodySize)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            recomposer.join()
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("Theme must not create layout nodes")
        override fun insertBottomUp(index: Int, instance: Unit) = error("Theme must not create layout nodes")
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
