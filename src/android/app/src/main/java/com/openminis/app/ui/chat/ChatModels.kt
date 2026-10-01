package com.openminis.app.ui.chat

import android.net.Uri
import androidx.compose.ui.graphics.vector.ImageVector
import novex.android.data.model.ThinkingLevel
import novex.core.ContextUsageRecord

// 会话 UI 层的模型契约：消息气泡、流式增量、排队提示、工具块、斜杠命令。
// 全部是数据形状定义，行为逻辑在各 ViewModel / 渲染器里。

/** 流式增量侧信道：一条 assistant 消息在回合进行中高频变动的部分。
 * 渲染端按 `streamingById[id]?.content ?: message.content` 取有效值；
 * 回合结束后侧信道排空回写进 message 本体。 */
data class StreamingDelta(
    val content: String,
    val toolBlocks: List<AssistantBlock>,
    val isAwaitingModelResponse: Boolean,
)

data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val isStreaming: Boolean = false,
    // 等下一个模型 chunk 的网络间隙：回合首个 chunk 之前，或工具结果送回后、
    // 下一轮流式开始前。下一个内容 chunk（text/thinking/tool_use）到达即清除。
    val isAwaitingModelResponse: Boolean = false,
    val imageUris: List<Uri> = emptyList(),
    val attachmentNames: List<String> = emptyList(),
    // 非图片附件的 file:// URI，用户气泡里的文件 chip 点进 FilePreviewScreen。
    // 与 attachmentNames 尾段（imageUris 个图片条目之后）一一对齐。
    val attachmentUris: List<Uri> = emptyList(),
    val toolBlocks: List<AssistantBlock> = emptyList(),
    // 本条 assistant 消息创建时的 thinking-level 快照。会话级深度思考开关
    // 关掉时 UI 靠它隐藏 Deep Thinking 折叠块（强推理模型仍可能吐
    // reasoning_content——UI 选择隐藏而不是静默）。仅内存态：DB 恢复出来的
    // 消息为 null，渲染时回落到会话当前档位。
    val thinkingLevel: ThinkingLevel? = null,
    val error: String? = null,
    // 已入队、等待注入正在运行的 agent loop 的用户提示。
    val isQueued: Boolean = false,
    val queuedPromptId: String? = null,
    /** 本次请求实际选中的结构化来源；选择前或老回合为 null。 */
    val novexContextUsage: ContextUsageRecord? = null,
    // 已被折叠进 compact 摘要标记的历史区间里的消息：留在 UI 可滚动可读，
    // 但降透明度提示它已不在模型活跃上下文里。
    val isCompactedHistory: Boolean = false,
    // 该 UI 气泡代表的全部 DB 行 id——通常一个，但连续 assistant 回合在
    // loadSessionMessages 合并后气泡携带每个来源行的 id。Phase 2.5 边界
    // 解析用它在合并尾巴上定位正确的分割线位置。
    val sourceDbIds: List<String> = emptyList(),
    /** 气泡代表的首个持久化行；兄弟分支导航用它。 */
    val branchAnchorDbId: String? = sourceDbIds.firstOrNull(),
    /** 一基兄弟位次，渲染为 2/2 之类。 */
    val branchIndex: Int = 1,
    val branchCount: Int = 1,
) {
    /** 内部桥接消息：排队提示注入时插进 agentHistory 的角色交替占位。
     * 只面向 LLM，绝不渲染成气泡——本属性是 uiMessages 出口处的兜底过滤。 */
    val isInternalBridge: Boolean
        get() = role == "assistant" && isInternalBridgeText(content)

    companion object {
        /** 现行桥接文案——必须与 ChatViewModel.injectQueuedPromptsAsNewTurn
         *  写入的字符串逐字节一致。 */
        private const val INTERNAL_BRIDGE_TEXT =
            "(Interrupted mid-task by a new user message. Decide based on the new " +
                "message and overall context whether the prior task should continue — do " +
                "not forget or abandon it unless the user explicitly says to stop, or the " +
                "new message makes clear it is no longer needed.)"

        // 历史上出现过的所有桥接文案，一起匹配——老版本写出的桥接消息
        // 在 restore 后也要被认出，不能漏成可见气泡。
        private val INTERNAL_BRIDGE_TEXTS = listOf(
            INTERNAL_BRIDGE_TEXT,
            "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
        )

        /** [text] 是否为任一已知桥接文案；先 trim 容忍往返编码漂移。 */
        fun isInternalBridgeText(text: String): Boolean {
            val trimmed = text.trim()
            return INTERNAL_BRIDGE_TEXTS.any { trimmed == it }
        }
    }
}

/** agent loop 运行中入队的用户提示。 */
data class QueuedPrompt(
    val id: String,
    val text: String,
    val attachments: List<InputAttachment> = emptyList(),
)

/** 工具块的执行状态机：
 *  STREAMING 输入 JSON 还在到达；PENDING JSON 齐了等执行调度；
 *  RUNNING 执行中；SUCCESS/FAILED/CANCELLED 正常终态；
 *  TIMEOUT 包装超时（与 FAILED 分开，UI 画时钟而不是错误）。 */
enum class ToolBlockStatus {
    STREAMING, PENDING, RUNNING, SUCCESS, FAILED, CANCELLED, TIMEOUT
}

/** "/" 弹出菜单里的命令行。isSkill=true 的行由已安装 Skill 合成。 */
data class SlashCommand(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    // Skill 行点按只往输入框填 /<name>；SKILL.md 的读取发生在模型侧。
    val isSkill: Boolean = false,
)

data class AssistantBlock(
    val id: String,
    val kind: String,       // "text", "tool_use", "thinking", "info"
    val content: String = "",
    val toolStatus: ToolBlockStatus? = null,
    val toolTitle: String = "",
    val toolName: String = "",
    val toolArgs: String = "",   // 原始 JSON 参数（command/path/old_string 等），UI 渲染用
    val durationMs: Long = 0L,
    val startTimeMs: Long = 0L,
    /** 浏览器动作执行时刻的页面 URL。 */
    val browserURL: String? = null,
    /** 截图 JPEG 的本地路径。 */
    val imageFilePath: String? = null,
    /** Gemini 3.x 的 thought signature，持久化路径（buildTurnParts）回写 DB 用；
     *  非 Gemini provider 与关思考的调用为 null。 */
    val thoughtSignature: String? = null,
    /** 宿主判定的通道标记：随工具回合出现的文字是过程说明，不是最终回答。 */
    val executionText: Boolean = false,
    /** 宿主加工后的生效参数；raw toolArgs 与落库 input 保持原文。 */
    val executionArgs: String? = null,
) {
    val isText: Boolean get() = kind == "text"

    /** 会话渲染器的呈现通道——把「这段文本是回答还是过程」的判定收在
     *  块结构旁边，免得每个屏各自猜。 */
    internal fun presentationChannel(): NovexPresentationChannel = when {
        kind == "thinking" -> NovexPresentationChannel.THINKING
        kind == "tool_use" -> NovexPresentationChannel.TOOL
        kind == "info" -> NovexPresentationChannel.INFO
        executionText -> NovexPresentationChannel.PROCESS_TEXT
        else -> NovexPresentationChannel.FORMAL_ANSWER
    }
}

/** 稳定的呈现通道枚举；这不是 provider 协议。 */
internal enum class NovexPresentationChannel {
    FORMAL_ANSWER,
    PROCESS_TEXT,
    THINKING,
    TOOL,
    INFO,
}

/** 可见回答的统一投影：只拼非过程文本块；原始/导出/provider 内容不受影响。 */
internal fun formalAssistantText(blocks: List<AssistantBlock>, fallback: String): String =
    if (blocks.isEmpty()) fallback else blocks.filter { it.isText && !it.executionText }
        .joinToString("\n\n") { it.content }
