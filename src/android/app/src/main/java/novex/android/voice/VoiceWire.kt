package novex.android.voice

import novex.android.data.model.LLMModel
import org.json.JSONObject

/**
 * 语音子系统的跨厂商词汇（P3.2 真重写版）：请求/响应值类型与错误分类。
 *
 * 命名沿「ASR=音频进文本出、TTS=文本进音频出」的能力轴，而不是上游的
 * input/output 轴——消费方（识别引擎/朗读播放器/快速测试）各自只用半边，
 * 能力轴让调用点的读法自解释。
 */

// ---- 线上格式词汇 --------------------------------------------------------------

/** 转写结果的载体格式（Whisper 系端点的 response_format 值）。 */
enum class VoiceAsrFormat(val wireValue: String) { JSON("json"), TEXT("text"), SRT("srt"), VTT("vtt") }

/** 合成音频的容器格式。 */
enum class VoiceTtsFormat(val wireValue: String) { MP3("mp3"), OPUS("opus"), WAV("wav"), AAC("aac") }

// ---- 请求/响应 ----------------------------------------------------------------

/** 一次转写（ASR）：音频进、文本出。 */
data class VoiceAsrRequest(
    /** 录音器产出的 16 kHz 单声道 WAV（或厂商可接受的其他音频）。 */
    val audioData: ByteArray,
    /** null = 交给厂商自动检测口语语言。 */
    val language: String? = null,
    val model: String? = null,
    /** 偏置提示，改善领域词识别。 */
    val prompt: String? = null,
    val responseFormat: VoiceAsrFormat = VoiceAsrFormat.JSON,
    /** 已解析的模型条目——供客户端路由 chat 型转写机型。 */
    val resolvedModel: LLMModel? = null,
) {
    // 含 ByteArray 的请求值对象：身份 equals 足够。
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** 一次合成（TTS）：文本进、音频出。 */
data class VoiceTtsRequest(
    val input: String,
    val voice: String? = null,
    /** 0.25 ~ 4.0；null = 1.0（厂商默认语速）。 */
    val speed: Float? = null,
    val model: String? = null,
    val responseFormat: VoiceTtsFormat = VoiceTtsFormat.MP3,
)

data class VoiceAsrResponse(
    val text: String,
    val language: String? = null,
    val durationSeconds: Double? = null,
)

// ---- 错误分类 ------------------------------------------------------------------

/**
 * 语音客户端的错误分类。分类是消费方的归因依据：Auth 不进网络重试、Parse 是
 * 响应外壳问题（换端点也没用）、Http 带原始错误体供 UI 呈现厂商原文。
 */
sealed class VoiceClientException(message: String) : Exception(message) {
    class Unsupported(detail: String) : VoiceClientException("Unsupported: $detail")

    class Parse(detail: String) : VoiceClientException("Parse failed: $detail")

    class Auth : VoiceClientException("Authentication failed, please check the API key")

    class NoAudioData : VoiceClientException("No audio data")

    class Http(val code: Int, val body: ByteArray?) : VoiceClientException(rejectionText(code, body))

    companion object {
        /** HTTP 拒绝的人话描述：厂商错误体优先，404 附自查提示，兜底状态码。 */
        private fun rejectionText(code: Int, body: ByteArray?): String = when {
            serverMessage(body) != null -> "Voice endpoint rejected the call (HTTP $code): ${serverMessage(body)}"
            code == 404 -> "HTTP 404 from voice endpoint — the base URL or model id is likely wrong"
            else -> "Voice endpoint rejected the call (HTTP $code)"
        }

        /** 从 JSON/文本错误体抽取厂商消息；取不到给 null。 */
        fun serverMessage(body: ByteArray?): String? {
            val text = rawBody(body) ?: return null
            return jsonHint(text) ?: plainHint(text)
        }

        private fun rawBody(body: ByteArray?): String? =
            body?.takeIf(ByteArray::isNotEmpty)
                ?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }

        /** JSON 外壳的三处候选：error.message → error 串 → message 键。 */
        private fun jsonHint(text: String): String? {
            val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return null
            parsed.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)?.let { return it }
            parsed.optString("error").takeIf(String::isNotBlank)?.let { return it }
            parsed.optString("message").takeIf(String::isNotBlank)?.let { return it }
            return null
        }

        /** 非 JSON 但足够短的文本直通（截断上限 200 字，防整页 HTML 进错误串）。 */
        private fun plainHint(text: String): String? =
            text.trim().takeIf { it.isNotEmpty() && it.length < 200 }
    }
}
