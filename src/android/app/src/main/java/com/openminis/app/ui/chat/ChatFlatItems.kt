package com.openminis.app.ui.chat

// 把会话消息折叠成 LazyColumn 用的扁平行列表。模型在 FlatChatItem.kt；
// 本文件只保留合并/构建逻辑。

/**
 * T-streaming-side-channel：把活跃的 [StreamingDelta] 盖到规范 [messages]
 * 列表上，产出 [buildFlatChatItems] 折叠用的快照。原列表不被修改，命中
 * 项用 copy() 替换，下游键/相等性不受影响。无流时原样返回，跳过逐元素
 * 遍历。
 */
internal fun mergeStreamingOverlay(
    messages: List<ChatMessage>,
    streaming: Map<String, StreamingDelta>,
): List<ChatMessage> {
    if (streaming.isEmpty()) return messages
    return messages.map { m ->
        val delta = streaming[m.id] ?: return@map m
        m.copy(
            content = delta.content,
            isStreaming = true,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
    }
}

internal fun buildFlatChatItems(
    messages: List<ChatMessage>,
    // [T-android-perf-logging] 可选——传入后每 100 条打一行进度面包屑，
    // 低内存复现时能看出是哪批消息把堆推高的。默认 null 不打，流式逐
    // token 的热路径保持零日志。
    sessionId: String? = null,
    // [T-android-stream-pipeline-incremental] 只给 messages[fromIndex, size)
    // 建行。邻居回看（precededByUser / isResumeContinuation）仍读全表，
    // 所以后缀构建与全量构建同区段逐行一致——行只依赖更早的消息。
    fromIndex: Int = 0,
    // 分段构建的去重延续：传入冻结前缀的键集，防御性撞键后缀的行为才能
    // 与单次全量构建完全一致。
    seedKeys: Set<String> = emptySet(),
    /** 该会话是否由角色卡发声。普通与世界 Nova 会话保持无身份行转录。 */
    showAssistantIdentity: Boolean = false,
): List<FlatChatItem> {
    val fold = FlatItemFold(
        messages = messages,
        sessionId = sessionId,
        seedKeys = seedKeys,
        showAssistantIdentity = showAssistantIdentity,
    )
    for (idx in fromIndex until messages.size) fold.emit(idx)
    return fold.items
}

/**
 * 单条消息的折叠上下文：把"这条消息要算哪些一次性的块级标记"集中起来，
 * 而不是散在 300 行循环体的开头。
 */
private class MessageContext(val message: ChatMessage, idx: Int, messages: List<ChatMessage>) {
    val isSystem = message.role == "system"
    val blocks = message.toolBlocks
    val toolPillBlocks = blocks.filter { it.kind == "tool_use" }
    val lastThinkingId = blocks.lastOrNull { it.kind == "thinking" }?.id
    // [T-android-thinking-auto-collapse] 任意种类的最后一块——后续
    // text/tool_use 一到就让尾 thinking 转折叠。
    val lastBlockId = blocks.lastOrNull()?.id
    val lastTextIdx = blocks.indexOfLast { it.kind == "text" }
    val hasAnyTextBlock = lastTextIdx >= 0
    // 最新一条助手正文保持在单一稳定行跨越 流式→完成 过渡：此刻把活
    // AssistantText 换成多个 markdown 行会让当前视口锚点失效。
    val isTailAssistantMessage = idx == messages.lastIndex ||
        (idx + 1 until messages.size).all { messages[it].role == "system" }
    // 只有消息内最后那个被取消的 tool_use 拿 Retry——retryLast() 重跑整
    // 回合，一个按钮够了。
    val lastCancelledToolId = blocks.lastOrNull {
        it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.CANCELLED
    }?.id
    val joinedMarkdown = formalAssistantText(blocks, message.content)

    // T83：Resume 在用户停掉流式回合后新建助手气泡时，前一条（被取消的）
    // 助手消息紧跟在前。视觉上两条应读作同一回合——抑制重复的 "Minis"
    // 头部。回看时跳过 system 行（它们渲染成分隔线不算发言）。iOS 靠
    // runAgentLoop(resumingAt:) 复用同一条 ChatMessage 达到同样效果；
    // 我们在渲染层达成。
    val isResumeContinuation: Boolean =
        (idx - 1 downTo 0).asSequence()
            .map { messages[it] }
            .firstOrNull { it.role != "system" }
            ?.role == "assistant"

    // [T-android-flatitems-sublist-cme] 用下标扫描而不是
    // messages.subList(idx+1, size).all{} —— subList 是共享父表 modCount
    // 的活视图，背表一变就抛 ConcurrentModificationException。纯下标循环
    // 不碰视图。
    val isLastAssistantTurn = idx == messages.lastIndex ||
        (idx + 1 until messages.size).all { messages[it].role != "assistant" }

    /**
     * 打字指示器可见性："有可见内容"要按"会渲染"算，不是"存在"算。两个
     * 块曾经算内容却什么都不画，把指示器提前掐灭成空档（"thinking 还没
     * 等到内容出现就消失了"）：
     *  - thinking 块 + 消息思考档为 OFF——强制推理模型（MiniMax M2、Grok…）
     *    仍流 reasoning_content，但快照档被禁时 ChatScreen 不画（T300）；
     *  - 刚创建但 content 还为空的 text 块。
     * thinkingLevel 快照为 null 时渲染器回退到当前会话档，这个纯构建器读
     * 不到——按可见处理（老消息、保守、旧行为）。
     */
    val hasVisibleContent = message.content.isNotEmpty() || blocks.any {
        when (it.kind) {
            "info" -> false
            "thinking" -> message.thinkingLevel?.isEnabled ?: true
            "text" -> it.content.isNotEmpty()
            else -> true // tool_use 立即渲染
        }
    }
}

private class FlatItemFold(
    private val messages: List<ChatMessage>,
    private val sessionId: String?,
    seedKeys: Set<String>,
    private val showAssistantIdentity: Boolean,
) {
    val items = mutableListOf<FlatChatItem>()
    private val usedKeys = seedKeys.toMutableSet()
    private var hasTurnStartRow = false

    fun emit(idx: Int) {
        val message = messages[idx]
        // [T-android-perf-logging] 每 100 条一行进度面包屑。out.size 是
        // 行数水位，两行之间的突增定位重的批次。只在全量构建路径触发。
        if (sessionId != null && idx > 0 && idx % 100 == 0) {
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "buildFlatChatItems.progress",
                "msgIdx=$idx of=${messages.size} rowsSoFar=${items.size}",
            )
        }
        if (message.role == "user") emitUser(idx, message) else emitAssistant(idx, message)
    }

    /**
     * 防御性去重：key 撞车会让 LazyColumn 崩。撞了就用 `#n` 后缀递增到
     * 唯一为止。上游去重正常时永远不触发。
     */
    private fun add(item: FlatChatItem) {
        var n = 2
        var candidate = item
        while (!usedKeys.add(candidate.key)) {
            candidate = item.keyVariant(n++)
        }
        items.add(candidate)
    }

    /**
     * 流式助手回合的第一个可见行独占一个稳定 key，从等待指示器一路传到
     * 首个 thinking/text/tool 内容。没有这个交接，"typing" 被替换时
     * LazyColumn 丢尾部锚点回落到旧行。
     */
    private fun claimTurnStartRow(rendered: Boolean = true): Boolean {
        if (!rendered || hasTurnStartRow) return false
        hasTurnStartRow = true
        return true
    }

    private fun emitUser(idx: Int, message: ChatMessage) {
        if (message.content.startsWith("⁣NOVEX_CONTROL:")) return
        // [T-android-candidate-bubble-gap] 前一条也是用户消息时置位，气泡
        // 加顶距分隔——连发的候选/排队消息间没有 AssistantHeader，否则会
        // 视觉上粘成一条。
        val prevIsUser = idx > 0 && messages[idx - 1].role == "user"
        add(FlatChatItem.UserBubble(message, precededByUser = prevIsUser))
        if (message.branchCount > 1) {
            add(FlatChatItem.BranchSwitcher(
                messageId = message.branchAnchorDbId ?: message.id,
                index = message.branchIndex,
                count = message.branchCount,
            ))
        }
    }

    private fun emitAssistant(idx: Int, message: ChatMessage) {
        val ctx = MessageContext(message, idx, messages)
        hasTurnStartRow = false

        // system 消息（斜杠通知、压缩分隔线等）渲染成贯通分隔行——无
        // "Minis" 署名、无卡片。跳过助手头让每个 info 块独立站立。
        // 对齐 iOS systemDividerRow / compactDividerRow。
        //
        // 普通/世界 Nova 以叙事为主面，无身份行。角色卡会话不同：一段
        // 连续助手回合共享一个头部，直到玩家开口。
        if (showAssistantIdentity && !ctx.isSystem && !ctx.isResumeContinuation) {
            add(FlatChatItem.AssistantHeader(message.id))
        }

        ctx.blocks.forEachIndexed { index, block ->
            when (block.kind) {
                "text" -> emitTextBlock(ctx, message, index, block)
                "thinking" -> add(FlatChatItem.AssistantThinking(
                    messageId = message.id,
                    block = block,
                    isLast = block.id == ctx.lastThinkingId,
                    messageIsStreaming = message.isStreaming,
                    messageThinkingLevel = message.thinkingLevel,
                    isLastBlockOverall = block.id == ctx.lastBlockId,
                    isTurnStart = claimTurnStartRow(message.thinkingLevel?.isEnabled ?: true),
                ))
                "info" -> add(FlatChatItem.AssistantInfo(
                    messageId = message.id,
                    block = block,
                ))
                else -> add(FlatChatItem.AssistantToolUse(
                    messageId = message.id,
                    block = block,
                    allToolBlocks = ctx.toolPillBlocks,
                    isLastCancelled = block.id == ctx.lastCancelledToolId,
                    isTurnStart = claimTurnStartRow(),
                    messageIsStreaming = message.isStreaming,
                ))
            }
        }

        // 打字指示器：流式中且 (a) 还没有可见内容到达，或 (b) 正在网络
        // 空档等模型下一段响应（比如工具结果已回传）。对齐 iOS
        // `isActiveMessage && (!hasVisibleContent || isAwaitingModelResponse)`。
        if (message.isStreaming && (!ctx.hasVisibleContent || message.isAwaitingModelResponse)) {
            add(FlatChatItem.AssistantTyping(
                messageId = message.id,
                isTurnStart = claimTurnStartRow(),
            ))
        }

        // 老会话兜底：迁移前的消息全部文本在 content 里没有 text 块，
        // 仅在此时把 content 渲染在块之后。
        if (!ctx.hasAnyTextBlock && message.content.isNotEmpty()) {
            add(FlatChatItem.AssistantLegacyContent(
                messageId = message.id,
                content = message.content,
                isStreaming = message.isStreaming,
            ))
        }

        // 模型偶尔真打了选项菜单却漏掉 present_choices 调用。只在回合冻结
        // 后补一个保守的客户端兜底行，且绝不与真工具行重复。
        if (!ctx.isSystem && !message.isStreaming &&
            ctx.blocks.none { it.kind == "tool_use" && it.toolName == "present_choices" }
        ) {
            val fallbackChoices = NovexChoiceFallback.extract(ctx.joinedMarkdown)
            if (fallbackChoices.size >= 2) {
                add(FlatChatItem.AssistantFallbackChoices(message.id, fallbackChoices))
            }
        }

        // 内联错误横幅
        message.error?.let {
            add(FlatChatItem.AssistantError(message.id, it))
        }
        if (!ctx.isSystem && message.branchCount > 1) {
            add(FlatChatItem.BranchSwitcher(
                messageId = message.branchAnchorDbId ?: message.id,
                index = message.branchIndex,
                count = message.branchCount,
            ))
        }
    }

    private fun emitTextBlock(
        ctx: MessageContext,
        message: ChatMessage,
        index: Int,
        block: AssistantBlock,
    ) {
        if (block.content.isEmpty()) return
        val isLastText = index == ctx.lastTextIdx

        // 活正文保持在单一稳定行：key 只依赖消息+源块，新增段落改行内容
        // 不插兄弟行，列表锚点不动。
        if (message.isStreaming || ctx.isTailAssistantMessage) {
            add(FlatChatItem.AssistantText(
                messageId = message.id,
                block = block,
                isStreaming = message.isStreaming,
                isTurnStart = claimTurnStartRow(),
                messageMarkdown = ctx.joinedMarkdown,
            ))
            return
        }

        // 冻结消息把 text 块拆成独立 markdown 片段行。冻结前缀的片段由
        // LazyList 各自锚定；流式中会变高的只有尾部活片段。
        //
        // [T-android-defensive-fragment-merge] 冻结历史消息合并相邻纯文本
        // 片段，让长回复只产几行而不是几十行——冷开全量行数降 ~8x，低内存
        // 设备 GC 压力缓解。刚完成的尾消息在新回合到来前保持细粒度片段；
        // 代码围栏两种路径都独立成行。
        val rawFragments = splitMarkdownIntoBlockTexts(block.content)
        val fragments = if (isLastText && ctx.isLastAssistantTurn) {
            rawFragments
        } else {
            coalesceMarkdownFragments(rawFragments)
        }

        if (fragments.isEmpty()) {
            // 防御：非空输入却拆不出片段（不应发生）时整体成一行，不丢内容。
            add(FlatChatItem.AssistantMarkdownBlock(
                messageId = message.id,
                parentBlockId = block.id,
                executionText = block.executionText,
                rawText = block.content,
                blockIndex = 0,
                isLastBlockOfMessage = isLastText && message.isStreaming,
                messageIsStreaming = message.isStreaming && isLastText,
                isTurnStart = claimTurnStartRow(),
                messageMarkdown = ctx.joinedMarkdown,
            ))
        } else {
            fragments.forEachIndexed { fragIdx, raw ->
                add(FlatChatItem.AssistantMarkdownBlock(
                    messageId = message.id,
                    parentBlockId = block.id,
                    executionText = block.executionText,
                    rawText = raw,
                    blockIndex = fragIdx,
                    isLastBlockOfMessage = isLastText && fragIdx == fragments.lastIndex,
                    messageIsStreaming = message.isStreaming && isLastText,
                    isTurnStart = claimTurnStartRow(),
                    messageMarkdown = ctx.joinedMarkdown,
                ))
            }
        }
    }
}
