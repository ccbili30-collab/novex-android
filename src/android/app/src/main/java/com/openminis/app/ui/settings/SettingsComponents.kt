package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexSettingsCustomRow
import com.openminis.app.ui.novex.NovexSettingsScaffold
import com.openminis.app.ui.novex.NovexSettingsSection
import com.openminis.app.ui.novex.NovexSettingsVectorRow
import com.openminis.app.ui.novex.NovexSettingsVectorToggleRow

/**
 * Compatibility adapter for existing settings pages.
 *
 * The public names remain stable while all chrome, sections and rows are
 * rendered by the Novex visual modules. New settings code should use the
 * Novex modules directly.
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    navigation: @Composable (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") centerTitle: Boolean = false,
    floatingActionButton: @Composable (() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    NovexSettingsScaffold(
        title = title,
        onBack = onBack,
        actions = { actions?.invoke() },
        navigation = navigation,
        floatingActionButton = floatingActionButton,
        scrollable = scrollable,
        content = content,
    )
}

@Composable
fun SettingsSection(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NovexSettingsSection(
        title = header,
        footer = footer,
        modifier = modifier,
        content = content,
    )
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = 56.dp,
) {
    NovexSettingsVectorRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showChevron = showChevron,
        showDivider = showDivider,
        titleColor = titleColor,
        trailing = trailing,
        minHeight = minHeight,
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    NovexSettingsVectorToggleRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        showDivider = showDivider,
    )
}

@Composable
fun SettingsValueRow(
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconColor = iconColor,
        onClick = onClick,
        showChevron = onClick != null,
        showDivider = showDivider,
        trailing = {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
fun SettingsChoiceRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true,
) {
    NovexSettingsCustomRow(
        title = title,
        leading = leading,
        trailing = if (selected) {
            {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = NovexColors.Primary,
                )
            }
        } else {
            null
        },
        onClick = onSelect,
        showChevron = false,
        showDivider = showDivider,
    )
}

@Composable
fun SettingsCardBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        content = content,
    )
}
