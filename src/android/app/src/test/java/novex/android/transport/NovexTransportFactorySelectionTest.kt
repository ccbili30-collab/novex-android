package novex.android.transport

import novex.android.data.model.LLMModel
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import novex.model.WireProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3.1e 工厂选择点：原上游 openai 包的九类实例（①Codex-OAuth ②官方直连
 * ③useResponsesAPI ④Azure ⑤前尘回退 ⑥局域网明文 ⑦OpenRouter ⑧xAI ⑨Kimi）
 * 全部构造 NovexTransportProvider——上游包已整体删除，工厂不再有任何回退实现。
 * LAN 明文政策与 P3.1c 一致：适配器请求前置预检给确定性报错（见
 * NovexTransportProviderTest 的预检用例），工厂仍照常构造适配器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovexTransportFactorySelectionTest {

    private fun instance(
        type: ProviderType = ProviderType.openAI,
        customBaseURL: String? = "https://relay.example.com",
        useResponsesAPI: Boolean = false,
        azureMode: Boolean = false,
        autoResponsesFallback: Boolean = false,
        credentialType: ProviderCredential = ProviderCredential.apiKey,
    ) = ProviderInstance(
        id = "inst-1",
        label = "中转",
        providerType = type,
        credentialType = credentialType,
        customBaseURL = customBaseURL,
        useResponsesAPI = useResponsesAPI,
        azureMode = azureMode,
        autoResponsesFallback = autoResponsesFallback,
    )

    private fun novex(instance: ProviderInstance, key: String = "relay-key") =
        ProviderFactory.create(instance, key, LLMModel.gpt4oMini) as NovexTransportProvider

    @Test
    fun `自定 base 的纯 chat 中转换管到 NovexTransportProvider`() {
        assertTrue(novex(instance()) is NovexTransportProvider)
    }

    @Test
    fun `空密钥的自定中转同样换管`() {
        assertTrue(novex(instance(), "") is NovexTransportProvider)
    }

    @Test
    fun `官方直连换管且走 chat 线官方基址`() {
        val provider = novex(instance(customBaseURL = null), "sk-key")
        assertEquals(WireProtocol.CHAT_COMPLETIONS, provider.lineProtocol())
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            provider.completionUrl().toString(),
        )
    }

    @Test
    fun `②Responses 开关走 responses 线`() {
        val provider = novex(instance(useResponsesAPI = true))
        assertEquals(WireProtocol.RESPONSES, provider.lineProtocol())
        assertEquals("https://relay.example.com/v1/responses", provider.completionUrl().toString())
    }

    @Test
    fun `④Azure 模式走 deployments 路径`() {
        val provider = novex(
            instance(
                customBaseURL = "https://x.openai.azure.com?api-version=2025-04-01-preview",
                azureMode = true,
            ),
        )
        assertEquals(
            "https://x.openai.azure.com/openai/deployments/gpt-4o-mini/chat/completions?api-version=2025-04-01-preview",
            provider.completionUrl().toString(),
        )
        // Azure 用 api-key 头，非 Bearer。
        val request = provider.buildStreamRequest(
            listOf(novex.android.data.model.LLMMessage(novex.android.data.model.LLMMessage.Role.USER, "hi")),
            null, 128, emptyList(), emptyList(), novex.android.data.model.ThinkingLevel.OFF,
        )
        assertEquals("api-key", provider.wireEndpoint(request).tokenHeader)
    }

    @Test
    fun `⑤前尘回退实例换管且构造时仍走 chat 线`() {
        val provider = novex(instance(autoResponsesFallback = true))
        assertEquals(WireProtocol.CHAT_COMPLETIONS, provider.lineProtocol())
    }

    @Test
    fun `⑥局域网明文中继仍构造适配器-预检报错政策见适配器测试`() {
        val provider = novex(instance(customBaseURL = "http://192.168.1.10:11434"), "")
        assertTrue(provider is NovexTransportProvider)
        assertFalse(provider.endpointAcceptable())
    }

    @Test
    fun `⑥本机明文中继换管到自有传输`() {
        val provider = novex(instance(customBaseURL = "http://127.0.0.1:11434"), "")
        assertTrue(provider is NovexTransportProvider)
        assertTrue(provider.endpointAcceptable())
    }

    @Test
    fun `anthropic 与 gemini 分支换管到自有传输`() {
        // [P3.1c] 两家原生协议整体切 NovexTransportProvider；上游实现已随 P3.1d 删除。
        val anthropic = ProviderFactory.create(instance(type = ProviderType.anthropic), "k", LLMModel.claudeSonnet46)
        assertTrue(anthropic is NovexTransportProvider)
        assertEquals("Anthropic", (anthropic as LLMProvider).name)

        val anthropicCustom = ProviderFactory.create(
            instance(type = ProviderType.anthropic, customBaseURL = "https://relay.example.com"), "k", LLMModel.claudeSonnet46,
        )
        assertTrue(anthropicCustom is NovexTransportProvider)

        val anthropicOAuth = ProviderFactory.create(
            instance(type = ProviderType.anthropic, credentialType = ProviderCredential.oauth), "k", LLMModel.claudeSonnet46,
        )
        assertTrue(anthropicOAuth is NovexTransportProvider)
        assertTrue((anthropicOAuth as NovexTransportProvider).isAnthropicOAuth)

        val gemini = ProviderFactory.create(instance(type = ProviderType.gemini), "k", LLMModel.gemini25Flash)
        assertTrue(gemini is NovexTransportProvider)
        assertEquals("Google", (gemini as LLMProvider).name)

        // OAuth 判定只在 OAuth 凭据时为真（API key 实例为假）。
        assertFalse((anthropic as NovexTransportProvider).isAnthropicOAuth)
    }

    @Test
    fun `⑦openRouter 分支换管并携带附加头`() {
        val provider = novex(instance(type = ProviderType.openRouter, customBaseURL = null))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", provider.completionUrl().toString())
        val headers = provider.outboundHeaders()
        assertEquals("https://github.com/ccbili30-collab/novex-android", headers["HTTP-Referer"])
        assertEquals("Minis App", headers["X-Title"])
    }

    @Test
    fun `⑧⑨xAI 与 Kimi 的 API-key 形态换管`() {
        val xai = novex(instance(type = ProviderType.xAI, customBaseURL = null))
        assertEquals("https://api.x.ai/v1/chat/completions", xai.completionUrl().toString())
        val kimi = novex(instance(type = ProviderType.kimiCode, customBaseURL = null))
        assertEquals(
            "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1/chat/completions",
            kimi.completionUrl().toString(),
        )
    }

    @Test
    fun `①Codex OAuth 工厂构造-responses 线与指纹头`() {
        // 九类实例唯一无工厂构造用例的一类：OAuth 凭据 + 无自定基址 + 有 Context
        // → Codex 模式（chatgpt.com Responses 后端 + codex 指纹头）。需要
        // Robolectric Context 读（加密回落明文的）OAuth 存储。
        val provider = ProviderFactory.create(
            instance(customBaseURL = null, credentialType = ProviderCredential.oauth),
            "unused",
            LLMModel.gpt4oMini,
            RuntimeEnvironment.getApplication(),
        ) as NovexTransportProvider
        assertEquals(WireProtocol.RESPONSES, provider.lineProtocol())
        assertTrue(provider.isCodexOAuth)
        // 聊天端点固定到 chatgpt.com 后端（basePath 只是生图线兜底）。
        assertEquals(
            "https://chatgpt.com/backend-api/codex/responses",
            provider.completionUrl().toString(),
        )
        val headers = provider.outboundHeaders()
        assertEquals("codex_cli_rs", headers["Originator"])
        assertEquals("responses=experimental", headers["Openai-Beta"])
        assertTrue(headers.containsKey("Version"))
        assertTrue((headers["User-Agent"] ?: "").contains("codex_cli_rs"))

        // 手工 bearer 令牌不走 Codex 线（chatgpt.com 后端只收真会话令牌）——存进
        // OAuth 存储后按 API-key 形态走 chat 线。
        val oauth = com.openminis.app.auth.OAuthManager.forInstance(
            RuntimeEnvironment.getApplication(),
            instance(customBaseURL = null, credentialType = ProviderCredential.oauth),
        )!!
        oauth.saveManualBearerToken("manual-bearer-token")
        val manual = ProviderFactory.create(
            instance(customBaseURL = null, credentialType = ProviderCredential.oauth),
            "unused",
            LLMModel.gpt4oMini,
            RuntimeEnvironment.getApplication(),
        ) as NovexTransportProvider
        assertEquals(WireProtocol.CHAT_COMPLETIONS, manual.lineProtocol())
        assertFalse(manual.isCodexOAuth)
        oauth.deleteManualBearerToken()
    }

    @Test
    fun `自定 UA 的中转仍换管且 UA 进出站头`() {
        val provider = ProviderFactory.create(
            instance(customBaseURL = "https://relay.example.com").apply { customUserAgent = "MyUA/2.0" },
            "k",
            LLMModel.gpt4oMini,
        )
        assertTrue(provider is NovexTransportProvider)
        assertTrue((provider as NovexTransportProvider).outboundHeaders().containsValue("MyUA/2.0"))
    }
}
