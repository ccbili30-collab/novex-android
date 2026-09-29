package novex.android.voice

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * 语音客户端的假服务器 parity 测试（P3.2 随 provider/voice 绞杀新增）。
 *
 * 关键厂商逐一钉「请求断言 + 响应解析 + 错误分类」三面：Doubao TTS/ASR 与
 * OpenAI TTS 是任务书点名的最低集；MiniMax 的双响应外壳与 base URL 剥段、
 * 基座的 401/403→Auth 与 multipart 形状、OpenRouter 的 ASR 路由谓词（自
 * OpenRouterVoiceRoutingTest 等价迁移）与 WAV 包头一并钉住。
 */
class VoiceClientParityTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = VoiceClient(
        "test-instance",
        server.url("/").toString().trimEnd('/'),
        "sk-test-key",
    )

    // ---- 基座：OpenAI TTS ----------------------------------------------------

    @Test
    fun `openai tts 请求形状-模型-音色-格式-速度`() = runBlocking {
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04)  // "ID3"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setBody(okio.Buffer().write(mp3)),
        )

        val out = client().synthesize(
            VoiceTtsRequest(
                input = "你好世界",
                model = "tts-1-hd",
                voice = "nova",
                speed = 1.5f,
                responseFormat = VoiceTtsFormat.MP3,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/audio/speech", recorded.path)
        assertEquals("POST", recorded.method)
        assertEquals("Bearer sk-test-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("tts-1-hd", body.getString("model"))
        assertEquals("你好世界", body.getString("input"))
        assertEquals("nova", body.getString("voice"))
        assertEquals("mp3", body.getString("response_format"))
        assertEquals(1.5, body.getDouble("speed"), 1e-9)
        assertTrue(out.contentEquals(mp3))
    }

    @Test
    fun `openai tts 401 归为 Auth 错误`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val error = runCatching {
            runBlocking { client().synthesize(VoiceTtsRequest(input = "x")) }
        }.exceptionOrNull()!!
        assertTrue(error is VoiceClientException.Auth)
    }

    @Test
    fun `openai tts 其余非 2xx 归为 Http 且消息取错误体`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""))

        val error = runCatching {
            runBlocking { client().synthesize(VoiceTtsRequest(input = "x")) }
        }.exceptionOrNull()!!
        assertTrue(error is VoiceClientException.Http)
        assertEquals(429, (error as VoiceClientException.Http).code)
        assertTrue(error.message.orEmpty().contains("rate limited"))
        assertTrue(error.message.orEmpty().contains("429"))
    }

    // ---- 基座：OpenAI ASR（multipart）----------------------------------------

    @Test
    fun `openai asr multipart 形状与语言归约`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"你好","language":"zh","duration":1.5}"""),
        )

        val wav = VoiceClient.wrapPcm16InWav(ByteArray(16), 16000)
        val out = client().transcribe(
            VoiceAsrRequest(audioData = wav, model = "whisper-1", language = "zh-CN"),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("whisper-1"))
        // 区域限定标签归约为 ISO-639-1 两字母码。
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("\r\nzh\r\n"))
        assertTrue(body.contains("name=\"response_format\""))
        assertEquals("你好", out.text)
        assertEquals("zh", out.language)
        assertEquals(1.5, out.durationSeconds!!, 1e-9)
    }

    // ---- Doubao TTS ----------------------------------------------------------

    private fun doubao() = DoubaoVoiceClient(
        "doubao", "dk", baseOverride = server.url("/").toString().trimEnd('/'),
    )


    @Test
    fun `doubao tts 请求形状-resourceId 按音色族选择-帧解析`() = runBlocking {
        val audio = byteArrayOf(1, 2, 3, 4)
        val frames = listOf(byteArrayOf(1, 2), byteArrayOf(3, 4))
            .joinToString("\n") { chunk ->
                """{"code":0,"data":"${Base64.getEncoder().encodeToString(chunk)}"}"""
            }
        server.enqueue(MockResponse().setResponseCode(200).setBody(frames))

        val out = doubao().synthesize(
            VoiceTtsRequest(
                input = "你好",
                // uranus 族音色 → seed-tts-2.0 资源 id
                model = "zh_female_cancan_uranus_bigtts",
                responseFormat = VoiceTtsFormat.MP3,
            ),
        )

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/api/v3/tts/unidirectional"))
        assertEquals("dk", recorded.getHeader("X-Api-Key"))
        assertEquals("seed-tts-2.0", recorded.getHeader("X-Api-Resource-Id"))
        assertEquals("keep-alive", recorded.getHeader("Connection"))
        val body = org.json.JSONObject(recorded.body.readUtf8())
        val reqParams = body.getJSONObject("req_params")
        assertEquals("你好", reqParams.getString("text"))
        assertEquals("zh_female_cancan_uranus_bigtts", reqParams.getString("speaker"))
        assertEquals("mp3", reqParams.getJSONObject("audio_params").getString("format"))
        assertEquals(24000, reqParams.getJSONObject("audio_params").getInt("sample_rate"))
        assertTrue(out.contentEquals(audio))
    }

    @Test
    fun `doubao tts moon 族音色走 seed-tts-1-0 且 seed-tts 前缀回落默认`() = runBlocking {
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("""{"code":0,"data":"${java.util.Base64.getEncoder().encodeToString(byteArrayOf(7))}"}"""),
            )
        }

        // moon 族（非 uranus、非 saturn_ 前缀）→ 1.0 资源 id。
        doubao().synthesize(
            VoiceTtsRequest(input = "hi", voice = "zh_female_shuangkuaisisi_moon_bigtts"),
        )
        var recorded = server.takeRequest()
        assertEquals("seed-tts-1.0", recorded.getHeader("X-Api-Resource-Id"))

        // seed-tts- 前缀的裸值视作无效音色，回落默认灿灿（默认是 uranus 族 → 2.0）。
        doubao().synthesize(
            VoiceTtsRequest(input = "hi", voice = "seed-tts-foo"),
        )
        recorded = server.takeRequest()
        assertEquals("seed-tts-2.0", recorded.getHeader("X-Api-Resource-Id"))
        val body = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals(
            "zh_female_cancan_uranus_bigtts",
            body.getJSONObject("req_params").getString("speaker"),
        )
    }

    @Test
    fun `doubao tts 无音频帧报解析错误并附原文预览`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"code":3001,"message":"quota"}"""))

        val error = runCatching {
            runBlocking {
                doubao().synthesize(VoiceTtsRequest(input = "hi"))
            }
        }.exceptionOrNull()!!
        assertTrue(error is VoiceClientException.Parse)
        assertTrue(error.message.orEmpty().contains("no audio frames"))
        assertTrue(error.message.orEmpty().contains("quota"))
    }

    // ---- Doubao ASR ----------------------------------------------------------

    @Test
    fun `doubao asr 请求形状与结果解析`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"result":{"text":"火山识别"},"audio_info":{"duration":2500}}"""),
        )

        val wav = VoiceClient.wrapPcm16InWav(ByteArray(8), 16000)
        val out = doubao().transcribe(
            VoiceAsrRequest(audioData = wav, language = "zh-CN"),
        )

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/api/v3/auc/bigmodel/recognize/flash"))
        assertEquals("dk", recorded.getHeader("X-Api-Key"))
        assertEquals("volc.bigasr.auc_turbo", recorded.getHeader("X-Api-Resource-Id"))
        assertEquals("-1", recorded.getHeader("X-Api-Sequence"))
        assertNull(recorded.getHeader("Authorization"))  // 不走 Bearer
        val requestId = recorded.getHeader("X-Api-Request-Id")
        assertTrue(requestId != null && requestId.isNotEmpty())
        val body = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("minis_user", body.getJSONObject("user").getString("uid"))
        assertEquals("bigmodel", body.getJSONObject("request").getString("model_name"))
        val sent = Base64.getDecoder().decode(body.getJSONObject("audio").getString("data"))
        assertTrue(sent.contentEquals(wav))
        assertEquals("火山识别", out.text)
        assertEquals(2.5, out.durationSeconds!!, 1e-9)
    }

    // ---- MiniMax（响应外壳与 base URL 剥段）-----------------------------------

    @Test
    fun `minimax base 剥掉 v1 与 anthropic 段-错误码透传-hex 音频优先`() = runBlocking {
        val audio = byteArrayOf(9, 9)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"base_resp":{"status_code":0},"data":{"audio":"${audio.joinToString("") { "%02x".format(it) }}"}}""",
                ),
        )

        val url = server.url("/v1/anthropic").toString().trimEnd('/')
        val out = MiniMaxVoiceClient("mm", url, "mk").synthesize(
            VoiceTtsRequest(input = "hi", model = "speech-2.8-hd"),
        )

        val recorded = server.takeRequest()
        // /v1 与 /anthropic 都被剥掉后拼 /v1/t2a_v2。
        assertEquals("/v1/t2a_v2", recorded.path)
        val body = org.json.JSONObject(recorded.body.readUtf8())
        assertEquals("speech-2.8-hd", body.getString("model"))
        // voice == model 视为未选音色 → 默认 female-shaonv。
        assertEquals("female-shaonv", body.getJSONObject("voice_setting").getString("voice_id"))
        assertEquals(100, body.getJSONObject("voice_setting").getInt("speed"))
        assertEquals(32000, body.getJSONObject("audio_setting").getInt("sample_rate"))
        assertTrue(out.contentEquals(audio))
    }

    @Test
    fun `minimax base_resp 非零码以厂商错误收流`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"base_resp":{"status_code":2054,"status_msg":"voice id not exist"}}""",
            ),
        )

        val url = server.url("/").toString().trimEnd('/')
        val error = runCatching {
            runBlocking {
                MiniMaxVoiceClient("mm", url, "mk").synthesize(VoiceTtsRequest(input = "hi"))
            }
        }.exceptionOrNull()!!
        assertTrue(error is VoiceClientException.Parse)
        assertTrue(error.message.orEmpty().contains("2054"))
        assertTrue(error.message.orEmpty().contains("voice id not exist"))
    }

    // ---- OpenRouter：ASR 路由谓词与 WAV 包头（自 OpenRouterVoiceRoutingTest 迁移）----

    @Test
    fun `whisper 与 gpt-4o transcribe 变体留在 REST 转写端点`() {
        val provider = OpenRouterVoiceClient("or", "https://openrouter.ai/api", "k")
        fun model(id: String) = com.openminis.app.data.model.LLMModel(id = id, displayName = id, provider = "OpenRouter")
        assertFalse(provider.usesChatBasedAsr(model("openai/whisper-1")))
        assertFalse(provider.usesChatBasedAsr(model("openai/gpt-4o-transcribe")))
        assertFalse(provider.usesChatBasedAsr(model("openai/gpt-4o-mini-transcribe")))
        assertFalse(provider.usesChatBasedAsr(model("deepgram/nova-3")))
    }

    @Test
    fun `deepgram 按厂商限定匹配-amazon nova 不被误路由`() {
        val provider = OpenRouterVoiceClient("or", "https://openrouter.ai/api", "k")
        fun model(id: String) = com.openminis.app.data.model.LLMModel(id = id, displayName = id, provider = "OpenRouter")
        assertTrue(provider.usesChatBasedAsr(model("amazon/nova-2-lite-v1")))
        assertTrue(provider.usesChatBasedAsr(model("google/gemini-3.6-flash")))
        assertTrue(provider.usesChatBasedAsr(model("openai/gpt-audio-mini")))
        // 相对基类规则的反转：未来音频聊天机型默认走 chat，无需改代码。
        val future = model("somevendor/brand-new-speech-chat")
        assertTrue(provider.usesChatBasedAsr(future))
        assertFalse(VoiceClient("i", "https://x", null).usesChatBasedAsr(future))
    }

    @Test
    fun `PCM16 包进 RIFF WAV 头并带声明的采样率`() {
        // synthesize() 必须返回媒体播放器能打开的东西；无头 PCM 不是。24 kHz 被
        // 断言是因为错采样率不是错误、只是错音高——不钉就静默。
        val pcm = ByteArray(480) { 0 }
        val wav = VoiceClient.wrapPcm16InWav(pcm, 24000)

        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals(44 + pcm.size, wav.size)

        fun le32(at: Int) = (wav[at].toInt() and 0xFF) or
            ((wav[at + 1].toInt() and 0xFF) shl 8) or
            ((wav[at + 2].toInt() and 0xFF) shl 16) or
            ((wav[at + 3].toInt() and 0xFF) shl 24)

        assertEquals(24000, le32(24))          // 采样率
        assertEquals(24000 * 2, le32(28))      // 字节率 = 率 × 1ch × 16bit/8
        assertEquals(pcm.size, le32(40))       // data 块大小
    }

    // ---- 工厂判定 ------------------------------------------------------------

    @Test
    fun `工厂按 base URL 分派厂商且讯飞复合凭据不足时不可用`() {
        fun inst(base: String?, type: com.openminis.app.data.model.ProviderType = com.openminis.app.data.model.ProviderType.openAI) =
            com.openminis.app.data.model.ProviderInstance(
                id = "i", label = "L", providerType = type,
                credentialType = com.openminis.app.data.model.ProviderCredential.apiKey,
                customBaseURL = base,
            )

        assertTrue(VoiceClientFactory.make(inst("https://openspeech.bytedance.com"), "k") is DoubaoVoiceClient)
        assertTrue(VoiceClientFactory.make(inst("https://api.minimax.io"), "k") is MiniMaxVoiceClient)
        assertTrue(
            VoiceClientFactory.make(
                inst("https://api.minimax.io", com.openminis.app.data.model.ProviderType.anthropic),
                "k",
            ) is MiniMaxVoiceClient,
        )
        assertTrue(VoiceClientFactory.make(inst(null), "k") is VoiceClient)
        assertTrue(VoiceClientFactory.make(inst("https://api.elevenlabs.io"), "k") is ElevenLabsVoiceClient)
        assertTrue(VoiceClientFactory.make(inst(null, com.openminis.app.data.model.ProviderType.gemini), "k") is GeminiVoiceClient)
        assertNull(VoiceClientFactory.make(inst(null, com.openminis.app.data.model.ProviderType.kimiCode), "k"))
        assertNull(VoiceClientFactory.make(inst(null, com.openminis.app.data.model.ProviderType.anthropic), "k"))

        // 讯飞需要 "appId;apiKey;apiSecret" 复合凭据；不足三段时不可用。
        assertNull(VoiceClientFactory.make(inst("https://iat-api.xfyun.cn"), "only-one"))
        assertTrue(
            VoiceClientFactory.make(inst("https://iat-api.xfyun.cn"), "app;key;secret") is XunfeiVoiceClient,
        )
        assertEquals(listOf("a", "b"), VoiceClientFactory.splitCompound(" a ; b ; "))
    }
}
