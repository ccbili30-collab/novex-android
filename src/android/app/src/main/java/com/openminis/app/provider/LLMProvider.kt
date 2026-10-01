package com.openminis.app.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.LLMError
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMModel
import novex.android.data.model.LLMResponse
import novex.android.data.model.LLMStreamChunk
import novex.android.data.model.ThinkingLevel

/**
 * 供应商接入的公共接口（P4 骨架耦合件：血统清剿 P3.7 就地真重写文件内容，
 * **接口签名面整体冻结**——成员名/类型/默认值即全仓消费方与
 * NovexTransportProvider 的依赖面，搬包留给 P4）。
 *
 * 结构分三层：
 *  1. 公共入口 [sendMessage]/[streamMessage]——全部调用方（agent 循环、
 *     兜底、快测、模型用量…）只用这两个；默认实现做且只做一次思考档
 *     钳制，然后把钳好的档位交给第 2 层；
 *  2. 供应商实现面 [sendMessageClamped]/[streamMessageClamped]——实现方
 *     只覆写这两个；收到的 thinkingLevel 已按当前 [model] 的目录天花板
 *     钳过，实现**不得**再钳；
 *  3. 档位钳制 [clampThinkingLevel] 与输出上限的取值协议。
 */
interface LLMProvider {

    val name: String

    var model: LLMModel

    /**
     * [T-android-thinking-level-arch] 按当前 [model] 目录天花板（rank 口径）
     * 钳请求档位。钳制做在结构层（公共入口默认实现）而不是每个实现各自
     * 记得做：任何代码路径都发不出越界档位——兜底流中途换 [model] 的，
     * 自动按**新**模型的上限重钳。对齐 iOS AgentProvider.streamAgentMessage
     * → …Clamped 的分层。
     */
    fun clampThinkingLevel(
        level: ThinkingLevel,
    ): ThinkingLevel {
        val ceiling = model.catalogMaxThinkingLevel
        return if (ceiling.rank < level.rank) ceiling else level
    }

    /**
     * [T-android-thinking-level-arch] 公共入口（非流式）。不被实现覆写：
     * 钳一次，转 [sendMessageClamped]。
     */
    suspend fun sendMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): LLMResponse = sendMessageClamped(
        messages = messages,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        temperature = temperature,
        imageParts = imageParts,
        tools = tools,
        thinkingLevel = clampThinkingLevel(thinkingLevel),
    )

    /**
     * [T-stream-stall-watchdog] 公共入口（流式）+[failOnStreamStall] 看门狗
     * 的唯一挂载点：一处挂全供应商生效，看门狗的 NetworkError 进既有自动
     * 重试链——中转收下请求然后装死，5 分钟后断线，不再把聊天挂上大半个
     * 小时（conversation-f899bf05：51 分钟空洞）。
     */
    fun streamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double? = null,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): Flow<LLMStreamChunk> = streamMessageClamped(
        messages = messages,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        temperature = temperature,
        imageParts = imageParts,
        tools = tools,
        thinkingLevel = clampThinkingLevel(thinkingLevel),
    ).failOnStreamStall(name)

    /** 供应商实现面（非流式）——实现方覆写这个而非 [sendMessage]。 */
    suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse

    /** 供应商实现面（流式）。 */
    fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk>

    /**
     * 给定模型的生效输出 token 上限。取值优先级：model.maxOutputTokens >
     * 供应商级缺省 [defaultMaxOutputTokens]。dynamicMaxTokens() 的上界。
     */
    fun effectiveMaxOutputTokens(
        model: LLMModel,
    ): Int = model.maxOutputTokens ?: defaultMaxOutputTokens

    /** model.maxOutputTokens 未知时的供应商级回落。 */
    val defaultMaxOutputTokens: Int get() = 16_384

    /**
     * [T-android-tool-splits-reply-fix] 流式助手文本是否为「单块整段」形态
     * （OpenAI Chat Completions 即如此）：text 增量与 tool_calls 增量之间
     * **没有**位置关系——非流式物化永远是 {content 在前, tool_calls}——所以
     * 一次流式响应的全部 text 增量都属于先于工具块的那一个文本块。个别
     * 端点（qwen）在 tool_calls 增量之后又冲出尾随 content 块，纯属分块
     * 伪象；按时序重排会捏造 wire 格式表达不了的顺序。输出块天然有序的
     * 格式（Responses API output items、Anthropic content blocks）为
     * false——那里的到达顺序就是语义块序。
     */
    val streamTextIsMonolithic: Boolean get() = false
}

/**
 * [T-android-empty-stream-retry] 静默截断流检测（顶层算子）。
 *
 * 中转/上游不带错误状态地断开 SSE 连接时，provider 流会「正常」收尾但既无
 * 内容也无 finish reason——聊天凭空停住、不报错（用户报告原话）。iOS 在流
 * 收集层把它当瞬态错误（AIChatViewModel.isEmptyResponse →
 * LLMError.transientError）自动重试；本算子是 Android provider 层的等价物。
 *
 * 「空」的定义：无 text/thinking/reasoning/tool-call/媒体块，且无 finish
 * reason。带停止原因但没内容的流（"stop"/"end_turn"）刻意放行——那种情况
 * 归 agent 循环的「工具结果后空回应提醒」路径（ChatViewModel）管；
 * "length"/"max_tokens" 截断出来的空是合法的空。取消与抛错先于此检查传播
 * （`collect` 之后的代码只在正常收尾时执行）。
 */
fun Flow<LLMStreamChunk>.failOnSilentEmptyCompletion(providerName: String): Flow<LLMStreamChunk> = flow {
    val witness = StreamWitness()
    collect { chunk ->
        witness.observe(chunk)
        emit(chunk)
    }
    if (witness.isEmptyRun()) {
        android.util.Log.w(
            "LLMProvider",
            "$providerName: stream completed with no content and no finish reason — treating as transient upstream failure",
        )
        throw LLMError.TransientError("Server returned an empty response (connection dropped or upstream error)")
    }
}

/** 空流判定的私有记账：见过实质内容或停止原因之一即非空。 */
private class StreamWitness {

    private var contentSeen = false
    private var stopReasonSeen = false

    fun observe(chunk: LLMStreamChunk) {
        when (chunk) {
            is LLMStreamChunk.Text -> noteContent(chunk.text.isNotEmpty())
            is LLMStreamChunk.ThinkingDelta -> noteContent(chunk.text.isNotEmpty())
            is LLMStreamChunk.ReasoningContent -> noteContent(chunk.content.isNotEmpty())
            is LLMStreamChunk.ToolUseStart,
            is LLMStreamChunk.ToolInputDelta,
            is LLMStreamChunk.ToolCallComplete,
            is LLMStreamChunk.MediaAttachment -> noteContent(true)
            is LLMStreamChunk.Finished -> if (chunk.stopReason != null) stopReasonSeen = true
            else -> {}
        }
    }

    fun isEmptyRun(): Boolean = !contentSeen && !stopReasonSeen

    private fun noteContent(present: Boolean) {
        if (present) contentSeen = true
    }
}
