package novex.android.voice

import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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

/**
 * 各厂商方言（P3.2 真重写版）。每个方言只声明与 OpenAI 兼容默认不同的部分：
 * 能力面、端点路径、调用体、响应外壳或鉴权。全部经由 [VoiceClient] 的
 * HttpCall/dispatch 骨架出网——方言不直接碰 OkHttp（讯飞 WebSocket 除外，
 * 其协议本身是 WS）。
 *
 * 响应解析统一「先剥厂商错误外壳、再取音频」：各家把错误放在不同位置
 * （HTTP 状态、base_resp、帧内 code、WS code），分类口径在各方言内闭环。
 */

// ---- Groq：转写专用，OpenAI 兼容，仅默认机型不同 -------------------------------

class GroqVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {
    override val offers get() = ASR_ONLY
    override fun defaultAsrModel() = "whisper-large-v3-turbo"
}

// ---- 阿里百炼：OpenAI 兼容，仅默认机型/音色不同 ---------------------------------

class AlibabaVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {
    override fun defaultAsrModel() = "paraformer-realtime-v2"
    override fun defaultTtsModel() = "cosyvoice-v2"
    override fun defaultTtsVoice() = "longxiaochun"
}

// ---- xAI：转写端点在 /v1/stt ---------------------------------------------------

class XaiVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {
    override fun asrPath() = "/v1/stt"
    override fun defaultAsrModel() = "grok-stt"
    override fun defaultTtsModel() = "grok-tts-1"
    override fun defaultTtsVoice() = "eve"
}

// ---- MiniMax：合成专用，t2a_v2 独立体 + 双响应外壳 ------------------------------

class MiniMaxVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    override val offers get() = TTS_ONLY

    /**
     * t2a_v2 挂在 API 主机根部：用户为聊天配的 /anthropic 代理路径要剥掉，
     * 连尾部 /v1 一并剥（拼接时会按版本段规则重新处理）。
     */
    override fun base(): String =
        listOf("/anthropic", "/v1").fold(super.base()) { acc, suffix ->
            if (acc.endsWith(suffix)) acc.dropLast(suffix.length).trimEnd('/') else acc
        }

    override fun ttsPath() = "/v1/t2a_v2"
    override fun defaultTtsModel() = "speech-2.8-hd"
    override fun defaultTtsVoice() = "female-shaonv"

    /** 语速是百分数整数（1.0× → 100），波动 0~200。 */
    override fun ttsCall(request: VoiceTtsRequest): HttpCall = HttpCall(
        url = join(ttsPath()),
        headers = authHeaders(),
        mediaType = "application/json",
        payload = jsonOf(
            "model" to (request.model ?: defaultTtsModel()),
            "text" to request.input,
            "stream" to false,
            "voice_setting" to jsonOf(
                "voice_id" to (voiceIdOrNull(request) ?: defaultTtsVoice()),
                "speed" to ((request.speed ?: 1.0f) * 100).toInt(),
                "vol" to 100,
                "pitch" to 0,
            ),
            "audio_setting" to jsonOf(
                "sample_rate" to 32000,
                "bitrate" to 128000,
                "format" to request.responseFormat.wireValue,
            ),
        ).toString().toByteArray(Charsets.UTF_8),
    )

    /**
     * 选择器把机型条目当音色传（voice == model）；MiniMax 两者是独立命名空间，
     * 机型 id 当 voice_id 发必回 2054 voice id not exist——视作未选，回落默认。
     */
    private fun voiceIdOrNull(request: VoiceTtsRequest): String? =
        request.voice?.takeIf { it.isNotEmpty() && it != request.model }

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val payload = dispatch(ttsCall(request))
        val envelope = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
        rejectIfReported(envelope?.optJSONObject("base_resp"))
        return audioFrom(envelope) ?: throw VoiceClientException.Parse("MiniMax TTS response carried no audio")
    }

    /** base_resp 非零码 = 厂商级失败（此时无音频）；透传码与原文。 */
    private fun rejectIfReported(baseResp: JSONObject?) {
        val code = baseResp?.optInt("status_code", 0) ?: 0
        if (code != 0) {
            val note = baseResp?.optString("status_msg")?.takeIf(String::isNotBlank) ?: "unknown error"
            throw VoiceClientException.Parse("MiniMax TTS error [$code]: $note")
        }
    }

    /**
     * 双响应外壳：现行端点在 data.audio 放 hex 编码音频；旧部署在 audio.audio
     * 放 base64。data.audio 先试 hex 再试 base64，取到即返回。
     */
    private fun audioFrom(envelope: JSONObject?): ByteArray? {
        val nested = envelope?.optJSONObject("data")?.optString("audio")?.takeIf(String::isNotEmpty)
        if (nested != null) {
            hexToBytes(nested)?.let { return it }
            runCatching { decodeBase64OrThrow(nested) }.getOrNull()?.let { return it }
            throw VoiceClientException.Parse("MiniMax data.audio is neither hex nor base64")
        }
        return envelope?.optJSONObject("audio")?.optString("audio")?.takeIf(String::isNotEmpty)
            ?.let { runCatching { decodeBase64OrThrow(it) }.getOrNull() }
    }

    private fun hexToBytes(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        return ByteArray(s.length / 2) { i ->
            val hi = Character.digit(s[i * 2], 16)
            val lo = Character.digit(s[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) or lo).toByte()
        }
    }
}

// ---- 豆包 / 火山：v3 双端点（TTS 单向流式 / ASR flash 识别）---------------------

class DoubaoVoiceClient(id: String, key: String?, baseOverride: String? = null) :
    VoiceClient(id, baseOverride ?: "https://openspeech.bytedance.com", key) {

    override fun authHeaders(): Map<String, String> =
        apiKey?.takeIf(String::isNotEmpty)?.let { mapOf("X-Api-Key" to it) }.orEmpty()

    // -- 合成 --

    override fun ttsPath() = "/api/v3/tts/unidirectional"
    override fun defaultAsrModel() = "bigmodel"
    override fun defaultTtsModel() = "zh_female_cancan_uranus_bigtts"
    override fun defaultTtsVoice() = "zh_female_cancan_uranus_bigtts"

    override fun ttsCall(request: VoiceTtsRequest): HttpCall {
        val speaker = speakerId(request)
        return HttpCall(
            url = join(ttsPath()),
            headers = authHeaders() + mapOf(
                "X-Api-Resource-Id" to resourceIdOf(speaker),
                "Connection" to "keep-alive",
            ),
            mediaType = "application/json",
            payload = jsonOf(
                "req_params" to jsonOf(
                    "text" to request.input,
                    "speaker" to speaker,
                    "audio_params" to jsonOf(
                        "format" to if (request.responseFormat == VoiceTtsFormat.WAV) "wav" else "mp3",
                        "sample_rate" to 24000,
                    ),
                ),
            ).toString().toByteArray(Charsets.UTF_8),
        )
    }

    /** seed-tts- 前缀的裸值不是有效音色，回落默认；其余透传（机型或音色 id 皆可）。 */
    private fun speakerId(request: VoiceTtsRequest): String {
        val raw = request.model ?: request.voice ?: defaultTtsVoice()
        return raw.takeUnless { it.startsWith("seed-tts-") } ?: defaultTtsVoice()
    }

    /** 资源 id 按音色代际：uranus 族与 saturn_ 前缀走 2.0，其余 1.0。 */
    private fun resourceIdOf(speaker: String): String =
        if ("_uranus_" in speaker || speaker.startsWith("saturn_")) "seed-tts-2.0" else "seed-tts-1.0"

    /**
     * 响应是 HTTP chunked 的 JSON 帧序列（每帧 base64 音频）；码非 0 的帧跳过。
     * 全程无音频按解析失败收（附响应头预览，可判读原始报错）。
     */
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val raw = dispatch(ttsCall(request))
        val audio = String(raw, Charsets.UTF_8).lineSequence()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optInt("code", -1) == 0 }
            .mapNotNull { it.optString("data").takeIf(String::isNotEmpty) }
            .mapNotNull { runCatching { decodeBase64OrThrow(it) }.getOrNull() }
            .fold(ByteArrayOutputStream()) { acc, chunk -> acc.apply { write(chunk) } }
            .toByteArray()
        if (audio.isEmpty()) {
            val head = String(raw.copyOfRange(0, minOf(raw.size, 500)), Charsets.UTF_8)
            throw VoiceClientException.Parse("Doubao v3 TTS: no audio frames in response. Raw: $head")
        }
        return audio
    }

    // -- 转写 --

    override fun asrPath() = "/api/v3/auc/bigmodel/recognize/flash"

    override fun asrCall(request: VoiceAsrRequest): HttpCall = HttpCall(
        url = join(asrPath()),
        headers = authHeaders() + mapOf(
            "X-Api-Resource-Id" to "volc.bigasr.auc_turbo",
            "X-Api-Request-Id" to UUID.randomUUID().toString(),
            "X-Api-Sequence" to "-1",
        ),
        mediaType = "application/json",
        payload = jsonOf(
            "user" to jsonOf("uid" to "minis_user"),
            "audio" to jsonOf("data" to encodeBase64(request.audioData)),
            "request" to jsonOf("model_name" to "bigmodel"),
        ).toString().toByteArray(Charsets.UTF_8),
    )

    override fun parseAsr(payload: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val envelope = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
        val heard = envelope?.optJSONObject("result")?.optString("text")
            ?: throw VoiceClientException.Parse("Unexpected Doubao v3 ASR response format")
        val seconds = envelope.optJSONObject("audio_info")?.optDouble("duration")?.takeIf { !it.isNaN() }?.div(1000)
        return VoiceAsrResponse(text = heard, language = request.language, durationSeconds = seconds)
    }
}

// ---- 讯飞：签名 URL 鉴权；转写走 REST、合成走 WebSocket -------------------------

class XunfeiVoiceClient(
    id: String,
    private val appId: String,
    key: String,
    private val apiSecret: String,
) : VoiceClient(id, "https://iat-api.xfyun.cn", key) {

    override fun authHeaders(): Map<String, String> = emptyMap()   // 鉴权在 URL 签名里

    override fun asrCall(request: VoiceAsrRequest): HttpCall = HttpCall(
        url = signedUrl("iat-api.xfyun.cn", "/v2/iat", Date()),
        mediaType = "application/json",
        payload = iatBody(request).toByteArray(Charsets.UTF_8),
    )

    private fun iatBody(request: VoiceAsrRequest) = jsonOf(
        "header" to jsonOf("app_id" to appId, "status" to 3),
        "parameter" to jsonOf(
            "iat" to jsonOf(
                "domain" to "iat",
                "language" to (request.language ?: "zh_cn"),
                "accent" to "mandarin",
                "result" to jsonOf("encoding" to "utf8", "compress" to "raw", "format" to "json"),
            ),
        ),
        "payload" to jsonOf(
            "audio" to jsonOf(
                "encoding" to "raw",
                "sample_rate" to 16000,
                "channels" to 1,
                "bit_depth" to 16,
                "status" to 3,
                "audio" to encodeBase64(request.audioData),
            ),
        ),
    ).toString()

    override fun parseAsr(payload: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        val envelope = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?: throw malformed()
        if (envelope.optJSONObject("header")?.optInt("code", -1) != 0) throw malformed()
        val encoded = envelope.optJSONObject("payload")?.optJSONObject("result")?.optString("text")
            ?.takeIf(String::isNotEmpty) ?: throw malformed()
        val sheet = runCatching { JSONObject(String(decodeBase64OrThrow(encoded), Charsets.UTF_8)) }.getOrNull()
            ?: throw malformed()
        return VoiceAsrResponse(text = wordsOf(sheet), language = request.language)
    }

    /** 讯飞的转写结果是 ws→cw→w 三层嵌套，逐层展平成文本。 */
    private fun wordsOf(sheet: JSONObject): String = buildString {
        val words = sheet.optJSONArray("ws") ?: JSONArray()
        for (i in 0 until words.length()) {
            val slots = words.optJSONObject(i)?.optJSONArray("cw") ?: continue
            for (j in 0 until slots.length()) {
                slots.optJSONObject(j)?.optString("w")?.let(::append)
            }
        }
    }

    private fun malformed() = VoiceClientException.Parse("Unexpected Xunfei ASR response format")

    // -- 合成（WebSocket 流式 PCM）--

    /**
     * 帧收发协议：首帧 business 参数 + 整段文本，末帧 status=2；回帧 data.audio
     * 是 base64 PCM16、末帧 data.status=2。收齐后包 16k WAV。
     */
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray =
        wsSpeechExchange(
            url = signedUrl("tts-api.xfyun.cn", "/v2/tts", Date()).replaceFirst("https://", "wss://"),
            openingFrame = ttsOpeningFrame(request),
            connect = httpClient::newWebSocket,
        )

    internal fun ttsOpeningFrame(request: VoiceTtsRequest): String = jsonOf(
        "common" to jsonOf("app_id" to appId),
        "business" to jsonOf(
            "aue" to "raw",
            "auf" to "audio/L16;rate=16000",
            "vcn" to (request.voice?.takeIf(String::isNotEmpty) ?: "xiaoyan"),
            "tte" to "UTF8",
        ),
        "data" to jsonOf("status" to 2, "text" to encodeBase64(request.input.toByteArray(Charsets.UTF_8))),
    ).toString()

    /**
     * WS 交换的可注入核心：connect 打开 socket（测试注假 socket 假帧，确定性），
     * 收流协议在此闭环——非零 code 抛厂商错误、末帧收尾、空流拒绝。
     */
    internal suspend fun wsSpeechExchange(
        url: String,
        openingFrame: String,
        connect: (Request, WebSocketListener) -> WebSocket,
    ): ByteArray {
        val finished = CompletableDeferred<ByteArray>()
        val pcm = ByteArrayOutputStream()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(openingFrame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
                frame.optInt("code", 0).takeIf { it != 0 }?.let { code ->
                    webSocket.cancel()
                    finished.completeExceptionally(
                        VoiceClientException.Parse("Xunfei TTS error code $code: ${frame.optString("message")}"),
                    )
                    return
                }
                val data = frame.optJSONObject("data") ?: return
                data.optString("audio").takeIf(String::isNotEmpty)
                    ?.let { runCatching { decodeBase64OrThrow(it) }.getOrNull() }
                    ?.let(pcm::write)
                if (data.optInt("status", 0) == 2) {
                    webSocket.close(1000, null)
                    val collected = pcm.toByteArray()
                    if (collected.isEmpty()) {
                        finished.completeExceptionally(VoiceClientException.Parse("Xunfei TTS empty audio"))
                    } else {
                        finished.complete(wrapPcm16InWav(collected, sampleRate = 16000))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finished.completeExceptionally(t)
            }
        }
        val socket = connect(Request.Builder().url(url).build(), listener)
        try {
            return finished.await()
        } finally {
            socket.cancel()
        }
    }

    // -- 签名 URL --

    /**
     * 鉴权走 URL 签名（RFC1123 GMT 时间戳 + HMAC-SHA256）：
     * 签名基串是「host/date/GET 请求行」三行拼接；authorization 是
     * api_key+algorithm+headers+signature 的说明串再整体 base64。
     */
    internal fun signedUrl(host: String, path: String, at: Date): String {
        val stamp = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(at)
        val signature = hmacSha256("host: $host\ndate: $stamp\nGET $path HTTP/1.1", apiSecret)
        val authorization = encodeBase64(
            buildString {
                append("api_key=\"").append(apiKey.orEmpty()).append("\", ")
                append("algorithm=\"hmac-sha256\", ")
                append("headers=\"host date request-line\", ")
                append("signature=\"").append(signature).append('\"')
            }.toByteArray(Charsets.UTF_8),
        )
        return "https://$host$path?authorization=$authorization&date=${URLEncoder.encode(stamp, "UTF-8")}&host=$host"
    }

    private fun hmacSha256(data: String, secret: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            encodeBase64(doFinal(data.toByteArray(Charsets.UTF_8)))
        }
}

// ---- Gemini：generateContent + AUDIO 模态（原生）--------------------------------

class GeminiVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    override val offers get() = TTS_ONLY
    override fun defaultTtsModel() = "gemini-2.5-flash-preview-tts"

    override fun ttsCall(request: VoiceTtsRequest): HttpCall = HttpCall(
        url = v1betaRoot() + "/models/" + (request.model ?: defaultTtsModel()) +
            ":generateContent?key=${apiKey.orEmpty()}",
        mediaType = "application/json",
        payload = jsonOf(
            "contents" to JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.input))),
            ),
            "generationConfig" to jsonOf(
                "responseModalities" to JSONArray().put("AUDIO"),
                "speechConfig" to jsonOf(
                    "voiceConfig" to jsonOf(
                        "prebuiltVoiceConfig" to jsonOf("voiceName" to voiceName(request.voice)),
                    ),
                ),
            ),
        ).toString().toByteArray(Charsets.UTF_8),
    )

    /** 基址没写 v1beta 就补上（目录习惯给的是根）。 */
    private fun v1betaRoot(): String = base().takeIf { it.contains("/v1beta") } ?: base() + "/v1beta"

    /** 只认官方音色名；其余回落 Kore（未知名是配置噪声而非可转发值）。 */
    private fun voiceName(requested: String?): String =
        requested?.takeIf { it in KNOWN_VOICES } ?: "Kore"

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val payload = dispatch(ttsCall(request))
        val reply = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?: throw VoiceClientException.Parse("Unexpected Gemini TTS response")
        val parts = reply.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw VoiceClientException.Parse("Unexpected Gemini TTS response")
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val encoded = inline.optString("data").takeIf(String::isNotEmpty) ?: continue
            val pcm = runCatching { decodeBase64OrThrow(encoded) }.getOrNull() ?: continue
            val mime = inline.optString("mimeType").ifBlank { "audio/L16;rate=24000" }
            return if (mime.lowercase().contains("wav")) pcm else wrapPcm16InWav(pcm, rateIn(mime))
        }
        throw VoiceClientException.Parse("No audio in Gemini TTS response")
    }

    private fun rateIn(mime: String): Int =
        mime.substringAfter("rate=", "").takeWhile(Char::isDigit).toIntOrNull() ?: 24000

    private companion object {
        val KNOWN_VOICES = setOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr", "Leda", "Orus")
    }
}

// ---- ElevenLabs：REST，条目 id 即 voice_id --------------------------------------

class ElevenLabsVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    override val offers get() = TTS_ONLY

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val voiceId = request.model?.takeIf(String::isNotEmpty)
            ?: request.voice?.takeIf(String::isNotEmpty)
            ?: "21m00Tcm4TlvDq8ikWAM"   // Rachel
        return dispatch(
            HttpCall(
                url = v1Root() + "/text-to-speech/$voiceId",
                headers = mapOf("xi-api-key" to apiKey.orEmpty(), "Accept" to "audio/mpeg"),
                mediaType = "application/json",
                payload = jsonOf(
                    "text" to request.input,
                    "model_id" to "eleven_multilingual_v2",
                    "voice_settings" to jsonOf("stability" to 0.5, "similarity_boost" to 0.75),
                ).toString().toByteArray(Charsets.UTF_8),
            ),
        )   // MP3 字节直通
    }

    private fun v1Root(): String = base().takeIf { it.contains("/v1") } ?: base() + "/v1"
}

// ---- Deepgram：/v1/listen 与 /v1/speak，Token 头 --------------------------------

class DeepgramVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    private fun tokenHeaders() = mapOf("Authorization" to "Token ${apiKey.orEmpty()}")
    private fun v1Root(): String = base().takeIf { it.contains("/v1") } ?: base() + "/v1"

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray = dispatch(
        HttpCall(
            url = v1Root() + "/speak?model=" + (request.model?.takeIf(String::isNotEmpty) ?: "aura-asteria-en"),
            headers = tokenHeaders(),
            mediaType = "application/json",
            payload = jsonOf("text" to request.input).toString().toByteArray(Charsets.UTF_8),
        ),
    )

    override fun asrCall(request: VoiceAsrRequest): HttpCall {
        val query = buildString {
            append("model=").append(request.model?.takeIf(String::isNotEmpty) ?: "nova-2")
            append("&smart_format=true")
            request.language?.takeIf(String::isNotEmpty)?.let { append("&language=").append(it) }
        }
        return HttpCall(
            url = v1Root() + "/listen?$query",
            headers = tokenHeaders(),
            body = request.audioData.toRequestBody("audio/wav".toMediaType()),
        )
    }

    override fun parseAsr(payload: ByteArray, request: VoiceAsrRequest): VoiceAsrResponse {
        fun chain(o: JSONObject?) = o?.optJSONObject("results")?.optJSONArray("channels")
            ?.optJSONObject(0)?.optJSONArray("alternatives")?.optJSONObject(0)
        val transcript = chain(runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull())
            ?.optString("transcript")
            ?: throw VoiceClientException.Parse("Unexpected Deepgram ASR response")
        return VoiceAsrResponse(text = transcript, language = request.language)
    }
}

// ---- Azure：SSML 体 + 订阅回头 --------------------------------------------------

class AzureTtsVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    override val offers get() = TTS_ONLY
    override fun defaultTtsModel() = "azure-tts"
    override fun defaultTtsVoice() = "zh-CN-XiaoxiaoNeural"

    override fun ttsCall(request: VoiceTtsRequest): HttpCall {
        val voice = request.voice ?: defaultTtsVoice()
        return HttpCall(
            url = join("/cognitiveservices/v1"),
            headers = buildMap {
                put("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
                apiKey?.takeIf(String::isNotEmpty)?.let { put("Ocp-Apim-Subscription-Key", it) }
            },
            mediaType = "application/ssml+xml",
            payload = ssml(voice, request.input).toByteArray(Charsets.UTF_8),
        )
    }

    /** 语言位取音色名前两段（zh-CN-Xiaoxiao → zh-CN，缺段回 en-US）；正文做 XML 转义。 */
    private fun ssml(voice: String, text: String): String {
        val locale = voice.split('-').run { if (size >= 2) "${this[0]}-${this[1]}" else "en-US" }
        val escaped = listOf("&" to "&amp;", "<" to "&lt;", ">" to "&gt;")
            .fold(text) { acc, (raw, safe) -> acc.replace(raw, safe) }
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"$locale\">" +
            "<voice name=\"$voice\">$escaped</voice></speak>"
    }
}

// ---- MiMo：两端点都骑在 chat/completions 上 ------------------------------------

class MimoVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    private val apiTtsModels = setOf("mimo-v2.5-tts", "mimo-v2.5-tts-voicedesign", "mimo-v2.5-tts-voiceclone")

    override fun defaultAsrModel() = "mimo-v2.5-asr"
    override fun defaultTtsModel() = "mimo-v2.5-tts"
    override fun defaultTtsVoice() = "mimo_default"

    /** 转写：input_audio 单部件 + asr_options.language；音频直接当对话输入。 */
    override suspend fun transcribe(request: VoiceAsrRequest): VoiceAsrResponse {
        val payload = dispatch(
            HttpCall(
                url = join("/v1/chat/completions"),
                headers = authHeaders(),
                mediaType = "application/json",
                payload = jsonOf(
                    "model" to (request.model ?: defaultAsrModel()),
                    "messages" to JSONArray().put(
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
                    "asr_options" to jsonOf("language" to (iso6391(request.language) ?: "auto")),
                ).toString().toByteArray(Charsets.UTF_8),
            ),
        )
        val reply = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
        val heard = reply?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?: throw VoiceClientException.Parse("Unexpected MiMo ASR response format")
        val seconds = reply.optJSONObject("usage")?.optDouble("seconds")?.takeIf { !it.isNaN() }
        return VoiceAsrResponse(text = heard, durationSeconds = seconds)
    }

    /** 合成：待说文本放 assistant 轮、空 user 轮开路；音频配置带格式与音色。 */
    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val (apiModel, voice) = resolveTtsTarget(request)
        val payload = dispatch(
            HttpCall(
                url = join("/v1/chat/completions"),
                headers = authHeaders(),
                mediaType = "application/json",
                payload = jsonOf(
                    "model" to apiModel,
                    "messages" to JSONArray()
                        .put(JSONObject().put("role", "user").put("content", ""))
                        .put(JSONObject().put("role", "assistant").put("content", request.input)),
                    "audio" to jsonOf(
                        "format" to "wav",
                        "voice" to voice?.takeIf { apiModel != "mimo-v2.5-tts-voicedesign" },
                    ),
                ).toString().toByteArray(Charsets.UTF_8),
            ),
        )
        val encoded = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optJSONObject("audio")
            ?.optString("data")?.takeIf(String::isNotEmpty)
            ?: throw VoiceClientException.Parse("Unexpected MiMo TTS response format")
        return runCatching { decodeBase64OrThrow(encoded) }.getOrNull()
            ?: throw VoiceClientException.Parse("Unexpected MiMo TTS response format")
    }

    /** 目录条目既可能是 API 机型也可能是音色名：在 API 机型集内是机型，否则当音色。 */
    private fun resolveTtsTarget(request: VoiceTtsRequest): Pair<String, String?> {
        val entry = request.model ?: defaultTtsModel()
        return if (entry in apiTtsModels) entry to request.voice else "mimo-v2.5-tts" to entry
    }
}

// ---- OpenRouter：两端点都骑在 chat/completions 上（音频模态）--------------------

/**
 * OpenRouter 没有独立的语音端点：
 *  - 合成只有 chat-audio 机型能出声（modalities+audio+stream 三硬约束，流式下
 *    format 只收 pcm16）；其余机型沿用 OpenAI 语音端点形状（在 OpenRouter 上
 *    必 400，但那是这些条目的既有状态，不在此改判）；
 *  - 转写拆在两个不相交机型集的端点：专用转写机型（whisper/transcribe/
 *    deepgram 厂商限定）走 REST，其余默认当音频 chat 机型——新机型无需加清单。
 */
class OpenRouterVoiceClient(id: String, base: String, key: String?) : VoiceClient(id, base, key) {

    /** OpenAI 音频预览机型恒发 24 kHz PCM16，无字段宣告——错采样率=错音高。 */
    private val pcmRate = 24000

    /** 官方 400 原文给出的音色集；仅用于认名，回落默认音色。 */
    private val knownVoices = setOf(
        "alloy", "echo", "fable", "onyx", "nova", "shimmer", "coral",
        "verse", "ballad", "ash", "sage", "marin", "cedar",
    )

    /** 转写机型的判定（方向与基座相反：默认 chat，认得出转写词干的才走 REST）。 */
    internal fun isDedicatedTranscriptionModel(modelId: String): Boolean {
        val id = modelId.lowercase(Locale.ROOT)
        return "whisper" in id || "transcribe" in id || id.contains("deepgram/")
    }

    override fun usesChatBasedAsr(model: LLMModel): Boolean = !isDedicatedTranscriptionModel(model.id)

    override suspend fun synthesize(request: VoiceTtsRequest): ByteArray {
        val modelId = request.model ?: defaultTtsModel()
        if (!isChatAudioModel(modelId)) return super.synthesize(request)

        val pcm = collectStreamedPcm(
            HttpCall(
                url = join("/v1/chat/completions"),
                headers = authHeaders(),
                mediaType = "application/json",
                payload = jsonOf(
                    "model" to modelId,
                    "modalities" to JSONArray().put("text").put("audio"),
                    "audio" to jsonOf(
                        "voice" to chatVoice(request.voice, modelId),
                        "format" to "pcm16",   // 流式强制，且流式下只收 pcm16
                    ),
                    "stream" to true,
                    "messages" to JSONArray().put(
                        JSONObject().put("role", "user").put("content", request.input),
                    ),
                ).toString().toByteArray(Charsets.UTF_8),
            ),
        )
        if (pcm.isEmpty()) {
            throw VoiceClientException.Parse(
                "OpenRouter returned no audio data for $modelId" +
                    lastTranscript.takeIf(String::isNotBlank)?.let { " (transcript: ${it.take(80)})" }.orEmpty(),
            )
        }
        AppLogger.info("VoiceClient", "openrouter chat-audio ok model=$modelId pcm=${pcm.size}B")
        return wrapPcm16InWav(pcm, pcmRate)
    }

    /** 机型与音色是两个轴；不认得的「音色」值是机型名混进来的，回落默认。 */
    private fun chatVoice(requested: String?, modelId: String): String {
        val wanted = requested?.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT) ?: return defaultTtsVoice()
        return knownVoices.firstOrNull { it == wanted } ?: defaultTtsVoice().also {
            AppLogger.info("VoiceClient", "openrouter: dropping non-voice '$requested' for $modelId, using $it")
        }
    }

    private fun isChatAudioModel(modelId: String): Boolean {
        val id = modelId.lowercase(Locale.ROOT)
        return "audio" in id && ("gpt" in id || "openai" in id)
    }

    private var lastTranscript: String = ""

    /** SSE 流收集：逐 data: 行取 delta.audio.data 拼_pcm；401/403 先行分类。 */
    private suspend fun collectStreamedPcm(call: HttpCall): ByteArray {
        lastTranscript = ""
        val pcm = ByteArrayOutputStream()
        val transcript = StringBuilder()
        withContext(Dispatchers.IO) {
            httpClient.newCall(
                Request.Builder().url(call.url)
                    .also { builder -> call.headers.forEach(builder::header) }
                    .post(call.payload!!.toRequestBody(call.mediaType!!.toMediaType()))
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.bytes()
                    if (response.code == 401 || response.code == 403) {
                        AppLogger.error("VoiceClient", "openrouter voice auth rejected: HTTP ${response.code}")
                        throw VoiceClientException.Auth()
                    }
                    AppLogger.error("VoiceClient", "openrouter chat-audio rejected: HTTP ${response.code} body=${raw?.toString(Charsets.UTF_8).orEmpty()}")
                    throw VoiceClientException.Http(response.code, raw)
                }
                val source = response.body?.source()
                    ?: throw VoiceClientException.Parse("OpenRouter returned an empty body")
                generateSequence(source::readUtf8Line).forEach { line ->
                    if (!line.startsWith("data:")) return@forEach
                    when (val frame = line.removePrefix("data:").trim()) {
                        "[DONE]" -> return@use
                        else -> runCatching { JSONObject(frame) }.getOrNull()
                            ?.optJSONArray("choices")?.let { choices ->
                                for (i in 0 until choices.length()) {
                                    val audio = choices.optJSONObject(i)?.optJSONObject("delta")
                                        ?.optJSONObject("audio") ?: continue
                                    audio.optString("data").takeIf(String::isNotEmpty)?.let { encoded ->
                                        runCatching { decodeBase64OrThrow(encoded) }.getOrNull()?.let(pcm::write)
                                    }
                                    audio.optString("transcript").takeIf(String::isNotEmpty)?.let(transcript::append)
                                }
                            }
                    }
                }
            }
        }
        lastTranscript = transcript.toString()
        return pcm.toByteArray()
    }
}
