package com.openminis.app.ui.chat

// 扁平聊天气泡项模型。LazyColumn 的行身份由 key 决定；流式重建会整体
// 重造列表，LazyColumn 靠 equals 判断 skip —— 所以正文/长 markdown 字段
// 一律用长度或引用比较，不做逐字符扫描（性能档案：自动生成的 equals 曾
// 在一次流式中吃 ~3.7s 主线程，String.charAt 独占 91.8%）。

import novex.android.data.model.ThinkingLevel

/** 引用相等包装：把"按 === 比较"塞进值列表里用。 */
private class Ref(val target: Any?) {
    override fun equals(other: Any?): Boolean =
        other is Ref && target === other.target
    override fun hashCode(): Int = System.identityHashCode(target)
}

/** 逐字段比对的通用骨架：类型一致 + 指纹列表相等。 */
private inline fun <reified T : FlatChatItem> T.fingerprintEquals(
    other: Any?,
    fingerprint: T.() -> List<Any?>,
): Boolean {
    if (this === other) return true
    if (other !is T) return false
    return fingerprint() == other.fingerprint()
}

private fun fingerprintHash(fingerprint: List<Any?>): Int {
    var h = 1
    for (part in fingerprint) h = h * 31 + (part?.hashCode() ?: 0)
    return h
}

internal sealed class FlatChatItem {
    abstract val key: String
    abstract val contentType: String

    /** 键撞车时派生同型副本，把 `#n` 后缀编进 key。 */
    abstract fun keyVariant(n: Int): FlatChatItem

    /**
     * [T-android-candidate-bubble-gap] `precededByUser`：紧邻上一条也是用户
     * 气泡时为 true（连发候选/排队消息）。连续用户气泡间没有
     * AssistantHeader 做视觉分隔，spacedBy(2.dp) 太挤——置位时
     * UserMessageBubble 加额外顶距，让两条读作独立消息。
     */
    class UserBubble(
        val message: ChatMessage,
        val precededByUser: Boolean = false,
    ) : FlatChatItem() {
        override val key = "user:${message.id}"
        override val contentType = "user"

        // ChatMessage 是 data class，直接复用其 equals（用户气泡正文短、
        // 附件小，代价可忽略）。
        override fun equals(other: Any?) =
            fingerprintEquals(other) { listOf(message, precededByUser) }

        override fun hashCode() = fingerprintHash(listOf(message, precededByUser))

        override fun keyVariant(n: Int) =
            UserBubble(message.copy(id = "${message.id}#$n"), precededByUser)
    }

    data class AssistantProcess(
        val messageId: String,
        val rows: List<FlatChatItem>,
        override val key: String,
        /** [T-live-tool-tail] 被豁免留在主文流的飞行工具状态——驱动"进行中"标签。 */
        val liveToolStatuses: List<ToolBlockStatus> = emptyList(),
    ) : FlatChatItem() {
        override val contentType = "execution_process"
        val tools: List<AssistantToolUse> get() = rows.filterIsInstance<AssistantToolUse>()
        override fun keyVariant(n: Int) = copy(key = "$key#$n")
    }

    data class AssistantHeader(val messageId: String) : FlatChatItem() {
        override val key = "header:$messageId"
        override val contentType = "header"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    /**
     * 助手正文行。流式期间整条正文保持在同一稳定行（key 只依赖
     * messageId+blockId），新增段落只改行内容不插兄弟行，视口锚点不动。
     */
    class AssistantText(
        val messageId: String,
        val block: AssistantBlock,
        val isStreaming: Boolean,
        val isTurnStart: Boolean = false,
        /** 父消息的全部 markdown 拼接，供选区工具条的 Copy Markdown 用。 */
        val messageMarkdown: String,
    ) : FlatChatItem() {
        override val key = if (isTurnStart) "assistant-start:$messageId" else "text:$messageId:${block.id}"
        override val contentType = "text"

        override fun equals(other: Any?) = fingerprintEquals(other) {
            listOf(messageId, Ref(block), isStreaming, isTurnStart, messageMarkdown.length)
        }

        override fun hashCode() = fingerprintHash(
            listOf(messageId, Ref(block), isStreaming, isTurnStart, messageMarkdown.length)
        )

        override fun keyVariant(n: Int) = AssistantText(
            messageId = "$messageId#$n",
            block = block,
            isStreaming = isStreaming,
            isTurnStart = isTurnStart,
            messageMarkdown = messageMarkdown,
        )
    }

    /**
     * 已完成助手回复的一个冻结 markdown 子块（段落/代码块/列表…）。活文
     * 本刻意留在单一 AssistantText 行；只有流结束后才散成这些行——给每个
     * 新活段插 LazyColumn 行会破坏反向布局锚点，让视口跳回上一条回复。
     *
     * `rawText`/`messageMarkdown` 可能很长：按长度与引用比较，不逐字符。
     */
    class AssistantMarkdownBlock(
        val messageId: String,
        val parentBlockId: String,
        val rawText: String,
        val blockIndex: Int,
        val isLastBlockOfMessage: Boolean,
        val messageIsStreaming: Boolean,
        val isTurnStart: Boolean = false,
        /** 父消息的全部 markdown 拼接，供 Copy Markdown 用。 */
        val messageMarkdown: String,
        val executionText: Boolean = false,
    ) : FlatChatItem() {
        override val key = if (isTurnStart) "assistant-start:$messageId" else "mdblock:$messageId:$parentBlockId:$blockIndex"
        override val contentType = "mdblock"

        /** 该片段是活消息的流式尾部。 */
        val isStreaming: Boolean get() = messageIsStreaming && isLastBlockOfMessage

        override fun equals(other: Any?) = fingerprintEquals(other) {
            listOf(
                messageId, parentBlockId, blockIndex,
                isLastBlockOfMessage, messageIsStreaming, executionText,
                isTurnStart, rawText.length, messageMarkdown.length,
            )
        }

        override fun hashCode() = fingerprintHash(
            listOf(
                messageId, parentBlockId, blockIndex,
                isLastBlockOfMessage, messageIsStreaming, executionText,
                isTurnStart, rawText.length, messageMarkdown.length,
            )
        )

        override fun keyVariant(n: Int) = AssistantMarkdownBlock(
            messageId = "$messageId#$n",
            parentBlockId = parentBlockId,
            executionText = executionText,
            rawText = rawText,
            blockIndex = blockIndex,
            isLastBlockOfMessage = isLastBlockOfMessage,
            messageIsStreaming = messageIsStreaming,
            isTurnStart = isTurnStart,
            messageMarkdown = messageMarkdown,
        )
    }

    data class AssistantThinking(
        val messageId: String,
        val block: AssistantBlock,
        val isLast: Boolean,
        val messageIsStreaming: Boolean,
        // T300: 消息创建时快照的思考档位。DB 恢复的老消息为 null——渲染
        // 器回退到会话当前档位。
        val messageThinkingLevel: ThinkingLevel? = null,
        // [T-android-thinking-auto-collapse] 该 thinking 块是消息内任意
        // 种类的最后一块时为 true（不止最后一个 thinking 块）。驱动自动
        // 折叠信号：后续 text/tool_use 一到，thinking 立即塌缩——对齐 iOS
        // ThinkingBlockView 只在真是尾块时才给 isStreaming=true。默认 false
        // 让 DB 恢复的老项按折叠渲染（改动前非尾块的行为）。
        val isLastBlockOverall: Boolean = false,
        val isTurnStart: Boolean = false,
    ) : FlatChatItem() {
        override val key = if (isTurnStart) "assistant-start:$messageId" else "thinking:$messageId:${block.id}"
        override val contentType = "thinking"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    data class AssistantToolUse(
        val messageId: String,
        val block: AssistantBlock,
        val allToolBlocks: List<AssistantBlock>,
        /** 该消息内最后被取消的工具才有 Retry——retryLast() 重跑整回合，一个按钮够。 */
        val isLastCancelled: Boolean = false,
        val isTurnStart: Boolean = false,
        /**
         * [T-live-tool-tail] 所属消息是否仍在流式。飞行豁免只对活流成立：
         * 恢复路径（journal 重放）会产出历史 PENDING/RUNNING 块，那些不是
         * 本回合的活工具，照常折叠（净眼 P1）。
         */
        val messageIsStreaming: Boolean = false,
    ) : FlatChatItem() {
        override val key = if (isTurnStart) "assistant-start:$messageId" else "tool:$messageId:${block.id}"
        override val contentType = "tool"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    data class AssistantFallbackChoices(
        val messageId: String,
        val choices: List<String>,
    ) : FlatChatItem() {
        override val key = "fallback-choices:$messageId"
        override val contentType = "fallback_choices"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    data class AssistantInfo(
        val messageId: String,
        val block: AssistantBlock,
    ) : FlatChatItem() {
        override val key = "info:$messageId:${block.id}"
        override val contentType = "info"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    data class AssistantTyping(
        val messageId: String,
        val isTurnStart: Boolean = false,
    ) : FlatChatItem() {
        override val key = if (isTurnStart) "assistant-start:$messageId" else "typing:$messageId"
        override val contentType = "typing"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    data class AssistantError(val messageId: String, val error: String) : FlatChatItem() {
        override val key = "error:$messageId"
        override val contentType = "error"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    data class BranchSwitcher(
        val messageId: String,
        val index: Int,
        val count: Int,
    ) : FlatChatItem() {
        override val key = "branch:$messageId"
        override val contentType = "branch"
        override fun keyVariant(n: Int) = copy(messageId = "$messageId#$n")
    }

    /** 迁移前的老会话：全部文本在 message.content，没有 text 块。 */
    class AssistantLegacyContent(
        val messageId: String,
        val content: String,
        val isStreaming: Boolean,
        /** 与 content 同值（老行没有独立的块级 markdown）。 */
        val messageMarkdown: String = content,
    ) : FlatChatItem() {
        override val key = "legacy:$messageId"
        override val contentType = "legacy"

        override fun equals(other: Any?) = fingerprintEquals(other) {
            listOf(messageId, isStreaming, content.length, messageMarkdown.length)
        }

        override fun hashCode() = fingerprintHash(
            listOf(messageId, isStreaming, content.length, messageMarkdown.length)
        )

        override fun keyVariant(n: Int) = AssistantLegacyContent(
            messageId = "$messageId#$n",
            content = content,
            isStreaming = isStreaming,
            messageMarkdown = messageMarkdown,
        )
    }
}

/** 可挂到助手消息名下、供操作行锚定的项。 */
internal val FlatChatItem.ownerMessageId: String?
    get() = when (this) {
        is FlatChatItem.AssistantHeader -> messageId
        is FlatChatItem.AssistantText -> messageId
        is FlatChatItem.AssistantMarkdownBlock -> messageId
        is FlatChatItem.AssistantThinking -> messageId
        is FlatChatItem.AssistantToolUse -> messageId
        is FlatChatItem.AssistantProcess -> messageId
        is FlatChatItem.AssistantFallbackChoices -> messageId
        is FlatChatItem.AssistantInfo -> messageId
        is FlatChatItem.AssistantError -> messageId
        is FlatChatItem.AssistantLegacyContent -> messageId
        is FlatChatItem.BranchSwitcher -> messageId
        else -> null
    }

/**
 * [feat/ui-rikkahub] 单条回复的操作行锚点。操作行渲染在每条助手消息的
 * 最后一个扁平项之下（同一 LazyColumn 项，不加新行），复制该消息的
 * 拼接 markdown。
 */
internal data class AssistantActionAnchor(
    val messageId: String,
    val lastItemKey: String,
    val markdown: String,
    /** 该消息自身的活信号——流式回复不挂操作行。 */
    val isStreaming: Boolean,
)

internal fun assistantActionAnchors(items: List<FlatChatItem>): Map<String, AssistantActionAnchor> {
    // 第一遍：每条消息最后所属项的 key——操作行只锚在那里。
    val lastKeys = HashMap<String, String>()
    for (item in items) item.ownerMessageId?.let { owner -> lastKeys[owner] = item.key }

    // 第二遍：按消息累计 markdown/流式状态，每个锚定项产出一个
    // AssistantActionAnchor，以锚定项的 key 为键——转录循环手里正好有这个。
    val anchors = HashMap<String, AssistantActionAnchor>()
    var prevOwner: String? = null
    var prevMarkdown = ""
    var prevStreaming = false
    for (item in items) {
        val owner = item.ownerMessageId ?: continue
        if (owner != prevOwner) {
            prevMarkdown = ""
            prevStreaming = false
        }
        // 每个文本行都带父消息的拼接 markdown，所以就算后面跟工具行，
        // 最后一个文本行仍是最终正文。
        val markdown = when (item) {
            is FlatChatItem.AssistantText -> item.messageMarkdown
            is FlatChatItem.AssistantMarkdownBlock -> item.messageMarkdown
            else -> prevMarkdown
        }
        val streaming = when (item) {
            is FlatChatItem.AssistantText -> item.isStreaming
            is FlatChatItem.AssistantMarkdownBlock -> item.messageIsStreaming
            is FlatChatItem.AssistantThinking -> item.messageIsStreaming
            is FlatChatItem.AssistantToolUse -> item.messageIsStreaming
            // 折叠工作行唯一携带的活信号是豁免中的飞行工具。
            is FlatChatItem.AssistantProcess -> item.liveToolStatuses.isNotEmpty()
            else -> prevStreaming
        }
        if (lastKeys[owner] == item.key) {
            anchors[item.key] = AssistantActionAnchor(owner, item.key, markdown, streaming)
        }
        prevOwner = owner
        prevMarkdown = markdown
        prevStreaming = streaming
    }
    return anchors
}
