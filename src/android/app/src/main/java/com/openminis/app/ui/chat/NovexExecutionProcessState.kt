package com.openminis.app.ui.chat

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
        val folded = turn.filter { row -> when (row) {
            is FlatChatItem.AssistantToolUse -> row.block.canFoldExecution()
            is FlatChatItem.AssistantText -> row.block.executionText
            is FlatChatItem.AssistantMarkdownBlock -> row.executionText
            is FlatChatItem.AssistantThinking -> true
            else -> false
        } }
        if (folded.isEmpty()) output += turn
        else {
            val keys = folded.map { it.key }.toSet()
            val first = folded.first()
            val messageId = when (first) {
                is FlatChatItem.AssistantToolUse -> first.messageId
                is FlatChatItem.AssistantText -> first.messageId
                is FlatChatItem.AssistantMarkdownBlock -> first.messageId
                is FlatChatItem.AssistantThinking -> first.messageId
                else -> error("Unexpected execution row")
            }
            val process = FlatChatItem.AssistantProcess(messageId, folded, first.key)
            turn.forEach { row ->
                if (row === first) output += process
                else if (row.key !in keys) output += row
            }
        }
        start = end
    }
    rows.forEachIndexed { index, row ->
        if (row is FlatChatItem.UserBubble || row is FlatChatItem.BranchSwitcher) flush(index)
    }
    flush(rows.size)
    return output
}

// Interactive outputs remain directly usable. Every other tool state belongs to the process area.
private fun AssistantBlock.canFoldExecution(): Boolean =
    toolName !in setOf("present_choices", "render_panel", "panel", "present_system_panel", "generate_image") ||
        toolStatus in setOf(ToolBlockStatus.FAILED, ToolBlockStatus.CANCELLED, ToolBlockStatus.TIMEOUT)

internal fun FlatChatItem.AssistantProcess.statusLabel(): String {
    val states = tools.map { it.block.toolStatus }
    return when {
        states.any { it == ToolBlockStatus.RUNNING || it == ToolBlockStatus.STREAMING || it == ToolBlockStatus.PENDING } -> "进行中"
        states.any { it == ToolBlockStatus.FAILED || it == ToolBlockStatus.TIMEOUT } -> "有操作未完成"
        states.any { it == ToolBlockStatus.CANCELLED } -> "已停止"
        rows.any { it is FlatChatItem.AssistantText && it.isStreaming || it is FlatChatItem.AssistantThinking && it.messageIsStreaming } -> "进行中"
        else -> "查看记录"
    }
}

