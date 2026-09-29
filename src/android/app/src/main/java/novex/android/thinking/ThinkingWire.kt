package novex.android.thinking

import com.openminis.app.data.model.ThinkingLevel

/**
 * 思考控制在线协议上的形态词汇表（P3.2 自有实现，替换上游 provider/thinking 包）。
 *
 * 每一形态都锚定一次已出货的厂商行为或文档（详见各条注释）。分层与被替换实现
 * 一致：OpenAI 兼容线真正解析落体的只有 OmitEverything / ReasoningEffort /
 * ReasoningEffortNested / DeepSeekSibling / QwenDual 五种；anthropic / gemini
 * 两家不共享 OpenAI 请求体，形态仅在此登记词表（解析器对它们各给纯函数形状，
 * 见 [ThinkingContractResolver.geminiThinkingConfig] 与 novex.model.AnthropicWire），
 * 自定义规则可通过 CustomPath 逃生舱引用全词表。
 */
sealed interface ThinkingWireFormat {

    /**
     * 什么都不发。不是「发关闭」——是整个思考键都不出现。
     *
     * Mistral（GH OpenMinis#87）：AssistantMessage 是闭 schema，请求会以
     * 422 extra_forbidden 拒掉 reasoning。Venice（GH OpenMinis#86）同类：请求级
     * additionalProperties:false，未知根键在模型分发前的 schema 校验即被拒——这
     * 就是当时 Venice 全模型失败、且关思考也没用的原因（关闭分支照样发键）。
     */
    data object OmitEverything : ThinkingWireFormat

    /**
     * 根级 `reasoning_effort: "<tier>"`（OpenAI Chat Completions 形态）。
     *
     * [offValue] 为 null 表示关闭时整个键省略——这是个承重区分：MiMo/Agnes 按
     * low/medium/high 严格枚举校验，收到 "minimal" 会拒绝整个请求，一条回复都
     * 不会有，比发关闭想防的厂商默认值更糟（iOS c5efeb1e）。
     */
    data class ReasoningEffort(val offValue: String?) : ThinkingWireFormat

    /**
     * 嵌套 `reasoning: {effort: "<tier>"}`（OpenAI Responses / OpenRouter 形态）。
     * OpenRouter 关闭时整体省略，强制思考后端才不会以
     * "Reasoning is mandatory for this endpoint" 拒掉 effort:"none"。
     */
    data class ReasoningEffortNested(val offValue: String?) : ThinkingWireFormat

    /**
     * DeepSeek V4 的 OpenAI 形态：开关与档位是根级兄弟键——
     * `{"thinking":{"type":"enabled"}, "reasoning_effort":"high"}`。
     *
     * 档位绝不能嵌进 thinking。嵌进去就成了无人认识的键且根上没有档位，V4 的
     * 每个请求都静默跑厂商默认——iOS 上约 3 个月（847822eb），Android 移植前
     * 更久（df776253）。V4 默认开思考，所以 OFF 必须显式 {"type":"disabled"}
     * 且不带档位（该端点校验的枚举里没有关闭词表）。
     */
    data object DeepSeekSibling : ThinkingWireFormat

    /**
     * Qwen/DashScope：`enable_thinking` + `thinking_budget` 同时落在根级与
     * `extra_body` 内（DashScope 读 extra_body；vLLM/SGLang 收顶层）。
     *
     * budget 必须严格小于 `max_completion_tokens`——相等同样被拒
     * （"[16384] must be greater than [16384]"，issues #35/#641），且上限逐机型
     * 不同，故按 maxTokens 相对计算（iOS a5a0de20）。
     */
    data object QwenDual : ThinkingWireFormat

    // ---- 仅登记词表、不参与 OpenAI 线解析的形态 ----

    /**
     * Anthropic `thinking:{type:…, budget_tokens:N}`。Claude 4.6+ 走 adaptive
     * thinking 并忽略旧的 enabled+budget 形态，所以世代判定有影响。
     */
    data class AnthropicThinking(val style: AnthropicThinkingStyle) : ThinkingWireFormat

    /**
     * Gemini `generationConfig.thinkingConfig.thinkingBudget`。[floor] 存在是因为
     * 必思考机型上 thinkingBudget:0 非法（2.5 Pro 回 400 INVALID_ARGUMENT）；
     * df8a823d 为未识别 id 加了 128 地板。models.dev 按机型发布 min 值。
     */
    data class GeminiBudget(val floor: Int, val canDisable: Boolean) : ThinkingWireFormat

    /** Gemini 3.x 的 `thinkingLevel` 字符串档位而非数字预算。 */
    data object GeminiThinkingLevel : ThinkingWireFormat

    /** 无档位的根级布尔开关。models.dev reasoning_options 的 "toggle"。 */
    data class BooleanToggle(val path: String) : ThinkingWireFormat

    /**
     * `extra_body` 下的嵌套布尔，如 extra_body.thinking.enabled。DeepSeek 官方端
     * 默认思考且真开关在这里；此前从未发送，官方端点一直跑默认配置
     * （GH OpenMinis#171）。
     */
    data class ExtraBodyToggle(val path: String) : ThinkingWireFormat

    /**
     * 逃生舱——自定义规则的兜底形态。
     *
     * 设计意图：Venice 类故障是「一个没预料到的端点形态」，用户可编辑的规则把
     * 「等发版」变成「30 秒自己修好」。刻意限制为点线路径 + 逐档取值——不是
     * JSONPath 也不是模板引擎——每条规则保持静态可检查、可在 trace 里解释。
     */
    data class CustomPath(
        val path: String,
        val values: Map<ThinkingLevel, String>,
        val offValue: String?,
    ) : ThinkingWireFormat
}

/** Anthropic 思考控制的形态随模型世代变化。 */
enum class AnthropicThinkingStyle {
    /** Claude 4.6+——`thinking:{type:"adaptive"}`；旧的预算形态被忽略。 */
    ADAPTIVE,

    /** 4.6 之前——`thinking:{type:"enabled", budget_tokens:N}`。 */
    BUDGET_TOKENS,
}

/**
 * 捕获的思考如何在 assistant 历史轮回放。
 *
 * 发送侧与回放侧是同一厂商合同的两半——拆开它们正是 GH OpenMinis#22 在
 * OpenAI 路径修好而 #70 在 Anthropic 路径仍坏的原因。回放判定目前落在适配器
 * （novex.android.transport）的 historyReasoningContent；此类型承载合同的
 * 回放半边词表（字段名拼写 × 时机），供自定义规则表达。
 */
data class ReasoningEchoPolicy(
    /**
     * `reasoning_content` / `reasoning` / `reasoning_text`——三种野生拼写，
     * 有时一个网关上同时出现三种（GH OpenMinis#171）。
     */
    val fieldName: String,
    val timing: Timing,
) {
    enum class Timing {
        /** 有些网关在思考激活后无条件校验（nous）。 */
        EVERY_TURN,

        /** DeepSeek 的文档要求：只有工具调用轮必须回放。 */
        AFTER_TOOL_USE_ONLY,

        /** Mistral：任何情况下都不。 */
        NEVER,
    }
}
