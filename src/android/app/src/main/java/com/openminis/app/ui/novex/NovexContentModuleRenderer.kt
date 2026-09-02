package com.openminis.app.ui.novex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.toPlainText

internal enum class NovexContentModuleLayout {
    MAP,
    TIMELINE,
    COLLECTION,
    ARTICLE,
}

internal fun ContentModuleType.novexContentLayout(): NovexContentModuleLayout = when (this) {
    ContentModuleType.MAP -> NovexContentModuleLayout.MAP
    ContentModuleType.TIMELINE,
    ContentModuleType.ERA_EVENT,
    ContentModuleType.WORLD_EXPERIENCE,
    -> NovexContentModuleLayout.TIMELINE
    ContentModuleType.REGION,
    ContentModuleType.FACTION,
    ContentModuleType.RACE,
    ContentModuleType.QUOTES,
    ContentModuleType.ATTRIBUTE_PANEL,
    ContentModuleType.EQUIPMENT,
    ContentModuleType.TALENT_SKILL,
    ContentModuleType.APPEARANCE_PERSONALITY,
    ContentModuleType.INTEREST,
    -> NovexContentModuleLayout.COLLECTION
    ContentModuleType.CUSTOM -> NovexContentModuleLayout.ARTICLE
}

internal data class NovexContentModulePresentation(
    val id: String,
    val type: ContentModuleType,
    val title: String,
    val document: ContentModuleDocument,
    val body: String,
    val summary: String,
) {
    val hasText: Boolean
        get() = body.isNotBlank()
}

internal fun ContentModuleEntity.toNovexPresentation(
    maxSummaryCharacters: Int = 48,
): NovexContentModulePresentation {
    val document = ContentModuleDocumentCodec.decode(type, contentJson)
    val body = document.toPlainText()
    return NovexContentModulePresentation(
        id = id,
        type = type,
        title = name,
        document = document,
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
    itemImageModels: Map<String, Any?> = emptyMap(),
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
        when (presentation.type.novexContentLayout()) {
            NovexContentModuleLayout.MAP -> MapModuleBody(presentation, imageModel)
            NovexContentModuleLayout.TIMELINE -> TimelineModuleBody(presentation, imageModel)
            NovexContentModuleLayout.COLLECTION -> CollectionModuleBody(
                presentation,
                imageModel,
                itemImageModels,
            )
            NovexContentModuleLayout.ARTICLE -> ArticleModuleBody(presentation, imageModel)
        }
    }
}

@Composable
private fun MapModuleBody(presentation: NovexContentModulePresentation, imageModel: Any?) {
    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
            contentDescription = "${presentation.title}地图",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(176.dp).clip(RoundedCornerShape(8.dp)),
        )
    } else {
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp).height(112.dp)
                .clip(RoundedCornerShape(8.dp)).background(NovexColors.PrimarySoft),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text("尚未添加地图图片", color = NovexColors.SecondaryText, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
        }
    }
    ModuleText(presentation, maxLines = 4)
}

@Composable
private fun TimelineModuleBody(presentation: NovexContentModulePresentation, imageModel: Any?) {
    val timeline = presentation.document as? ContentModuleDocument.Timeline
    val nodes = timeline?.nodes.orEmpty()
    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
            contentDescription = "${presentation.title}图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(132.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
    if (nodes.isEmpty()) {
        ModuleText(presentation, maxLines = 5)
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        nodes.take(6).forEachIndexed { index, node ->
            TimelineNodeRow(
                time = node.time,
                title = node.title,
                description = node.description,
                drawTail = index < nodes.lastIndex && index < 5,
            )
        }
    }
}

@Composable
private fun TimelineNodeRow(
    time: String,
    title: String,
    description: String,
    drawTail: Boolean,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(16.dp)) {
            Spacer(Modifier.height(5.dp))
            Spacer(Modifier.size(8.dp).clip(CircleShape).background(NovexColors.Primary))
            if (drawTail) Spacer(Modifier.width(2.dp).height(52.dp).background(NovexColors.Divider))
        }
        Column(Modifier.weight(1f).padding(start = 10.dp, bottom = if (drawTail) 10.dp else 0.dp)) {
            val heading = listOf(time, title).filter(String::isNotBlank).joinToString(" · ")
            if (heading.isNotBlank()) {
                Text(
                    heading,
                    color = NovexColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = NovexColors.SecondaryText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (heading.isBlank()) 0.dp else 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CollectionModuleBody(
    presentation: NovexContentModulePresentation,
    imageModel: Any?,
    itemImageModels: Map<String, Any?>,
) {
    val collection = presentation.document as? ContentModuleDocument.Collection
    val items = collection?.items.orEmpty()
    if (items.isEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = "${presentation.title}代表图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            ModuleText(presentation, maxLines = 5, modifier = Modifier.weight(1f).padding(top = 0.dp))
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        items.take(6).forEachIndexed { index, item ->
            val itemImage = item.visualKey?.let(itemImageModels::get)
                ?: imageModel.takeIf { index == 0 }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (itemImage != null) {
                    AsyncImage(
                        model = itemImage,
                        contentDescription = "${item.name.ifBlank { presentation.title }}代表图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (item.name.isNotBlank()) {
                        Text(
                            item.name,
                            color = NovexColors.Text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.summary.isNotBlank()) {
                        Text(
                            item.summary,
                            color = NovexColors.SecondaryText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (item.name.isBlank()) 0.dp else 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleModuleBody(presentation: NovexContentModulePresentation, imageModel: Any?) {
    if (imageModel != null) {
        AsyncImage(
            model = imageModel,
            contentDescription = "${presentation.title}图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(148.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
    ModuleText(presentation, maxLines = 7)
}

@Composable
private fun ModuleText(
    presentation: NovexContentModulePresentation,
    maxLines: Int,
    modifier: Modifier = Modifier.padding(top = 8.dp),
) {
    Text(
        if (presentation.hasText) presentation.body else "尚未填写内容",
        color = if (presentation.hasText) NovexColors.Text else NovexColors.SecondaryText,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
