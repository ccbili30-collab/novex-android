package novex.android.voice

import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.logging.AppLogger

/**
 * 配置的供应商实例 → 具体语音客户端的映射（P3.2 自有实现，替换上游
 * provider/voice 包的工厂件；判定矩阵逐分支对齐被替换实现）。
 *
 * 本产品没有独立的「语音供应商类型」：搭载在 OpenAI/Anthropic 兼容实例上的
 * 厂商（Groq、MiniMax、豆包、讯飞、阿里、MiMo…）按实例的自定 base URL 识别。
 * 实例无法服务语音时返回 null。
 *
 * 豆包 / 讯飞需要多份凭据。为不扩展凭据存储，用户在唯一 API-key 字段里以
 * ";" 连接的复合串输入（讯飞是 "appId;apiKey;apiSecret"）；[splitCompound]
 * 解包。调用方传入从仓库加密存储取出的 API key，工厂保持无存储依赖。
 */
object VoiceClientFactory {

    private const val TAG = "VoiceFactory"

    fun make(instance: ProviderInstance, apiKey: String?): VoiceClient? {
        val custom = instance.customBaseURL
        val normalizedBase = (custom ?: "").lowercase()

        return when (instance.providerType) {
            // OpenAI 兼容家族 -----------------------------------------------
            ProviderType.openAI, ProviderType.openRouter -> {
                if (instance.providerType == ProviderType.openRouter) {
                    // OpenRouter。TTS 经 chat.completions + audio 模态，不是
                    // /v1/audio/speech——该端点在那里不存在、对一切机型 id 400。
                    // ASR 按机型拆在 chat.completions 与 /v1/audio/transcriptions
                    // 两端。
                    return OpenRouterVoiceClient(
                        instance.id,
                        custom ?: "https://openrouter.ai/api",
                        apiKey,
                    )
                }
                when {
                    normalizedBase.contains("groq.com") ->
                        GroqVoiceClient(instance.id, custom ?: "https://api.groq.com/openai", apiKey)
                    normalizedBase.contains("dashscope") ->
                        AlibabaVoiceClient(instance.id, custom ?: "https://dashscope.aliyuncs.com/compatible-mode", apiKey)
                    normalizedBase.contains("minimax") ->
                        MiniMaxVoiceClient(instance.id, custom ?: "https://api.minimax.io", apiKey)
                    // 豆包 / 火山 v3：单一 API Key（新控制台）。
                    normalizedBase.contains("openspeech.bytedance") || normalizedBase.contains("volcano") -> {
                        AppLogger.info(TAG, "Doubao voice client: base=$normalizedBase keyLen=${apiKey?.length ?: -1}")
                        DoubaoVoiceClient(instance.id, apiKey)
                    }
                    // 讯飞：API-key 字段承载 "appId;apiKey;apiSecret"。
                    normalizedBase.contains("xfyun") -> {
                        val p = splitCompound(apiKey)
                        if (p.size >= 3) XunfeiVoiceClient(instance.id, p[0], p[1], p[2]) else null
                    }
                    normalizedBase.contains("xiaomimimo") ->
                        MimoVoiceClient(instance.id, custom ?: "https://api.xiaomimimo.com", apiKey)
                    normalizedBase.contains("elevenlabs") ->
                        ElevenLabsVoiceClient(instance.id, custom ?: "https://api.elevenlabs.io", apiKey)
                    normalizedBase.contains("tts.speech.microsoft.com") -> {
                        // 用户若带上了 /cognitiveservices 就剥掉——端点路径里会
                        // 重新拼上。
                        var azureBase = custom ?: "https://eastasia.tts.speech.microsoft.com"
                        if (azureBase.endsWith("/cognitiveservices")) {
                            azureBase = azureBase.removeSuffix("/cognitiveservices")
                        }
                        AzureTtsVoiceClient(instance.id, azureBase, apiKey)
                    }
                    normalizedBase.contains("deepgram") ->
                        DeepgramVoiceClient(instance.id, custom ?: "https://api.deepgram.com", apiKey)
                    else ->
                        VoiceClient(instance.id, custom ?: "https://api.openai.com", apiKey)
                }
            }

            // xAI Grok --------------------------------------------------------
            ProviderType.xAI ->
                XaiVoiceClient(instance.id, custom ?: "https://api.x.ai", apiKey)

            // Anthropic——只有 Anthropic 基址背后的 MiniMax 服务语音 ----------------
            ProviderType.anthropic ->
                if (normalizedBase.contains("minimax")) {
                    MiniMaxVoiceClient(instance.id, custom ?: "https://api.minimax.io", apiKey)
                } else {
                    null
                }

            // 原生 Gemini TTS（generateContent + AUDIO 模态，?key= 鉴权）。
            ProviderType.gemini ->
                GeminiVoiceClient(
                    instance.id,
                    custom ?: "https://generativelanguage.googleapis.com/v1beta",
                    apiKey,
                )

            // Kimi Coding Plan 不服务语音机型。
            ProviderType.kimiCode -> null
        }
    }

    /**
     * [make] 是否会为带 [apiKey] 的 [instance] 返回客户端——影子语音候选门。
     * 传真实存储 key：讯飞的复合凭据检查（"appId;apiKey;apiSecret"）依赖它。
     */
    fun supports(instance: ProviderInstance, apiKey: String?): Boolean =
        make(instance, apiKey) != null

    /**
     * 拆开以单串存储的复合凭据（"appId;key" / "appId;key;secret"）。容忍空白；
     * 丢弃空段。
     */
    fun splitCompound(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
