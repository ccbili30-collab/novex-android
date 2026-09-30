package novex.android.thinking

import novex.android.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P3.3 裁军→内置] qianchen-relay-gemini 内置席的 resolver 级测试。
 *
 * 前尘 API 中转（proxy.qianc.ltd）会把 gemini 系机型的思考参数错译成
 * Claude thinking 触发 400——原预设是用户可见的 CUSTOM 规则（gemini-* →
 * OmitEverything），裁军后收编为内置席。金表走 MockWebServer（localhost）
 * 打不中适配器的主机嗅探（`base.contains("proxy.qianc.ltd")`），所以这里
 * 直接以 [ThinkingResolveContext.isQianchenRelay] 输入 resolver，钉死座次
 * 表行为；适配器把 base URL 嗅探折叠进该标志（NovexTransportProvider
 * .isQianchenRelayHost），两层各自可测。
 */
class QianchenRelayGeminiSeatTest {

    private fun trace(
        modelId: String,
        qianchen: Boolean,
        level: ThinkingLevel = ThinkingLevel.HIGH,
        offEffort: String? = null,
        mistral: Boolean = false,
    ): Pair<JSONObject, ThinkingResolveTrace> {
        val body = JSONObject()
        val t = ThinkingContractResolver.apply(
            body,
            ThinkingResolveContext(
                modelId = modelId,
                supportsReasoning = true,
                declaredEffortValues = listOf("low", "medium", "high"),
                level = level,
                maxTokens = 8192,
                isOpenRouter = false,
                usesUnifiedReasoningEffort = false,
                isMistral = mistral,
                isDashScope = false,
                isQianchenRelay = qianchen,
                offEffort = offEffort,
            ),
        )
        return body to t
    }

    // ---- 命中面：gemini-* 完全省略思考参数 --------------------------------

    @Test
    fun `gemini model on qianchen relay omits every thinking key`() {
        for (id in listOf(
            "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
            "gemini-3-pro-preview", "gemini-flash-latest",
        )) {
            val (body, t) = trace(id, qianchen = true)
            assertEquals("$id should hit the built-in seat", "qianchen-relay-gemini", t.matchedRuleLabel)
            assertEquals(ThinkingContract.Kind.OFFICIAL_VENDOR, t.matchedRuleKind)
            assertEquals("rule", t.formatSource)
            assertTrue("$id must emit no keys: $body", body.length() == 0)
            assertTrue(t.emittedKeys.isEmpty())
        }
    }

    @Test
    fun `OFF level also omits - OmitEverything has no off path`() {
        // 关闭档位同样一个键都不发：该席位的形态就是完全省略，不存在
        // 「关闭时发 off 值」的分支（发出去同样会被中转错译成 400）。
        val (body, t) = trace("gemini-2.5-pro", qianchen = true, level = ThinkingLevel.OFF)
        assertEquals("qianchen-relay-gemini", t.matchedRuleLabel)
        assertEquals(0, body.length())
    }

    @Test
    fun `declared effort tiers do not resurrect the field`() {
        // 目录声明档位也不能把键救回来——上游预设的语义是「参数完全省略」，
        // 与「声明缺失」无关（对照 xAI 空档位守卫的口径差异）。
        val body = JSONObject()
        ThinkingContractResolver.apply(
            body,
            ThinkingResolveContext(
                modelId = "gemini-2.5-pro",
                supportsReasoning = true,
                declaredEffortValues = listOf("low", "medium", "high"),
                level = ThinkingLevel.HIGH,
                maxTokens = 8192,
                isOpenRouter = false,
                usesUnifiedReasoningEffort = false,
                isMistral = false,
                isDashScope = false,
                isQianchenRelay = true,
                offEffort = "low",
            ),
        )
        assertEquals(0, body.length())
    }

    // ---- 控制组：席位命中面不得扩大 --------------------------------------

    @Test
    fun `non-gemini model on qianchen relay falls through to the default seat`() {
        // 席位是 gemini-* 模式匹配，不是 AllModels：同一中转上的非 gemini
        // 机型保持 OpenAI 兼容默认形态（前尘中转的 400 只在 gemini 系复现）。
        val (body, t) = trace("claude-sonnet-4.6", qianchen = true, offEffort = null)
        assertEquals("openai-compatible-default", t.matchedRuleLabel)
        assertTrue(body.has("reasoning_effort"))
        assertEquals("high", body.getString("reasoning_effort"))
    }

    @Test
    fun `gemini model on any other endpoint is unchanged`() {
        // 普通端点上的 gemini 机型（目录镜像带 gemini-* id 的中转很多）
        // 绝不能被顺带省略——那会静默关掉别人家 gemini 的思考。
        val (body, t) = trace("gemini-2.5-pro", qianchen = false)
        assertEquals("openai-compatible-default", t.matchedRuleLabel)
        assertTrue("must keep reasoning_effort: $body", body.has("reasoning_effort"))
    }

    @Test
    fun `built-in rules materialize the seat only for the relay`() {
        val ctx = ThinkingResolveContext(
            modelId = "gemini-2.5-pro",
            supportsReasoning = true,
            declaredEffortValues = null,
            level = ThinkingLevel.HIGH,
            maxTokens = 8192,
            isOpenRouter = false,
            usesUnifiedReasoningEffort = false,
            isMistral = false,
            isDashScope = false,
            isQianchenRelay = true,
            offEffort = null,
        )
        val labels = ThinkingContractResolver.builtInRules(ctx).map { it.label }
        assertTrue(labels.contains("qianchen-relay-gemini"))
        // 表序即优先序：qianchen 席位必须先于 openai-native 与兜底，
        // 否则 gemini-*（非 o*/gpt-5*/qwen 前缀）虽仍会落兜底，但形态
        // 会从 OmitEverything 静默变成 ReasoningEffort。
        assertTrue(
            "seat order broken: $labels",
            labels.indexOf("qianchen-relay-gemini") < labels.indexOf("openai-compatible-default"),
        )
    }

    // ---- 与 mistral 总禁令的次序（承重事实：mistral 压一切） -------------

    @Test
    fun `mistral ban still outranks the relay seat`() {
        val (body, t) = trace("mistral-large-latest", qianchen = true, mistral = true)
        // 同 true 时 mistral 席在表首（422 extra_forbidden 压一切）。
        assertEquals("mistral-official", t.matchedRuleLabel)
        assertFalse(body.has("reasoning_effort"))
    }
}
