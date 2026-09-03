package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared settings chrome used by both new Novex pages and legacy settings adapters. */
@Composable
internal fun NovexSettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit = {},
    navigation: (@Composable () -> Unit)? = null,
    floatingActionButton: (@Composable () -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = NovexColors.Background,
        topBar = {
            NovexPageTopBar(
                title = title,
                onBack = onBack,
                navigation = navigation,
                actions = actions,
                backgroundColor = NovexPageTone.SETTINGS.color,
            )
        },
        floatingActionButton = { floatingActionButton?.invoke() },
    ) { padding ->
        val body = Modifier
            .fillMaxSize()
            .padding(padding)
            .imePadding()
            .navigationBarsPadding()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        Column(modifier = body, content = content)
    }
}

@Composable
internal fun NovexSettingsSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sectionShape = RoundedCornerShape(NovexDimensions.SectionRadius)
    Column(modifier.fillMaxWidth().padding(top = 22.dp)) {
        title?.let {
            Text(
                text = it,
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = NovexDimensions.PageHorizontal, vertical = 7.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovexDimensions.PageHorizontal)
                .clip(sectionShape)
                .background(NovexColors.Surface)
                .border(NovexDimensions.Hairline, NovexColors.Divider, sectionShape),
            content = content,
        )
        footer?.let {
            Text(
                text = it,
                color = NovexColors.TertiaryText,
                style = NovexType.Metadata,
                modifier = Modifier.padding(horizontal = NovexDimensions.PageHorizontal, vertical = 8.dp),
            )
        }
    }
}

/** Shared check/no-check control used by every binary setting. */
@Composable
internal fun NovexCheckIndicator(
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(NovexDimensions.SmallRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(28.dp)
            .clip(shape)
            .background(
                if (checked) NovexColors.Primary else NovexColors.SurfaceMuted,
                shape,
            )
            .border(
                NovexDimensions.Hairline,
                if (checked) NovexColors.Primary else NovexColors.Divider,
                shape,
            ),
    ) {
        if (checked) {
            Icon(
                painter = painterResource(com.openminis.app.R.drawable.ic_phosphor_check),
                contentDescription = null,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Standalone touch target for binary settings outside a settings row. */
@Composable
internal fun NovexCheckToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(NovexDimensions.MinimumTouch)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onCheckedChange?.invoke(it) },
            ),
    ) {
        NovexCheckIndicator(checked = checked, enabled = enabled)
    }
}

@Composable
internal fun NovexSettingsVectorToggleRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = NovexColors.Primary,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    NovexSettingsRowFrame(
        icon = icon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(21.dp),
                )
            }
        },
        title = title,
        subtitle = subtitle,
        showChevron = false,
        showDivider = showDivider,
        trailing = { NovexCheckIndicator(checked = checked, enabled = enabled) },
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        onClick = null,
    )
}

@Composable
internal fun NovexSettingsRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    onClick: () -> Unit,
) {
    NovexSettingsRowFrame(
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = NovexColors.Primary,
                modifier = Modifier.size(21.dp),
            )
        },
        title = title,
        subtitle = subtitle,
        showDivider = showDivider,
        onClick = onClick,
    )
}

@Composable
internal fun NovexSettingsVectorRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = NovexColors.Primary,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    titleColor: Color = NovexColors.Text,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = 56.dp,
) {
    NovexSettingsRowFrame(
        icon = icon?.let { vector ->
            {
                Icon(
                    imageVector = vector,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(21.dp),
                )
            }
        },
        title = title,
        subtitle = subtitle,
        showChevron = showChevron,
        showDivider = showDivider,
        titleColor = titleColor,
        trailing = trailing,
        minHeight = minHeight,
        onClick = onClick,
    )
}

@Composable
internal fun NovexSettingsCustomRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    showDivider: Boolean = true,
    titleColor: Color = NovexColors.Text,
    minHeight: Dp = 56.dp,
) {
    NovexSettingsRowFrame(
        icon = leading,
        title = title,
        subtitle = subtitle,
        showChevron = showChevron,
        showDivider = showDivider,
        titleColor = titleColor,
        trailing = trailing,
        minHeight = minHeight,
        onClick = onClick,
    )
}

@Composable
private fun NovexSettingsRowFrame(
    icon: (@Composable () -> Unit)?,
    title: String,
    subtitle: String?,
    showChevron: Boolean = true,
    showDivider: Boolean,
    titleColor: Color = NovexColors.Text,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = 60.dp,
    onClick: (() -> Unit)?,
    checked: Boolean? = null,
    enabled: Boolean = true,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(
                    when {
                        checked != null && onCheckedChange != null -> Modifier.toggleable(
                            value = checked,
                            enabled = enabled,
                            role = Role.Checkbox,
                            onValueChange = onCheckedChange,
                        )
                        onClick != null -> Modifier.clickable(enabled = enabled, onClick = onClick)
                        else -> Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = titleColor,
                    style = NovexType.Body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        color = NovexColors.SecondaryText,
                        style = NovexType.Metadata,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.let {
                Spacer(Modifier.width(8.dp))
                it()
            }
            if (showChevron) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    painterResource(com.openminis.app.R.drawable.ic_phosphor_caret_right),
                    contentDescription = null,
                    tint = NovexColors.TertiaryText,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = NovexColors.Divider,
                modifier = Modifier.padding(start = if (icon == null) 14.dp else 48.dp),
            )
        }
    }
}
