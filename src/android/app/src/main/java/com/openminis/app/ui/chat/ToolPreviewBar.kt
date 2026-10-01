package com.openminis.app.ui.chat

// 工具缩略预览 + 悬浮状态条：工具运行中吸附在输入栏上方的 100×65 缩略卡
// 和 38dp 状态条。由 ChatComposerWidgets 拆出；shell_execute 终端缩略图与
// browser_use 截图回退随两个退役工具的分支一并删除（残余块走通用兜底）。

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import novex.android.ui.NovexIcons
import org.json.JSONObject

private val ThumbShape = RoundedCornerShape(8.dp)
private val ThumbDiffOld = Color(0xFFFF6B6B)
private val ThumbDiffNew = Color(0xFF4ADE80)
private val ThumbDarkBg = Color(0xFF1A1A1E)

// ─── 缩略图体 ────────────────────────────────────────────────────────────────

private val THUMB_WIDTH = 100.dp
private val THUMB_HEIGHT = 65.dp

@Composable
private fun ToolPreviewThumbnail(
    block: AssistantBlock,
    toolAccent: Color,
    onClick: () -> Unit,
) {
    val args = remember(block.toolArgs) {
        try { JSONObject(block.toolArgs) } catch (_: Exception) { JSONObject() }
    }

    Box(
        modifier = Modifier
            .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
            .shadow(
                elevation = 10.dp,
                shape = ThumbShape,
                ambientColor = Color.Black.copy(alpha = 0.15f),
                spotColor = Color.Black.copy(alpha = 0.25f),
            )
            .clip(ThumbShape)
            .background(
                when (block.toolName) {
                    "file_read", "file_write" -> ChatColors.secondaryBg
                    else -> ThumbDarkBg
                }
            )
            .border(0.5.dp, ChatColors.thumbnailBorder, ThumbShape)
            .clickable(onClick = onClick),
    ) {
        when (block.toolName) {
            "file_edit" -> DiffThumb(args, block, toolAccent)
            "file_read", "file_write" -> FileDocThumb(args, block, toolAccent)
            "memory_write", "memory_get" -> MemoryThumb(args, block, toolAccent)
            "read_image" -> ImageThumb(
                path = block.imageFilePath,
                toolName = block.toolName,
                toolAccent = toolAccent,
                contentDescription = "Read image",
            )
            else -> GenericThumb(block, toolAccent)
        }
    }
}

/** 末行等宽小字预览：file_edit / 文档 / 记忆 / 通用四类共用的正文渲染。 */
@Composable
private fun ThumbLines(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.lines().takeLast(12).joinToString("\n"),
        fontSize = 5.5.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = 12,
        lineHeight = 6.5.sp,
        modifier = modifier,
    )
}

@Composable
private fun ThumbHeader(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 7.sp,
        lineHeight = 8.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** file_edit diff：旧行红 `-`、新行绿 `+` 各取末 5 行。 */
@Composable
private fun DiffThumb(args: JSONObject, block: AssistantBlock, toolAccent: Color) {
    val oldStr = args.optString("old_string", "")
        .ifEmpty { extractPartialJsonString("old_string", block.toolArgs) ?: "" }
    val newStr = args.optString("new_string", "")
        .ifEmpty { extractPartialJsonString("new_string", block.toolArgs) ?: "" }
    if (oldStr.isEmpty() && newStr.isEmpty()) {
        ThumbIconPlaceholder("file_edit", toolAccent)
        return
    }
    Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
        DiffLines(oldStr, "-", ThumbDiffOld)
        DiffLines(newStr, "+", ThumbDiffNew)
    }
}

@Composable
private fun DiffLines(text: String, sign: String, color: Color) {
    if (text.isEmpty()) return
    text.lines().takeLast(5).forEach { line ->
        Text(
            text = "$sign $line",
            fontSize = 5.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 6.sp,
        )
    }
}

/** file_read / file_write：文件名头 + 末 12 行。file_write 流式时优先取
 *  toolArgs 里的半段 content（对齐 iOS block.streamingFileContent）。 */
@Composable
private fun FileDocThumb(args: JSONObject, block: AssistantBlock, toolAccent: Color) {
    val path = args.optString("path", "")
        .ifEmpty { extractPartialJsonString("path", block.toolArgs) ?: "" }
    val header = path.substringAfterLast("/").ifEmpty {
        if (block.toolName == "file_write") "Write file" else "Read file"
    }
    val body = when {
        block.toolName == "file_write" -> args.optString("content", "")
            .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
            .ifEmpty { block.content }
        else -> block.content
    }
    Column(modifier = Modifier.padding(4.dp)) {
        ThumbHeader(header, ChatColors.secondaryText)
        if (body.isNotEmpty()) ThumbLines(body, toolAccent.copy(alpha = 0.85f))
    }
}

/** memory_write 取 toolArgs 的待写入内容；memory_get 取结果正文并前置
 *  查询关键词——与 ToolDetailSheet 的解析口径一致。 */
@Composable
private fun MemoryThumb(args: JSONObject, block: AssistantBlock, toolAccent: Color) {
    val body = when (block.toolName) {
        "memory_write" -> args.optString("content", "")
            .ifEmpty { extractPartialJsonString("content", block.toolArgs) ?: "" }
            .ifEmpty { block.content }
        else -> {
            val keywords = args.optString("keywords", "")
                .ifEmpty { extractPartialJsonString("keywords", block.toolArgs) ?: "" }
            if (keywords.isNotEmpty()) "Keywords: $keywords\n${block.content}" else block.content
        }
    }
    Column(modifier = Modifier.padding(4.dp)) {
        ThumbHeader(block.toolName, ToolMemoryAccent.copy(alpha = 0.75f))
        if (body.isNotEmpty()) ThumbLines(body, toolAccent.copy(alpha = 0.85f))
    }
}

/** 读图类缩略图：IO 线程解码（T285：BitmapFactory.decodeFile 在主线程
 *  同步跑曾把 chat→预览 的导航转场卡 ~150-350ms）。 */
@Composable
private fun ImageThumb(
    path: String?,
    toolName: String,
    toolAccent: Color,
    contentDescription: String,
) {
    val decoded by produceState<Bitmap?>(initialValue = null, path) {
        value = path?.let { p ->
            withContext(Dispatchers.IO) {
                try { BitmapFactory.decodeFile(p) } catch (_: Exception) { null }
            }
        }
    }
    val bmp = decoded
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        ThumbIconPlaceholder(toolName, toolAccent, iconSize = 24.dp)
    }
}

@Composable
private fun ThumbIconPlaceholder(
    toolName: String,
    toolAccent: Color,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            toolIconFor(toolName),
            contentDescription = null,
            tint = toolAccent.copy(alpha = 0.5f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 通用兜底：有内容画末 12 行，否则工具图标。 */
@Composable
private fun GenericThumb(block: AssistantBlock, toolAccent: Color) {
    if (block.content.isNotEmpty()) {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            ThumbLines(block.content, toolAccent.copy(alpha = 0.85f))
        }
    } else {
        ThumbIconPlaceholder(block.toolName, toolAccent)
    }
}

// ─── 悬浮状态条 ──────────────────────────────────────────────────────────────

private val BAR_HEIGHT = 38.dp
private val THUMB_INSET = 10.dp

/**
 * iOS 布局：ZStack(.bottomLeading)——缩略图浮在状态条上方探出 14dp。
 * T261：详情打开的点击经 ChatViewModel 状态走，与列表内 pill 共用同一张
 * 常驻 sheet（不再有各开各的 local-remember sheet 互踢，也没有
 * LaunchedEffect(lastIndex) 在新工具启动时抢跳页）。
 */
@Composable
internal fun FloatingToolStatusBar(
    toolBlocks: List<AssistantBlock>,
    onStop: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onOpenDetail: (String) -> Unit = {},
) {
    var currentIndex by remember { mutableStateOf(toolBlocks.lastIndex.coerceAtLeast(0)) }
    val lastIndex = toolBlocks.lastIndex
    LaunchedEffect(lastIndex) {
        // 当前展示项不再活动时回跳最新项；用户正在翻页看活动项则不打扰。
        if (!toolBlocks.getOrNull(currentIndex).isActiveTool) {
            currentIndex = lastIndex.coerceAtLeast(0)
        }
    }
    val block = toolBlocks.getOrNull(currentIndex) ?: return
    val openCurrentDetail: () -> Unit = { onOpenDetail(block.id) }
    val toolAccent = toolAccentColor(block.toolName)
    val previewEnabled = LocalToolPreviewEnabled.current
    val isRunning = block.isActiveTool

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 给探出条的缩略图预留顶部空间。
            .padding(top = if (previewEnabled) THUMB_HEIGHT - BAR_HEIGHT else 0.dp),
    ) {
        // 层 1：状态条（底层，贴底铺满）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .align(Alignment.BottomCenter)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(10.dp),
                    ambientColor = Color.Black.copy(alpha = 0.06f),
                    spotColor = Color.Black.copy(alpha = 0.12f),
                )
                .background(ChatColors.inputBg, RoundedCornerShape(10.dp))
                .border(0.5.dp, ChatColors.toolBorder, RoundedCornerShape(10.dp))
                .padding(
                    start = if (previewEnabled) THUMB_WIDTH + THUMB_INSET + 8.dp else 12.dp,
                    end = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolStatusGlyph(block, toolAccent)
            Spacer(modifier = Modifier.width(6.dp))

            // [T-step-timestamp v2] 内联 HH:mm:ss 前缀移除——详见
            // ToolDetailSheet 顶栏的 "HH:mm:ss · 3s" 展示，只在点开的详情
            // sheet 里出现，常驻条保持干净。
            Text(
                text = block.toolTitle.ifEmpty { block.toolName },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ChatColors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = openCurrentDetail,
                    ),
            )

            if (toolBlocks.size > 1) {
                BarPager(
                    current = currentIndex,
                    total = toolBlocks.size,
                    onPrev = { if (currentIndex > 0) currentIndex-- },
                    onNext = { if (currentIndex < toolBlocks.lastIndex) currentIndex++ },
                )
            }
            if (!isRunning && onDismiss != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    NovexIcons.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onDismiss,
                        ),
                )
            }
            // T170：悬浮条上的停止按钮已撤——列表内 ToolCallPill 保留红色
            // 方块。同一个活工具放两个停手入口在窄屏上冗余。
        }

        // 层 2：缩略图浮在条上（iOS ZStack 覆盖）
        if (previewEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = THUMB_INSET),
            ) {
                ToolPreviewThumbnail(
                    block = block,
                    toolAccent = toolAccent,
                    onClick = openCurrentDetail,
                )
            }
        }
    }
}

private val AssistantBlock?.isActiveTool: Boolean
    get() = this?.toolStatus == ToolBlockStatus.RUNNING ||
        this?.toolStatus == ToolBlockStatus.STREAMING ||
        this?.toolStatus == ToolBlockStatus.PENDING

@Composable
private fun ToolStatusGlyph(block: AssistantBlock, toolAccent: Color) {
    when {
        block.isActiveTool -> CircularProgressIndicator(
            modifier = Modifier.size(15.dp),
            color = toolAccent,
            strokeWidth = 1.5.dp,
        )
        else -> {
            val (icon, tint) = when (block.toolStatus) {
                ToolBlockStatus.SUCCESS -> NovexIcons.CheckCircle to ToolCheckColor
                ToolBlockStatus.FAILED, ToolBlockStatus.TIMEOUT ->
                    NovexIcons.Error to ToolErrorColor
                ToolBlockStatus.CANCELLED -> NovexIcons.Close to ToolCancelColor
                else -> NovexIcons.Build to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun BarPager(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PagerChevron(
            icon = NovexIcons.ChevronLeft,
            contentDescription = "Previous",
            enabled = current > 0,
            onClick = onPrev,
        )
        Text(
            "${current + 1}/$total",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PagerChevron(
            icon = NovexIcons.ChevronRight,
            contentDescription = "Next",
            enabled = current < total - 1,
            onClick = onNext,
        )
    }
}

@Composable
private fun PagerChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        },
        modifier = Modifier
            .size(18.dp)
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
    )
}
