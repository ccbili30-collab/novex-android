package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.openminis.app.ui.novex.NovexContentDialog
import com.openminis.app.ui.novex.TextButton
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

private fun ToolBlockStatus?.displayLabel(): String = when (this) {
    ToolBlockStatus.STREAMING -> "准备中"
    ToolBlockStatus.PENDING -> "等待执行"
    ToolBlockStatus.RUNNING -> "执行中"
    ToolBlockStatus.SUCCESS -> "已完成"
    ToolBlockStatus.FAILED -> "未完成"
    ToolBlockStatus.CANCELLED -> "已停止"
    ToolBlockStatus.TIMEOUT -> "已超时"
    null -> "记录"
}

/**
 * [T-execution-activity-strip] 生成中钉在输入栏上方的活动条（2026-09-15 用户
 * 决策：技能调用动态要贴底滚动播报，否则用户看不到 AI 在干什么）。固定 3 行
 * 滚动显示最新的工具动态，像终端尾巴；点击打开完整工作记录弹窗；回合结束后
 * 由调用方切换成「✓ 本轮完成」样式停留 3 秒再收起。
 */
@Composable
internal fun NovexExecutionActivityStrip(
    process: FlatChatItem.AssistantProcess,
    done: Boolean,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        Text(
            if (done) "✓ 本轮完成 · 查看记录 ›" else "正在处理 · 最新动态 ›",
            color = if (done) ChatColors.primaryText else ChatColors.secondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!done) {
            process.tools.takeLast(3).forEach { row ->
                val title = row.block.toolTitle.ifBlank {
                    buildNovexStandardToolDetailPresentation(row.block.toolName, row.block.toolArgs, row.block.content)?.title
                        ?: "查看操作详情"
                }
                Text(
                    "${row.block.toolStatus.displayLabel()} · $title",
                    color = ChatColors.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
internal fun NovexExecutionProcessRow(process: FlatChatItem.AssistantProcess) {
    // [T-turn-single-card] 2026-09-16 用户批③：工作行只占一行——预览子行全撤
    // （回合一张卡后子行只会更长），失败/停止以角标提示，完整时间线点开看。
    // [feat/ui-rikkahub] 2026-09-27 取消浮窗：点行原地内联展开完整时间线，
    // 工具记录也在内联切换参数/结果，不再弹执行过程对话框。
    val active = process.statusLabel() == "进行中"
    val failedCount = process.tools.count { it.block.toolStatus in setOf(ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT) }
    var expanded by remember { mutableStateOf(false) }
    // [feat/ui-rikkahub] ZCode-style collapsed header: total work time
    // (工具耗时合计，"已工作 X 分 X 秒") instead of step count; falls back
    // to a step count when no timing data exists.
    val workSeconds = (process.tools.sumOf { it.block.durationMs } / 1000L).toInt()
    val headLabel = when {
        workSeconds >= 60 -> "已工作 ${workSeconds / 60} 分 ${workSeconds % 60} 秒"
        workSeconds > 0 -> "已工作 $workSeconds 秒"
        else -> "本轮 ${process.rows.size} 步"
    }
    // [feat/ui-rikkahub] ChainOfThought timeline step: 24dp node + connector
    // stubs (both drawn — the chain continues through this row), shimmer
    // label while running. Same geometry as the thinking/tool steps.
    val lineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val shimmer = rememberInfiniteTransition(label = "processShimmer")
    val shimmerAlpha by shimmer.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "processAlpha",
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        // [feat/ui-rikkahub] Collapsed header = ZCode: bare text + chevron
        // hugging the label, no icon, no stub segments.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                headLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(if (active) shimmerAlpha else 1f),
            )
            Icon(
                imageVector = if (expanded) com.openminis.app.ui.novex.NovexIcons.KeyboardArrowUp else com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp),
            )
            if (failedCount > 0) {
                Text(
                    "⚠ $failedCount 步未完成",
                    color = ChatColors.secondaryText,
                    fontSize = 12.sp,
                )
            }
        }
        AnimatedVisibility(expanded) {
            // [feat/ui-rikkahub] Continuous left rail (ZCode-style): a single
            // line at x=12dp behind every step node, instead of per-row stubs.
            // Only working steps live in the timeline (思考/工具) — the answer
            // prose renders outside, matching ZCode's own transcript logic.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(8.dp.toPx(), 0f),
                            end = Offset(8.dp.toPx(), size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            ) {
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    process.rows.forEach { row -> when (row) {
                        is FlatChatItem.AssistantThinking -> ProcessStepRow(
                            icon = com.openminis.app.ui.novex.NovexIcons.Psychology,
                            label = "思考 · " + if (row.block.content.length >= 1000) {
                                "${row.block.content.length / 1000}K 字"
                            } else {
                                "${row.block.content.length} 字"
                            },
                            content = row.block.content,
                        )
                        is FlatChatItem.AssistantToolUse -> ProcessStepRow(
                            icon = toolIconFor(row.block.toolName),
                            label = run {
                                val dur = if (row.block.durationMs > 0) {
                                    val s = row.block.durationMs / 1000.0
                                    " · " + if (s < 10) String.format("%.1f", s) + "s" else String.format("%.0f", s) + "s"
                                } else ""
                                "${row.block.toolStatus.displayLabel()} · " + row.block.toolTitle.ifBlank {
                                    buildNovexStandardToolDetailPresentation(
                                        row.block.toolName, row.block.toolArgs, row.block.content,
                                    )?.title ?: "工具调用"
                                } + dur
                            },
                            args = row.block.toolArgs.takeIf { it.isNotBlank() },
                            result = row.block.content.takeIf { it.isNotBlank() },
                        )
                        else -> Unit
                    } }
                }
            }
        }
    }
}

/**
 * [feat/ui-rikkahub] One step on the continuous rail: 24dp node column over
 * the rail line, label + chevron, tap toggles its detail inline — no sheets,
 * no dialogs anywhere.
 */
@Composable
private fun ProcessStepRow(
    icon: ImageVector,
    label: String,
    args: String? = null,
    result: String? = null,
    content: String? = null,
) {
    var open by remember(label) { mutableStateOf(false) }
    val hasDetail = content != null || args != null || result != null
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.width(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hasDetail) Modifier.clickable { open = !open } else Modifier),
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasDetail) {
                    Icon(
                        imageVector = if (open) com.openminis.app.ui.novex.NovexIcons.KeyboardArrowUp else com.openminis.app.ui.novex.NovexIcons.KeyboardArrowDown,
                        contentDescription = if (open) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            AnimatedVisibility(open) {
                Column {
                    result?.let { DetailBlock("结果", prettyToolJson(it)) }
                    args?.let { DetailBlock("参数", prettyToolJson(it)) }
                    content?.let { DetailBlock("思考", it) }
                }
            }
        }
    }
}

/** [feat/ui-rikkahub] Pretty-print JSON payloads; verbatim fallback for prose/malformed text. */
internal fun prettyToolJson(raw: String): String {
    if (raw.isBlank()) return raw
    return try {
        when (raw.trimStart().firstOrNull()) {
            '{' -> org.json.JSONObject(raw).toString(2)
            '[' -> org.json.JSONArray(raw).toString(2)
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}

/**
 * [feat/ui-rikkahub] Detail payload block: label + 10-line faded preview +
 * 展开全文 dialog. Never dumps an unbounded wall of text into the transcript.
 */
@Composable
internal fun DetailBlock(label: String, text: String) {
    if (text.isBlank()) return
    val lines = text.lines()
    val truncated = lines.size > 10
    var showFull by remember(text) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            if (truncated) lines.take(10).joinToString("\n") else text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 1.dp),
        )
        if (truncated) {
            Text(
                "展开全文 · ${lines.size} 行",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable { showFull = true },
            )
        }
    }
    if (showFull) {
        NovexContentDialog(
            label,
            onDismiss = { showFull = false },
            confirmButton = { TextButton(onClick = { showFull = false }) { Text("关闭") } },
        ) {
            Text(
                text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
internal fun NovexExecutionProcessDialog(process: FlatChatItem.AssistantProcess, onDismiss: () -> Unit, onOpenTool: (AssistantBlock) -> Unit) {
    NovexContentDialog("执行过程", onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回对话") } }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            process.rows.forEach { row -> when (row) {
                is FlatChatItem.AssistantText -> StreamingMarkdownText(
                    content = row.block.content,
                    isStreaming = false,
                    modifier = Modifier.padding(vertical = 6.dp),
                    shardId = TextShardId(row.messageId, "process:${row.block.id}"),
                )
                is FlatChatItem.AssistantMarkdownBlock -> MarkdownBlock(
                    rawText = row.rawText,
                    isStreaming = false,
                    modifier = Modifier.padding(vertical = 6.dp),
                    shardId = TextShardId(row.messageId, "process:${row.parentBlockId}:${row.blockIndex}"),
                )
                is FlatChatItem.AssistantThinking -> Text(row.block.content, modifier = Modifier.padding(vertical = 6.dp), fontSize = 13.sp, lineHeight = 19.sp)
                is FlatChatItem.AssistantToolUse -> TextButton(onClick = { onOpenTool(row.block) }) {
                    Text("${row.block.toolStatus.displayLabel()} · " + row.block.toolTitle.ifBlank { buildNovexStandardToolDetailPresentation(row.block.toolName, row.block.toolArgs, row.block.content)?.title ?: "查看操作详情" })
                }
                else -> Unit
            } }
        }
    }
}

@Composable
internal fun NovexCardTaskStatusRow(block: AssistantBlock, canContinue: Boolean, onOpenCard: (String, String) -> Unit, onContinue: () -> Unit) {
    val status = runCatching { JSONObject(block.toolArgs).optString("status") }.getOrDefault("incomplete")
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(block.content, color = ChatColors.secondaryText)
        val cards = runCatching { JSONObject(block.toolArgs).optJSONArray("cards") }.getOrNull()
        if (cards != null) for (index in 0 until cards.length()) {
            val card = cards.optJSONObject(index) ?: continue
            val kind = card.optString("kind")
            val id = card.optString("id")
            val label = when (kind) { "integrated" -> "卡片"; "world" -> "世界卡"; "character_version" -> "角色卡"; "game" -> "文游卡"; else -> null }
            if (label != null && id.isNotBlank()) TextButton(onClick = { onOpenCard(kind, id) }) {
                Text(card.optString("name").takeIf(String::isNotBlank)?.let { "打开《$it》" } ?: "打开$label ${index + 1}")
            }
        }
        if (canContinue && status in setOf("incomplete", "saved_needs_review")) TextButton(onClick = onContinue) { Text("继续核对与完成") }
    }
}
