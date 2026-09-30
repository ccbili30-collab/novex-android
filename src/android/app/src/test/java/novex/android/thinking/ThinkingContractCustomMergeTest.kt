package novex.android.thinking

import novex.android.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义规则合并行为（P3.2 随 provider/thinking 绞杀迁移；原上游同名
 * 测试的等价迁移——断言集原样保留）。
 *
 * 承重安全不变量：空自定义规则表必须与仅内置路径逐字节一致地解析。金表测试
 * 已钉内置输出；本文件钉 (a) 空 = 不变、(b) 自定义规则真的能覆盖。
 */
class ThinkingContractCustomMergeTest {

    @After
    fun tearDown() {
        // 绝不把缓存状态泄漏进同 JVM 的其他测试。
        ThinkingContractResolver.setAllCustomRules(emptyMap())
    }

    private fun ctx(modelId: String, instanceId: String?) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = instanceId,
        supportsReasoning = true,
        declaredEffortValues = null,
        level = ThinkingLevel.HIGH,
        maxTokens = 4096,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        offEffort = null,
    )

    @Test
    fun `empty custom rules leave the openai-compatible default untouched`() {
        ThinkingContractResolver.setAllCustomRules(emptyMap())
        val body = JSONObject()
        val trace = ThinkingContractResolver.apply(body, ctx("some-model", "inst-A"))
        // 默认 openai 兼容路径：根级 reasoning_effort @ HIGH。
        assertEquals("high", body.optString("reasoning_effort"))
        assertEquals("openai-compatible-default", trace.matchedRuleLabel)
        assertEquals(ThinkingContract.Kind.PROVIDER_TYPE_DEFAULT, trace.matchedRuleKind)
    }

    @Test
    fun `a custom OmitEverything rule wins over the built-in default`() {
        ThinkingContractResolver.setCustomRules(
            "inst-A",
            listOf(
                ThinkingContract(
                    kind = ThinkingContract.Kind.CUSTOM,
                    scope = ThinkingContract.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    label = "my-omit",
                ),
            ),
        )
        val body = JSONObject()
        val trace = ThinkingContractResolver.apply(body, ctx("some-model", "inst-A"))
        // OmitEverything ⇒ 完全没有思考键。
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("thinking"))
        assertEquals("my-omit", trace.matchedRuleLabel)
        assertEquals(ThinkingContract.Kind.CUSTOM, trace.matchedRuleKind)
    }

    @Test
    fun `a custom rule scoped to a pattern only fires for matching models`() {
        ThinkingContractResolver.setCustomRules(
            "inst-A",
            listOf(
                ThinkingContract(
                    kind = ThinkingContract.Kind.CUSTOM,
                    scope = ThinkingContract.Scope.ModelPattern("deepseek-v4*"),
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    label = "ds-omit",
                ),
            ),
        )
        // 命中机型 → 自定义规则胜出。
        val hit = JSONObject()
        assertEquals("ds-omit", ThinkingContractResolver.apply(hit, ctx("deepseek-v4-chat", "inst-A")).matchedRuleLabel)
        assertFalse(hit.has("reasoning_effort"))
        // 未命中机型 → 落入内置默认。
        val miss = JSONObject()
        val missTrace = ThinkingContractResolver.apply(miss, ctx("gpt-4o", "inst-A"))
        assertEquals("high", miss.optString("reasoning_effort"))
        assertTrue(missTrace.matchedRuleLabel != "ds-omit")
    }

    @Test
    fun `custom rules on one instance do not leak to another`() {
        ThinkingContractResolver.setCustomRules(
            "inst-A",
            listOf(
                ThinkingContract(
                    kind = ThinkingContract.Kind.CUSTOM,
                    scope = ThinkingContract.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.OmitEverything,
                    label = "a-only",
                ),
            ),
        )
        val bodyB = JSONObject()
        val traceB = ThinkingContractResolver.apply(bodyB, ctx("some-model", "inst-B"))
        assertEquals("high", bodyB.optString("reasoning_effort"))
        assertTrue(traceB.matchedRuleLabel != "a-only")
    }
}
