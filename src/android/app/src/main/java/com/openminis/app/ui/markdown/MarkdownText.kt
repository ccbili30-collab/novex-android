package com.openminis.app.ui.markdown

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import novex.android.ui.NovexIcons

/**
 * 静态 Markdown 渲染入口：块级（标题/段落/代码块/引用/列表/表格/分隔线/
 * 媒体）分派 + 行内（粗斜体/删除线/行内码/链接）扫面。数学占位符
 * （￼MATHn￼）交给 KaTeX 行内瓦片。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val parsed = remember(markdown) { MarkdownParser.parseWithMath(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in parsed.blocks) {
            BlockRenderer(block, color, style, parsed.mathSpans)
        }
    }
}

// ── 块级分派 ────────────────────────────────────────────────────────────────

@Composable
private fun BlockRenderer(
    block: MarkdownParser.Block,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    when (block) {
        is MarkdownParser.Block.Heading -> HeadingView(block, color)
        is MarkdownParser.Block.Paragraph -> MathAwareText(block.content, color, baseStyle, mathSpans)
        is MarkdownParser.Block.CodeBlock -> CodeBlockCard(block)
        is MarkdownParser.Block.Blockquote -> QuoteColumn(block, baseStyle, mathSpans)
        is MarkdownParser.Block.BulletList -> BulletListRows(block, color, baseStyle)
        is MarkdownParser.Block.NumberedList -> NumberedListRows(block, color, baseStyle)
        is MarkdownParser.Block.Table -> TableCard(block, color, baseStyle, mathSpans)
        is MarkdownParser.Block.MathBlock -> MathBlockView(latex = block.latex)
        is MarkdownParser.Block.ThematicBreak -> HorizontalDivider(
            Modifier.padding(vertical = 4.dp),
            color = color.copy(alpha = 0.3f),
        )
        is MarkdownParser.Block.Image -> InlineImageCard(block)
        is MarkdownParser.Block.Video -> VideoCard(block)
        is MarkdownParser.Block.Audio -> AudioCard(block)
    }
}

// ── 标题 ────────────────────────────────────────────────────────────────────

private val HEADING_STYLE = mapOf(
    1 to (24.sp to FontWeight.Bold),
    2 to (20.sp to FontWeight.Bold),
    3 to (18.sp to FontWeight.SemiBold),
    4 to (16.sp to FontWeight.SemiBold),
    5 to (15.sp to FontWeight.Medium),
)

@Composable
private fun HeadingView(heading: MarkdownParser.Block.Heading, color: Color) {
    val (size, weight) = HEADING_STYLE[heading.level] ?: (14.sp to FontWeight.Medium)
    InlineContent(heading.content, color, TextStyle(fontSize = size, fontWeight = weight))
}

// ── 数学占位符感知的段落（段落/表格单元共用）──────────────────────────────────

private val MATH_PLACEHOLDER = Regex("￼MATH(\\d+)￼")

/** 含行内数学占位符的文本：按占位符切段，文本段走 InlineContent，
 *  数学段走非 display 的 KaTeX 小瓦片（行高随正文、左对齐，视觉上
 *  跟在上一段文字后面流）。display 数学（$$..$$）是独立块不经过这里。 */
@Composable
private fun MathAwareText(
    text: String,
    color: Color,
    style: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    if (mathSpans.isEmpty() || !text.contains('￼')) {
        InlineContent(text, color, style)
        return
    }
    val byPlaceholder = remember(mathSpans) { mathSpans.associateBy { it.placeholder } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        var cursor = 0
        for (match in MATH_PLACEHOLDER.findAll(text)) {
            val span = byPlaceholder[match.value] ?: continue
            TextSegment(text.substring(cursor, match.range.first), color, style)
            KaTeXRenderView(latex = span.latex, displayMode = false)
            cursor = match.range.last + 1
        }
        if (cursor < text.length) TextSegment(text.substring(cursor), color, style)
    }
}

@Composable
private fun TextSegment(segment: String, color: Color, style: TextStyle) {
    if (segment.isNotBlank()) InlineContent(segment, color, style)
}

// ── 代码块 ──────────────────────────────────────────────────────────────────

@Composable
private fun CodeBlockCard(block: MarkdownParser.Block.CodeBlock) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                block.language.ifEmpty { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("code", block.code))
                },
                modifier = Modifier.height(28.dp),
            ) {
                Icon(
                    NovexIcons.ContentCopy,
                    contentDescription = "Copy code",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(16.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                block.code,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFF4EC9B0),
                    lineHeight = 18.sp,
                ),
            )
        }
    }
}

// ── 引用 ────────────────────────────────────────────────────────────────────

@Composable
private fun QuoteColumn(
    block: MarkdownParser.Block.Blockquote,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    // 前缘橙色竖条；正文用次级色呈现引文感。
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Color(0xFFFF9500), topLeft = Offset.Zero, size = Size(3.dp.toPx(), size.height))
            }
            .padding(start = 12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val quoted = MaterialTheme.colorScheme.onSurfaceVariant
            for (inner in block.blocks) BlockRenderer(inner, quoted, baseStyle, mathSpans)
        }
    }
}

// ── 列表 ────────────────────────────────────────────────────────────────────

@Composable
private fun BulletListRows(
    block: MarkdownParser.Block.BulletList,
    color: Color,
    baseStyle: TextStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (item in block.items) {
            Row(Modifier.fillMaxWidth()) {
                val checked = item.checked
                if (checked != null) {
                    Icon(
                        if (checked) NovexIcons.CheckBox else NovexIcons.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = color.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 4.dp, top = 2.dp).size(18.dp),
                    )
                } else {
                    Text("•", color = color, style = baseStyle, modifier = Modifier.padding(end = 8.dp))
                }
                InlineContent(item.content, color, baseStyle, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumberedListRows(
    block: MarkdownParser.Block.NumberedList,
    color: Color,
    baseStyle: TextStyle,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((index, item) in block.items.withIndex()) {
            Row(Modifier.fillMaxWidth()) {
                Text("${block.startNumber + index}.", color = color, style = baseStyle, modifier = Modifier.padding(end = 8.dp))
                InlineContent(item.content, color, baseStyle, Modifier.weight(1f))
            }
        }
    }
}

// ── 表格 ────────────────────────────────────────────────────────────────────

@Composable
private fun TableCard(
    table: MarkdownParser.Block.Table,
    color: Color,
    baseStyle: TextStyle,
    mathSpans: List<MarkdownParser.MathSpan>,
) {
    val borderColor = color.copy(alpha = 0.2f)

    @Composable
    fun cell(text: String, colIdx: Int, header: Boolean) {
        val align = table.alignments.getOrElse(colIdx) { MarkdownParser.Alignment.LEFT }
        Box(
            Modifier
                .width(120.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = when (align) {
                MarkdownParser.Alignment.CENTER -> Alignment.Center
                MarkdownParser.Alignment.RIGHT -> Alignment.CenterEnd
                MarkdownParser.Alignment.LEFT -> Alignment.CenterStart
            },
        ) {
            MathAwareText(
                text,
                color,
                if (header) baseStyle.copy(fontWeight = FontWeight.Bold) else baseStyle,
                mathSpans,
            )
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(0.5.dp, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Column {
            Row(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .height(IntrinsicSize.Min),
            ) {
                table.headers.forEachIndexed { i, header -> cell(header, i, header = true) }
            }
            HorizontalDivider(color = borderColor)
            for (row in table.rows) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.forEachIndexed { i, text -> cell(text, i, header = false) }
                }
                HorizontalDivider(color = borderColor)
            }
        }
    }
}

// ── 行内内容 ────────────────────────────────────────────────────────────────

@Composable
private fun InlineContent(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary

    // 清理漏到行内渲染的数学占位符（列表/标题等不带 span 的路径）。
    val cleaned = remember(text) {
        if (text.contains('￼')) text.replace(MATH_PLACEHOLDER, "") else text
    }

    val context = LocalContext.current
    val linkListener = remember(context) {
        LinkInteractionListener { link ->
            (link as? LinkAnnotation.Url)?.url?.let {
                com.openminis.app.ui.components.openExternalUrl(context, it)
            }
        }
    }

    val annotated = remember(cleaned, color, linkListener) {
        scanInline(cleaned, baseStyle = style, codeColor = accent, codeBg = inlineCodeBg,
            linkColor = accent, linkListener = linkListener)
    }
    Text(annotated, modifier = modifier, style = style.copy(color = color))
}

/** 行内语法扫面：一组有序规则，每条返回消费到的下标（null=不匹配），
 *  全部落空时输出普通字符前进一格。规则序即优先级：
 *  转义 > 行内码 > 链接 > 粗斜体 > 粗体 > 删除线 > 斜体。 */
private fun scanInline(
    text: String,
    baseStyle: TextStyle,
    codeColor: Color,
    codeBg: Color,
    linkColor: Color,
    linkListener: LinkInteractionListener?,
): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color = codeColor,
        background = codeBg,
        fontSize = (baseStyle.fontSize.value * 0.85).sp,
    )
    val rules: List<AnnotatedString.Builder.(Int) -> Int?> = listOf(
        // \x 转义：输出字面下一字符
        { pos ->
            if (text[pos] == '\\' && pos + 1 < text.length) {
                append(text[pos + 1]); pos + 2
            } else null
        },
        // `行内码`
        { pos -> delimitedSpan(text, pos, "`", codeStyle, requireContent = false) },
        // [文字](url)
        { pos -> linkSpan(text, pos, linkColor, linkListener) },
        // ***粗斜体***（空内容也消费，与历史行为一致）
        { pos ->
            delimitedSpan(text, pos, "***",
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic), requireContent = false)
        },
        // **粗体** / __粗体__
        { pos ->
            delimitedSpan(text, pos, "**", bold, requireContent = false)
                ?: delimitedSpan(text, pos, "__", bold, requireContent = false)
        },
        // ~~删除线~~
        { pos -> delimitedSpan(text, pos, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough), requireContent = false) },
        // *斜体* / _斜体_：单分隔符且紧跟同字符时让位给粗体规则（先匹配失败的
        // `**` 不会在这里被吃掉，保证 `**abc` 原样渲染）
        { pos ->
            val c = text[pos]
            if ((c == '*' || c == '_') && (pos + 1 >= text.length || text[pos + 1] != c)) {
                delimitedSpan(text, pos, c.toString(), SpanStyle(fontStyle = FontStyle.Italic), requireContent = true)
            } else null
        },
    )

    var i = 0
    while (i < text.length) {
        i = rules.firstNotNullOfOrNull { it(i) } ?: run { append(text[i]); i + 1 }
    }
}

/** 通用「对称分隔符包裹」规则：从 [start] 匹配 [delim]，找下一个同分隔符
 *  收尾，中间内容套 [style]。[requireContent] 为真时要求至少一个内容字符。 */
private fun AnnotatedString.Builder.delimitedSpan(
    text: String,
    start: Int,
    delim: String,
    style: SpanStyle,
    requireContent: Boolean = true,
): Int? {
    if (!text.startsWith(delim, start)) return null
    val end = text.indexOf(delim, start + delim.length)
    if (end < 0) return null
    if (requireContent && end == start + delim.length) return null
    withStyle(style) { append(text.substring(start + delim.length, end)) }
    return end + delim.length
}

/** `[文字](url)` 规则；结构不完整（缺 `]`/`(`/`)`）返回 null 让字面输出。 */
private fun AnnotatedString.Builder.linkSpan(
    text: String,
    start: Int,
    linkColor: Color,
    linkListener: LinkInteractionListener?,
): Int? {
    if (text[start] != '[') return null
    val closeBracket = text.indexOf(']', start + 1)
    if (closeBracket <= start) return null
    val openParen = closeBracket + 1
    if (openParen >= text.length || text[openParen] != '(') return null
    val closeParen = text.indexOf(')', openParen + 1)
    if (closeParen <= closeBracket) return null
    val url = text.substring(openParen + 1, closeParen)
    withLink(LinkAnnotation.Url(url, linkInteractionListener = linkListener)) {
        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
            append(text.substring(start + 1, closeBracket))
        }
    }
    return closeParen + 1
}
