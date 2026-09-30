package novex.android.voice

import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType

/**
 * 语音厂商的接入模板（P3.2 真重写版）。
 *
 * 模板只服务两件事：预填新增供应商流程（底层类型 + 基址）；为没有 /models
 * 端点的厂商种下语音种子条目。语音可见性不由此判——那是机型模态（音频条目上
 * 的影子视图）的事；base URL 标记只用于种种子与请求路由（[VoiceClientFactory]）。
 *
 * 种子数据的值（机型 id / 显示名 / 基址）与 iOS 逐字节一致——一平台导出的
 * 配置在另一平台无损导入，这是数据契约，不是实现细节。
 */
data class VoiceVendorTemplate(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseURL: String,
    val appendV1: Boolean,
    /** 厂商能做什么；UI 据此本地化标签。 */
    val capability: Capability,
    val baseURLMarkers: List<String>,
    val mockModels: List<LLMModel>,
    /** UI 呈现的可选提示（额外凭据等）。 */
    val note: String? = null,
) {
    enum class Capability { TTS, ASR, BOTH }

    companion object {

        /** 标记命中基址的模板；无则 null。 */
        fun template(forBaseURL: String?): VoiceVendorTemplate? {
            val probe = forBaseURL?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
            return all.firstOrNull { tpl -> tpl.baseURLMarkers.any(probe::contains) }
        }

        /** 为命中模板的实例构造种子条目。 */
        fun mockEntries(instance: ProviderInstance): List<ModelEntry> =
            template(instance.customBaseURL)?.mockModels
                ?.map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                .orEmpty()

        /** 所有模板。 */
        val all: List<VoiceVendorTemplate> = buildList {
            add(elevenLabs())
            add(deepgram())
            add(azureTts())
            add(minimax())
            add(alibaba())
            add(doubao())
            add(xunfei())
            add(mimo())
        }

        // ---- 种子条目构造：显示名键值表 + 厂商映射 ----------------------------

        /** ASR 种子的模态形状：仅 inputs ["audio"]。 */
        private fun asrSeed(id: String, label: String, vendor: String) = LLMModel(
            id = id, displayName = label, provider = vendor,
            inputModalities = listOf("audio"),
        )

        /** TTS 种子的模态形状：仅 outputs ["audio"]。 */
        private fun ttsSeed(id: String, label: String, vendor: String) = LLMModel(
            id = id, displayName = label, provider = vendor,
            outputModalities = listOf("audio"),
        )

        private fun Map<String, String>.toSeeds(vendor: String, seed: (String, String, String) -> LLMModel): List<LLMModel> =
            entries.map { entry -> seed(entry.key, entry.value, vendor) }

        private fun elevenLabs() = VoiceVendorTemplate(
            id = "elevenlabs", name = "ElevenLabs",
            providerType = ProviderType.openAI,
            baseURL = "https://api.elevenlabs.io", appendV1 = false,
            capability = Capability.TTS,
            baseURLMarkers = listOf("elevenlabs"),
            // 条目 id 即 ElevenLabs voice_id。
            mockModels = mapOf(
                "21m00Tcm4TlvDq8ikWAM" to "Rachel (EN, F)",
                "pNInz6obpgDQGcFmaJgB" to "Adam (EN, M)",
                "EXAVITQu4vr4xnSDxMaL" to "Bella (EN, F)",
                "ErXwobaYiN019PkySvjV" to "Antoni (EN, M)",
            ).toSeeds("elevenlabs", ::ttsSeed),
        )

        private fun deepgram() = VoiceVendorTemplate(
            id = "deepgram", name = "Deepgram",
            providerType = ProviderType.openAI,
            baseURL = "https://api.deepgram.com", appendV1 = false,
            capability = Capability.BOTH,
            baseURLMarkers = listOf("deepgram"),
            mockModels = mapOf(
                "nova-2" to "Nova-2 (ASR)",
                "nova-3" to "Nova-3 (ASR)",
            ).toSeeds("deepgram", ::asrSeed) + mapOf(
                "aura-asteria-en" to "Aura Asteria (TTS, EN F)",
                "aura-luna-en" to "Aura Luna (TTS, EN F)",
                "aura-orion-en" to "Aura Orion (TTS, EN M)",
            ).toSeeds("deepgram", ::ttsSeed),
        )

        private fun azureTts() = VoiceVendorTemplate(
            id = "azure-tts", name = "Azure TTS",
            providerType = ProviderType.openAI,
            baseURL = "https://eastasia.tts.speech.microsoft.com", appendV1 = false,
            capability = Capability.TTS,
            baseURLMarkers = listOf("tts.speech.microsoft.com"),
            mockModels = mapOf(
                // 中文（普通话）
                "zh-CN-XiaoxiaoNeural" to "Xiaoxiao (ZH, F)",
                "zh-CN-YunxiNeural" to "Yunxi (ZH, M)",
                "zh-CN-YunyangNeural" to "Yunyang (ZH, M)",
                "zh-CN-XiaoyiNeural" to "Xiaoyi (ZH, F)",
                "zh-CN-XiaochenNeural" to "Xiaochen (ZH, F)",
                "zh-CN-XiaohanNeural" to "Xiaohan (ZH, F)",
                "zh-CN-XiaomengNeural" to "Xiaomeng (ZH, F)",
                "zh-CN-XiaomoNeural" to "Xiaomo (ZH, F)",
                "zh-CN-XiaoruiNeural" to "Xiaorui (ZH, F)",
                "zh-CN-XiaoshuangNeural" to "Xiaoshuang (ZH, F, Child)",
                "zh-CN-XiaoyouNeural" to "Xiaoyou (ZH, F, Child)",
                "zh-CN-XiaozhenNeural" to "Xiaozhen (ZH, F)",
                "zh-CN-YunfengNeural" to "Yunfeng (ZH, M)",
                "zh-CN-YunhaoNeural" to "Yunhao (ZH, M)",
                "zh-CN-YunjianNeural" to "Yunjian (ZH, M)",
                "zh-CN-YunxiaNeural" to "Yunxia (ZH, M)",
                "zh-CN-YunyeNeural" to "Yunye (ZH, M)",
                "zh-CN-YunzeNeural" to "Yunze (ZH, M)",
                // 中文（粤语）
                "zh-HK-HiuMaanNeural" to "HiuMaan (HK, F)",
                "zh-HK-WanLungNeural" to "WanLung (HK, M)",
                "zh-HK-HiuGaaiNeural" to "HiuGaai (HK, F)",
                // 中文（台湾）
                "zh-TW-HsiaoChenNeural" to "HsiaoChen (TW, F)",
                "zh-TW-YunJheNeural" to "YunJhe (TW, M)",
                "zh-TW-HsiaoYuNeural" to "HsiaoYu (TW, F)",
                // 英语（美音）——常选
                "en-US-JennyNeural" to "Jenny (EN, F)",
                "en-US-GuyNeural" to "Guy (EN, M)",
                "en-US-AriaNeural" to "Aria (EN, F)",
                "en-US-DavisNeural" to "Davis (EN, M)",
                "en-US-AvaNeural" to "Ava (EN, F)",
                "en-US-AndrewNeural" to "Andrew (EN, M)",
                "en-US-EmmaNeural" to "Emma (EN, F)",
                "en-US-BrianNeural" to "Brian (EN, M)",
                // 日语
                "ja-JP-NanamiNeural" to "Nanami (JA, F)",
                "ja-JP-KeitaNeural" to "Keita (JA, M)",
                "ja-JP-AoiNeural" to "Aoi (JA, F)",
                "ja-JP-DaichiNeural" to "Daichi (JA, M)",
                "ja-JP-ShioriNeural" to "Shiori (JA, F)",
                // 韩语
                "ko-KR-SunHiNeural" to "SunHi (KO, F)",
                "ko-KR-InJoonNeural" to "InJoon (KO, M)",
            ).toSeeds("azure-tts", ::ttsSeed),
            note = "Enter the Azure Speech Services subscription key. Set the base URL to your region, e.g. https://eastasia.tts.speech.microsoft.com",
        )

        private fun minimax() = VoiceVendorTemplate(
            id = "minimax", name = "MiniMax",
            providerType = ProviderType.anthropic,
            baseURL = "https://api.minimax.io", appendV1 = false,
            capability = Capability.TTS,
            baseURLMarkers = listOf("minimax"),
            mockModels = mapOf(
                "speech-2.8-hd" to "MiniMax Speech 2.8 HD",
                "speech-2.8-turbo" to "MiniMax Speech 2.8 Turbo",
            ).toSeeds("minimax", ::ttsSeed),
        )

        private fun alibaba() = VoiceVendorTemplate(
            id = "alibaba", name = "Alibaba Bailian",
            providerType = ProviderType.openAI,
            baseURL = "https://dashscope.aliyuncs.com/compatible-mode", appendV1 = true,
            capability = Capability.BOTH,
            baseURLMarkers = listOf("dashscope"),
            mockModels = mapOf(
                "paraformer-realtime-v2" to "Paraformer Realtime v2",
            ).toSeeds("alibaba", ::asrSeed) + mapOf(
                "cosyvoice-v2" to "CosyVoice v2",
            ).toSeeds("alibaba", ::ttsSeed),
        )

        private fun doubao() = VoiceVendorTemplate(
            id = "doubao", name = "Doubao (Volcano)",
            providerType = ProviderType.openAI,
            baseURL = "https://openspeech.bytedance.com", appendV1 = false,
            capability = Capability.BOTH,
            baseURLMarkers = listOf("openspeech.bytedance", "volcano"),
            mockModels = mapOf(
                "bigmodel" to "Doubao ASR (bigmodel)",
            ).toSeeds("doubao", ::asrSeed) + mapOf(
                // Seed TTS 2.0（大模型，uranus）
                "zh_female_cancan_uranus_bigtts" to "灿灿 (通用, 女)",
                "zh_female_vv_uranus_bigtts" to "Vivi (表现力, 女)",
                "zh_male_liufei_uranus_bigtts" to "刘飞 (通用, 男)",
                "zh_male_m191_uranus_bigtts" to "云舟 (清爽, 男)",
                // Seed TTS 1.0（大模型，moon）
                "zh_female_shuangkuaisisi_moon_bigtts" to "爽快思思 (爽朗, 女)",
                "zh_female_sajiaonvyou_moon_bigtts" to "撒娇女友 (撒娇, 女)",
                "zh_female_gaolengyujie_moon_bigtts" to "高冷御姐 (御姐, 女)",
                "multi_female_shuangkuaisisi_moon_bigtts" to "爽快思思 (多语, 女)",
            ).toSeeds("doubao", ::ttsSeed),
            note = "Enter the API Key from the Volcano Engine new console.",
        )

        private fun xunfei() = VoiceVendorTemplate(
            id = "xunfei", name = "iFlytek (Xunfei)",
            providerType = ProviderType.openAI,
            baseURL = "https://iat-api.xfyun.cn", appendV1 = false,
            capability = Capability.BOTH,
            baseURLMarkers = listOf("xfyun"),
            mockModels = mapOf(
                "iat" to "iFlytek IAT (ASR)",
            ).toSeeds("xunfei", ::asrSeed) + mapOf(
                "xiaoyan" to "讯飞·晓燕 (TTS, 中文女)",
                "aisjiuxu" to "讯飞·许久 (TTS, 中文男)",
            ).toSeeds("xunfei", ::ttsSeed),
            note = "iFlytek needs App ID and API Secret — enter them as \"appId;apiKey;apiSecret\" in the API Key field.",
        )

        private fun mimo() = VoiceVendorTemplate(
            id = "mimo", name = "Xiaomi MiMo",
            providerType = ProviderType.openAI,
            baseURL = "https://api.xiaomimimo.com", appendV1 = true,
            capability = Capability.BOTH,
            baseURLMarkers = listOf("xiaomimimo"),
            mockModels = mapOf(
                "mimo-v2.5-asr" to "MiMo ASR v2.5",
            ).toSeeds("mimo", ::asrSeed) + mapOf(
                "mimo_default" to "MiMo 默认 (Auto)",
                "冰糖" to "冰糖 (中文, 女)",
                "茉莉" to "茉莉 (中文, 女)",
                "苏打" to "苏打 (中文, 男)",
                "白桦" to "白桦 (中文, 男)",
                "Mia" to "Mia (EN, F)",
                "Chloe" to "Chloe (EN, F)",
                "Milo" to "Milo (EN, M)",
                "Dean" to "Dean (EN, M)",
            ).toSeeds("mimo", ::ttsSeed),
        )
    }
}
