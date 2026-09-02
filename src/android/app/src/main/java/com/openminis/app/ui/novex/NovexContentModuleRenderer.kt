package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
