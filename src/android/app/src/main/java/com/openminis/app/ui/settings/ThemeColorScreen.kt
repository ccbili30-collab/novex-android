package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import com.openminis.app.ui.novex.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.theme.AppThemeColorPreferences
import com.openminis.app.ui.theme.AppThemeColors
import com.openminis.app.ui.theme.MinisThemePreview
import com.openminis.app.ui.theme.ThemeColorField
import com.openminis.app.ui.theme.ThemeColorPreset
import com.openminis.app.ui.theme.ThemeColorPresets
import com.openminis.app.ui.theme.ThemeVariantColors
import com.openminis.app.ui.theme.ThemeVariantMode
import com.openminis.app.ui.theme.formatOpaqueThemeColor
import com.openminis.app.ui.theme.parseOpaqueThemeColor
import com.openminis.app.ui.theme.validateThemeColors

private data class ColorPickerTarget(
    val field: ThemeColorField,
    val initialColor: Int,
)

@Composable
fun ThemeColorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit = onBack,
) {
    val context = LocalContext.current
    val prefs = remember { getAppearancePrefs(context) }
    val initialColors = remember { AppThemeColorPreferences.read(prefs) }
    var draft by remember { mutableStateOf(initialColors) }
    var previewModeName by rememberSaveable { mutableStateOf(ThemeVariantMode.Light.name) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<ColorPickerTarget?>(null) }
    var saveFailed by remember { mutableStateOf(false) }

    val previewMode = runCatching { ThemeVariantMode.valueOf(previewModeName) }
        .getOrDefault(ThemeVariantMode.Light)
    val activeColors = draft.variant(previewMode)
    val issues = validateThemeColors(draft)

    BackHandler(onBack = onBack)

    MinisThemePreview(
        darkTheme = previewMode == ThemeVariantMode.Dark,
        themeColors = draft,
    ) {
        SettingsScaffold(
            title = stringResource(R.string.theme_colors_title),
            onBack = null,
            centerTitle = true,
            navigation = {
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            actions = {
                TextButton(
                    enabled = issues.isEmpty(),
                    onClick = {
                        saveFailed = !AppThemeColorPreferences.write(prefs, draft)
                        if (!saveFailed) onSaved()
                    },
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
        ) {
            SettingsSection(
                header = stringResource(R.string.theme_colors_preview_header),
                footer = stringResource(R.string.theme_colors_preview_footer),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ThemeModeChip(
                            label = stringResource(R.string.theme_colors_mode_light),
                            selected = previewMode == ThemeVariantMode.Light,
                            onClick = { previewModeName = ThemeVariantMode.Light.name },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeModeChip(
                            label = stringResource(R.string.theme_colors_mode_dark),
                            selected = previewMode == ThemeVariantMode.Dark,
                            onClick = { previewModeName = ThemeVariantMode.Dark.name },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.theme_colors_preview_body),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.theme_colors_preview_secondary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = {}) {
                                Text(stringResource(R.string.theme_colors_preview_primary))
                            }
                        }
                    }
                }
            }

            SettingsSection(
                header = stringResource(R.string.theme_colors_presets_header),
                footer = stringResource(R.string.theme_colors_presets_footer),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ThemeColorPresets.all.chunked(3).forEach { rowPresets ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rowPresets.forEach { preset ->
                                ThemePresetCard(
                                    preset = preset,
                                    selected = draft.presetId == preset.id,
                                    onClick = {
                                        draft = preset.colors
                                        saveFailed = false
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(3 - rowPresets.size) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (draft.presetId == ThemeColorPresets.CUSTOM_ID) {
                        Text(
                            text = stringResource(R.string.theme_colors_preset_custom),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }

            SettingsSection(
                header = stringResource(R.string.theme_colors_accent_header),
                footer = stringResource(R.string.theme_colors_accent_footer),
            ) {
                ColorValueRow(
                    title = stringResource(R.string.theme_colors_accent_title),
                    color = activeColors.accent,
                    onClick = {
                        pickerTarget = ColorPickerTarget(ThemeColorField.Accent, activeColors.accent)
                    },
                    showDivider = false,
                )
            }

            SettingsSection(header = stringResource(R.string.theme_colors_advanced_header)) {
                SettingsRow(
                    icon = com.openminis.app.ui.novex.NovexIcons.Palette,
                    title = stringResource(
                        if (advancedExpanded) {
                            R.string.theme_colors_advanced_hide
                        } else {
                            R.string.theme_colors_advanced_show
                        }
                    ),
                    onClick = { advancedExpanded = !advancedExpanded },
                    showChevron = false,
                    showDivider = advancedExpanded,
                    trailing = {
                        Icon(
                            imageVector = if (advancedExpanded) {
                                com.openminis.app.ui.novex.NovexIcons.KeyboardArrowUp
                            } else {
                                com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown
                            },
                            contentDescription = null,
                        )
                    },
                )
                if (advancedExpanded) {
                    ColorValueRow(
                        title = stringResource(R.string.theme_colors_background_title),
                        color = activeColors.background,
                        onClick = {
                            pickerTarget = ColorPickerTarget(
                                ThemeColorField.Background,
                                activeColors.background,
                            )
                        },
                        showDivider = true,
                    )
                    ColorValueRow(
                        title = stringResource(R.string.theme_colors_foreground_title),
                        color = activeColors.foreground,
                        onClick = {
                            pickerTarget = ColorPickerTarget(
                                ThemeColorField.Foreground,
                                activeColors.foreground,
                            )
                        },
                        showDivider = false,
                    )
                }
            }

            SettingsSection {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (issues.isEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                com.openminis.app.ui.novex.NovexIcons.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.theme_colors_contrast_ok),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        issues.forEach { issue ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(
                                    com.openminis.app.ui.novex.NovexIcons.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = stringResource(
                                        if (issue.field == ThemeColorField.Foreground) {
                                            R.string.theme_colors_contrast_text_error
                                        } else {
                                            R.string.theme_colors_contrast_accent_error
                                        },
                                        stringResource(
                                            if (issue.mode == ThemeVariantMode.Light) {
                                                R.string.appearance_theme_light
                                            } else {
                                                R.string.appearance_theme_dark
                                            }
                                        ),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    if (saveFailed) {
                        Text(
                            stringResource(R.string.theme_colors_save_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        onClick = {
                            draft = ThemeColorPresets.default.colors
                            saveFailed = false
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(stringResource(R.string.theme_colors_reset))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        pickerTarget?.let { target ->
            ColorPickerDialog(
                target = target,
                onDismiss = { pickerTarget = null },
                onConfirm = { color ->
                    val changed = when (target.field) {
                        ThemeColorField.Accent -> activeColors.copy(accent = color)
                        ThemeColorField.Background -> activeColors.copy(background = color)
                        ThemeColorField.Foreground -> activeColors.copy(foreground = color)
                    }
                    draft = draft.update(previewMode, changed)
                    saveFailed = false
                    pickerTarget = null
                },
            )
        }
    }
}

@Composable
private fun ThemeModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        leadingIcon = if (selected) {
            { Icon(com.openminis.app.ui.novex.NovexIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
private fun ThemePresetCard(
    preset: ThemeColorPreset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ThemeSwatch(preset.colors.light.background, size = 24)
            ThemeSwatch(preset.colors.light.accent, size = 24)
            ThemeSwatch(preset.colors.dark.accent, size = 24)
        }
        Text(
            text = stringResource(presetNameResource(preset.id)),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun ColorValueRow(
    title: String,
    color: Int,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    SettingsRow(
        title = title,
        onClick = onClick,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ThemeSwatch(color, size = 30)
                Text(
                    formatOpaqueThemeColor(color),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun ThemeSwatch(color: Int, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(Color(color), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun ColorPickerDialog(
    target: ColorPickerTarget,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember(target) { mutableStateOf(formatOpaqueThemeColor(target.initialColor)) }
    val parsed = parseOpaqueThemeColor(input)
    val fieldName = stringResource(
        when (target.field) {
            ThemeColorField.Accent -> R.string.theme_colors_accent_title
            ThemeColorField.Background -> R.string.theme_colors_background_title
            ThemeColorField.Foreground -> R.string.theme_colors_foreground_title
        }
    )
    val commonColors = listOf(
        0xFF528AD2.toInt(),
        0xFF2563EB.toInt(),
        0xFF7C3AED.toInt(),
        0xFFBE185D.toInt(),
        0xFF047857.toInt(),
        0xFFB45309.toInt(),
        0xFF1A1C1F.toInt(),
        0xFFFFFFFF.toInt(),
        0xFF000000.toInt(),
        0xFFF2F2F7.toInt(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_colors_picker_title, fieldName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                parsed?.let {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(Color(it), RoundedCornerShape(14.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(14.dp),
                            ),
                    )
                }
                commonColors.chunked(5).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        row.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(color), CircleShape)
                                    .border(
                                        width = if (parsed == color) 3.dp else 1.dp,
                                        color = if (parsed == color) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { input = formatOpaqueThemeColor(color) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.take(7) },
                    label = { Text(stringResource(R.string.theme_colors_picker_hex)) },
                    isError = parsed == null,
                    supportingText = if (parsed == null) {
                        { Text(stringResource(R.string.theme_colors_picker_invalid)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = { parsed?.let(onConfirm) },
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private fun presetNameResource(id: String): Int = when (id) {
    "novex" -> R.string.theme_colors_preset_novex
    "blue" -> R.string.theme_colors_preset_blue
    "purple" -> R.string.theme_colors_preset_purple
    "pink" -> R.string.theme_colors_preset_pink
    "green" -> R.string.theme_colors_preset_green
    "amber" -> R.string.theme_colors_preset_amber
    "monochrome" -> R.string.theme_colors_preset_monochrome
    else -> R.string.theme_colors_preset_custom
}
