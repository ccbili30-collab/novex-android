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
 * OpenAI 兼容形态的语音客户端基座（P3.2 自有实现，替换上游 provider/voice 包的
 * 基类件）。实现 /v1/audio/transcriptions（ASR）与 /v1/audio/speech（TTS）两
 * 端点；各厂商子类覆盖钩子以适配非 OpenAI 的请求/响应外壳。
 * `transcribe`/`synthesize` 保持 open：响应外壳不同的厂商（MiniMax/Doubao 把
 * 音频裹在 base64 JSON 里）整体覆盖。
 *
 * 与被替换实现的等价要点（parity 面钉在 novex.android.voice 的假服务器测试）：
 *  - 超时：连接 30s、读/写 120s（TTS 响应大且慢）；
 *  - 401/403 → Auth 分类（不进网络重试语义）；其余非 2xx → Http(code, body)，
 *    消息按错误体抽取（error.message / error / message / 短文本直通），404 附
 *    「检查 Base URL 与模型名」人话提示；
 *  - Base64 走 java.util（编码无换行等价 NO_WRAP；解码用 MIME 解码器接受换行，
 *    对齐 android.util.Base64.DEFAULT 的宽容面）。
 */
open class VoiceClient(
    val providerId: String,
    val baseURL: String,
    val apiKey: String? = null,
) {
    companion object {
        const val TAG = "VoiceClient"

        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /**
         * 语言标签（如 "zh-CN"、"zh-Hans-CN"、"en"）归约为 ISO-639-1 两字母主码
         * （"zh"、"en"）——Whisper 期望的形态。
         */
        fun iso6391(tag: String?): String? {
            val trimmed = tag?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return trimmed.split('-', '_').firstOrNull()?.lowercase(Locale.ROOT)
        }

        /** 把 16 位小端单声道 PCM 包进最小 WAV 容器。 */
        fun wrapPcm16InWav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcm.size
            val header = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)                       // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            return header.array() + pcm
        }

        internal fun encodeBase64(bytes: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(bytes)

        internal fun decodeBase64OrThrow(text: String): ByteArray =
            java.util.Base64.getMimeDecoder().decode(text)
    }

    // -- 入口 ---------------------------------------------------------------

    open suspend fun transcribe(request: VoiceAsrRequest): VoiceAsrResponse {
        val model = request.resolvedModel
        if (model != null && usesChatBasedAsr(model)) {
            AppLogger.info(TAG, "Using chat-based ASR for ${model.displayName}")
            return transcribeChatBased(request)
        }
        if (!supportsAsr) {
            throw VoiceClientException.Unsupported("${javaClass.simpleName} does not support voice input")
        }
        val req = buildAsrRequest(request)
        val data = executeRequest(req)
        return parseAsrResponse(data, request)
    }

    open suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        if (!supportsTts) {
            throw VoiceClientException.Unsupported("${javaClass.simpleName} does not support voice output")
        }
        val req = buildTtsRequest(request)
        return executeRequest(req)
    }

    // -- 能力位（覆盖点） -----------------------------------------------------

    open val supportsAsr: Boolean get() = true
    open val supportsTts: Boolean get() = true

    // -- 端点路径（覆盖点） ---------------------------------------------------

    open fun asrEndpointPath(): String = "/v1/audio/transcriptions"
    open fun ttsEndpointPath(): String = "/v1/audio/speech"

    // -- 默认模型 / 音色（覆盖点） --------------------------------------------

    open fun defaultAsrModel(): String = "whisper-1"
    open fun defaultTtsModel(): String = "tts-1"
    open fun defaultTtsVoice(): String = "alloy"

    // -- 请求构造（覆盖点） ---------------------------------------------------

    /** 构造 ASR 请求。默认：OpenAI multipart/form-data。 */
    open fun buildAsrRequest(request: VoiceAsrRequest): Request {
        val url = composedUrlString(asrEndpointPath())
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "voice.wav",
                request.audioData.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", request.model ?: defaultAsrModel())
        // Whisper/OpenAI 兼容端点期望 ISO-639-1 两字母码，带区域限定的
        // "zh-CN" 之类会 400。
        iso6391(request.language)?.let { multipart.addFormDataPart("language", it) }
        multipart.addFormDataPart("response_format", request.responseFormat.wireValue)
        request.prompt?.let { multipart.addFormDataPart("prompt", it) }

        val builder = Request.Builder().url(url).post(multipart.build())
        applyAuth(builder)
        return builder.build()
    }

    /** 构造 TTS 请求。默认：OpenAI JSON 体。 */
    open fun buildTtsRequest(request: VoiceTtsRequest): Request {
        val url = composedUrlString(ttsEndpointPath())
        val body = JSONObject().apply {
            put("model", request.model ?: defaultTtsModel())
            put("input", request.input)
            put("voice", request.voice ?: defaultTtsVoice())
            put("response_format", request.responseFormat.wireValue)
            request.speed?.let { put("speed", it.toDouble()) }
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyAuth(builder)
        return builder.build()
    }

    /** 解析 ASR 响应。默认：OpenAI JSON，回落纯文本。 */
    open fun parseAsrResponse(data: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val text = String(data, Charsets.UTF_8)
        runCatching {
            val obj = JSONObject(text)
            if (obj.has("text")) {
                return VoiceAsrResponse(
                    text = obj.getString("text"),
                    language = obj.optString("language").takeIf { it.isNotBlank() },
                    durationSeconds = if (obj.has("duration")) obj.optDouble("duration") else null,
                )
            }
        }
        // 部分兼容端点返回纯文本。
        if (text.isNotEmpty()) return VoiceAsrResponse(text = text)
        throw VoiceClientException.Parse("Empty or undecodable ASR response")
    }

    /** 注入鉴权头。默认：Bearer。 */
    open fun applyAuth(builder: Request.Builder) {
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: return
        builder.header("Authorization", "Bearer $key")
    }

    // -- 辅助 -----------------------------------------------------------------

    open fun effectiveBaseURL(): String = baseURL.trimEnd('/')

    /**
     * 基址与端点路径拼接，避免版本段重复（如 Groq 的基址已以 /v1 结尾而默认
     * 路径以 /v1/ 起头——朴素拼接会得到 /v1/v1/… → 404）。
     */
    fun composedUrlString(path: String): String {
        val base = effectiveBaseURL()
        var p = path
        for (version in listOf("/v1", "/v2", "/v3")) {
            if (base.endsWith(version) && p.startsWith("$version/")) {
                p = p.removePrefix(version)
                break
            }
        }
        return base + p
    }

    suspend fun executeRequest(request: Request): ByteArray = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.bytes()
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) {
                    AppLogger.error(TAG, "Voice auth failed: HTTP ${response.code}")
                    throw VoiceClientException.Auth()
                }
                AppLogger.error(TAG, "Voice request failed: HTTP ${response.code}")
                throw VoiceClientException.Http(response.code, body)
            }
            body ?: ByteArray(0)
        }
    }

    // -- chat 型 ASR（音频+文本多模态聊天机型） -------------------------------

    /**
     * 该机型是否经由 chat completions 转写而非 Whisper 式 transcriptions 端点。
     * 默认走 transcriptions 端点——音频聊天机型是可枚举的小家族（gpt*audio*、
     * qwen*audio*、*omni*）；专用 ASR 机型是开放集，必须做兜底。机型名是信号
     * ——模态位区分不了手工标注的 Whisper 与真音频聊天机型。
     */
    open fun usesChatBasedAsr(model: LLMModel): Boolean {
        val id = model.id.lowercase()
        if (id.contains("omni")) return true
        if (!id.contains("audio")) return false
        return id.contains("gpt") || id.contains("qwen")
    }

    /**
     * 经由 chat completions 转写。系统提示迫使机型只输出逐字转写，使其表现得
     * 像专用 ASR 引擎。
     */
    open suspend fun transcribeChatBased(request: VoiceAsrRequest): VoiceAsrResponse {
        val url = composedUrlString("/v1/chat/completions")
        val audioBase64 = encodeBase64(request.audioData)
        val langHint = iso6391(request.language) ?: "auto"

        val body = JSONObject().apply {
            put("model", request.model ?: "gpt-4o-mini-audio-preview")
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                "You are a speech-to-text transcription engine. Output ONLY the exact verbatim transcription of the audio. No commentary, no punctuation corrections, no translations, no markdown, no extra text. If the audio is empty or unintelligible, output an empty string. Language hint: $langHint.",
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray().put(
                                    JSONObject()
                                        .put("type", "input_audio")
                                        .put(
                                            "input_audio",
                                            JSONObject()
                                                .put("data", audioBase64)
                                                .put("format", "wav"),
                                        ),
                                ),
                            ),
                    ),
            )
            put("max_tokens", 4096)
            put("temperature", 0)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyAuth(builder)

        val data = executeRequest(builder.build())
        val obj = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceClientException.Parse("Undecodable chat ASR response")
        val text = obj.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            ?: ""
        return VoiceAsrResponse(text = text)
    }
}
