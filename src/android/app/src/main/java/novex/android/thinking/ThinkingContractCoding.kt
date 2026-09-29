package novex.android.thinking

import com.openminis.app.data.db.ProviderThinkingContractEntity
import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject

/**
 * 用户自定义 [ThinkingContract] 与 Room 行之间的（反）序列化（P3.2 自有实现，
 * 替换上游 provider/thinking 包的编码件；与 iOS 同名编码件对等）。
 *
 * 只有 CUSTOM 规则经此往返。[ThinkingWireFormat] 密封层级编码为小型带 tag 的
 * JSON 对象（`{"type":"reasoning_effort","offValue":"low"}`），选它而非类型化
 * 列列：新增线形态无需 schema 迁移。DB 内既有 blob 的 tag 词汇与本表逐字兼容
 * （存量自定义规则无损）。
 *
 * 刻意保持完全与防御：解码失败的行（损坏 blob、新版本写的形态）产出 wireFormat
 * 为 null 的规则——「无意见」——解析器安全落空，而不是请求中途抛异常。
 */
object ThinkingContractCoding {

    // ---- 线形态 <-> JSON ----

    fun encodeWireFormat(fmt: ThinkingWireFormat?): String? {
        if (fmt == null) return null
        val o = JSONObject()
        when (fmt) {
            is ThinkingWireFormat.OmitEverything -> o.put("type", "omit_everything")
            is ThinkingWireFormat.ReasoningEffort -> {
                o.put("type", "reasoning_effort")
                fmt.offValue?.let { o.put("offValue", it) }
            }
            is ThinkingWireFormat.ReasoningEffortNested -> {
                o.put("type", "reasoning_effort_nested")
                fmt.offValue?.let { o.put("offValue", it) }
            }
            is ThinkingWireFormat.DeepSeekSibling -> o.put("type", "deepseek_sibling")
            is ThinkingWireFormat.QwenDual -> o.put("type", "qwen_dual")
            is ThinkingWireFormat.AnthropicThinking -> {
                o.put("type", "anthropic_thinking")
                o.put("style", fmt.style.name)
            }
            is ThinkingWireFormat.GeminiBudget -> {
                o.put("type", "gemini_budget")
                o.put("floor", fmt.floor)
                o.put("canDisable", fmt.canDisable)
            }
            is ThinkingWireFormat.GeminiThinkingLevel -> o.put("type", "gemini_thinking_level")
            is ThinkingWireFormat.BooleanToggle -> {
                o.put("type", "boolean_toggle")
                o.put("path", fmt.path)
            }
            is ThinkingWireFormat.ExtraBodyToggle -> {
                o.put("type", "extra_body_toggle")
                o.put("path", fmt.path)
            }
            is ThinkingWireFormat.CustomPath -> {
                o.put("type", "custom_path")
                o.put("path", fmt.path)
                fmt.offValue?.let { o.put("offValue", it) }
                val vals = JSONObject()
                for ((lvl, v) in fmt.values) vals.put(lvl.name, v)
                o.put("values", vals)
            }
        }
        return o.toString()
    }

    fun decodeWireFormat(json: String?): ThinkingWireFormat? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            when (o.optString("type")) {
                "omit_everything" -> ThinkingWireFormat.OmitEverything
                "reasoning_effort" ->
                    ThinkingWireFormat.ReasoningEffort(o.optString("offValue", "").ifEmpty { null })
                "reasoning_effort_nested" ->
                    ThinkingWireFormat.ReasoningEffortNested(o.optString("offValue", "").ifEmpty { null })
                "deepseek_sibling" -> ThinkingWireFormat.DeepSeekSibling
                "qwen_dual" -> ThinkingWireFormat.QwenDual
                "anthropic_thinking" -> ThinkingWireFormat.AnthropicThinking(
                    runCatching { AnthropicThinkingStyle.valueOf(o.optString("style")) }
                        .getOrDefault(AnthropicThinkingStyle.ADAPTIVE),
                )
                "gemini_budget" -> ThinkingWireFormat.GeminiBudget(
                    floor = o.optInt("floor", 128),
                    canDisable = o.optBoolean("canDisable", true),
                )
                "gemini_thinking_level" -> ThinkingWireFormat.GeminiThinkingLevel
                "boolean_toggle" -> ThinkingWireFormat.BooleanToggle(o.optString("path", "thinking"))
                "extra_body_toggle" -> ThinkingWireFormat.ExtraBodyToggle(o.optString("path", "thinking.enabled"))
                "custom_path" -> {
                    val values = mutableMapOf<ThinkingLevel, String>()
                    o.optJSONObject("values")?.let { v ->
                        for (k in v.keys()) {
                            runCatching { ThinkingLevel.valueOf(k) }.getOrNull()?.let { lvl ->
                                values[lvl] = v.optString(k)
                            }
                        }
                    }
                    ThinkingWireFormat.CustomPath(
                        path = o.optString("path", ""),
                        values = values,
                        offValue = o.optString("offValue", "").ifEmpty { null },
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- ReasoningEchoPolicy <-> JSON ----

    fun encodeEcho(echo: ReasoningEchoPolicy?): String? {
        if (echo == null) return null
        return JSONObject()
            .put("fieldName", echo.fieldName)
            .put("timing", echo.timing.name)
            .toString()
    }

    fun decodeEcho(json: String?): ReasoningEchoPolicy? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            ReasoningEchoPolicy(
                fieldName = o.optString("fieldName", "reasoning_content"),
                timing = runCatching { ReasoningEchoPolicy.Timing.valueOf(o.optString("timing")) }
                    .getOrDefault(ReasoningEchoPolicy.Timing.EVERY_TURN),
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- 规则 <-> Entity ----

    fun toEntity(rule: ThinkingContract, id: String, instanceId: String, sortOrder: Int): ProviderThinkingContractEntity {
        val (kind, pattern) = when (val s = rule.scope) {
            is ThinkingContract.Scope.AllModels -> "allModels" to null
            is ThinkingContract.Scope.ModelPattern -> "modelPattern" to s.pattern
        }
        return ProviderThinkingContractEntity(
            id = id,
            providerInstanceId = instanceId,
            label = rule.label,
            scopeKind = kind,
            scopePattern = pattern,
            wireFormatJson = encodeWireFormat(rule.wireFormat),
            reasoningEchoJson = encodeEcho(rule.reasoningEcho),
            sortOrder = sortOrder,
        )
    }

    fun toRule(e: ProviderThinkingContractEntity): ThinkingContract {
        val scope = when (e.scopeKind) {
            "modelPattern" -> ThinkingContract.Scope.ModelPattern(e.scopePattern ?: "*")
            else -> ThinkingContract.Scope.AllModels
        }
        return ThinkingContract(
            kind = ThinkingContract.Kind.CUSTOM,
            scope = scope,
            wireFormat = decodeWireFormat(e.wireFormatJson),
            reasoningEcho = decodeEcho(e.reasoningEchoJson),
            label = e.label,
        )
    }
}
