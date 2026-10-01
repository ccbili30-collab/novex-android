package com.openminis.app.ui.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.R
import com.openminis.app.data.repository.AppIconRepository
import com.openminis.app.ui.components.MinisTextButton
import kotlin.math.roundToInt
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.Slider

// ── 偏好键（跨进程契约，逐字冻结）─────────────────────────────────────────────

const val PREF_APPEARANCE = "appearance_prefs"
const val KEY_THEME_MODE = "theme_mode"                    // 0=System, 1=Light, 2=Dark
const val KEY_RETURN_KEY_BEHAVIOR = "returnKeyBehavior"    // 0=Newline(默认), 1=Send
const val KEY_KEEP_SCREEN_AWAKE = "keepScreenAwakeDuringTasks"
const val KEY_TOOL_PREVIEW = "tool_preview"                // 默认 true
const val KEY_CHROME_AUTO_FADE = "chat.chromeAutoFade"     // 默认 false：仅手动点空白淡出
const val KEY_SHOW_CONTEXT_METER = "chat.showContextMeter" // 默认 true
const val KEY_AUTO_FOCUS_AFTER_REPLY = "chat.autoFocusAfterReply"  // 默认 true
const val KEY_SHOW_CHAT_TITLE = "appearance.show_chat_title"       // 默认 true
const val KEY_AUTO_EXPAND_THINKING = "chat.autoExpandThinking"     // 默认 true
const val KEY_AUTO_GROUPING = "autoGroupingEnabled"                // 默认 true
const val KEY_FONT_CHAT_INPUT = "font_chat_input"          // 档位 -2..3
const val KEY_FONT_MESSAGE = "font_message"
const val KEY_FONT_APP_BASE = "font_app_base"
const val KEY_LANGUAGE = "app_language"                    // ""=跟随系统, en/zh/ja/ko/fr/de/ru

fun getAppearancePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences(PREF_APPEARANCE, Context.MODE_PRIVATE)

fun getThemeMode(context: Context): Int =
    getAppearancePrefs(context).getInt(KEY_THEME_MODE, 0)

fun returnKeySendsMessage(context: Context): Boolean =
    getAppearancePrefs(context).getInt(KEY_RETURN_KEY_BEHAVIOR, 0) == 1

fun keepScreenAwakeEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_KEEP_SCREEN_AWAKE, false)

fun chromeAutoFadeEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_CHROME_AUTO_FADE, false)

fun autoGroupingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_GROUPING, true)

fun showChatTitleEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_CHAT_TITLE, true)

fun autoExpandThinkingEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_AUTO_EXPAND_THINKING, true)

fun showContextMeterEnabled(context: Context): Boolean =
    getAppearancePrefs(context).getBoolean(KEY_SHOW_CONTEXT_METER, true)

// ── 字号档位 ─────────────────────────────────────────────────────────────────

private val FONT_LEVELS = listOf(-2, -1, 0, 1, 2, 3)
private val FONT_LEVEL_LABELS = listOf("XS", "Small", "Default", "Medium", "Large", "XL")
private val FONT_MULTIPLIERS = listOf(0.88f, 0.94f, 1.0f, 1.06f, 1.12f, 1.21f)

fun fontScaleForLevel(level: Int): Float =
    FONT_MULTIPLIERS[FONT_LEVELS.indexOf(level).coerceIn(0, FONT_MULTIPLIERS.lastIndex)]

fun getFontScale(context: Context, key: String): Float =
    fontScaleForLevel(getAppearancePrefs(context).getInt(key, 0))

// ── 语言选项 ─────────────────────────────────────────────────────────────────

private data class LanguageOption(val code: String, val flag: String, val selfName: String)

// code 为空 = 跟随系统，标签在调用处 stringResource 取（跟随 UI 语言）；
// 其余用各语言自称，不随 UI 语言变。
private val languageOptions = listOf(
    LanguageOption("", "🌐", ""),
    LanguageOption("en", "🇺🇸", "English"),
    LanguageOption("zh", "🇨🇳", "简体中文"),
    LanguageOption("ja", "🇯🇵", "日本語"),
    LanguageOption("ko", "🇰🇷", "한국어"),
    LanguageOption("fr", "🇫🇷", "Français"),
    LanguageOption("de", "🇩🇪", "Deutsch"),
    LanguageOption("ru", "🇷🇺", "Русский"),
)

// ── 开关行规格（数据驱动的段渲染）────────────────────────────────────────────

private class SwitchSpec(
    val key: String,
    val default: Boolean,
    val headerRes: Int,
    val footerRes: Int,
    val titleRes: Int,
    val subtitleRes: Int? = null,
    val icon: ImageVector,
    val tint: Color,
)

@Composable
private fun rememberBoolPref(
    prefs: SharedPreferences,
    key: String,
    default: Boolean,
): MutableState<Boolean> =
    remember { mutableStateOf(prefs.getBoolean(key, default)) }

// ── 页面 ────────────────────────────────────────────────────────────────────

private val TilePurple = Color(0xFF5856D6)
private val TileBlue = Color(0xFF007AFF)
private val TileOrange = Color(0xFFFF9500)
private val TileGreen = Color(0xFF34C759)
private val TileTeal = Color(0xFF5AC8FA)

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onThemeChanged: (Int) -> Unit = {},
    onColorThemeClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }

    var themeMode by remember { mutableIntStateOf(prefs.getInt(KEY_THEME_MODE, 0)) }
    var returnKey by remember { mutableIntStateOf(prefs.getInt(KEY_RETURN_KEY_BEHAVIOR, 0)) }
    var chatInputLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_CHAT_INPUT, 0)) }
    var messageLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_MESSAGE, 0)) }
    var appBaseLevel by remember { mutableIntStateOf(prefs.getInt(KEY_FONT_APP_BASE, 0)) }
    var selectedLanguage by remember { mutableStateOf(prefs.getString(KEY_LANGUAGE, "") ?: "") }
    var selectedAppIcon by remember { mutableStateOf(AppIconRepository.current(context)) }

    val switches = listOf(
        SwitchSpec(KEY_KEEP_SCREEN_AWAKE, false,
            R.string.appearance_section_keep_awake, R.string.appearance_keep_awake_footer,
            R.string.appearance_keep_awake_title,
            icon = NovexIcons.ScreenLockPortrait, tint = TileGreen),
        SwitchSpec(KEY_CHROME_AUTO_FADE, false,
            R.string.appearance_section_chrome_autofade, R.string.appearance_chrome_autofade_footer,
            R.string.appearance_chrome_autofade_title,
            icon = NovexIcons.VisibilityOff, tint = TilePurple),
        SwitchSpec(KEY_TOOL_PREVIEW, true,
            R.string.appearance_section_tool_preview, R.string.appearance_tool_preview_footer,
            R.string.appearance_tool_preview_title,
            icon = NovexIcons.Visibility, tint = TileTeal),
        SwitchSpec(KEY_AUTO_EXPAND_THINKING, true,
            R.string.appearance_section_deep_thinking, R.string.appearance_auto_expand_thinking_footer,
            R.string.appearance_auto_expand_thinking_title,
            icon = NovexIcons.Psychology, tint = TilePurple),
        SwitchSpec(KEY_AUTO_FOCUS_AFTER_REPLY, true,
            R.string.appearance_section_auto_focus_after_reply, R.string.appearance_auto_focus_after_reply_footer,
            R.string.appearance_auto_focus_after_reply_title,
            icon = NovexIcons.Keyboard, tint = TileBlue),
        SwitchSpec(KEY_SHOW_CHAT_TITLE, true,
            R.string.appearance_section_chat_title, R.string.appearance_show_chat_title_footer,
            R.string.appearance_show_chat_title, subtitleRes = R.string.appearance_show_chat_title_subtitle,
            icon = NovexIcons.ChatBubbleOutline, tint = TileBlue),
        SwitchSpec(KEY_AUTO_GROUPING, true,
            R.string.appearance_section_grouping, R.string.appearance_auto_grouping_footer,
            R.string.appearance_auto_grouping, subtitleRes = R.string.appearance_auto_grouping_subtitle,
            icon = NovexIcons.Folder, tint = TileBlue),
    )
    val switchValues = switches.associate { spec ->
        spec.key to rememberBoolPref(prefs, spec.key, spec.default)
    }
    val contextMeterState = rememberBoolPref(prefs, KEY_SHOW_CONTEXT_METER, true)

    val fontsModified = chatInputLevel != 0 || messageLevel != 0 || appBaseLevel != 0

    SettingsScaffold(title = stringResource(R.string.appearance_title), onBack = onBack) {

        // 主题模式：三选一 + 色彩主题入口行。
        SettingsSection(
            header = stringResource(R.string.appearance_section_theme),
            footer = stringResource(R.string.appearance_theme_footer),
        ) {
            val themeChoices = listOf(
                Triple(stringResource(R.string.appearance_theme_system), NovexIcons.BrightnessAuto, TilePurple),
                Triple(stringResource(R.string.appearance_theme_light), NovexIcons.LightMode, TileOrange),
                Triple(stringResource(R.string.appearance_theme_dark), NovexIcons.DarkMode, TilePurple),
            )
            themeChoices.forEachIndexed { idx, (label, icon, tint) ->
                SettingsChoiceRow(
                    title = label,
                    selected = themeMode == idx,
                    onSelect = {
                        themeMode = idx
                        prefs.edit().putInt(KEY_THEME_MODE, idx).apply()
                        onThemeChanged(idx)
                    },
                    leading = { Icon(icon, contentDescription = null, tint = tint) },
                    showDivider = true,
                )
            }
            SettingsRow(
                icon = NovexIcons.Palette,
                iconColor = TileBlue,
                title = stringResource(R.string.appearance_color_theme_title),
                subtitle = stringResource(R.string.appearance_color_theme_subtitle),
                onClick = onColorThemeClick,
                showDivider = false,
            )
        }

        // 回车行为：0=换行（默认） 1=发送。Shift+Enter 始终换行。
        SettingsSection(
            header = stringResource(R.string.appearance_section_return_key),
            footer = stringResource(R.string.appearance_return_key_footer),
        ) {
            val returnChoices = listOf(
                Triple(stringResource(R.string.appearance_return_key_newline), 0, NovexIcons.KeyboardReturn to TilePurple),
                Triple(stringResource(R.string.appearance_return_key_send), 1, NovexIcons.Send to TileGreen),
            )
            returnChoices.forEachIndexed { idx, (label, value, iconTint) ->
                val (icon, tint) = iconTint
                SettingsChoiceRow(
                    title = label,
                    selected = returnKey == value,
                    onSelect = {
                        returnKey = value
                        prefs.edit().putInt(KEY_RETURN_KEY_BEHAVIOR, value).apply()
                    },
                    leading = { Icon(icon, contentDescription = null, tint = tint) },
                    showDivider = idx < returnChoices.lastIndex,
                )
            }
        }

        // 开关组（数据驱动段）。
        for (spec in switches) {
            val state = switchValues.getValue(spec.key)
            SettingsSection(
                header = stringResource(spec.headerRes),
                footer = stringResource(spec.footerRes),
            ) {
                SettingsSwitchRow(
                    icon = spec.icon,
                    iconColor = spec.tint,
                    title = stringResource(spec.titleRes),
                    subtitle = spec.subtitleRes?.let { stringResource(it) },
                    checked = state.value,
                    onCheckedChange = {
                        state.value = it
                        prefs.edit().putBoolean(spec.key, it).apply()
                    },
                    showDivider = false,
                )
            }
        }

        // 上下文用量。
        SettingsSection(header = "上下文用量", footer = "在输入框旁显示当前模型实际上下文窗口的使用进度。") {
            SettingsSwitchRow(
                icon = NovexIcons.DataUsage,
                iconColor = TileBlue,
                title = "显示上下文用量",
                subtitle = "点击圆圈可切换百分比与进度视图",
                checked = contextMeterState.value,
                onCheckedChange = {
                    contextMeterState.value = it
                    prefs.edit().putBoolean(KEY_SHOW_CONTEXT_METER, it).apply()
                },
                showDivider = false,
            )
        }

        // 字号。
        SettingsSection(
            header = stringResource(R.string.appearance_section_font_size),
            footer = stringResource(R.string.appearance_font_size_footer),
        ) {
            SettingsRow(
                icon = NovexIcons.FormatSize,
                iconColor = TileOrange,
                title = stringResource(R.string.appearance_font_scale_title),
                subtitle = stringResource(R.string.appearance_font_scale_subtitle),
                onClick = null,
                showChevron = false,
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_chat_input),
                level = chatInputLevel,
                onLevelChange = {
                    chatInputLevel = it
                    prefs.edit().putInt(KEY_FONT_CHAT_INPUT, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_message),
                level = messageLevel,
                onLevelChange = {
                    messageLevel = it
                    prefs.edit().putInt(KEY_FONT_MESSAGE, it).apply()
                },
                showDivider = true,
            )
            FontScaleSliderRow(
                label = stringResource(R.string.appearance_font_app_base),
                level = appBaseLevel,
                onLevelChange = {
                    appBaseLevel = it
                    prefs.edit().putInt(KEY_FONT_APP_BASE, it).apply()
                },
                showDivider = fontsModified,
            )
            if (fontsModified) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    MinisTextButton(onClick = {
                        chatInputLevel = 0; messageLevel = 0; appBaseLevel = 0
                        prefs.edit()
                            .putInt(KEY_FONT_CHAT_INPUT, 0)
                            .putInt(KEY_FONT_MESSAGE, 0)
                            .putInt(KEY_FONT_APP_BASE, 0)
                            .apply()
                    }) {
                        Text(stringResource(R.string.appearance_font_reset), color = NovexColors.Danger)
                    }
                }
            }
        }

        // 应用图标三格选择：点选翻转 activity-alias，图标带选中角标。
        SettingsSection(
            header = stringResource(R.string.appearance_section_app_icon),
            footer = stringResource(R.string.appearance_app_icon_footer),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (option in appIconOptions) {
                    AppIconTile(
                        option = option,
                        selected = selectedAppIcon == option.variant,
                        onSelect = {
                            if (selectedAppIcon != option.variant) {
                                selectedAppIcon = option.variant
                                AppIconRepository.apply(context, option.variant)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 语言。
        SettingsSection(
            header = stringResource(R.string.appearance_section_language),
            footer = stringResource(R.string.appearance_language_footer),
        ) {
            languageOptions.forEachIndexed { idx, lang ->
                SettingsChoiceRow(
                    title = if (lang.code.isEmpty()) stringResource(R.string.appearance_theme_system) else lang.selfName,
                    selected = selectedLanguage == lang.code,
                    onSelect = {
                        selectedLanguage = lang.code
                        prefs.edit().putString(KEY_LANGUAGE, lang.code).apply()
                        applyLanguage(context, lang.code)
                    },
                    leading = { Text(lang.flag, fontSize = 22.sp, modifier = Modifier.width(30.dp)) },
                    showDivider = idx < languageOptions.lastIndex,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── 私有部件 ─────────────────────────────────────────────────────────────────

private fun applyLanguage(context: Context, code: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
    } else {
        // 老版本没有 LocaleManager：LocaleWrap 在下次 attachBaseContext 读
        // SharedPreferences，这里 recreate 让新配置立即生效。
        (context as? Activity)?.recreate()
    }
}

private data class AppIconOption(
    val variant: AppIconRepository.Variant,
    val titleRes: Int,
    val mipmapRes: Int,
)

private val appIconOptions = listOf(
    AppIconOption(AppIconRepository.Variant.Auto, R.string.appearance_app_icon_auto, R.mipmap.ic_launcher),
    AppIconOption(AppIconRepository.Variant.ClassicLight, R.string.appearance_app_icon_light, R.mipmap.ic_launcher_classic_light),
    AppIconOption(AppIconRepository.Variant.ClassicDark, R.string.appearance_app_icon_dark, R.mipmap.ic_launcher_classic_dark),
)

@Composable
private fun AppIconTile(
    option: AppIconOption,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // painterResource 解不了 adaptive-icon XML，先栅化成 Bitmap。
    val painter = remember(option.mipmapRes) {
        val drawable = ResourcesCompat.getDrawable(context.resources, option.mipmapRes, context.theme)
        val bmp = drawable?.toBitmap(width = 192, height = 192)
            ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        BitmapPainter(bmp.asImageBitmap())
    }

    Column(
        modifier.clickable(onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = if (selected) 2.dp else 0.5.dp,
                    color = if (selected) NovexColors.Primary else NovexColors.Divider,
                    shape = RoundedCornerShape(18.dp),
                ),
        ) {
            Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NovexIcons.CheckCircle, contentDescription = null, tint = NovexColors.Primary, modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(
            stringResource(option.titleRes),
            style = NovexType.Metadata,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) NovexColors.Primary else NovexColors.Text,
        )
    }
}

@Composable
private fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val startIndex = FONT_LEVELS.indexOf(level).coerceIn(0, FONT_LEVELS.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(startIndex.toFloat()) }
    val currentLabel = FONT_LEVEL_LABELS.getOrElse(sliderPos.roundToInt()) { "Default" }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = NovexType.ItemTitle)
            Text(currentLabel, style = NovexType.Metadata, color = NovexColors.SecondaryText)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("A", fontSize = 12.sp, color = NovexColors.SecondaryText)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val snapped = sliderPos.roundToInt().coerceIn(0, FONT_LEVELS.lastIndex)
                    sliderPos = snapped.toFloat()
                    onLevelChange(FONT_LEVELS[snapped])
                },
                valueRange = 0f..(FONT_LEVELS.lastIndex).toFloat(),
                steps = FONT_LEVELS.size - 2,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = NovexColors.SecondaryText)
        }
    }
    if (showDivider) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(NovexDimensions.Hairline)
                .background(NovexColors.Divider),
        )
    }
}
