package com.openminis.app.ui.novex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleTextCodec
import com.openminis.app.data.character.ContentModuleType

internal data class NovexContentModulePresentation(
    val id: String,
    val type: ContentModuleType,
    val title: String,
    val body: String,
    val summary: String,
) {
    val hasText: Boolean
        get() = body.isNotBlank()
}

internal fun ContentModuleEntity.toNovexPresentation(
    maxSummaryCharacters: Int = 48,
): NovexContentModulePresentation {
    val body = ContentModuleTextCodec.decode(contentJson)
    return NovexContentModulePresentation(
        id = id,
        type = type,
        title = name,
        body = body,
        summary = novexModuleSummary(body, maxSummaryCharacters),
    )
}

internal fun novexModuleSummary(text: String, maxCharacters: Int = 48): String {
    val normalized = text.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" ")
    if (normalized.isBlank()) return "尚未填写内容"
    if (normalized.length <= maxCharacters) return normalized
    return normalized.take(maxCharacters).trimEnd('。', '，', '；', '、', ' ') + "…"
}

/** Compact shared rendering used by editors and, later, the full display renderer. */
@Composable
internal fun NovexContentModuleSummary(
    presentation: NovexContentModulePresentation,
    imageModel: Any?,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = "${presentation.title}代表图",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                presentation.title,
                color = NovexColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                presentation.summary,
                color = NovexColors.SecondaryText,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Full-width display block shared by saved pages and draft previews. */
@Composable
internal fun NovexContentModuleBlock(
    presentation: NovexContentModulePresentation,
    imageModel: Any?,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactive = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Column(interactive.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                presentation.title,
                color = NovexColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onClick != null) {
                androidx.compose.material3.Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_phosphor_caret_right),
                    contentDescription = "打开${presentation.title}",
                    tint = NovexColors.SecondaryText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (imageModel != null) {
            AsyncImage(
                model = imageModel,
                contentDescription = "${presentation.title}图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(156.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            if (presentation.hasText) presentation.body else "尚未填写内容",
            color = if (presentation.hasText) NovexColors.Text else NovexColors.SecondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
