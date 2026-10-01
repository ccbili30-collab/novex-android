package com.openminis.app.ui.chat

// 聊天信息流里的"系统行"组件：压缩/记忆/思考等系统提示的分隔线块 +
// 点开后的摘要底部弹层。原为 ChatScreen 尾部杂件，本文件只保留活跃件。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import novex.android.ui.NovexIcons

// [P3.3 裁军] LocalBrowserTabPool / rememberBrowserLiveSnapshot 随内置
// 浏览器退役删除；LocalToolPreviewEnabled（工具预览开关）保留。
internal val LocalToolPreviewEnabled = compositionLocalOf { true }

// ─── 系统分隔行（对齐 iOS systemDividerRow / compactDividerRow）───────────────
//
// 一条贯通的细分隔线，中央压小图标+标签；iOS 是 HStack { Divider, label,
// Divider }。规则用单 Box 层实现而非两个 weight(1f) 段——后者在父宽度被
// 标签固有尺寸耗尽时会塌成零宽。

@Composable
internal fun FallbackInfoBlock(
    block: AssistantBlock,
    onRevert: (() -> Unit)? = null,
    compactedHistoryExpanded: Boolean? = null,
    onToggleCompactedHistory: (() -> Unit)? = null,
) {
    val divider = ChatColors.separator
    val fg = ChatColors.secondaryText
    val icon = systemRowIcon(block.toolName)
    val hasDetail = block.toolArgs.isNotEmpty()
    var showDetail by remember(block.id) { mutableStateOf(false) }
    val visibleLabel = when (compactedHistoryExpanded) {
        true -> stringResource(R.string.chat_compacted_history_hide, block.content)
        false -> stringResource(R.string.chat_compacted_history_show, block.content)
        null -> block.content
    }

    // SubcomposeLayout：先量中央标签的固有宽度，再把两侧分隔线对称铺满
    // 剩余空间。早前 weight(1f) 写法在本 Compose 版本会塌成零宽。
    SubcomposeLayout(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) { constraints ->
        val totalWidth = constraints.maxWidth
        val labelGap = 8.dp.roundToPx()
        val ruleHeight = 1.dp.roundToPx()
        // 标签最多占 80%，保证长线状态串下分隔线仍有意义长度。
        val labelMax = (totalWidth * 0.80f).toInt().coerceAtLeast(0)

        val labelPlaceables = subcompose("label") {
            DividerLabel(
                icon = icon,
                label = visibleLabel,
                fg = fg,
                hasDetail = hasDetail,
                compactedHistoryExpanded = compactedHistoryExpanded,
                onToggleCompactedHistory = onToggleCompactedHistory,
                onShowDetail = { showDetail = true },
            )
        }.map { it.measure(Constraints(maxWidth = labelMax)) }

        val labelW = labelPlaceables.maxOfOrNull { it.width } ?: 0
        val labelH = labelPlaceables.maxOfOrNull { it.height } ?: 0
        val sideW = (totalWidth - labelW - 2 * labelGap).coerceAtLeast(0) / 2

        val rules = subcompose("rules") {
            repeat(2) {
                Box(modifier = Modifier.background(divider).height(1.dp))
            }
        }.map {
            it.measure(Constraints.fixed(width = sideW, height = ruleHeight))
        }

        val rowHeight = maxOf(labelH, ruleHeight)
        layout(totalWidth, rowHeight) {
            rules.getOrNull(0)?.placeRelative(0, (rowHeight - ruleHeight) / 2)
            var x = sideW + labelGap
            for (p in labelPlaceables) {
                p.placeRelative(x, (rowHeight - p.height) / 2)
                x += p.width
            }
            rules.getOrNull(1)?.placeRelative(totalWidth - sideW, (rowHeight - ruleHeight) / 2)
        }
    }

    if (showDetail && hasDetail) {
        CompactSummarySheet(
            summary = block.toolArgs,
            onDismiss = { showDetail = false },
            onRevert = onRevert,
        )
    }
}

private fun systemRowIcon(toolName: String): ImageVector = when (toolName) {
    // T84: CloseFullscreen ≈ iOS arrow.down.right.and.arrow.up.left（内向
    // 对角双箭头=折叠义），比竖向 Compress 更贴近 iOS compactDividerRow
    // 的图标。其余老压缩提示保留 squeeze 向后兼容。
    "compact" -> NovexIcons.CloseFullscreen
    "memory" -> NovexIcons.Psychology
    "thinking" -> NovexIcons.Lightbulb
    else -> NovexIcons.Info
}

@Composable
private fun DividerLabel(
    icon: ImageVector,
    label: String,
    fg: Color,
    hasDetail: Boolean,
    compactedHistoryExpanded: Boolean?,
    onToggleCompactedHistory: (() -> Unit)?,
    onShowDetail: () -> Unit,
) {
    Row(
        modifier = if (onToggleCompactedHistory != null) {
            Modifier.clickable(onClick = onToggleCompactedHistory)
        } else {
            Modifier
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(10.dp),
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight(1f, fill=false)：文本按需取宽、收紧时先让位，
            // info 图标永不被挤出屏。
            modifier = Modifier.weight(1f, fill = false),
        )
        if (hasDetail) {
            Icon(
                imageVector = NovexIcons.Info,
                contentDescription = "Show full summary",
                tint = fg,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onShowDetail),
            )
        }
        if (compactedHistoryExpanded != null) {
            Icon(
                imageVector = if (compactedHistoryExpanded) {
                    NovexIcons.KeyboardArrowUp
                } else {
                    NovexIcons.KeyboardArrowDown
                },
                contentDescription = if (compactedHistoryExpanded) {
                    "折叠已压缩对话"
                } else {
                    "展开已压缩对话"
                },
                tint = fg,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ─── 压缩摘要弹层 ────────────────────────────────────────────────────────────

/**
 * 展示完整压缩摘要的底部弹层：可滚动、可复制、可撤销（对齐 iOS
 * CompactSummarySheet）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSummarySheet(
    summary: String,
    onDismiss: () -> Unit,
    onRevert: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var showRevertConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    StandardChatSheet(
        title = "Compact Summary",
        onDismiss = onDismiss,
        leadingAction = {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(summary))
                copied = true
                scope.launch {
                    delay(1500)
                    copied = false
                }
            }) {
                Icon(
                    imageVector = if (copied) NovexIcons.Check else NovexIcons.ContentCopy,
                    contentDescription = "Copy",
                    tint = if (copied) Color(0xFF34C759) else ChatColors.secondaryText,
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SelectionContainer(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = ChatColors.primaryText,
                    lineHeight = 20.sp,
                )
            }
            if (onRevert != null) {
                HorizontalDivider(color = ChatColors.separator)
                MinisTextButton(
                    onClick = { showRevertConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = NovexIcons.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Revert Compact",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    if (showRevertConfirm && onRevert != null) {
        MinisAlertDialog(
            onDismissRequest = { showRevertConfirm = false },
            title = "Revert this compact?",
            text = "The summary will be discarded and the messages it covered " +
                "will become active again. This may push the conversation past " +
                "the model's context window — if that happens, long-press a " +
                "message to re-compact from that point.",
            confirmText = "Revert",
            onConfirm = {
                showRevertConfirm = false
                onDismiss()
                onRevert()
            },
            isDestructive = true,
        )
    }
}
