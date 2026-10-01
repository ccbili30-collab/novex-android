package novex.android.thinking

import novex.android.data.model.ThinkingLevel
import org.json.JSONObject


/** 持久化编码骨架：tag 必写，可选参数 null 跳过（与旧 blob 的可缺省键兼容）。 */
private fun taggedJson(tag: String, vararg extra: Pair<String, Any?>): JSONObject =
    JSONObject().put("type", tag).also { o -> extra.forEach { (k, v) -> if (v != null) o.put(k, v) } }

/**
 * 思考控制的「线形态」词表（P3.2 真重写版）。
 *
 * 设计立场：一个厂商的思考合同由三件事构成——开关写在哪、档位写在哪、关闭时写
 * 什么。本词表按这三件事给形态分组，并把每个形态的持久化编解码内聚到形态自身
 * （[tag] + [toStorage] + [fromStorage]），让「新增一种形态」只改一处，而不是
 * 在外部编码器里再添一排 when 分支。
 *
 * 分组与用途：
 *  - 静默组：端点校验闭 schema，任何思考键都不许出现（Mistral/Venice 类）；
 *  - 档位组：以 tier 字符串表达强度，根键或嵌套键两型，offValue 为空表示「关闭
 *    即整个键省略」——严格枚举端点（MiMo/Agnes）对多余档位值的容忍度是零；
 *  - 开关组：布尔或类型标记直接驱动（Qwen 双写、DeepSeek V4 兄弟键、布尔/嵌套
 *    布尔开关）；
 *  - 预算组：Anthropic/Gemini 各自的 token 预算与档位字符串（词表登记，OpenAI
 *    线不解析，由各自的形状函数消费）；
 *  - 逃生舱：用户自建的点线路径 + 逐档取值，救「没预料到的端点形态」。
 */
sealed interface ThinkingWireFormat {

    /** 持久化 tag（DB blob 与 minis-config 的 JSON 词汇，稳定不改）。 */
    val tag: String

    /** 本形态的持久化编码（含 tag 与专属参数）。 */
    fun toStorage(): JSONObject

    // ---- 静默组 ----------------------------------------------------------

    /** 端点闭 schema：思考键一个都不发。发「关闭值」同样是错。 */
    data object OmitEverything : StorageFormat {
        override val tag = "omit_everything"
    }

    // ---- 档位组 ----------------------------------------------------------

    /** tier 的载体键位置。 */
    enum class TierSite { ROOT, NESTED }

    /** 以 tier 字符串驱动：根键 reasoning_effort 或嵌套 reasoning:{effort}。 */
    sealed interface TierFormat : ThinkingWireFormat {
        val site: TierSite
        val offValue: String?
    }

    /** 根级 reasoning_effort（OpenAI Chat Completions）。 */
    data class ReasoningEffort(override val offValue: String?) : TierFormat {
        override val tag = "reasoning_effort"
        override val site = TierSite.ROOT
        override fun toStorage() = taggedJson(tag, "offValue" to offValue)
    }

    /** 嵌套 reasoning:{effort}（OpenAI Responses / OpenRouter）。 */
    data class ReasoningEffortNested(override val offValue: String?) : TierFormat {
        override val tag = "reasoning_effort_nested"
        override val site = TierSite.NESTED
        override fun toStorage() = taggedJson(tag, "offValue" to offValue)
    }

    // ---- 开关组 ----------------------------------------------------------

    /** Qwen/DashScope：enable_thinking + thinking_budget 同时写根级与 extra_body。 */
    data object QwenDual : StorageFormat {
        override val tag = "qwen_dual"
    }

    /** DeepSeek V4：thinking:{type} 与 reasoning_effort 是根级兄弟键。 */
    data object DeepSeekSibling : StorageFormat {
        override val tag = "deepseek_sibling"
    }

    /**
     * 智谱 GLM 直连（open.bigmodel.cn）：开档与 [DeepSeekSibling] 同形态
     * （thinking:{type:"enabled"} + 根级 reasoning_effort 兄弟键），差异在
     * 关档——GLM-5.3 官方文档明确 thinking.type:"disabled" 会被拒（迁移指引：
     * 改发 enabled + reasoning_effort=low），此处关闭即整个省略，交厂商默认。
     */
    data object GlmZhipuSibling : StorageFormat {
        override val tag = "glm_zhipu_sibling"
    }

    /** 无档位的根级布尔开关（点线路径自定）。 */
    data class BooleanToggle(val path: String) : StorageFormat {
        override val tag = "boolean_toggle"
        override fun toStorage() = taggedJson(tag, "path" to path)
    }

    /** extra_body 下的嵌套布尔（DeepSeek 官方端真开关所在地）。 */
    data class ExtraBodyToggle(val path: String) : StorageFormat {
        override val tag = "extra_body_toggle"
        override fun toStorage() = taggedJson(tag, "path" to path)
    }

    // ---- 预算组（词表登记；OpenAI 线不解析） ------------------------------

    /** Anthropic thinking:{type/budget_tokens}，形态随模型世代切换。 */
    data class AnthropicThinking(val style: AnthropicThinkingStyle) : StorageFormat {
        override val tag = "anthropic_thinking"
        override fun toStorage() = taggedJson(tag, "style" to style.name)
    }

    /** Gemini thinkingBudget；必思考机型 0 非法，故带地板值。 */
    data class GeminiBudget(val floor: Int, val canDisable: Boolean) : StorageFormat {
        override val tag = "gemini_budget"
        override fun toStorage() = taggedJson(tag, "floor" to floor, "canDisable" to canDisable)
    }

    /** Gemini 3.x 的 thinkingLevel 字符串档位。 */
    data object GeminiThinkingLevel : StorageFormat {
        override val tag = "gemini_thinking_level"
    }

    // ---- 逃生舱 ------------------------------------------------------------

    /** 用户自建规则的自定义点线路径；逐档取值表 + 可选关闭值。 */
    data class CustomPath(
        val path: String,
        val values: Map<ThinkingLevel, String>,
        val offValue: String?,
    ) : ThinkingWireFormat {
        override val tag = "custom_path"
        override fun toStorage(): JSONObject = taggedJson(tag, "path" to path, "offValue" to offValue)
            .put("values", JSONObject().apply { values.forEach { (lvl, v) -> put(lvl.name, v) } })
    }

    /** 静默/无参形态的公共编码。 */
    private interface StorageFormat : ThinkingWireFormat {
        override fun toStorage(): JSONObject = taggedJson(tag)
    }

    companion object {

        /**
         * 从持久化 JSON 还原形态；未知 tag、损坏结构、新版本写入的形态一律给
         * null——解析层把它当「无意见」安全落空，绝不因存量数据抛异常。
         */
        fun fromStorage(json: String?): ThinkingWireFormat? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            return when (o.optString("type")) {
                OmitEverything.tag -> OmitEverything
                "reasoning_effort" -> ReasoningEffort(o.optional("offValue"))
                "reasoning_effort_nested" -> ReasoningEffortNested(o.optional("offValue"))
                QwenDual.tag -> QwenDual
                DeepSeekSibling.tag -> DeepSeekSibling
                GlmZhipuSibling.tag -> GlmZhipuSibling
                "boolean_toggle" -> BooleanToggle(o.optString("path", "thinking"))
                "extra_body_toggle" -> ExtraBodyToggle(o.optString("path", "thinking.enabled"))
                "anthropic_thinking" -> AnthropicThinking(
                    runCatching { AnthropicThinkingStyle.valueOf(o.optString("style")) }
                        .getOrDefault(AnthropicThinkingStyle.ADAPTIVE),
                )
                "gemini_budget" -> GeminiBudget(o.optInt("floor", 128), o.optBoolean("canDisable", true))
                GeminiThinkingLevel.tag -> GeminiThinkingLevel
                "custom_path" -> CustomPath(
                    path = o.optString("path", ""),
                    values = o.levelMap("values"),
                    offValue = o.optional("offValue"),
                )
                else -> null
            }
        }

        private fun JSONObject.optional(key: String): String? =
            optString(key, "").ifEmpty { null }

        private fun JSONObject.levelMap(key: String): Map<ThinkingLevel, String> {
            val table = optJSONObject(key) ?: return emptyMap()
            return table.keys().asSequence()
                .mapNotNull { name -> runCatching { ThinkingLevel.valueOf(name) }.getOrNull()?.let { it to table.optString(name) } }
                .toMap()
        }
    }
}

/** Anthropic 思考控制的形态随模型世代切换。 */
enum class AnthropicThinkingStyle {
    /** Claude 4.6+：thinking:{type:"adaptive"}，旧预算形态被忽略。 */
    ADAPTIVE,

    /** 4.6 之前：thinking:{type:"enabled", budget_tokens:N}。 */
    BUDGET_TOKENS,
}

/**
 * 已捕获思考在 assistant 历史轮的回放合同：回放键拼写 × 回放时机。
 *
 * 发送侧与回放侧是同一厂商合同的两半，拆开各自维护正是历史上 OpenAI 路径修好
 * 而 Anthropic 路径仍坏（GH OpenMinis#22/#70）的原因。回放执行点在适配器的消息
 * 装配；本类型只承载合同声明。
 */
data class ReasoningEchoPolicy(
    /** reasoning_content / reasoning / reasoning_text——野生拼写不止一种。 */
    val fieldName: String,
    val timing: Timing,
) {
    enum class Timing {
        /** 思考激活后每轮都校验的网关（nous）。 */
        EVERY_TURN,

        /** DeepSeek 文档要求：仅工具调用轮回放。 */
        AFTER_TOOL_USE_ONLY,

        /** Mistral：任何情况都不回放。 */
        NEVER,
    }

    fun toStorage(): JSONObject = JSONObject()
        .put("fieldName", fieldName)
        .put("timing", timing.name)

    companion object {
        fun fromStorage(json: String?): ReasoningEchoPolicy? {
            if (json.isNullOrBlank()) return null
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val timing = runCatching { Timing.valueOf(o.optString("timing")) }
                .getOrDefault(Timing.EVERY_TURN)
            return ReasoningEchoPolicy(o.optString("fieldName", "reasoning_content"), timing)
        }
    }
}
