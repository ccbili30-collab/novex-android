package novex.android.voice

import com.openminis.app.data.model.LLMModel
import org.json.JSONObject

/**
 * 语音子系统（ASR 语音识别 / TTS 语音合成）的请求响应值类型与错误分类
 * （P3.2 自有实现，替换上游 provider/voice 包的值类型件）。
 */

/** 一次语音识别（ASR）请求：音频进、文本出。 */
data class VoiceAsrRequest(
    /** 录音器产出的 16 kHz 单声道 WAV（或厂商可接受的其他音频）。 */
    val audioData: ByteArray,
    val model: String? = null,
    /** null = 让厂商自动检测口语语言。 */
    val language: String? = null,
    val responseFormat: VoiceAsrFormat = VoiceAsrFormat.JSON,
    /** 可选的偏置提示，改善领域词识别。 */
    val prompt: String? = null,
    /** 已解析的模型条目——供厂商客户端路由 chat 型 ASR 机型。 */
    val resolvedModel: LLMModel? = null,
) {
    // ByteArray 字段：请求值对象用身份 equals 足够。
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

enum class VoiceAsrFormat(val wireValue: String) {
    JSON("json"), TEXT("text"), SRT("srt"), VTT("vtt")
}

data class VoiceAsrResponse(
    val text: String,
    val language: String? = null,
    val durationSeconds: Double? = null,
)

/** 一次语音合成（TTS）请求：文本进、音频出。 */
data class VoiceTtsRequest(
    val input: String,
    val model: String? = null,
    val voice: String? = null,
    /** 0.25 ~ 4.0；null = 1.0（厂商默认）。 */
    val speed: Float? = null,
    val responseFormat: VoiceTtsFormat = VoiceTtsFormat.MP3,
)

enum class VoiceTtsFormat(val wireValue: String) {
    MP3("mp3"), OPUS("opus"), WAV("wav"), AAC("aac")
}

/** 语音客户端的错误分类（与被替换实现的分类一一对应，消费方按类归因）。 */
sealed class VoiceClientException(message: String) : Exception(message) {
    class Unsupported(detail: String) : VoiceClientException("Unsupported: $detail")
    class Http(val code: Int, val body: ByteArray?) :
        VoiceClientException(httpMessage(code, body))
    class Parse(detail: String) : VoiceClientException("Parse failed: $detail")
    class Auth : VoiceClientException("Authentication failed, please check the API key")
    class NoAudioData : VoiceClientException("No audio data")

    companion object {
        private fun httpMessage(code: Int, body: ByteArray?): String {
            serverMessage(body)?.let { return "Request failed (HTTP $code): $it" }
            if (code == 404) {
                return "Request failed (HTTP 404) — check the provider's Base URL and model name"
            }
            return "Request failed (HTTP $code)"
        }

        /** 从 JSON/文本错误体尽力抽取厂商错误消息。 */
        fun serverMessage(body: ByteArray?): String? {
            if (body == null || body.isEmpty()) return null
            val text = runCatching { String(body, Charsets.UTF_8) }.getOrNull() ?: return null
            runCatching {
                val obj = JSONObject(text)
                (obj.optJSONObject("error")?.optString("message"))?.takeIf { it.isNotBlank() }
                    ?.let { return it }
                obj.optString("error").takeIf { it.isNotBlank() }?.let { return it }
                obj.optString("message").takeIf { it.isNotBlank() }?.let { return it }
            }
            val trimmed = text.trim()
            return trimmed.takeIf { it.isNotEmpty() && it.length < 200 }
        }
    }
}
