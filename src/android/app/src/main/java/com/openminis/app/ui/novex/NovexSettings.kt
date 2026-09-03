package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
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
    Column(modifier.fillMaxWidth().padding(top = 22.dp)) {
        title?.let {
            Text(
                text = it,
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 7.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NovexColors.Surface),
            content = content,
        )
        footer?.let {
            Text(
                text = it,
                color = NovexColors.TertiaryText,
                style = NovexType.Metadata,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
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
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
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
