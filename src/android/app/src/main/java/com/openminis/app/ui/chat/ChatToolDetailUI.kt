package com.openminis.app.ui.chat

import android.graphics.BitmapFactory
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalAppCodeFontFamily
import com.openminis.app.ui.theme.LocalAppSemanticPalette
import novex.android.ui.GhostIconButton
import novex.android.ui.ModalBottomSheet
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 工具调用详情面板（自有实现）。
 *
 * 一个模态面板看一轮里全部工具块：顶栏=图标+名称+复制；主体按工具种类
 * 分派——file_edit 走 diff 视图、file_read/file_write/memory_* 走文档卡、
 * read_image 走图预览、其余走命名字段呈现（[buildNovexStandardToolDetailPresentation]）
 * 或增量展开的纯文本；底栏=状态/耗时/翻页。
 *
 * 上游 iOS ToolLiveSheet 的 shell_execute 终端卡与 browser_use 截图链随
 * 对应工具退役整体移除；旧的 read 会话若仍有这些块，落到通用文本视图。
 */

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailSheet(
    toolBlocks: List<AssistantBlock>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {},
) {
    var index by remember {
        mutableStateOf(initialIndex.coerceIn(0, toolBlocks.lastIndex.coerceAtLeast(0)))
    }
    val block = toolBlocks.getOrNull(index) ?: return onDismiss()
    val live = block.toolStatus == ToolBlockStatus.RUNNING ||
        block.toolStatus == ToolBlockStatus.STREAMING ||
        block.toolStatus == ToolBlockStatus.PENDING

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(NovexColors.Background),
        ) {
            ToolSheetTopBar(block = block, onDismiss = onDismiss)
            HorizontalDivider(thickness = NovexDimensions.Hairline, color = NovexColors.Divider)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                ToolSheetBody(block, live, toolBlocks, onOpenUrl)
            }
            ToolSheetFooter(
                block = block,
                live = live,
                index = index,
                total = toolBlocks.size,
                onPrev = { if (index > 0) index-- },
                onNext = { if (index < toolBlocks.lastIndex) index++ },
            )
        }
    }
}

// ── 顶栏：关闭 / 工具名 / 复制输出 ──────────────────────────────────────────

@Composable
private fun ToolSheetTopBar(block: AssistantBlock, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1600)
            copied = false
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(NovexColors.Surface)
            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GhostIconButton(
            icon = NovexIcons.Close,
            contentDescription = "关闭",
            onClick = onDismiss,
        )
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                toolTitleLabel(block.toolName),
                style = NovexType.ItemTitle,
                color = NovexColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (block.toolTitle.isNotEmpty()) {
                Text(
                    block.toolTitle,
                    style = NovexType.Metadata,
                    color = NovexColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        GhostIconButton(
            icon = if (copied) NovexIcons.Check else NovexIcons.ContentCopy,
            contentDescription = if (copied) "已复制" else "复制输出",
            onClick = {
                if (block.content.isNotEmpty()) {
                    clipboard.setText(AnnotatedString(block.content))
                    copied = true
                }
            },
        )
    }
}

// ── 主体分派 ────────────────────────────────────────────────────────────────

@Composable
private fun ToolSheetBody(
    block: AssistantBlock,
    live: Boolean,
    siblings: List<AssistantBlock>,
    onOpenUrl: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val args = remember(block.toolArgs) {
        runCatching { org.json.JSONObject(block.toolArgs) }.getOrElse { org.json.JSONObject() }
    }
    when (block.toolName) {
        "file_edit" -> EditDiffPanel(block, args, live, scroll)
        "file_read", "file_write" -> {
            val path = argText(args, "path", block.toolArgs)
            val body = if (block.toolName == "file_write") {
                argText(args, "content", block.toolArgs).ifEmpty { block.content }
            } else block.content
            DocumentPanel(
                title = path.substringAfterLast('/').ifEmpty { path.ifEmpty { "file" } },
                icon = if (block.toolName == "file_read") NovexIcons.Description else NovexIcons.NoteAdd,
                iconTint = NovexColors.SecondaryText,
                titleColor = NovexColors.Text,
                metaColor = NovexColors.TertiaryText,
                body = body,
                bodyColor = NovexColors.Text,
                streaming = live,
                scroll = scroll,
                footnote = if (block.toolName == "file_write" &&
                    block.content.isNotEmpty() && block.content != body) block.content else null,
            )
        }
        "memory_write", "memory_get" -> {
            val body = if (block.toolName == "memory_write") {
                argText(args, "content", block.toolArgs).ifEmpty { block.content }
            } else block.content
            val keywords = argText(args, "keywords", block.toolArgs)
            val header = if (keywords.isNotEmpty() && block.toolName == "memory_get") {
                "关键词：$keywords\n\n"
            } else ""
            DocumentPanel(
                title = block.toolName,
                icon = NovexIcons.Psychology,
                iconTint = ToolMemoryAccent.copy(alpha = 0.6f),
                titleColor = ToolMemoryAccent,
                metaColor = ToolMemoryAccent.copy(alpha = 0.5f),
                body = header + body,
                bodyColor = ToolMemoryAccent.copy(alpha = 0.85f),
                streaming = live,
                scroll = scroll,
            )
        }
        "read_image" -> ImagePreviewPanel(block, scroll)
        else -> {
            val detail = remember(block.toolName, block.toolArgs, block.content) {
                buildNovexStandardToolDetailPresentation(
                    toolName = block.toolName,
                    argumentsJson = block.toolArgs,
                    resultText = block.content,
                )
            }
            when {
                detail != null -> NovexStandardToolDetailContent(detail, scroll)
                block.content.isNotEmpty() -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(NovexDimensions.PageHorizontal),
                ) {
                    SelectionContainer {
                        IncrementalToolText(text = block.content, color = NovexColors.Text, scroll = scroll)
                    }
                }
                else -> EmptyToolBody(block, live)
            }
        }
    }
}

// ── file_edit：删除/新增行分色视图 ──────────────────────────────────────────

@Composable
private fun EditDiffPanel(
    block: AssistantBlock,
    args: org.json.JSONObject,
    live: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
) {
    val path = argText(args, "path", block.toolArgs)
    val removed = argText(args, "old_string", block.toolArgs)
    val added = argText(args, "new_string", block.toolArgs)

    val palette = LocalAppSemanticPalette.current
    val dark = ChatColors.isDark
    val panelBg = if (dark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val panelEdge = if (dark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val removedBg = if (dark) Color(0xFF4D1414) else Color(0xFFFFE5E5)
    val addedBg = if (dark) Color(0xFF144D14) else Color(0xFFE5FFE5)

    val bytes = removed.toByteArray(Charsets.UTF_8).size + added.toByteArray(Charsets.UTF_8).size
    val sizeText = when {
        live -> "写入中…"
        bytes < 1024 -> "$bytes B"
        else -> "${bytes / 1024} KB"
    }
    // 结果尾注：工具回报里的 "(N replacement(s), N bytes)" 之类括注。
    val resultNote = block.content
        .takeIf { !live && it.isNotEmpty() }
        ?.let { Regex("""\(([^)]+)\)""").find(it)?.value }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = maxWidth * 3f / 4f)
                .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
                .background(panelBg),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(NovexColors.SurfaceMuted)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(NovexIcons.EditNote, null, tint = Color(0xFFFF9500), modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    path.substringAfterLast('/').ifEmpty { "(file)" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = LocalAppCodeFontFamily.current,
                    color = NovexColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "($sizeText)",
                    fontSize = 11.sp,
                    color = if (live) Color(0xCCFF9500) else NovexColors.TertiaryText,
                )
            }
            HorizontalDivider(thickness = NovexDimensions.Hairline, color = panelEdge)
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scroll),
                ) {
                    removed.lines().forEach { line ->
                        DiffLine("- $line", palette.diffRemoved, removedBg)
                    }
                    added.lines().forEach { line ->
                        DiffLine("+ $line", palette.diffAdded, addedBg)
                    }
                }
            }
            if (!live && (path.isNotEmpty() || resultNote != null)) {
                HorizontalDivider(thickness = NovexDimensions.Hairline, color = panelEdge)
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已写入", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NovexColors.Text)
                        if (path.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                path,
                                fontSize = 12.sp,
                                fontFamily = LocalAppCodeFontFamily.current,
                                color = NovexColors.SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    resultNote?.let {
                        Text(it, fontSize = 12.sp, color = NovexColors.TertiaryText, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLine(text: String, color: Color, background: Color) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontFamily = LocalAppCodeFontFamily.current,
        color = color,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

// ── 文档卡：file_read / file_write / memory_* 共用的标题条+正文卡 ──────────

@Composable
private fun DocumentPanel(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    titleColor: Color,
    metaColor: Color,
    body: String,
    bodyColor: Color,
    streaming: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
    footnote: String? = null,
) {
    val dark = ChatColors.isDark
    val panelBg = if (dark) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
    val headerBg = if (dark) Color(0xFF212121) else Color(0xFFEBEBEB)
    val panelEdge = if (dark) Color(0xFF404040) else Color(0xFFD1D1D1)
    val sizeText = body.toByteArray(Charsets.UTF_8).size.let { bytes ->
        when {
            body.isEmpty() -> null
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
                .background(panelBg),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(14.dp))
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sizeText != null) {
                    Text(
                        if (streaming) "($sizeText 已接收)" else "($sizeText)",
                        fontSize = 11.sp,
                        color = if (streaming) Color(0xFFFF9500).copy(alpha = 0.8f) else metaColor,
                    )
                }
            }
            HorizontalDivider(thickness = NovexDimensions.Hairline, color = panelEdge)
            when {
                body.isNotEmpty() -> SelectionContainer {
                    IncrementalToolText(
                        text = body,
                        color = bodyColor,
                        scroll = scroll,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    )
                }
                streaming -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = iconTint, strokeWidth = 2.dp)
                }
            }
        }
        footnote?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, color = NovexColors.TertiaryText, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

// ── read_image：图 + 说明文字 ───────────────────────────────────────────────

@Composable
private fun ImagePreviewPanel(block: AssistantBlock, scroll: androidx.compose.foundation.ScrollState) {
    val path = block.imageFilePath
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NovexDimensions.MediaRadius)),
            )
            Spacer(Modifier.height(12.dp))
        }
        if (block.content.isNotEmpty()) {
            Text(
                block.content,
                fontSize = 13.sp,
                fontFamily = LocalAppCodeFontFamily.current,
                color = NovexColors.Text,
                lineHeight = 18.sp,
            )
        }
    }
}

// ── 空态 ────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyToolBody(block: AssistantBlock, live: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                toolIconFor(block.toolName),
                null,
                tint = toolAccentColor(block.toolName).copy(alpha = 0.3f),
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (live) "运行中…" else "暂无输出",
                fontSize = 14.sp,
                color = NovexColors.TertiaryText,
            )
        }
    }
}

// ── 底栏：状态 + 耗时 + 翻页 ────────────────────────────────────────────────

@Composable
private fun ToolSheetFooter(
    block: AssistantBlock,
    live: Boolean,
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val accent = toolAccentColor(block.toolName)
    Column(
        Modifier
            .fillMaxWidth()
            .background(NovexColors.Surface)
            .navigationBarsPadding(),
    ) {
        HorizontalDivider(thickness = NovexDimensions.Hairline, color = NovexColors.Divider)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live) {
                CircularProgressIndicator(Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
            } else {
                val (icon, tint) = when (block.toolStatus) {
                    ToolBlockStatus.SUCCESS -> NovexIcons.CheckCircle to ToolCheckColor
                    ToolBlockStatus.FAILED -> NovexIcons.Error to ToolErrorColor
                    ToolBlockStatus.CANCELLED -> NovexIcons.Cancel to ToolCancelColor
                    ToolBlockStatus.TIMEOUT -> NovexIcons.Schedule to ToolErrorColor
                    else -> NovexIcons.CheckCircle to ToolCheckColor
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    toolTitleLabel(block.toolName),
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NovexColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    block.toolTitle.ifEmpty { block.toolName },
                    fontSize = 12.sp,
                    lineHeight = 14.sp,
                    color = NovexColors.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if ((block.durationMs > 0 && !live) || block.startTimeMs > 0L) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    if (block.durationMs > 0 && !live) {
                        Text(
                            formatToolDuration(block.durationMs),
                            fontSize = 11.sp,
                            fontFamily = LocalAppCodeFontFamily.current,
                            color = NovexColors.TertiaryText,
                        )
                    }
                    if (block.startTimeMs > 0L) {
                        Text(
                            formatStepTimestamp(block.startTimeMs),
                            fontSize = 9.sp,
                            fontFamily = LocalAppCodeFontFamily.current,
                            color = NovexColors.TertiaryText.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                Icon(
                    NovexIcons.SkipPrevious, "上一个",
                    tint = if (index > 0) NovexColors.Text else NovexColors.TertiaryText,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (live) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(7.dp).background(Color(0xFF34C759), CircleShape))
                    Text("进行中", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = NovexColors.Text)
                }
            } else {
                Text(
                    "${index + 1} / $total",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = LocalAppCodeFontFamily.current,
                    color = NovexColors.SecondaryText,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNext, enabled = index < total - 1, modifier = Modifier.size(32.dp)) {
                Icon(
                    NovexIcons.SkipNext, "下一个",
                    tint = if (index < total - 1) NovexColors.Text else NovexColors.TertiaryText,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

// ── 参数取值：完整 JSON 优先，半截流式 JSON 走容忍扫描 ──────────────────────

private fun argText(args: org.json.JSONObject, key: String, rawJson: String): String =
    args.optString(key, "").ifEmpty { extractPartialJsonString(key, rawJson) ?: "" }

/**
 * 从（可能尚未写完的）工具参数 JSON 里取字符串字段。先定位 `"key"`，跳过
 * 空白、冒号与开引号，再扫到未转义的闭引号为止；扫不到结尾说明还在流式
 * 写入，返回已到的部分。供参数面/详情页在模型边写边显示。
 */
internal fun extractPartialJsonString(key: String, json: String): String? {
    if (json.isEmpty()) return null
    val anchor = "\"$key\""
    val at = json.indexOf(anchor)
    if (at < 0) return null
    var i = at + anchor.length
    val n = json.length
    while (i < n && (json[i] == ' ' || json[i] == '\t' || json[i] == ':')) i++
    if (i >= n || json[i] != '"') return null
    i++
    val value = StringBuilder()
    while (i < n) {
        val c = json[i]
        if (c == '\\' && i + 1 < n) {
            when (json[i + 1]) {
                'n' -> value.append('\n')
                't' -> value.append('\t')
                '"' -> value.append('"')
                '/' -> value.append('/')
                '\\' -> value.append('\\')
                else -> value.append('\\').append(json[i + 1])
            }
            i += 2
            continue
        }
        if (c == '"') return value.toString()
        value.append(c)
        i++
    }
    return value.toString().ifEmpty { null }
}

// ── 增量展开：大段工具输出不一次性排版 ──────────────────────────────────────

private const val OUTPUT_WINDOW_LINES = 200
private const val OUTPUT_WINDOW_BYTES = 10 * 1024

/** 把输出按 ~200 行/10KB 的窗口切段，每段保留换行以便拼接还原。 */
private fun splitOutputWindows(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val windows = mutableListOf<String>()
    val current = StringBuilder()
    var lines = 0
    for (line in text.split("\n")) {
        if (lines >= OUTPUT_WINDOW_LINES || current.length >= OUTPUT_WINDOW_BYTES) {
            windows += current.toString()
            current.clear()
            lines = 0
        }
        current.append(line).append('\n')
        lines++
    }
    if (current.isNotEmpty()) windows += current.toString()
    return windows
}

/**
 * 长输出增量渲染：先给出第一窗，滚动接近底部或点「继续/全部」再往后放，
 * 避免一次性排版几十 KB 的文本。调用方提供 verticalScroll 与 SelectionContainer。
 */
@Composable
private fun IncrementalToolText(
    text: String,
    color: Color,
    scroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
) {
    val windows = remember(text) { splitOutputWindows(text) }
    var shown by remember(text) { mutableStateOf(1) }
    val body = remember(text, shown) { windows.take(shown).joinToString("") }

    val linkClick = LocalMarkdownUrlClickHandler.current
    val rendered = remember(body, linkClick) {
        if (linkClick != null) {
            com.openminis.app.ui.util.linkifyUrls(text = body, onClick = linkClick)
        } else AnnotatedString(body)
    }

    // 接近底部自动放下一窗；maxValue 变化即重估，不逐像素重组。
    val nearEnd by remember {
        derivedStateOf {
            scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE &&
                scroll.value >= scroll.maxValue - 600
        }
    }
    LaunchedEffect(nearEnd, shown) {
        if (nearEnd && shown < windows.size) shown++
    }

    Column(modifier) {
        Text(
            rendered,
            fontSize = 13.sp,
            fontFamily = LocalAppCodeFontFamily.current,
            color = color,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown < windows.size) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    "继续加载",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { shown++ },
                )
                Text(
                    "全部展开",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { shown = windows.size },
                )
            }
        }
    }
}
