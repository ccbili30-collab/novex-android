package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.data.NovexAnnouncement
import com.openminis.app.ui.markdown.MarkdownParser

/**
 * [T-announcement-hero] 公告面板的「版本中心」式渲染：发布公告（索引带
 * version）上 hero 横幅（封面图+版本号+徽标+日期·渠道+标语），正文按
 * MarkdownParser 块级定制渲染。用户裁决：分节符号只给亮点类节标题
 * （新增→sparkle、改进/优化→gear），其余节与子节不加。
 */

private val metaLine = Regex("^\\*\\*[^*]+\\*\\*[：:]\\s*")
private val labeledBullet = Regex("^\\*\\*(.+?)\\*\\*[：:]\\s*(.*)$")
private val boldSpan = Regex("\\*\\*(.+?)\\*\\*")

/** hero 墨色：封面是自产浅色天空图，文字固定深藏青，不随主题翻转。 */
private val HeroInk = Color(0xFF173A5E)

@Composable
internal fun AnnouncementHero(announcement: NovexAnnouncement, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .heightIn(min = 140.dp),
    ) {
        val cover = announcement.coverUrl
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(Color(0xFFDCEBF8), Color(0xFFC2DCF4))),
                ),
            )
        }
        Column(
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "NOVEX",
                style = MaterialTheme.typography.labelSmall,
                color = HeroInk.copy(alpha = 0.70f),
                letterSpacing = 3.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    announcement.version ?: announcement.versionName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = HeroInk,
                )
                announcement.badge?.let { badge ->
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = HeroInk,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.72f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                buildAnnotatedString {
                    append(announcement.versionName.replace('-', '.'))
                    announcement.channel?.let { channel ->
                        append("  ·  ")
                        append(channel.replaceFirstChar { it.uppercase() })
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = HeroInk.copy(alpha = 0.75f),
            )
            announcement.tagline?.let { tagline ->
                Text(
                    tagline,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp,
                    color = HeroInk.copy(alpha = 0.88f),
                )
            }
        }
    }
}

/**
 * 公告正文块级渲染。[heroMode] 真=发布公告：跳过开头「**元信息**：」段
 * （已上横幅），首个正文段作导语（左侧色条强调）。非 hero 公告同渲染
 * 简排版。块类型覆盖公告所需（标题/段落/引用/列表/分隔线）；代码表、
 * 表格等富块公告不使用，忽略。
 */
@Composable
internal fun AnnouncementBody(markdown: String, heroMode: Boolean, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var visible = blocks
        if (heroMode) visible = visible.dropWhile { it is Block.Paragraph && metaLine.containsMatchIn(it.content) }
        var leadingParagraph = heroMode
        visible.forEach { block ->
            when (block) {
                is Block.Heading -> when (block.level) {
                    // 顶层标题与面板条目标题重复，不重复渲染
                    1 -> Unit
                    2 -> SectionHeading(block.content)
                    else -> InlineText(block.content, MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                is Block.Paragraph -> {
                    if (leadingParagraph) {
                        AccentParagraph(block.content)
                        leadingParagraph = false
                    } else {
                        InlineText(block.content, MaterialTheme.typography.bodyMedium)
                    }
                }
                is Block.Blockquote -> AccentParagraph(block.blocks.filterIsInstance<Block.Paragraph>().joinToString("\n") { it.content })
                is Block.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    block.items.forEach { item -> BulletCard(item.content) }
                }
                is Block.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${block.startNumber + i}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            InlineText(item.content, MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is Block.ThematicBreak -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                else -> Unit
            }
        }
    }
}

/** 分节符号按用户裁决只给亮点类节标题；正文内容我们自己写作，关键词映射可控。 */
@Composable
private fun SectionHeading(title: String) {
    val icon = when {
        title.contains("新增") -> R.drawable.ic_phosphor_sparkle
        title.contains("改进") || title.contains("优化") -> R.drawable.ic_phosphor_gear
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
        InlineText(title, MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 导语/引用：左侧主色条 + 底色轻衬。 */
@Composable
private fun AccentParagraph(content: String) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .padding(vertical = 2.dp)
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.padding(vertical = 2.dp)) {
            InlineText(content, MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 「**标题**：描述」条目 → 轻卡片行；无粗体前缀则整行正文。 */
@Composable
private fun BulletCard(content: String) {
    val match = labeledBullet.find(content)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (match != null) {
                Text(match.groupValues[1], style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                val rest = match.groupValues[2]
                if (rest.isNotBlank()) {
                    InlineText(rest, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                InlineText(content, MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 行内渲染：剥 `**粗体**` 为加粗 span，其余按纯文本（公告行内语法仅用粗体）。 */
@Composable
private fun InlineText(
    content: String,
    style: androidx.compose.ui.text.TextStyle,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
) {
    Text(
        buildAnnotatedString {
            var last = 0
            for (match in boldSpan.findAll(content)) {
                append(content.substring(last, match.range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(match.groupValues[1]) }
                last = match.range.last + 1
            }
            append(content.substring(last))
        },
        style = style,
        fontWeight = fontWeight,
        color = color,
    )
}

private typealias Block = MarkdownParser.Block
