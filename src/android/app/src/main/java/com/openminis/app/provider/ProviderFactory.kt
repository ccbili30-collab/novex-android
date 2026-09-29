package com.openminis.app.provider

import android.content.Context
import com.openminis.app.auth.OpenAIOAuthManager
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import novex.model.WireProtocol

object ProviderFactory {
    /**
     * Create a provider, optionally with OAuth support.
     * [context] is needed for OAuth (OpenAI/Codex、xAI、Kimi) to access encrypted
     * storage for token refresh.
     *
     * [P3.1e] 工厂全量换管：四条线协议（OpenAI 兼容 chat / Responses / anthropic /
     * gemini）九类实例（①Codex-OAuth ②官方直连 ③useResponsesAPI ④Azure ⑤前尘
     * responses 回退 ⑥局域网明文 ⑦OpenRouter ⑧xAI ⑨Kimi）全部构造自有传输适配器
     * novex.android.transport.NovexTransportProvider；上游 provider/openai/ 包已随
     * 本刀整体删除。LAN 明文政策与 P3.1c 一致：适配器请求前置预检端点安全契约
     * （仅 https/本机回环），局域网明文 http 中继以确定性中文 ProviderError 收流。
     */
    fun create(instance: ProviderInstance, apiKey: String, model: LLMModel, context: Context? = null): LLMProvider {
        // T174: route through ProviderInstance.effectiveBaseURL instead of
        // re-implementing the trim-+-endsWith dance inline. The previous
        // version did `url.endsWith("/v1")` on the raw, untrimmed string,
        // so a customBaseURL of "https://api.deepseek.com/v1/" (trailing
        // slash) failed the check and the code appended a second "/v1",
        // producing requests to "/v1//v1/chat/completions" → HTTP 404.
        // Likewise "https://api.deepseek.com/" was concatenated as-is to
        // ".../" + "/v1/chat/completions" = ".//v1/chat/completions", which
        // DeepSeek tolerated only by accident. effectiveBaseURL
        // trimEnd('/')'s the input first, so all four customBaseURL
        // shapes (no slash, trailing slash, /v1, /v1/) now collapse to
        // the same canonical "https://host/v1" string.
        val basePath = instance.effectiveBaseURL
        val provider: LLMProvider = when (instance.providerType) {
            ProviderType.anthropic -> {
                // [P3.1c 绞杀换管] Anthropic Messages 原生协议改走自有 novex.model
                // 传输（NovexTransportProvider 实现同一 LLMProvider 接口，调用面零
                // 改动）。OAuth（Claude Code）与自定中继一并换管：Bearer 鉴权、系统
                // 前缀块、CLI 指纹头在适配器内按 isAnthropicOAuth 分线。上游
                // AnthropicProvider 已随 P3.1d 删除（目录整体拆除）。
                val isOAuth = instance.credentialType == ProviderCredential.oauth
                novex.android.transport.NovexTransportProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = basePath ?: "https://api.anthropic.com",
                    customUserAgent = instance.customUserAgent,
                    instanceId = instance.id,
                    protocol = WireProtocol.ANTHROPIC_MESSAGES,
                    isAnthropicOAuth = isOAuth,
                )
            }
            ProviderType.gemini -> {
                // [P3.1c 绞杀换管] Gemini 原生协议改走自有 novex.model 传输；鉴权
                // 用 x-goog-api-key 头（等价 ?key=，且密钥不进 URL）。上游
                // GeminiProvider 已随 P3.1d 删除（目录整体拆除）；
                // 其不收自定 UA 的口径一并保留（上游原件已随 P3.1d 删除）。
                novex.android.transport.NovexTransportProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = basePath ?: "https://generativelanguage.googleapis.com/v1beta",
                    instanceId = instance.id,
                    protocol = WireProtocol.GEMINI_GENERATE_CONTENT,
                )
            }
            ProviderType.openAI -> {
                // Manual bearer token (set via Manual Bearer Token UI / imported
                // from JSON) bypasses the Codex OAuth flow entirely and is sent
                // verbatim as `Authorization: Bearer …` against api.openai.com
                // (or the user's custom base URL). Mirrors iOS LLMProviderFactory:
                // a manual token routes through the API-key (Chat Completions)
                // lane, not the OAuth (Codex Responses) lane — the chatgpt.com
                // codex backend only accepts real ChatGPT session tokens.
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null

                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && basePath == null && context != null
                ) {
                    // ① Codex OAuth mode — chatgpt.com Responses 后端 + 刷新感知令牌
                    // + codex_cli_rs 客户端指纹（含 gpt-image-2 的 codex 生图路线）。
                    // basePath 仅作生图线兜底（api.openai.com——Codex 令牌无 Images
                    // scope，401 与被替换实现同序）；聊天端点由适配器按
                    // isCodexOAuth 固定到 chatgpt.com 后端。
                    val oauthManager = OpenAIOAuthManager(context, instance.id)
                    novex.android.transport.NovexTransportProvider(
                        apiKey = "",
                        model = model,
                        basePath = "https://api.openai.com/v1",
                        instanceId = instance.id,
                        protocol = WireProtocol.RESPONSES,
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                        isCodexOAuth = true,
                        codexAccountId = oauthManager.accountId,
                    )
                } else {
                    // ② 官方直连 / ③ useResponsesAPI 自定端点 / ④ Azure / ⑤ 前尘
                    // responses 回退 / ⑥ 自定中转（含 LAN 明文——预检确定性报错）/
                    // 手工 bearer。 Responses 开关由实例的 useResponsesAPI 决定；
                    // Azure 传原始 customBaseURL（保留 ?api-version 查询，deployments
                    // 路径路由）；前尘回退在适配器内 chat 首块前失败时自动换线。
                    val effectiveKey = manualBearer ?: apiKey
                    novex.android.transport.NovexTransportProvider(
                        apiKey = effectiveKey,
                        model = model,
                        basePath = basePath ?: "https://api.openai.com/v1",
                        customUserAgent = instance.customUserAgent,
                        instanceId = instance.id,
                        protocol = if (instance.useResponsesAPI) WireProtocol.RESPONSES else WireProtocol.CHAT_COMPLETIONS,
                        azureBase = instance.customBaseURL?.takeIf { instance.azureMode },
                        allowResponsesFallback = instance.autoResponsesFallback,
                    )
                }
            }
            ProviderType.openRouter -> {
                // ⑦ OpenRouter：OpenAI 兼容 API + 附加头（HTTP-Referer / X-Title）；
                // max_tokens 键、无 stream_options、anthropic/ 前缀 cache_control
                // 均在适配器按主机分线。
                novex.android.transport.NovexTransportProvider(
                    apiKey = apiKey,
                    model = model,
                    basePath = "https://openrouter.ai/api/v1",
                    instanceId = instance.id,
                    protocol = WireProtocol.CHAT_COMPLETIONS,
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://github.com/ccbili30-collab/novex-android",
                        "X-Title" to "Minis App",
                    ),
                )
            }
            ProviderType.xAI -> {
                // ⑧ xAI：OpenAI 兼容 /v1/chat/completions。两种凭据形态：
                //   - OAuth（SuperGrok / X Premium+）：动态 bearer（刷新感知）；
                //   - 手工 API key / 手工 bearer：原样透传。
                val base = basePath ?: "https://api.x.ai/v1"
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null
                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && context != null
                ) {
                    // OAuth 但走 Chat Completions（api.x.ai 不是 Codex Responses 后端，
                    // OAuth bearer 上 codex 线会 404）。
                    val oauthManager = com.openminis.app.auth.XAIOAuthManager(context, instance.id)
                    novex.android.transport.NovexTransportProvider(
                        apiKey = "",
                        model = model,
                        basePath = base,
                        instanceId = instance.id,
                        protocol = WireProtocol.CHAT_COMPLETIONS,
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                    )
                } else {
                    novex.android.transport.NovexTransportProvider(
                        apiKey = manualBearer ?: apiKey,
                        model = model,
                        basePath = base,
                        instanceId = instance.id,
                        protocol = WireProtocol.CHAT_COMPLETIONS,
                    )
                }
            }
            ProviderType.kimiCode -> {
                // ⑨ Kimi Coding Plan — OpenAI 兼容上游。⚠️ `/v1` 是承重的：
                // /coding/chat/completions 404，只有 /coding/v1/chat/completions 可用
                // （iOS 实测）。OAuth 与手工 key 两形态同 xAI（OAuth 走 chat 线）。
                val base = basePath ?: "${com.openminis.app.auth.KimiDeviceFlow.CODING_API_BASE}/v1"
                val manualBearer = if (context != null &&
                    instance.credentialType == ProviderCredential.oauth) {
                    com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.loadManualBearerToken()
                } else null
                if (instance.credentialType == ProviderCredential.oauth && manualBearer.isNullOrEmpty()
                    && context != null
                ) {
                    val oauthManager = com.openminis.app.auth.KimiOAuthManager(context, instance.id)
                    novex.android.transport.NovexTransportProvider(
                        apiKey = "",
                        model = model,
                        basePath = base,
                        instanceId = instance.id,
                        protocol = WireProtocol.CHAT_COMPLETIONS,
                        oauthTokenProvider = {
                            oauthManager.validAccessToken()
                                ?: throw com.openminis.app.data.model.LLMError.InvalidApiKey()
                        },
                    )
                } else {
                    novex.android.transport.NovexTransportProvider(
                        apiKey = manualBearer ?: apiKey,
                        model = model,
                        basePath = base,
                        instanceId = instance.id,
                        protocol = WireProtocol.CHAT_COMPLETIONS,
                    )
                }
            }
        }
        return provider
    }
}
