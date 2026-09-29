package novex.android.voice

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

/**
 * 语音厂商模板目录（P3.2 自有实现，替换 data/model 的上游模板件；模板数据
 * 逐条对齐被替换实现——机型 id / 显示名 / base URL 与 iOS 保持逐字节一致，
 * 一平台导出的配置在另一平台无损导入）。
 *
 * 语音特化厂商（MiniMax / 阿里 / 豆包 / 讯飞 / MiMo…）不是独立的
 * ProviderType——它们搭载在按 base URL 识别的 OpenAI/Anthropic 兼容实例上。
 * 模板做两件事：
 *   1. 预填新增供应商流程（底层类型 + base URL）；
 *   2. 携带按单旗 SEED 模态形状（见 data/model VoiceModality）打标的 mock 语音
 *      机型：ASR 种子只有 inputs ["audio"]，TTS 种子只有 outputs ["audio"]。
 *      这些厂商没有 OpenAI 式 /models 端点，addInstance 直接种下这些条目；种下
 *      后就是普通 ModelEntry。
 *
 * baseURLMarkers 只服务两个窄用途：(1) 新增时机种 mock 机型；(2) 把语音请求
 * 路由到正确厂商客户端（[VoiceClientFactory]）。绝不据此把实例归类为「仅语音」
 * ——语音可见性按机型模态（音频条目上的影子视图）。
 */
data class VoiceVendorTemplate(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val baseURL: String,
    val appendV1: Boolean,
    /** 厂商能做什么——UI 按此本地化标签。 */
    val capability: Capability,
    val baseURLMarkers: List<String>,
    val mockModels: List<LLMModel>,
    /** UI 呈现的可选提示（额外凭据等）。 */
    val note: String? = null,
) {
    enum class Capability { TTS, ASR, BOTH }

    companion object {
        /** base URL 标记命中的模板，无则 null。 */
        fun template(forBaseURL: String?): VoiceVendorTemplate? {
            val base = forBaseURL?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return all.firstOrNull { tpl -> tpl.baseURLMarkers.any { base.contains(it) } }
        }

        /** 为命中模板的实例构造种子 ModelEntry 表。 */
        fun mockEntries(instance: ProviderInstance): List<ModelEntry> {
            val tpl = template(instance.customBaseURL) ?: return emptyList()
            return tpl.mockModels.map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
        }

        private fun tts(id: String, name: String, provider: String) = LLMModel(
            id = id, displayName = name, provider = provider,
            outputModalities = listOf("audio"),
        )

        private fun asr(id: String, name: String, provider: String) = LLMModel(
            id = id, displayName = name, provider = provider,
            inputModalities = listOf("audio"),
        )

        val all: List<VoiceVendorTemplate> = listOf(
            VoiceVendorTemplate(
                id = "elevenlabs",
                name = "ElevenLabs",
                providerType = ProviderType.openAI,
                baseURL = "https://api.elevenlabs.io",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("elevenlabs"),
                mockModels = listOf(
                    // 机型 `id` 即 ElevenLabs voice_id。
                    tts("21m00Tcm4TlvDq8ikWAM", "Rachel (EN, F)", "elevenlabs"),
                    tts("pNInz6obpgDQGcFmaJgB", "Adam (EN, M)", "elevenlabs"),
                    tts("EXAVITQu4vr4xnSDxMaL", "Bella (EN, F)", "elevenlabs"),
                    tts("ErXwobaYiN019PkySvjV", "Antoni (EN, M)", "elevenlabs"),
                ),
            ),
            VoiceVendorTemplate(
                id = "deepgram",
                name = "Deepgram",
                providerType = ProviderType.openAI,
                baseURL = "https://api.deepgram.com",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("deepgram"),
                mockModels = listOf(
                    asr("nova-2", "Nova-2 (ASR)", "deepgram"),
                    asr("nova-3", "Nova-3 (ASR)", "deepgram"),
                    tts("aura-asteria-en", "Aura Asteria (TTS, EN F)", "deepgram"),
                    tts("aura-luna-en", "Aura Luna (TTS, EN F)", "deepgram"),
                    tts("aura-orion-en", "Aura Orion (TTS, EN M)", "deepgram"),
                ),
            ),
            VoiceVendorTemplate(
                id = "azure-tts",
                name = "Azure TTS",
                providerType = ProviderType.openAI,
                baseURL = "https://eastasia.tts.speech.microsoft.com",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("tts.speech.microsoft.com"),
                mockModels = listOf(
                    // 中文（普通话）
                    tts("zh-CN-XiaoxiaoNeural", "Xiaoxiao (ZH, F)", "azure-tts"),
                    tts("zh-CN-YunxiNeural", "Yunxi (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunyangNeural", "Yunyang (ZH, M)", "azure-tts"),
                    tts("zh-CN-XiaoyiNeural", "Xiaoyi (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaochenNeural", "Xiaochen (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaohanNeural", "Xiaohan (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaomengNeural", "Xiaomeng (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaomoNeural", "Xiaomo (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaoruiNeural", "Xiaorui (ZH, F)", "azure-tts"),
                    tts("zh-CN-XiaoshuangNeural", "Xiaoshuang (ZH, F, Child)", "azure-tts"),
                    tts("zh-CN-XiaoyouNeural", "Xiaoyou (ZH, F, Child)", "azure-tts"),
                    tts("zh-CN-XiaozhenNeural", "Xiaozhen (ZH, F)", "azure-tts"),
                    tts("zh-CN-YunfengNeural", "Yunfeng (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunhaoNeural", "Yunhao (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunjianNeural", "Yunjian (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunxiaNeural", "Yunxia (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunyeNeural", "Yunye (ZH, M)", "azure-tts"),
                    tts("zh-CN-YunzeNeural", "Yunze (ZH, M)", "azure-tts"),
                    // 中文（粤语）
                    tts("zh-HK-HiuMaanNeural", "HiuMaan (HK, F)", "azure-tts"),
                    tts("zh-HK-WanLungNeural", "WanLung (HK, M)", "azure-tts"),
                    tts("zh-HK-HiuGaaiNeural", "HiuGaai (HK, F)", "azure-tts"),
                    // 中文（台湾）
                    tts("zh-TW-HsiaoChenNeural", "HsiaoChen (TW, F)", "azure-tts"),
                    tts("zh-TW-YunJheNeural", "YunJhe (TW, M)", "azure-tts"),
                    tts("zh-TW-HsiaoYuNeural", "HsiaoYu (TW, F)", "azure-tts"),
                    // 英语（美音）——常选
                    tts("en-US-JennyNeural", "Jenny (EN, F)", "azure-tts"),
                    tts("en-US-GuyNeural", "Guy (EN, M)", "azure-tts"),
                    tts("en-US-AriaNeural", "Aria (EN, F)", "azure-tts"),
                    tts("en-US-DavisNeural", "Davis (EN, M)", "azure-tts"),
                    tts("en-US-AvaNeural", "Ava (EN, F)", "azure-tts"),
                    tts("en-US-AndrewNeural", "Andrew (EN, M)", "azure-tts"),
                    tts("en-US-EmmaNeural", "Emma (EN, F)", "azure-tts"),
                    tts("en-US-BrianNeural", "Brian (EN, M)", "azure-tts"),
                    // 日语
                    tts("ja-JP-NanamiNeural", "Nanami (JA, F)", "azure-tts"),
                    tts("ja-JP-KeitaNeural", "Keita (JA, M)", "azure-tts"),
                    tts("ja-JP-AoiNeural", "Aoi (JA, F)", "azure-tts"),
                    tts("ja-JP-DaichiNeural", "Daichi (JA, M)", "azure-tts"),
                    tts("ja-JP-ShioriNeural", "Shiori (JA, F)", "azure-tts"),
                    // 韩语
                    tts("ko-KR-SunHiNeural", "SunHi (KO, F)", "azure-tts"),
                    tts("ko-KR-InJoonNeural", "InJoon (KO, M)", "azure-tts"),
                ),
                note = "Enter the Azure Speech Services subscription key. Set the base URL to your region, e.g. https://eastasia.tts.speech.microsoft.com",
            ),
            VoiceVendorTemplate(
                id = "minimax",
                name = "MiniMax",
                providerType = ProviderType.anthropic,
                baseURL = "https://api.minimax.io",
                appendV1 = false,
                capability = Capability.TTS,
                baseURLMarkers = listOf("minimax"),
                mockModels = listOf(
                    tts("speech-2.8-hd", "MiniMax Speech 2.8 HD", "minimax"),
                    tts("speech-2.8-turbo", "MiniMax Speech 2.8 Turbo", "minimax"),
                ),
            ),
            VoiceVendorTemplate(
                id = "alibaba",
                name = "Alibaba Bailian",
                providerType = ProviderType.openAI,
                baseURL = "https://dashscope.aliyuncs.com/compatible-mode",
                appendV1 = true,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("dashscope"),
                mockModels = listOf(
                    asr("paraformer-realtime-v2", "Paraformer Realtime v2", "alibaba"),
                    tts("cosyvoice-v2", "CosyVoice v2", "alibaba"),
                ),
            ),
            VoiceVendorTemplate(
                id = "doubao",
                name = "Doubao (Volcano)",
                providerType = ProviderType.openAI,
                baseURL = "https://openspeech.bytedance.com",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("openspeech.bytedance", "volcano"),
                mockModels = listOf(
                    asr("bigmodel", "Doubao ASR (bigmodel)", "doubao"),
                    // Seed TTS 2.0（大模型，uranus）
                    tts("zh_female_cancan_uranus_bigtts", "灿灿 (通用, 女)", "doubao"),
                    tts("zh_female_vv_uranus_bigtts", "Vivi (表现力, 女)", "doubao"),
                    tts("zh_male_liufei_uranus_bigtts", "刘飞 (通用, 男)", "doubao"),
                    tts("zh_male_m191_uranus_bigtts", "云舟 (清爽, 男)", "doubao"),
                    // Seed TTS 1.0（大模型，moon）
                    tts("zh_female_shuangkuaisisi_moon_bigtts", "爽快思思 (爽朗, 女)", "doubao"),
                    tts("zh_female_sajiaonvyou_moon_bigtts", "撒娇女友 (撒娇, 女)", "doubao"),
                    tts("zh_female_gaolengyujie_moon_bigtts", "高冷御姐 (御姐, 女)", "doubao"),
                    tts("multi_female_shuangkuaisisi_moon_bigtts", "爽快思思 (多语, 女)", "doubao"),
                ),
                note = "Enter the API Key from the Volcano Engine new console.",
            ),
            VoiceVendorTemplate(
                id = "xunfei",
                name = "iFlytek (Xunfei)",
                providerType = ProviderType.openAI,
                baseURL = "https://iat-api.xfyun.cn",
                appendV1 = false,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("xfyun"),
                mockModels = listOf(
                    asr("iat", "iFlytek IAT (ASR)", "xunfei"),
                    tts("xiaoyan", "讯飞·晓燕 (TTS, 中文女)", "xunfei"),
                    tts("aisjiuxu", "讯飞·许久 (TTS, 中文男)", "xunfei"),
                ),
                note = "iFlytek needs App ID and API Secret — enter them as \"appId;apiKey;apiSecret\" in the API Key field.",
            ),
            VoiceVendorTemplate(
                id = "mimo",
                name = "Xiaomi MiMo",
                providerType = ProviderType.openAI,
                baseURL = "https://api.xiaomimimo.com",
                appendV1 = true,
                capability = Capability.BOTH,
                baseURLMarkers = listOf("xiaomimimo"),
                mockModels = listOf(
                    asr("mimo-v2.5-asr", "MiMo ASR v2.5", "mimo"),
                    tts("mimo_default", "MiMo 默认 (Auto)", "mimo"),
                    tts("冰糖", "冰糖 (中文, 女)", "mimo"),
                    tts("茉莉", "茉莉 (中文, 女)", "mimo"),
                    tts("苏打", "苏打 (中文, 男)", "mimo"),
                    tts("白桦", "白桦 (中文, 男)", "mimo"),
                    tts("Mia", "Mia (EN, F)", "mimo"),
                    tts("Chloe", "Chloe (EN, F)", "mimo"),
                    tts("Milo", "Milo (EN, M)", "mimo"),
                    tts("Dean", "Dean (EN, M)", "mimo"),
                ),
            ),
        )
    }
}
