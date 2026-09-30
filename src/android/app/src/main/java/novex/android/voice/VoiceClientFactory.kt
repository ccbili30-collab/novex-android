package novex.android.voice

import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import com.openminis.app.logging.AppLogger

/**
 * 供应商实例 → 语音方言客户端的映射（P3.2 真重写版）。
 *
 * 判定模型：openAI 族实例按自定 base URL 的「中继标记」查表命中厂商方言
 * （标记表即路由表，顺序无关、互斥优先级由标记 specificity 天然给出）；其余
 * providerType 各自固定一条线。实例无法服务语音时给 null——「能不能语音」是
 * 方言问题，不是配置错误。
 *
 * 凭据口径：讯飞要三段凭据，用户以 ";" 连接的复合串存进唯一 API-key 字段，
 * [splitCompound] 解包；段数不足即视为不可用（不给半配置的方言）。
 */
object VoiceClientFactory {

    private const val LOG = "VoiceFactory"

    /** 中继标记 → 方言构造。标记互斥，查表即路由。 */
    private val relayDialects: List<RelayDialect> = listOf(
        RelayDialect("groq.com") { id, base, key -> GroqVoiceClient(id, base ?: "https://api.groq.com/openai", key) },
        RelayDialect("dashscope") { id, base, key -> AlibabaVoiceClient(id, base ?: "https://dashscope.aliyuncs.com/compatible-mode", key) },
        RelayDialect("minimax") { id, base, key -> MiniMaxVoiceClient(id, base ?: "https://api.minimax.io", key) },
        RelayDialect("openspeech.bytedance", "volcano") { id, _, key ->
            AppLogger.info(LOG, "doubao voice dialect: keyLen=${key?.length ?: -1}")
            DoubaoVoiceClient(id, key)
        },
        RelayDialect("xfyun") { id, _, key ->
            splitCompound(key).takeIf { it.size >= 3 }
                ?.let { (app, k, secret) -> XunfeiVoiceClient(id, app, k, secret) }
        },
        RelayDialect("xiaomimimo") { id, base, key -> MimoVoiceClient(id, base ?: "https://api.xiaomimimo.com", key) },
        RelayDialect("elevenlabs") { id, base, key -> ElevenLabsVoiceClient(id, base ?: "https://api.elevenlabs.io", key) },
        RelayDialect("tts.speech.microsoft.com") { id, base, key ->
            // 端点路径自带 /cognitiveservices，用户基址里重复带就剥掉。
            AzureTtsVoiceClient(id, base.orEmpty().removeSuffix("/cognitiveservices")
                .ifEmpty { "https://eastasia.tts.speech.microsoft.com" }, key)
        },
        RelayDialect("deepgram") { id, base, key -> DeepgramVoiceClient(id, base ?: "https://api.deepgram.com", key) },
    )

    private class RelayDialect(vararg val markers: String, val build: (String, String?, String?) -> VoiceClient?)

    fun make(instance: ProviderInstance, apiKey: String?): VoiceClient? {
        val customBase = instance.customBaseURL
        val markers = (customBase ?: "").lowercase()
        return when (instance.providerType) {
            ProviderType.openRouter -> OpenRouterVoiceClient(
                instance.id,
                customBase ?: "https://openrouter.ai/api",
                apiKey,
            )

            // 命中标记即定方言：方言构造返回 null（如讯飞凭据不足）就是不可用，
            // 不回落通用 OpenAI——半配置的方言比没有更糟。
            ProviderType.openAI -> {
                val relay = relayDialects.firstOrNull { markers.isNotEmpty() && it.markers.any(markers::contains) }
                if (relay != null) relay.build(instance.id, customBase, apiKey)
                else plainOpenAi(instance.id, customBase, apiKey)
            }

            ProviderType.xAI -> XaiVoiceClient(instance.id, customBase ?: "https://api.x.ai", apiKey)

            // Anthropic 线上只有 MiniMax 中继服务语音。
            ProviderType.anthropic ->
                if ("minimax" in markers) MiniMaxVoiceClient(instance.id, customBase ?: "https://api.minimax.io", apiKey)
                else null

            ProviderType.gemini -> GeminiVoiceClient(
                instance.id,
                customBase ?: "https://generativelanguage.googleapis.com/v1beta",
                apiKey,
            )

            // Kimi Coding Plan 无语音机型。
            ProviderType.kimiCode -> null
        }
    }

    private fun plainOpenAi(id: String, base: String?, key: String?) =
        VoiceClient(id, base ?: "https://api.openai.com", key)

    /** [make] 是否会产出客户端——影子语音候选门。传真实存储 key（讯飞凭据检查依赖它）。 */
    fun supports(instance: ProviderInstance, apiKey: String?): Boolean = make(instance, apiKey) != null

    /** 复合凭据拆包：容忍空白、丢弃空段。 */
    fun splitCompound(value: String?): List<String> =
        value?.split(';')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()
}
