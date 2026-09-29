package novex.android.voice

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 各厂商语音客户端（P3.2 自有实现，替换上游 provider/voice 包的厂商件）。每个
 * 厂商只覆盖与 OpenAI 兼容基座不同的部分：默认模型名、端点路径、请求体、响应
 * 外壳或鉴权。请求形状 / 鉴权 / 音频格式 / 错误分类逐厂商对齐被替换实现，
 * 假服务器测试钉关键厂商（Doubao TTS/ASR、OpenAI TTS 等）。
 */

// -- Groq（仅 ASR，OpenAI 兼容，只有默认机型不同） ------------------------------

class GroqVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {
    override val supportsTts: Boolean get() = false
    override fun defaultAsrModel() = "whisper-large-v3-turbo"
}

// -- 阿里百炼（OpenAI 兼容，只有默认机型不同） -----------------------------------

class AlibabaVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {
    override fun defaultAsrModel() = "paraformer-realtime-v2"
    override fun defaultTtsModel() = "cosyvoice-v2"
    override fun defaultTtsVoice() = "longxiaochun"
}

// -- xAI（ASR 端点路径不同：/v1/stt） --------------------------------------------

class XaiVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {
    override fun asrEndpointPath() = "/v1/stt"
    override fun defaultAsrModel() = "grok-stt"
    override fun defaultTtsModel() = "grok-tts-1"
    override fun defaultTtsVoice() = "eve"
}

// -- MiniMax（仅 TTS，独立请求体，base64 嵌套响应） ------------------------------

class MiniMaxVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    override val supportsAsr: Boolean get() = false

    /**
     * MiniMax 原生 TTS 端点（/v1/t2a_v2）在 API 主机根部——不在用户为聊天配的
     * /anthropic 代理路径下。剥掉尾部 /v1 与 /anthropic 段。
     */
    override fun effectiveBaseURL(): String {
        var base = super.effectiveBaseURL().trimEnd('/')
        for (suffix in listOf("/v1", "/anthropic")) {
            if (base.endsWith(suffix)) base = base.dropLast(suffix.length)
        }
        return base.trimEnd('/')
    }

    override fun ttsEndpointPath() = "/v1/t2a_v2"
    override fun defaultTtsModel() = "speech-2.8-hd"
    override fun defaultTtsVoice() = "female-shaonv"

    override fun buildTtsRequest(request: VoiceTtsRequest): Request {
        val url = composedUrlString(ttsEndpointPath())
        // MiniMax 专属形状：speed 是 0~200 的整数。
        val speedInt = ((request.speed ?: 1.0f) * 100).toInt()
        // 选择器（机型条目兼任音色）会把机型 id 放进 voice。MiniMax 的音色 id 是
        // 独立命名空间，把机型 id 当 voice_id 发会 2054 "voice id not exist"——
        // Quick Test 在 speech-2.8-hd / -turbo 上撞的正是这个。voice == model 视为
        // 「未选音色」回落默认。
        val requestedVoice = if (request.voice == request.model) null else request.voice
        val body = JSONObject().apply {
            put("model", request.model ?: defaultTtsModel())
            put("text", request.input)
            put("stream", false)
            put(
                "voice_setting",
                JSONObject()
                    .put("voice_id", requestedVoice ?: defaultTtsVoice())
                    .put("speed", speedInt)
                    .put("vol", 100)
                    .put("pitch", 0),
            )
            put(
                "audio_setting",
                JSONObject()
                    .put("sample_rate", 32000)
                    .put("bitrate", 128000)
                    .put("format", request.responseFormat.wireValue),
            )
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyAuth(builder)
        return builder.build()
    }

    // 响应：{ "audio": { "audio": "base64..." }, "base_resp": { status_code, status_msg } }
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val raw = executeRequest(buildTtsRequest(request))
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
        // 呈现 MiniMax 自家错误（base_resp）而非笼统解析失败——t2a_v2 失败时不给
        // audio、只有 base_resp。
        json?.optJSONObject("base_resp")?.let { base ->
            val code = base.optInt("status_code", 0)
            if (code != 0) {
                val msg = base.optString("status_msg").ifBlank { "unknown error" }
                throw VoiceClientException.Parse("MiniMax TTS error [$code]: $msg")
            }
        }
        // 现行 t2a_v2（api.minimaxi.com，iOS 2026-07-24 验证）在 data.audio 嵌
        // HEX 编码音频；旧部署在 audio.audio 回 base64。先试 hex 再试 base64。
        json?.optJSONObject("data")?.optString("audio")?.takeIf { it.isNotEmpty() }?.let { enc ->
            decodeHex(enc)?.let { return it }
            runCatching { decodeBase64OrThrow(enc) }.getOrNull()?.let { return it }
            throw VoiceClientException.Parse("MiniMax TTS: data.audio is neither hex nor base64")
        }
        val b64 = json?.optJSONObject("audio")?.optString("audio")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceClientException.Parse("Unexpected MiniMax TTS response format")
        return runCatching { decodeBase64OrThrow(b64) }.getOrNull()
            ?: throw VoiceClientException.Parse("Unexpected MiniMax TTS response format")
    }

    /** hex 串 → 字节；串不是合法 hex 时 null。 */
    private fun decodeHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty()) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

// -- 豆包 / 火山（TTS + ASR，X-Api-Key 鉴权，独立格式） --------------------------

class DoubaoVoiceClient(providerId: String, apiKey: String?, baseOverride: String? = null) :
    VoiceClient(providerId, baseOverride ?: "https://openspeech.bytedance.com", apiKey) {

    override fun applyAuth(builder: Request.Builder) {
        val key = apiKey?.takeIf { it.isNotEmpty() } ?: return
        builder.header("X-Api-Key", key)
    }

    // TTS（v3 单向流式）------------------------------------------------------

    override fun ttsEndpointPath() = "/api/v3/tts/unidirectional"
    override fun defaultAsrModel() = "bigmodel"
    override fun defaultTtsModel() = "zh_female_cancan_uranus_bigtts"
    override fun defaultTtsVoice() = "zh_female_cancan_uranus_bigtts"

    override fun buildTtsRequest(request: VoiceTtsRequest): Request {
        val url = composedUrlString(ttsEndpointPath())
        val raw = request.model ?: request.voice ?: defaultTtsVoice()
        val speaker = if (raw.startsWith("seed-tts-")) defaultTtsVoice() else raw
        val resourceId = if (speaker.contains("_uranus_") || speaker.startsWith("saturn_")) {
            "seed-tts-2.0"
        } else {
            "seed-tts-1.0"
        }
        val body = JSONObject().put(
            "req_params",
            JSONObject()
                .put("text", request.input)
                .put("speaker", speaker)
                .put(
                    "audio_params",
                    JSONObject()
                        .put("format", if (request.responseFormat == VoiceTtsFormat.WAV) "wav" else "mp3")
                        .put("sample_rate", 24000),
                ),
        )
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("X-Api-Resource-Id", resourceId)
            .header("Connection", "keep-alive")
        applyAuth(builder)
        return builder.build()
    }

    // v3 TTS 回 HTTP chunked：每行一帧 JSON、内嵌 base64 音频。
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val raw = executeRequest(buildTtsRequest(request))
        val out = ByteArrayOutputStream()
        for (line in String(raw, Charsets.UTF_8).split('\n')) {
            val frame = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (frame.optInt("code", -1) != 0) continue
            val b64 = frame.optString("data").takeIf { it.isNotEmpty() } ?: continue
            val chunk = runCatching { decodeBase64OrThrow(b64) }.getOrNull() ?: continue
            out.write(chunk)
        }
        val audio = out.toByteArray()
        if (audio.isEmpty()) {
            val preview = String(raw.copyOfRange(0, minOf(raw.size, 500)), Charsets.UTF_8)
            throw VoiceClientException.Parse("Doubao v3 TTS: no audio frames in response. Raw: $preview")
        }
        return audio
    }

    // ASR（v3 bigmodel flash 识别）--------------------------------------------

    override fun asrEndpointPath() = "/api/v3/auc/bigmodel/recognize/flash"

    override fun buildAsrRequest(request: VoiceAsrRequest): Request {
        val url = composedUrlString(asrEndpointPath())
        val body = JSONObject()
            .put("user", JSONObject().put("uid", "minis_user"))
            .put("audio", JSONObject().put("data", encodeBase64(request.audioData)))
            .put("request", JSONObject().put("model_name", "bigmodel"))
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("X-Api-Resource-Id", "volc.bigasr.auc_turbo")
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Sequence", "-1")
        applyAuth(builder)
        return builder.build()
    }

    // v3 ASR 响应：{ "result": { "text": "..." }, "audio_info": { duration } }
    override fun parseAsrResponse(data: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val text = json?.optJSONObject("result")?.optString("text")
            ?: throw VoiceClientException.Parse("Unexpected Doubao v3 ASR response format")
        val durationMs = json.optJSONObject("audio_info")?.optDouble("duration")
            ?.takeIf { !it.isNaN() }
        return VoiceAsrResponse(
            text = text,
            language = request.language,
            durationSeconds = durationMs?.div(1000),
        )
    }
}

// -- 讯飞 / iFlytek（ASR 走 HMAC-SHA256 签名 URL；TTS 走 WebSocket） --------------

class XunfeiVoiceClient(
    providerId: String,
    private val appId: String,
    apiKey: String,
    private val apiSecret: String,
) : VoiceClient(providerId, "https://iat-api.xfyun.cn", apiKey) {

    companion object {
        private const val DEFAULT_TTS_VOICE = "xiaoyan"
    }

    // 讯飞以签名 URL 鉴权，不是头。
    override fun applyAuth(builder: Request.Builder) {}

    override fun buildAsrRequest(request: VoiceAsrRequest): Request {
        val url = buildSignedURL(host = "iat-api.xfyun.cn", path = "/v2/iat", date = Date())
        val body = JSONObject()
            .put("header", JSONObject().put("app_id", appId).put("status", 3))
            .put(
                "parameter",
                JSONObject().put(
                    "iat",
                    JSONObject()
                        .put("domain", "iat")
                        .put("language", request.language ?: "zh_cn")
                        .put("accent", "mandarin")
                        .put(
                            "result",
                            JSONObject()
                                .put("encoding", "utf8")
                                .put("compress", "raw")
                                .put("format", "json"),
                        ),
                ),
            )
            .put(
                "payload",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("encoding", "raw")
                        .put("sample_rate", 16000)
                        .put("channels", 1)
                        .put("bit_depth", 16)
                        .put("status", 3)
                        .put("audio", encodeBase64(request.audioData)),
                ),
            )
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    override fun parseAsrResponse(data: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceClientException.Parse("Unexpected Xunfei ASR response format")
        if (json.optJSONObject("header")?.optInt("code", -1) != 0) {
            throw VoiceClientException.Parse("Unexpected Xunfei ASR response format")
        }
        val b64Text = json.optJSONObject("payload")?.optJSONObject("result")?.optString("text")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceClientException.Parse("Unexpected Xunfei ASR response format")
        val textJson = runCatching {
            JSONObject(String(decodeBase64OrThrow(b64Text), Charsets.UTF_8))
        }.getOrNull() ?: throw VoiceClientException.Parse("Unexpected Xunfei ASR response format")

        val sb = StringBuilder()
        val ws = textJson.optJSONArray("ws") ?: JSONArray()
        for (i in 0 until ws.length()) {
            val cw = ws.optJSONObject(i)?.optJSONArray("cw") ?: continue
            for (j in 0 until cw.length()) {
                cw.optJSONObject(j)?.optString("w")?.let { sb.append(it) }
            }
        }
        return VoiceAsrResponse(text = sb.toString(), language = request.language)
    }

    /**
     * 讯飞 TTS 是 WebSocket 端点（tts-api.xfyun.cn/v2/tts）流式回 base64 PCM 帧，
     * 签名方案与 ASR 相同。收齐全部帧后把 16k PCM 包进 WAV 头。
     */
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val signedUrl = buildSignedURL(host = "tts-api.xfyun.cn", path = "/v2/tts", date = Date())
            .replaceFirst("https://", "wss://")
        val voice = request.voice?.takeIf { it.isNotEmpty() } ?: DEFAULT_TTS_VOICE
        val textB64 = encodeBase64(request.input.toByteArray(Charsets.UTF_8))
        val payload = JSONObject()
            .put("common", JSONObject().put("app_id", appId))
            .put(
                "business",
                JSONObject()
                    .put("aue", "raw")
                    .put("auf", "audio/L16;rate=16000")
                    .put("vcn", voice)
                    .put("tte", "UTF8"),
            )
            .put("data", JSONObject().put("status", 2).put("text", textB64))
            .toString()

        return suspendCancellableCoroutine { cont ->
            val pcm = ByteArrayOutputStream()
            val wsRequest = Request.Builder().url(signedUrl).build()
            val socket = httpClient.newWebSocket(
                wsRequest,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(payload)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val code = json.optInt("code", 0)
                        if (code != 0) {
                            webSocket.cancel()
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    VoiceClientException.Parse(
                                        "Xunfei TTS error code $code: ${json.optString("message")}",
                                    ),
                                )
                            }
                            return
                        }
                        val dataObj = json.optJSONObject("data") ?: return
                        dataObj.optString("audio").takeIf { it.isNotEmpty() }?.let { b64 ->
                            runCatching { decodeBase64OrThrow(b64) }.getOrNull()
                                ?.let(pcm::write)
                        }
                        if (dataObj.optInt("status", 0) == 2) {   // 末帧
                            webSocket.close(1000, null)
                            if (cont.isActive) {
                                val bytes = pcm.toByteArray()
                                if (bytes.isEmpty()) {
                                    cont.resumeWithException(VoiceClientException.Parse("Xunfei TTS empty audio"))
                                } else {
                                    cont.resume(wrapPcm16InWav(bytes, sampleRate = 16000))
                                }
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                },
            )
            cont.invokeOnCancellation { socket.cancel() }
        }
    }

    // HMAC-SHA256 URL 签名 ----------------------------------------------------

    private fun buildSignedURL(host: String, path: String, date: Date): String {
        val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val dateStr = formatter.format(date)
        val signatureOrigin = "host: $host\ndate: $dateStr\nGET $path HTTP/1.1"
        val signatureB64 = hmacSha256Base64(signatureOrigin, apiSecret)
        val authOrigin = "api_key=\"${apiKey ?: ""}\", " +
            "algorithm=\"hmac-sha256\", " +
            "headers=\"host date request-line\", " +
            "signature=\"$signatureB64\""
        val authB64 = encodeBase64(authOrigin.toByteArray(Charsets.UTF_8))
        val encodedDate = URLEncoder.encode(dateStr, "UTF-8")
        return "https://$host$path?authorization=$authB64&date=$encodedDate&host=$host"
    }

    private fun hmacSha256Base64(data: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return encodeBase64(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }
}

// -- Google Gemini（原生 TTS：generateContent + AUDIO 模态） ---------------------

/**
 * Gemini TTS 非 OpenAI 兼容：POST {base}/v1beta/models/{model}:generateContent，
 * key 放 ?key=，candidates[0].content.parts[].inlineData.data 里是 base64 裸 PCM
 * （24 kHz 单声道）。PCM 包进 WAV。
 */
class GeminiVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    companion object {
        private val KNOWN_VOICES = setOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr", "Leda", "Orus")
        private fun geminiVoice(requested: String?): String {
            val v = requested?.takeIf { it.isNotEmpty() } ?: return "Kore"
            return if (v in KNOWN_VOICES) v else "Kore"
        }

        private fun sampleRate(fromMime: String): Int {
            val idx = fromMime.indexOf("rate=")
            if (idx < 0) return 24000
            return fromMime.substring(idx + 5).takeWhile { it.isDigit() }.toIntOrNull() ?: 24000
        }
    }

    override val supportsAsr: Boolean get() = false
    override fun defaultTtsModel() = "gemini-2.5-flash-preview-tts"

    override fun buildTtsRequest(request: VoiceTtsRequest): Request {
        val model = request.model ?: defaultTtsModel()
        var base = effectiveBaseURL()
        if (!base.contains("/v1beta")) base = base.trimEnd('/') + "/v1beta"
        val url = "$base/models/$model:generateContent?key=${apiKey ?: ""}"
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.input))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", geminiVoice(request.voice)),
                            ),
                        ),
                    ),
            )
        return Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val raw = executeRequest(buildTtsRequest(request))
        val json = runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceClientException.Parse("Unexpected Gemini TTS response")
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw VoiceClientException.Parse("Unexpected Gemini TTS response")
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val b64 = inline.optString("data").takeIf { it.isNotEmpty() } ?: continue
            val pcm = runCatching { decodeBase64OrThrow(b64) }.getOrNull() ?: continue
            val mime = inline.optString("mimeType").ifBlank { "audio/L16;rate=24000" }
            if (mime.lowercase().contains("wav")) return pcm
            return wrapPcm16InWav(pcm, sampleRate(mime))
        }
        throw VoiceClientException.Parse("No audio in Gemini TTS response")
    }
}

// -- ElevenLabs（TTS）REST、xi-api-key 头、回 MP3 -------------------------------

/**
 * ElevenLabs TTS。机型条目的 id 携带 ElevenLabs voice_id；固定 model_id
 * （eleven_multilingual_v2）驱动合成。POST {base}/v1/text-to-speech/{voice_id}
 * → audio/mpeg。
 */
class ElevenLabsVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    companion object {
        private const val DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM" // Rachel
        private const val MODEL_ID = "eleven_multilingual_v2"
    }

    override val supportsAsr: Boolean get() = false

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        // 选中的机型条目 id 就是 ElevenLabs voice_id。
        val voiceId = request.model?.takeIf { it.isNotEmpty() }
            ?: request.voice?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_VOICE_ID
        var base = effectiveBaseURL()
        if (!base.contains("/v1")) base += "/v1"
        val body = JSONObject()
            .put("text", request.input)
            .put("model_id", MODEL_ID)
            .put(
                "voice_settings",
                JSONObject().put("stability", 0.5).put("similarity_boost", 0.75),
            )
        val req = Request.Builder()
            .url("$base/text-to-speech/$voiceId")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("xi-api-key", apiKey ?: "")
            .header("Accept", "audio/mpeg")
            .build()
        return executeRequest(req)   // MP3 字节
    }
}

// -- Deepgram（ASR /v1/listen + TTS /v1/speak）、Token 头 ------------------------

class DeepgramVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    companion object {
        private const val DEFAULT_ASR_MODEL = "nova-2"
        private const val DEFAULT_TTS_MODEL = "aura-asteria-en"
    }

    private fun dgBase(): String {
        var base = effectiveBaseURL()
        if (!base.contains("/v1")) base += "/v1"
        return base
    }

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val model = request.model?.takeIf { it.isNotEmpty() } ?: DEFAULT_TTS_MODEL
        val req = Request.Builder()
            .url("${dgBase()}/speak?model=$model")
            .post(
                JSONObject().put("text", request.input).toString()
                    .toRequestBody("application/json".toMediaType()),
            )
            .header("Authorization", "Token ${apiKey ?: ""}")
            .build()
        return executeRequest(req)
    }

    override fun buildAsrRequest(request: VoiceAsrRequest): Request {
        val model = request.model?.takeIf { it.isNotEmpty() } ?: DEFAULT_ASR_MODEL
        var qs = "model=$model&smart_format=true"
        request.language?.takeIf { it.isNotEmpty() }?.let { qs += "&language=$it" }
        return Request.Builder()
            .url("${dgBase()}/listen?$qs")
            .post(request.audioData.toRequestBody("audio/wav".toMediaType()))
            .header("Authorization", "Token ${apiKey ?: ""}")
            .build()
    }

    override fun parseAsrResponse(data: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val transcript = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?.optJSONObject("results")
            ?.optJSONArray("channels")
            ?.optJSONObject(0)
            ?.optJSONArray("alternatives")
            ?.optJSONObject(0)
            ?.optString("transcript")
            ?: throw VoiceClientException.Parse("Unexpected Deepgram ASR response")
        return VoiceAsrResponse(text = transcript, language = request.language)
    }
}

// -- Azure TTS（REST、Ocp-Apim-Subscription-Key 鉴权） ---------------------------

class AzureTtsVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    override val supportsAsr: Boolean get() = false
    override fun defaultTtsModel() = "azure-tts"
    override fun defaultTtsVoice() = "zh-CN-XiaoxiaoNeural"

    override fun buildTtsRequest(request: VoiceTtsRequest): Request {
        val url = composedUrlString("/cognitiveservices/v1")
        val voice = request.voice ?: defaultTtsVoice()
        val parts = voice.split("-")
        val lang = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else "en-US"
        val escaped = request.input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$lang\">" +
            "<voice name=\"$voice\">$escaped</voice></speak>"
        val builder = Request.Builder()
            .url(url)
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
        apiKey?.takeIf { it.isNotEmpty() }?.let { builder.header("Ocp-Apim-Subscription-Key", it) }
        return builder.build()
    }
}

// -- 小米 MiMo（ASR + TTS 都走 /v1/chat/completions） ----------------------------

class MimoVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    companion object {
        private val API_MODELS = setOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone")
    }

    override fun defaultAsrModel() = "mimo-v2.5-asr"
    override fun defaultTtsModel() = "mimo-v2.5-tts"
    override fun defaultTtsVoice() = "mimo_default"

    // ASR ---------------------------------------------------------------------

    override suspend fun transcribe(request: VoiceAsrRequest): VoiceAsrResponse {
        val url = composedUrlString("/v1/chat/completions")
        val audioBase64 = encodeBase64(request.audioData)
        val lang = iso6391(request.language) ?: "auto"
        val body = JSONObject().apply {
            put("model", request.model ?: defaultAsrModel())
            put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray().put(
                                JSONObject()
                                    .put("type", "input_audio")
                                    .put(
                                        "input_audio",
                                        JSONObject().put("data", audioBase64).put("format", "wav"),
                                    ),
                            ),
                        ),
                ),
            )
            put("asr_options", JSONObject().put("language", lang))
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyAuth(builder)

        val data = executeRequest(builder.build())
        val json = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val text = json?.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?: throw VoiceClientException.Parse("Unexpected MiMo ASR response format")
        val seconds = json.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }
        return VoiceAsrResponse(text = text, durationSeconds = seconds)
    }

    // TTS ---------------------------------------------------------------------

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val url = composedUrlString("/v1/chat/completions")
        val rawModel = request.model ?: defaultTtsModel()
        val apiModel: String
        val voiceId: String?
        if (rawModel in API_MODELS) {
            apiModel = rawModel
            voiceId = request.voice
        } else {
            apiModel = "mimo-v2.5-tts"
            voiceId = rawModel
        }
        val audioParams = JSONObject().put("format", "wav")
        if (apiModel != "mimo-v2.5-tts-voicedesign" && voiceId != null) {
            audioParams.put("voice", voiceId)
        }
        val body = JSONObject().apply {
            put("model", apiModel)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", ""))
                    .put(JSONObject().put("role", "assistant").put("content", request.input)),
            )
            put("audio", audioParams)
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyAuth(builder)

        val data = executeRequest(builder.build())
        val b64 = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
            ?.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optJSONObject("audio")
            ?.optString("data")
            ?.takeIf { it.isNotEmpty() }
            ?: throw VoiceClientException.Parse("Unexpected MiMo TTS response format")
        return runCatching { decodeBase64OrThrow(b64) }.getOrNull()
            ?: throw VoiceClientException.Parse("Unexpected MiMo TTS response format")
    }
}

// -- OpenRouter（TTS + ASR 都走 /v1/chat/completions） ---------------------------

/**
 * OpenRouter 语音（对齐 iOS 同名厂商件：TTS 1946b0b2 / ASR 1f23fd02）。
 *
 * OpenRouter 根本没有 OpenAI 的专用 TTS 端点：POST /api/v1/audio/speech 对一切
 * 机型 id（含 openai/tts-1）都回 {"error":{"message":"Model <id> does not
 * exist","code":400}}。那个 400——端点不存在而非机型缺失——就是用户在每个
 * OpenRouter 语音条目上看到的。
 *
 * ASR 拆在两个服务「不相交机型集」的端点上：
 *   - /v1/audio/transcriptions 只收专用 ASR 机型（openai/whisper-1 可用；聊天
 *     机型发过去同样 400）；
 *   - /v1/chat/completions 配 input_audio 部件收音频聊天机型（gemini-3.6-
 *     flash、gpt-audio-mini），且明确拒绝 whisper（"is a transcription model
 *     and cannot be used with the chat/completions endpoint"）。
 *
 * 两半都无法只按 provider 类型路由——拆分按机型，OpenRouter 自家的错误文本
 * 就是规范。
 */
class OpenRouterVoiceClient(providerId: String, baseURL: String, apiKey: String?) :
    VoiceClient(providerId, baseURL, apiKey) {

    companion object {
        /**
         * OpenAI 音频预览机型发 24 kHz 单声道 PCM16，OpenRouter 原样代理该流。
         * 没有任何响应字段宣告采样率，在此固定——错了就是错音高，一耳朵可辨。
         */
        private const val PCM_SAMPLE_RATE = 24000

        /**
         * OpenAI 音频预览机型接受的音色集（其 400 原文返回 "Supported values
         * are: …"）。仅用于「认出」合法名，从不用于挑选——回落是默认音色。
         */
        private val KNOWN_VOICES = setOf(
            "alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral",
            "verse", "ballad", "ash", "sage", "marin", "cedar",
        )

        /**
         * 属于 /audio/transcriptions 端点的 id。刻意窄且基于名字：误报会把一个
         * 能用的聊天机型送进必 400 的端点。
         *
         * 每个模式都经活端点探测而非猜测——OpenRouter 不为它们发布目录：GET
         * /api/v1/models 只列聊天机型、不含任何 ASR id，而 openai/whisper-1 转写
         * 正常。已确认可用：openai/whisper-1、openai/gpt-4o-transcribe、
         * openai/gpt-4o-mini-transcribe、deepgram/nova-3。
         *
         * Deepgram 按厂商限定匹配、绝不按裸引擎名：裸 "nova-2"/"nova-3" 还会命
         * 中目录里的聊天机型 amazon/nova-2-lite-v1，把它误路由进正是本类要消除
         * 的那个 400。
         */
        fun isDedicatedTranscriptionModel(modelId: String): Boolean {
            val id = modelId.lowercase(Locale.ROOT)
            return id.contains("whisper") ||
                id.contains("transcribe") || // gpt-4o[-mini]-transcribe
                id.contains("deepgram/")
        }
    }

    // -- ASR -------------------------------------------------------------------

    /**
     * 把基类的规则反过来，反转让它泛化。基类对一小撮 chat-audio 词干做允许
     * 清单——那在兜底端点收任意第三方 ASR id 时是对的。这里兜底端点只收「专用
     * 转写机型」——可识别的小家族——默认翻转：不像转写机型的都按聊天机型处理。
     * gemini、gpt-audio、qwen-omni 及未来的音频聊天机型由此覆盖，无需第二张
     * 允许清单。
     */
    override fun usesChatBasedAsr(model: LLMModel): Boolean =
        !isDedicatedTranscriptionModel(model.id)

    // -- TTS -------------------------------------------------------------------

    /**
     * 只有 chat-audio 机型能在此产音频。
     *
     * 刻意不扩到 fish-audio 之流：它们是否经此协议在 OpenRouter 出音频未验证，
     * 给它们发 chat-audio 请求只是换一个 400。在有人确认前它们保留继承路径。
     */
    private fun usesChatAudioOutput(modelId: String): Boolean {
        val id = modelId.lowercase(Locale.ROOT)
        if (!id.contains("audio")) return false
        return id.contains("gpt") || id.contains("openai")
    }

    /**
     * 选 audio.voice 的值。
     *
     * 调用方对 [VoiceTtsRequest.voice] 的含义不统一：对「目录条目即音色」的厂商
     * （ElevenLabs voice_id、Doubao speaker）条目 id 直通，Quick Test 沿用该约定。
     * chat-audio 的机型与音色是两个轴，该约定到达时就成了
     * voice: "openai/gpt-audio"，上游回 "Invalid value: 'openai/gpt-audio'.
     * Supported values are: 'alloy', …"——藏在第一个 400 后面的第二个 400。
     *
     * 所以：认得出的音色才透传，否则回落默认而非转发必败值。OpenAI 未来加的
     * 真音色会降级到默认而不是报错——对 TTS 是更安全的方向。
     */
    private fun resolvedVoice(requested: String?, modelId: String): String {
        val want = requested?.takeIf { it.isNotEmpty() } ?: return defaultTtsVoice()
        val lowered = want.lowercase(Locale.ROOT)
        if (KNOWN_VOICES.contains(lowered)) return lowered
        AppLogger.info(
            TAG,
            "OpenRouter chat-audio: ignoring non-voice '$want' for $modelId, using ${defaultTtsVoice()}",
        )
        return defaultTtsVoice()
    }

    /**
     * 唯一出音频的路由是 POST /v1/chat/completions 的音频预览形状，三条硬约束
     * （每条都经活 API 验证）：
     *   - modalities: ["text","audio"] + audio: {voice, format}；
     *   - stream: true 是强制的——否则 "Audio output requires stream: true"；
     *   - 流式下 audio.format 只收 pcm16——wav 回 "does not support 'wav' when
     *     stream=true"。
     *
     * SSE 体是普通 chat-completions 流，每 delta 多两个字段：audio.data（base64
     * PCM16 块）与 audio.transcript。PCM 收齐后包进 WAV 容器——调用方把它喂给
     * MediaPlayer/ExoPlayer，无头 PCM 打不开。
     */
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val modelId = request.model ?: defaultTtsModel()
        if (!usesChatAudioOutput(modelId)) {
            // 非已知 chat-audio 机型——保持原行为而非瞎猜。（该路径在 OpenRouter
            // 上仍 400，但那是这些 id 的既有状态而非回归；若存在可用中继托管的
            // TTS 机型，也保住它能用。）
            return super.synthesize(request)
        }

        val url = composedUrlString("/v1/chat/completions")
        val body = JSONObject().apply {
            put("model", modelId)
            put("modalities", JSONArray().put("text").put("audio"))
            put(
                "audio",
                JSONObject()
                    .put("voice", resolvedVoice(request.voice, modelId))
                    // 流式是强制的，而流式下只收 pcm16——所以这是固定值不是选择。
                    .put("format", "pcm16"),
            )
            put("stream", true)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", request.input),
                ),
            )
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        applyAuth(builder)

        val pcm = ByteArrayOutputStream()
        val transcript = StringBuilder()
        withContext(Dispatchers.IO) {
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    // 把体读干让服务端消息进错误——一个不透明的 "HTTP 400" 正是
                    // 当初难定位的原因。
                    val errBody = response.body?.bytes()
                    if (response.code == 401 || response.code == 403) {
                        AppLogger.error(TAG, "OpenRouter voice auth failed: HTTP ${response.code}")
                        throw VoiceClientException.Auth()
                    }
                    AppLogger.error(
                        TAG,
                        "OpenRouter chat-audio failed: HTTP ${response.code} " +
                            "body=${errBody?.toString(Charsets.UTF_8).orEmpty()}",
                    )
                    throw VoiceClientException.Http(response.code, errBody)
                }
                val source = response.body?.source()
                    ?: throw VoiceClientException.Parse("OpenRouter returned an empty body")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val choices = obj.optJSONArray("choices") ?: continue
                    for (i in 0 until choices.length()) {
                        val audio = choices.optJSONObject(i)
                            ?.optJSONObject("delta")
                            ?.optJSONObject("audio")
                            ?: continue
                        audio.optString("data").takeIf { it.isNotEmpty() }?.let { b64 ->
                            runCatching { decodeBase64OrThrow(b64) }
                                .getOrNull()?.let { pcm.write(it) }
                        }
                        audio.optString("transcript").takeIf { it.isNotEmpty() }
                            ?.let { transcript.append(it) }
                    }
                }
            }
        }

        val pcmBytes = pcm.toByteArray()
        if (pcmBytes.isEmpty()) {
            throw VoiceClientException.Parse(
                "OpenRouter returned no audio data for $modelId" +
                    if (transcript.isEmpty()) "" else " (transcript: ${transcript.take(80)})",
            )
        }
        AppLogger.info(
            TAG,
            "OpenRouter chat-audio ok model=$modelId pcmBytes=${pcmBytes.size} " +
                "transcriptChars=${transcript.length}",
        )
        return wrapPcm16InWav(pcmBytes, PCM_SAMPLE_RATE)
    }
}
