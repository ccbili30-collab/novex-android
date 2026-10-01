package novex.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-provider-onboarding] 官方供应商预设的守护测试：
 *  - 智谱静态目录必须含 glm-5.3 系三个模型（官方无 /models 端点，目录
 *    只能内置，型号一个都不能发明、一个都不能少）；
 *  - 智谱基址是完整路径，预设必须以 appendV1Suffix=false 落库（/api/paas/v4
 *    被追加 /v1 即 404）；
 *  - host 判定只认官方域名（中转不误伤）。
 */
class NovexProviderPresetsTest {

    @Test
    fun `智谱静态目录内置三个旗舰 GLM 模型`() {
        val ids = NovexProviderPresets.zhipuStaticModels.map { it.id }
        assertEquals(listOf("glm-5.3", "glm-5.3-air", "glm-5.3-flash"), ids)
        // 文档明示的容量才写：glm-5.3 / glm-5.3-flash 上下文 1M、输出 128K。
        val flagship = NovexProviderPresets.zhipuStaticModels.first { it.id == "glm-5.3" }
        assertEquals(1_000_000, flagship.contextWindow)
        assertEquals(128_000, flagship.maxOutputTokens)
        assertEquals(true, flagship.supportsReasoning)
        // GLM-5.3 文档口径：reasoning_effort 取 low/high/max（思考关不掉）。
        assertEquals(listOf("low", "high", "max"), flagship.reasoningEffortValues)
    }

    @Test
    fun `智谱基址是完整路径且预设关闭 v1 追加`() {
        assertEquals("https://open.bigmodel.cn/api/paas/v4", NovexProviderPresets.ZHIPU_BASE_URL)
        // effectiveBaseURL 在 appendV1Suffix=false 时必须原样返回（根因②）。
        val instance = ProviderInstance(
            id = "preset-zhipu",
            label = NovexProviderPresets.ZHIPU_LABEL,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = NovexProviderPresets.ZHIPU_BASE_URL,
            appendV1Suffix = false,
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            instance.effectiveBaseURL,
        )
    }

    @Test
    fun `host 判定只认官方域名`() {
        assertTrue(NovexProviderPresets.isZhipuBase("https://open.bigmodel.cn/api/paas/v4"))
        assertTrue(NovexProviderPresets.isZhipuBase("https://open.bigmodel.cn/api/paas/v4/"))
        assertFalse(NovexProviderPresets.isZhipuBase("https://relay.example.com/api/paas/v4"))
        assertFalse(NovexProviderPresets.isZhipuBase(null))

        assertTrue(NovexProviderPresets.isDeepSeekBase("https://api.deepseek.com"))
        assertFalse(NovexProviderPresets.isDeepSeekBase("https://api.deepseek.com.example.org"))
        assertFalse(NovexProviderPresets.isDeepSeekBase(null))
    }

    @Test
    fun `深度求索兜底目录是官方双模型`() {
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner"),
            NovexProviderPresets.deepSeekFallbackModels.map { it.id },
        )
        assertEquals("https://api.deepseek.com", NovexProviderPresets.DEEPSEEK_BASE_URL)
        assertEquals("https://platform.deepseek.com/api_keys", NovexProviderPresets.DEEPSEEK_KEY_HELP_URL)
        assertEquals("https://open.bigmodel.cn/usercenter/apikeys", NovexProviderPresets.ZHIPU_KEY_HELP_URL)
    }
}
