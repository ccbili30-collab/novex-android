package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R

@Composable
internal fun NovexDetailScaffold(
    title: String,
    onBack: () -> Unit,
    pageTone: NovexPageTone = NovexPageTone.DISPLAY,
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pageColor = pageTone.color
    Scaffold(
        containerColor = pageColor,
        topBar = {
            NovexPageTopBar(
                title = title,
                onBack = onBack,
                actions = actions,
                backgroundColor = pageColor,
            )
        },
        bottomBar = { Box(Modifier.navigationBarsPadding()) { bottomBar() } },
    ) { padding ->
        val body = Modifier
            .fillMaxSize()
            .padding(padding)
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        Column(modifier = body, content = content)
    }
}

/** One top bar implementation for detail, editor, preview and settings pages. */
@Composable
internal fun NovexPageTopBar(
    title: String,
    onBack: (() -> Unit)?,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    backgroundColor: Color = NovexColors.Canvas,
) {
    NovexTopBarSurface(
        title = { Text(title, style = NovexType.SectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigation = {
            when {
                navigation != null -> navigation()
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_phosphor_arrow_left), "返回", Modifier.size(NovexDimensions.HeaderActionIconSize))
                }
            }
        },
        actions = { actions() },
        backgroundColor = backgroundColor,
    )
}

/** Slot-based header shared by old call signatures and all Novex detail pages. */
@Composable
internal fun NovexTopBarSurface(
    title: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = NovexColors.Canvas,
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .windowInsetsPadding(windowInsets)
            .height(NovexDimensions.TopBarHeight),
    ) {
        CompositionLocalProvider(LocalContentColor provides NovexColors.Text, LocalTextStyle provides NovexType.SectionTitle) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box { navigation() }
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                    Box { title() }
                },
            ) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val leading = measurables[0].measure(loose)
                val trailing = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - leading.width).coerceAtLeast(0)))
                val heading = measurables[2].measure(loose.copy(maxWidth = (constraints.maxWidth - leading.width - trailing.width).coerceAtLeast(0)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    leading.placeRelative(0, (constraints.maxHeight - leading.height) / 2)
                    trailing.placeRelative(constraints.maxWidth - trailing.width, (constraints.maxHeight - trailing.height) / 2)
                    heading.placeRelative(
                        novexHeaderTitleOffset(constraints.maxWidth, leading.width, trailing.width, heading.width),
                        (constraints.maxHeight - heading.height) / 2,
                    )
                }
            }
        }
    }
}

@Composable
internal fun NovexTopAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    label: String? = null,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(width = 48.dp, height = 52.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painterResource(icon),
                contentDescription = contentDescription,
                tint = NovexColors.Text,
                modifier = Modifier.size(if (label == null) 21.dp else 18.dp),
            )
            label?.let {
                Text(
                    it,
                    color = NovexColors.SecondaryText,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun NovexTopTextAction(
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        Text(
            text = label,
            color = if (danger) NovexColors.Danger else NovexColors.Primary,
            style = NovexType.Body,
            fontWeight = FontWeight.Medium,
        )
    }
}
