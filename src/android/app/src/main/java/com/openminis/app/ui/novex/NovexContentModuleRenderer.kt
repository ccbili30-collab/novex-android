package com.openminis.app.ui.novex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
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
    WORLD_GALLERY,
    CHARACTER_QUOTES,
    CHARACTER_FACTS,
    CHARACTER_COLLECTION,
    GAME_COLLECTION,
    ARTICLE,
}

internal fun ContentModuleType.novexContentLayout(): NovexContentModuleLayout = when (this) {
    ContentModuleType.MAP -> NovexContentModuleLayout.MAP
    ContentModuleType.TIMELINE,
    ContentModuleType.ERA_EVENT,
    ContentModuleType.WORLD_EXPERIENCE,
    -> NovexContentModuleLayout.TIMELINE
    ContentModuleType.FACTION,
    ContentModuleType.RACE,
    ContentModuleType.REGION,
    -> NovexContentModuleLayout.WORLD_GALLERY
    ContentModuleType.QUOTES -> NovexContentModuleLayout.CHARACTER_QUOTES
    ContentModuleType.ATTRIBUTE_PANEL -> NovexContentModuleLayout.CHARACTER_FACTS
    ContentModuleType.EQUIPMENT,
    ContentModuleType.TALENT_SKILL,
    ContentModuleType.APPEARANCE_PERSONALITY,
    ContentModuleType.INTEREST,
    -> NovexContentModuleLayout.CHARACTER_COLLECTION
    ContentModuleType.GAME_ATTRIBUTES,
    ContentModuleType.GAME_SKILLS,
    ContentModuleType.GAME_EQUIPMENT,
    ContentModuleType.GAME_ITEMS,
    ContentModuleType.GAME_QUESTS,
    ContentModuleType.GAME_CHECKS,
    ContentModuleType.GAME_ENDINGS,
    ContentModuleType.GAME_CHARACTER_STATUS,
    ContentModuleType.GAME_QUICK_ACTIONS,
    -> NovexContentModuleLayout.GAME_COLLECTION
    ContentModuleType.GAME_PLAYER_IDENTITY,
    ContentModuleType.GAME_OPENING,
    ContentModuleType.GAME_NARRATIVE_RULES,
    ContentModuleType.GAME_POWER_SYSTEM,
    ContentModuleType.CUSTOM,
    -> NovexContentModuleLayout.ARTICLE
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
            NovexContentModuleLayout.WORLD_GALLERY -> WorldGalleryModuleBody(
                presentation,
                imageModel,
                itemImageModels,
            )
            NovexContentModuleLayout.CHARACTER_QUOTES -> CharacterQuotesModuleBody(presentation)
            NovexContentModuleLayout.CHARACTER_FACTS -> CharacterFactsModuleBody(
                presentation,
                imageModel,
                itemImageModels,
            )
            NovexContentModuleLayout.CHARACTER_COLLECTION -> CollectionModuleBody(
                presentation,
                imageModel,
                itemImageModels,
            )
            NovexContentModuleLayout.GAME_COLLECTION -> CollectionModuleBody(
                presentation,
                imageModel,
                itemImageModels,
            )
            NovexContentModuleLayout.ARTICLE -> ArticleModuleBody(presentation, imageModel)
        }
    }
}

@Composable
private fun WorldGalleryModuleBody(
    presentation: NovexContentModulePresentation,
    imageModel: Any?,
    itemImageModels: Map<String, Any?>,
) {
    val entries = (presentation.document as? ContentModuleDocument.Collection)?.items.orEmpty()
    if (entries.isEmpty()) {
        ModuleText(presentation, maxLines = 5)
        return
    }
    LazyRow(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        items(entries.take(8), key = { it.id }) { item ->
            val itemImage = item.visualKey?.let(itemImageModels::get)
                ?: imageModel.takeIf { entries.firstOrNull()?.id == item.id }
            Column(Modifier.width(132.dp)) {
                if (itemImage != null) {
                    AsyncImage(
                        model = itemImage,
                        contentDescription = "${item.name.ifBlank { presentation.title }}代表图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(NovexColors.SurfaceMuted),
                    ) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(R.drawable.ic_phosphor_image),
                            contentDescription = null,
                            tint = NovexColors.TertiaryText,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(
                    item.name.ifBlank { "未命名条目" },
                    color = NovexColors.Text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
                if (item.summary.isNotBlank()) {
                    Text(
                        item.summary,
                        color = NovexColors.SecondaryText,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Ordered display list shared by world pages, character pages and draft previews. */
@Composable
internal fun NovexContentModuleList(
    modules: List<ContentModuleEntity>,
    moduleImages: Map<String, Any?>,
    moduleItemImages: Map<String, Map<String, Any?>>,
    onOpenModule: ((String) -> Unit)?,
) {
    if (modules.isEmpty()) return
    val shape = RoundedCornerShape(NovexDimensions.SectionRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 8.dp)
            .clip(shape)
            .background(NovexColors.Surface)
            .border(NovexDimensions.Hairline, NovexColors.Divider, shape),
    ) {
        modules.forEachIndexed { index, module ->
            if (index > 0) HorizontalDivider(
                color = NovexColors.Divider,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            NovexContentModuleBlock(
                presentation = module.toNovexPresentation(),
                imageModel = moduleImages[module.id],
                itemImageModels = moduleItemImages[module.id].orEmpty(),
                onClick = onOpenModule?.let { open -> { open(module.id) } },
            )
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
    val visibleNodes = nodes.take(8)
    LazyRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        itemsIndexed(visibleNodes) { index, node ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier.weight(1f).height(1.dp).background(
                            if (index == 0) androidx.compose.ui.graphics.Color.Transparent else NovexColors.Divider,
                        ),
                    )
                    Spacer(Modifier.size(8.dp).clip(CircleShape).background(NovexColors.Text))
                    Spacer(
                        Modifier.weight(1f).height(1.dp).background(
                            if (index == visibleNodes.lastIndex) {
                                androidx.compose.ui.graphics.Color.Transparent
                            } else {
                                NovexColors.Divider
                            },
                        ),
                    )
                }
                if (node.time.isNotBlank()) {
                    Text(
                        node.time,
                        color = NovexColors.SecondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
                Text(
                    node.title.ifBlank { node.description.ifBlank { "未命名节点" } },
                    color = NovexColors.Text,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CharacterQuotesModuleBody(presentation: NovexContentModulePresentation) {
    val items = (presentation.document as? ContentModuleDocument.Collection)?.items.orEmpty()
    if (items.isEmpty()) {
        ModuleText(presentation, maxLines = 6)
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        items.take(4).forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(
                    color = NovexColors.Divider,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(
                    "“",
                    color = NovexColors.Primary,
                    fontSize = 26.sp,
                    lineHeight = 28.sp,
                    modifier = Modifier.width(24.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        item.summary.ifBlank { item.name.ifBlank { "尚未填写语录" } },
                        color = NovexColors.Text,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.name.isNotBlank() && item.summary.isNotBlank()) {
                        Text(
                            item.name,
                            color = NovexColors.SecondaryText,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterFactsModuleBody(
    presentation: NovexContentModulePresentation,
    imageModel: Any?,
    itemImageModels: Map<String, Any?>,
) {
    val items = (presentation.document as? ContentModuleDocument.Collection)?.items.orEmpty()
    if (items.isEmpty()) {
        ModuleText(presentation, maxLines = 6)
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        items.take(8).forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = NovexColors.Divider)
            val itemImage = item.visualKey?.let(itemImageModels::get)
                ?: imageModel.takeIf { index == 0 }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (itemImage != null) {
                    AsyncImage(
                        model = itemImage,
                        contentDescription = "${item.name.ifBlank { presentation.title }}代表图",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    item.name.ifBlank { "未命名属性" },
                    color = NovexColors.SecondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(0.38f),
                )
                Text(
                    item.summary.ifBlank { "未填写" },
                    color = NovexColors.Text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.62f),
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
