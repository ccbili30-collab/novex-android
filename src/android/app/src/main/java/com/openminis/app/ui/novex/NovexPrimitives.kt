package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

@Composable
internal fun NovexRootHeader(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(start = if (leading == null) 20.dp else 8.dp, end = 8.dp),
    ) {
        leading?.invoke()
        Text(
            text = title,
            color = NovexColors.Text,
            style = if (title == "Novex") NovexType.Brand else NovexType.PageTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
internal fun NovexIconAction(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(NovexDimensions.HeaderActionSize)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = NovexColors.Text,
            modifier = Modifier.size(NovexDimensions.HeaderActionIconSize),
        )
    }
}

@Composable
internal fun NovexSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 5.dp)
            .height(42.dp)
            .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
            .background(NovexColors.SurfaceMuted)
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_phosphor_search),
            contentDescription = null,
            tint = NovexColors.TertiaryText,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(NovexColors.Primary),
            textStyle = NovexType.Body.copy(color = NovexColors.Text),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(placeholder, color = NovexColors.TertiaryText, style = NovexType.Body)
                    }
                    inner()
                }
            },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = placeholder },
        )
        if (value.isNotEmpty() && onClear != null) {
            androidx.compose.material3.IconButton(onClick = onClear) {
                Icon(NovexIcons.Close, contentDescription = "清除搜索", tint = NovexColors.SecondaryText)
            }
        }
    }
}

@Composable
internal fun <T> NovexFilterTabs(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = NovexDimensions.PageHorizontal),
    ) {
        items.forEach { item ->
            val active = item == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onSelect(item) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    label(item),
                    color = if (active) NovexColors.Text else NovexColors.SecondaryText,
                    fontSize = com.openminis.app.ui.novex.novexScaledSp(15),
                    lineHeight = com.openminis.app.ui.novex.novexScaledSp(20),
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .width(22.dp)
                        .height(2.dp)
                        .background(if (active) NovexColors.Primary else androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
}

@Composable
internal fun NovexSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = NovexColors.Text,
        style = NovexType.SectionTitle,
        modifier = modifier.padding(top = 15.dp, bottom = 8.dp),
    )
}

@Composable
internal fun NovexTextActionRow(
    label: String,
    @DrawableRes icon: Int = R.drawable.ic_phosphor_plus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = NovexColors.Text,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = NovexColors.Text, style = NovexType.Body, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun NovexContentSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = NovexDimensions.SectionGap)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = NovexDimensions.PageHorizontal),
        ) {
            Text(title, color = NovexColors.Text, style = NovexType.SectionTitle, modifier = Modifier.weight(1f))
            subtitle?.let {
                Text(
                    it,
                    color = NovexColors.SecondaryText,
                    style = NovexType.Metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        content()
    }
}

@Composable
internal fun NovexSummaryRow(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val interaction = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = interaction
            .fillMaxWidth()
            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 11.dp),
    ) {
        Text(
            title,
            color = NovexColors.Text,
            style = NovexType.Body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp),
        )
        Text(
            summary,
            color = NovexColors.SecondaryText,
            style = NovexType.Metadata,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            Icon(
                painterResource(R.drawable.ic_phosphor_caret_right),
                contentDescription = "打开$title",
                tint = NovexColors.TertiaryText,
                modifier = Modifier.padding(start = 8.dp).size(17.dp),
            )
        }
    }
}
