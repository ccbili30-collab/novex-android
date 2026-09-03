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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
    actions: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = NovexColors.Background,
        topBar = {
            NovexPageTopBar(title = title, onBack = onBack, actions = actions)
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
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .background(NovexColors.Background)
            .statusBarsPadding()
            .height(NovexDimensions.TopBarHeight),
    ) {
        when {
            navigation != null -> Box(Modifier.align(Alignment.CenterStart)) { navigation() }
            onBack != null -> IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    painterResource(R.drawable.ic_phosphor_arrow_left),
                    contentDescription = "返回",
                    tint = NovexColors.Text,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            title,
            color = NovexColors.Text,
            style = NovexType.SectionTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 104.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
        ) { actions() }
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
