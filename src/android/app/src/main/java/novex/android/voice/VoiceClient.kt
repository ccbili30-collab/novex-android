package novex.android.voice

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 语音客户端引擎 + OpenAI 兼容方言的默认实现（P3.2 真重写版）。
 *
 * 架构：方言只负责「描述一次调用」——产出声明式的 [HttpCall]（URL、头表、媒体
 * 类型、载荷字节）与解析函数；唯一入口 [dispatch] 把 spec 变成 OkHttp 请求并
 * 统一做错误分类。与被删上游的模板方法骨架（builder 回调钩子 + 各自散落的
 * Request 组装）不同，此处请求组装收拢为一处，方言无可绕过的旁路。
 *
 * 转写路由按 [AsrLane] 三岔：chat 多模态机型走对话端点、专用 ASR 机型走厂商
 * REST 端点、无该能力的厂商直接拒绝。
 */
open class VoiceClient(
    val providerId: String,
    val baseURL: String,
    val apiKey: String? = null,
) {
    companion object Engine {
        private const val LOG = "VoiceClient"

        /** TTS 响应大且慢：连接 30s、读/写各 120s。 */
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /** 语言标签归约为 ISO-639-1 主码（zh-Hans-CN → zh）；空/空白给 null。 */
        fun iso6391(tag: String?): String? =
            tag?.trim()?.takeIf(String::isNotEmpty)
                ?.split('-', '_')?.firstOrNull()?.lowercase(Locale.ROOT)

        /** 16 位小端单声道 PCM 的 WAV 封装（44 字节头 + 数据）。 */
        fun wrapPcm16InWav(pcm: ByteArray, sampleRate: Int): ByteArray =
            wavHeader(sampleRate, pcm.size) + pcm

        private fun wavHeader(rate: Int, dataLen: Int): ByteArray {
            val bytesPerFrame = 2            // 单声道 16 位
            fun le16(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            )
            fun le32(v: Int) = byteArrayOf(
                (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
            )
            return "RIFF".toByteArray(Charsets.US_ASCII) + le32(36 + dataLen) +
                "WAVE".toByteArray(Charsets.US_ASCII) +
                "fmt ".toByteArray(Charsets.US_ASCII) + le32(16) +
                le16(1) + le16(1) + le32(rate) + le32(rate * bytesPerFrame) +
                le16(bytesPerFrame) + le16(16) +
                "data".toByteArray(Charsets.US_ASCII) + le32(dataLen)
        }

        internal fun encodeBase64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(bytes)

        internal fun decodeBase64OrThrow(text: String): ByteArray =
            java.util.Base64.getMimeDecoder().decode(text)
    }

    // ------------------------------------------------------------------
    // 能力与路由
    // ------------------------------------------------------------------

    /** 本方言服务的能力面；默认两者皆备。 */
    open val offers: Set<Modality> = setOf(Modality.ASR, Modality.TTS)

    val supportsAsr: Boolean get() = Modality.ASR in offers
    val supportsTts: Boolean get() = Modality.TTS in offers

    enum class Modality { ASR, TTS }

    protected val ASR_ONLY: Set<Modality> = setOf(Modality.ASR)
    protected val TTS_ONLY: Set<Modality> = setOf(Modality.TTS)

    /** 转写路由。 */
    protected sealed interface AsrLane {
        /** 音频多模态 chat 机型：对话端点配 input_audio。 */
        data object ChatModel : AsrLane

        /** 专用 ASR 机型：厂商 REST 端点。 */
        data object Dedicated : AsrLane

        /** 本方言不做转写。 */
        data object None : AsrLane
    }

    private fun asrLaneOf(request: VoiceAsrRequest): AsrLane {
        val known = request.resolvedModel
        if (known != null && usesChatBasedAsr(known)) return AsrLane.ChatModel
        return if (supportsAsr) AsrLane.Dedicated else AsrLane.None
    }

    // ------------------------------------------------------------------
    // 两个入口
    // ------------------------------------------------------------------

    open suspend fun transcribe(request: VoiceAsrRequest): VoiceAsrResponse = when (asrLaneOf(request)) {
        AsrLane.ChatModel -> {
            AppLogger.info(LOG, "chat-based ASR for ${request.resolvedModel?.displayName}")
            chatTranscribe(request)
        }
        AsrLane.Dedicated -> parseAsr(dispatch(asrCall(request)), request)
        AsrLane.None -> throw VoiceClientException.Unsupported("${javaClass.simpleName} does not support voice input")
    }

    open suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        if (!supportsTts) throw VoiceClientException.Unsupported("${javaClass.simpleName} does not support voice output")
        return dispatch(ttsCall(request))
    }

    // ------------------------------------------------------------------
    // 方言描述面：默认 OpenAI 形状
    // ------------------------------------------------------------------

    /** 默认基址（去掉尾斜杠）；需要剥段的方言覆盖。 */
    open fun base(): String = baseURL.trimEnd('/')

    /** 基址 + 端点路径，版本段不重复（Groq 基址已带 /v1 时不再叠一层）。 */
    protected fun join(path: String): String {
        var tail = path
        for (v in listOf("/v1", "/v2", "/v3")) {
            if (base().endsWith(v) && tail.startsWith("$v/")) {
                tail = tail.removePrefix(v)
                break
            }
        }
        return base() + tail
    }

    open fun ttsPath(): String = "/v1/audio/speech"
    open fun asrPath(): String = "/v1/audio/transcriptions"

    open fun defaultAsrModel(): String = "whisper-1"
    open fun defaultTtsModel(): String = "tts-1"
    open fun defaultTtsVoice(): String = "alloy"

    /** 鉴权头；默认 Bearer。 */
    open fun authHeaders(): Map<String, String> =
        apiKey?.takeIf(String::isNotEmpty)?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()

    /** TTS 调用描述：默认 OpenAI JSON 体。 */
    open fun ttsCall(request: VoiceTtsRequest): HttpCall = HttpCall(
        url = join(ttsPath()),
        headers = authHeaders(),
        mediaType = "application/json",
        payload = jsonOf(
            "model" to (request.model ?: defaultTtsModel()),
            "input" to request.input,
            "voice" to (request.voice ?: defaultTtsVoice()),
            "response_format" to request.responseFormat.wireValue,
            "speed" to request.speed?.toDouble(),
        ).toString().toByteArray(Charsets.UTF_8),
    )

    /** ASR 调用描述：默认 OpenAI multipart。 */
    open fun asrCall(request: VoiceAsrRequest): HttpCall {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.wav", request.audioData.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", request.model ?: defaultAsrModel())
            .apply {
                // 区域限定标签（zh-CN）会被 Whisper 系端点 400；归约为两字母主码。
                iso6391(request.language)?.let { addFormDataPart("language", it) }
                addFormDataPart("response_format", request.responseFormat.wireValue)
                request.prompt?.let { addFormDataPart("prompt", it) }
            }
            .build()
        return HttpCall(join(asrPath()), authHeaders(), body = form)
    }

    /** ASR 响应解析：默认 OpenAI JSON，回落纯文本。 */
    open fun parseAsr(payload: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val text = String(payload, Charsets.UTF_8)
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("text") }?.let { o ->
            return VoiceAsrResponse(
                text = o.getString("text"),
                language = o.optString("language").takeIf(String::isNotBlank),
                durationSeconds = if (o.has("duration")) o.optDouble("duration") else null,
            )
        }
        if (text.isNotEmpty()) return VoiceAsrResponse(text = text)   // 部分兼容端点回纯文本
        throw VoiceClientException.Parse("Empty or undecodable ASR response")
    }

    /**
     * 该机型是否走对话端点转写。默认判定专用端点：音频 chat 机型是小家族
     * （omni / gpt·audio / qwen·audio），专用 ASR 机型是开放集，必须做兜底。
     */
    open fun usesChatBasedAsr(model: LLMModel): Boolean {
        val id = model.id.lowercase()
        return when {
            "omni" in id -> true
            "audio" !in id -> false
            else -> "gpt" in id || "qwen" in id
        }
    }

    /** chat 转写的系统提示——线上常量，措辞即协议（迫使只输出逐字转写）。 */
    private val CHAT_ASR_PROMPT =
        "You are a speech-to-text transcription engine. Output ONLY the exact verbatim transcription of the audio. " +
            "No commentary, no punctuation corrections, no translations, no markdown, no extra text. " +
            "If the audio is empty or unintelligible, output an empty string. Language hint: %s."

    /** 经对话端点转写：input_audio 部件 + 逐字转写系统提示。 */
    open suspend fun chatTranscribe(request: VoiceAsrRequest): VoiceAsrResponse {
        val hint = iso6391(request.language) ?: "auto"
        val body = jsonOf(
            "model" to (request.model ?: "gpt-4o-mini-audio-preview"),
            "messages" to JSONArray()
                .put(JSONObject().put("role", "system").put("content", CHAT_ASR_PROMPT.format(hint)))
                .put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            jsonOf(
                                "type" to "input_audio",
                                "input_audio" to jsonOf(
                                    "data" to encodeBase64(request.audioData),
                                    "format" to "wav",
                                ),
                            ),
                        ),
                    ),
                ),
            "max_tokens" to 4096,
            "temperature" to 0,
        )
        val payload = dispatch(HttpCall(join("/v1/chat/completions"), authHeaders(), mediaType = "application/json", payload = body.toString().toByteArray(Charsets.UTF_8)))
        val reply = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceClientException.Parse("Undecodable chat ASR response")
        val content = reply.optJSONArray("choices")
            ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.trim().orEmpty()
        return VoiceAsrResponse(text = content)
    }

    // ------------------------------------------------------------------
    // 引擎：唯一出口
    // ------------------------------------------------------------------

    /**
     * 一次调用描述 → 字节响应。401/403 映射 Auth（凭据态，非网络态）；其余非
     * 2xx 映射 Http 并携带原始错误体（厂商原文可呈现）。
     */
    suspend fun dispatch(call: HttpCall): ByteArray = withContext(Dispatchers.IO) {
        val outgoing = Request.Builder().url(call.url)
        call.headers.forEach(outgoing::header)
        val body = call.body ?: call.payload!!.toRequestBody(call.mediaType!!.toMediaType())
        httpClient.newCall(outgoing.post(body).build()).execute().use { response ->
            val bytes = response.body?.bytes()
            when {
                response.isSuccessful -> bytes ?: ByteArray(0)
                response.code == 401 || response.code == 403 -> {
                    AppLogger.error(LOG, "voice auth rejected: HTTP ${response.code}")
                    throw VoiceClientException.Auth()
                }
                else -> {
                    AppLogger.error(LOG, "voice call rejected: HTTP ${response.code}")
                    throw VoiceClientException.Http(response.code, bytes)
                }
            }
        }
    }
}

/** 一次 HTTP 调用的声明式描述；组装收拢在 [VoiceClient.dispatch]。 */
class HttpCall(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mediaType: String? = null,
    val payload: ByteArray? = null,
    val body: okhttp3.RequestBody? = null,
)

/** 键值对 JSON 构造器；null 值跳过（不发该键）。 */
internal fun jsonOf(vararg entries: Pair<String, Any?>): JSONObject =
    JSONObject().also { o -> entries.forEach { (k, v) -> if (v != null) o.put(k, v) } }
