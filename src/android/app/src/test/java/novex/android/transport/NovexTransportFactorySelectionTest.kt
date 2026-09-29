package novex.android.transport

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P3.1b 工厂选择点：OpenAI 兼容中转（自定 base URL + 纯 chat completions）
 * 换管到自有传输；官方直连 / Responses / Azure / 前尘回退 / 明文局域网 /
 * 其他供应商类型仍走上游实现。
 */
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

    @Test
    fun `自定 base 的纯 chat 中转换管到 NovexTransportProvider`() {
        val provider = ProviderFactory.create(instance(), "relay-key", LLMModel.gpt4oMini)
        assertTrue(provider is NovexTransportProvider)
    }

    @Test
    fun `空密钥的自定中转同样换管`() {
        val provider = ProviderFactory.create(instance(), "", LLMModel.gpt4oMini)
        assertTrue(provider is NovexTransportProvider)
    }

    @Test
    fun `官方直连仍走上游 OpenAIProvider`() {
        val provider = ProviderFactory.create(instance(customBaseURL = null), "sk-key", LLMModel.gpt4oMini)
        assertTrue(provider is OpenAIProvider)
    }

    @Test
    fun `Responses 开关 Azure 模式 前尘回退均留在上游`() {
        assertTrue(
            ProviderFactory.create(instance(useResponsesAPI = true), "k", LLMModel.gpt4oMini) is OpenAIProvider,
        )
        assertTrue(
            ProviderFactory.create(instance(azureMode = true), "k", LLMModel.gpt4oMini) is OpenAIProvider,
        )
        assertTrue(
            ProviderFactory.create(instance(autoResponsesFallback = true), "k", LLMModel.gpt4oMini) is OpenAIProvider,
        )
    }

    @Test
    fun `明文局域网中继留在上游`() {
        val provider = ProviderFactory.create(
            instance(customBaseURL = "http://192.168.1.10:11434"),
            "", LLMModel.gpt4oMini,
        )
        assertTrue(provider is OpenAIProvider)
        assertFalse(provider is NovexTransportProvider)
    }

    @Test
    fun `本机明文中继换管到自有传输`() {
        val provider = ProviderFactory.create(
            instance(customBaseURL = "http://127.0.0.1:11434"),
            "", LLMModel.gpt4oMini,
        )
        assertTrue(provider is NovexTransportProvider)
    }

    @Test
    fun `anthropic 与 gemini 分支换管到自有传输`() {
        // [P3.1c] 两家原生协议整体切 NovexTransportProvider；上游 AnthropicProvider /
        // GeminiProvider 已随 P3.1d 删除。
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
    fun `openRouter 分支不受影响`() {
        assertTrue(
            ProviderFactory.create(
                instance(type = ProviderType.openRouter, customBaseURL = null), "k", LLMModel.orGpt4o,
            ) is OpenAIProvider,
        )
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
