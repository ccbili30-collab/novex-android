package com.openminis.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
internal fun NovexExecutionProcessRow(process: FlatChatItem.AssistantProcess, onOpen: () -> Unit) {
    val active=process.statusLabel()=="进行中"
    // Keep the compact preview short, but never hide a failed/stopped tool
    // behind later successful rows. The folded row is the user's only clue
    // that a turn needs attention; the dialog still contains every row.
    val recent = if(active) process.tools.takeLast(3) else process.tools.takeLast(2)
    val failed = process.tools.filter { it.block.toolStatus in setOf(ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT) }
    val tools = (recent + failed).distinctBy { it.block.id }
    Column(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(horizontal=8.dp,vertical=10.dp)) {
        Text(
            "${if(active)"正在处理" else "工作记录"} · ${process.statusLabel()} · ${process.rows.size} 项 ›",
            color=ChatColors.secondaryText,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
        tools.forEach { row->
            val title=row.block.toolTitle.ifBlank {buildNovexStandardToolDetailPresentation(row.block.toolName,row.block.toolArgs,row.block.content)?.title ?: "查看操作详情"}
            Text(
                "${row.block.toolStatus.displayLabel()} · $title",
                color=ChatColors.secondaryText,
                fontSize = 13.sp,
                lineHeight = 20.sp,
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
