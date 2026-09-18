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
    // [T-turn-single-card] 2026-09-16 用户批③（方案 A）：一个回合一张卡——
    // 回合内**全部**可折叠行（工具/思考/过程文字）合并为唯一一条工作行，钉在
    // 回合末尾；叙述文字归组留在主文流，不再被过程块打断（此前"连续段折叠"
    // 会把一次建卡拆成 过程块×N + 叙述×N 交替，占屏且结构混乱）。
    fun foldable(row: FlatChatItem): Boolean = when (row) {
        // [T-live-tool-tail]（2026-09-17 用户批：参照 dsh/codex）**活流中**的
        // 飞行工具行不折叠：STREAMING/PENDING/RUNNING 留在主文流，配
        // ToolCallPill 的滚动小字尾巴持续可见"正在写什么"；状态落定后的
        // 下一次展平才收进工作行。messageIsStreaming 门槛把恢复路径（journal
        // 重放）产出的历史 PENDING/RUNNING 块挡在外面——那些不是本回合的
        // 活工具，照常折叠（净眼 P1）。
        is FlatChatItem.AssistantToolUse -> row.block.canFoldExecution() &&
            !(row.messageIsStreaming && row.block.toolStatus?.isInFlight() == true)
        is FlatChatItem.AssistantText -> row.block.presentationChannel() == NovexPresentationChannel.PROCESS_TEXT
        is FlatChatItem.AssistantMarkdownBlock -> row.executionText
        // [T-thinking-live] 进行中的思考块不折叠（用户 2026-09-15：思考应
        // 先实时显示文字，输出完再收档）：留在直播区由 ThinkingBlock 展开
        // 渲染、结束自动收起；后续块到达或回合冻结后，它才被收进工作记录。
        is FlatChatItem.AssistantThinking -> row.block.presentationChannel() == NovexPresentationChannel.THINKING &&
            !(row.isLastBlockOverall && row.messageIsStreaming)
        else -> false
    }
    fun flush(end: Int) {
        val turn = rows.subList(start, end)
        val folded = turn.filter(::foldable)
        val hasTool = folded.any { it is FlatChatItem.AssistantToolUse }
        val hasProcessText = folded.any {
            it is FlatChatItem.AssistantText || it is FlatChatItem.AssistantMarkdownBlock
        }
        // Thinking on its own already has a dedicated collapsed renderer.
        // Do not replace it with an empty “0 项” work record.
        if (folded.isEmpty() || (!hasTool && !hasProcessText)) output += turn
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
            // [T-live-tool-tail]（净眼 P2）被豁免的飞行工具不在 rows 里，
            // 但它们的存在必须反映在工作行状态——否则已折部分全落定时
            // 标签退化成"查看记录"，进行中信号丢失。
            val liveStatuses = turn.filterIsInstance<FlatChatItem.AssistantToolUse>()
                .filter { it.messageIsStreaming && it.block.toolStatus?.isInFlight() == true }
                .mapNotNull { it.block.toolStatus }
            val process = FlatChatItem.AssistantProcess(messageId, folded, first.key, liveStatuses)
            // 叙述与交互类工具保留原位；唯一工作行钉在回合末尾。
            turn.forEach { row -> if (row.key !in keys) output += row }
            output += process
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
    // Interactive results stay on the main transcript so a failed choice,
    // panel, or image action remains visible and actionable.  Non-interactive
    // tool I/O belongs to the process disclosure regardless of final status.
    toolName !in setOf("present_choices", "render_panel", "panel", "present_system_panel", "generate_image")

internal fun FlatChatItem.AssistantProcess.statusLabel(): String {
    val states = tools.map { it.block.toolStatus }
    return when {
        // [T-live-tool-tail] 主文流里还有飞行工具（被豁免未折）——回合仍在
        // 进行，即使已折部分全部落定（净眼 P2 修复）。
        liveToolStatuses.isNotEmpty() -> "进行中"
        states.any { it == ToolBlockStatus.RUNNING || it == ToolBlockStatus.STREAMING || it == ToolBlockStatus.PENDING } -> "进行中"
        states.any { it == ToolBlockStatus.FAILED || it == ToolBlockStatus.TIMEOUT } -> "有操作未完成"
        states.any { it == ToolBlockStatus.CANCELLED } -> "已停止"
        rows.any { it is FlatChatItem.AssistantText && it.isStreaming || it is FlatChatItem.AssistantThinking && it.messageIsStreaming } -> "进行中"
        else -> "查看记录"
    }
}

/** [T-live-tool-tail] 工具是否仍在飞行中（参数流式/待执行/执行中）——飞行中不折叠、挂滚动尾巴。 */
internal fun ToolBlockStatus.isInFlight(): Boolean =
    this == ToolBlockStatus.STREAMING || this == ToolBlockStatus.PENDING || this == ToolBlockStatus.RUNNING
