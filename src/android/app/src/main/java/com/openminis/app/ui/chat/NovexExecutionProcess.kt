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

/** Presentation only: raw messages, tool results and the incremental flatten cache stay intact. */
internal fun foldNovexExecutionProcesses(input: List<FlatChatItem>): List<FlatChatItem> {
    // A merged assistant run contains a persisted checkpoint per model turn. Show only its latest state.
    var latestTask: FlatChatItem.AssistantInfo? = null
    val retainedTasks = mutableSetOf<String>()
    input.forEach { row ->
        if (row is FlatChatItem.UserBubble) { latestTask?.let { retainedTasks += it.key }; latestTask = null }
        if (row is FlatChatItem.AssistantInfo && row.block.toolName == NovexCardCreationTask.MARKER) latestTask = row
    }
    latestTask?.let { retainedTasks += it.key }
    val rows = input.filterNot { it is FlatChatItem.AssistantInfo && it.block.toolName == NovexCardCreationTask.MARKER && it.key !in retainedTasks }

    val output = mutableListOf<FlatChatItem>()
    var start = 0
    fun flush(end: Int) {
        val turn = rows.subList(start, end)
        val lastTool = turn.indexOfLast { it is FlatChatItem.AssistantToolUse }
        val folded = turn.filterIndexed { index, row -> index <= lastTool && when (row) {
            is FlatChatItem.AssistantToolUse -> row.block.canFoldExecution()
            is FlatChatItem.AssistantText -> !row.isStreaming
            is FlatChatItem.AssistantMarkdownBlock -> !row.isStreaming
            else -> false
        } }
        val tools = folded.filterIsInstance<FlatChatItem.AssistantToolUse>()
        if (tools.isEmpty()) output += turn
        else {
            val keys = folded.map { it.key }.toSet()
            val first = folded.first()
            val process = FlatChatItem.AssistantProcess(tools.first().messageId, folded, first.key)
            turn.forEach { row ->
                if (row === first) output += process
                else if (row.key !in keys) output += row
            }
        }
        start = end
    }
    rows.forEachIndexed { index, row ->
        if (row is FlatChatItem.UserBubble || row is FlatChatItem.BranchSwitcher || row is FlatChatItem.AssistantInfo) flush(index)
    }
    flush(rows.size)
    return output
}

private fun AssistantBlock.canFoldExecution(): Boolean {
    if (toolStatus != ToolBlockStatus.SUCCESS) return false
    if (toolName in setOf("present_choices", "render_panel", "panel", "present_system_panel")) return false
    // Protected proposals and uncertain writes need a visible action, even when the tool itself succeeded.
    if (toolName in setOf("novex_propose_content_changes", "novex_propose_memory_changes")) return false
    val result = runCatching { JSONObject(content) }.getOrNull()
    val status = result?.optString("status").orEmpty()
    if (status in setOf("waiting_confirmation", "confirmation_required", "saved_needs_review")) return false
    if (result?.optBoolean("requires_confirmation") == true) return false
    return true
}

@Composable
internal fun NovexExecutionProcessRow(process: FlatChatItem.AssistantProcess, onOpen: () -> Unit) {
    Text("执行过程 · ${process.tools.size} 项已执行操作 ›", color = ChatColors.secondaryText,
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
                is FlatChatItem.AssistantToolUse -> TextButton(onClick = { onOpenTool(row.block) }) {
                    Text(row.block.toolTitle.ifBlank { buildNovexStandardToolDetailPresentation(row.block.toolName, row.block.toolArgs, row.block.content)?.title ?: "查看操作详情" })
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
            if (label != null && id.isNotBlank()) TextButton(onClick = { onOpenCard(kind, id) }) { Text("打开$label ${index + 1}") }
        }
        if (canContinue && status in setOf("incomplete", "saved_needs_review")) TextButton(onClick = onContinue) { Text("继续核对与完成") }
    }
}
