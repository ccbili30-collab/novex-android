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

@Composable
internal fun NovexExecutionProcessRow(process: FlatChatItem.AssistantProcess, onOpen: () -> Unit) {
    Text("执行过程 · ${process.statusLabel()}${if (process.tools.isEmpty()) "" else " · ${process.tools.size} 项操作"} ›", color = ChatColors.secondaryText,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen).padding(horizontal = 8.dp, vertical = 14.dp))
}

@Composable
internal fun NovexExecutionProcessDialog(process: FlatChatItem.AssistantProcess, onDismiss: () -> Unit, onOpenTool: (AssistantBlock) -> Unit) {
    NovexContentDialog("执行过程", onDismiss = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回对话") } }) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            process.rows.forEach { row -> when (row) {
                is FlatChatItem.AssistantText -> Text(row.block.content, modifier = Modifier.padding(vertical = 6.dp))
                is FlatChatItem.AssistantMarkdownBlock -> Text(row.rawText, modifier = Modifier.padding(vertical = 6.dp))
                is FlatChatItem.AssistantThinking -> Text(row.block.content, modifier = Modifier.padding(vertical = 6.dp))
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
            val label = when (kind) { "world" -> "世界卡"; "character_version" -> "角色卡"; "game" -> "文游卡"; else -> null }
            if (label != null && id.isNotBlank()) TextButton(onClick = { onOpenCard(kind, id) }) {
                Text(card.optString("name").takeIf(String::isNotBlank)?.let { "打开《$it》" } ?: "打开$label ${index + 1}")
            }
        }
        if (canContinue && status in setOf("incomplete", "saved_needs_review")) TextButton(onClick = onContinue) { Text("继续核对与完成") }
    }
}
