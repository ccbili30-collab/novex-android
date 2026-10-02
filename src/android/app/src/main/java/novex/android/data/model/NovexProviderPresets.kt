package novex.android.data.model

import java.net.URI

/*
 * 官方供应商接入预设（provider onboarding）：「选择接入方式」三卡（智谱 /
 * 深度求索 / 自定义）中前两卡的数据事实——base URL、取钥链接与静态模型目录。
 *
 * 这里只放厂商成文事实，不放推断：
 *  - 智谱没有公开 /models 端点，目录只能静态内置（glm-5.3 与 glm-5.3-flash
 *    两个文档确证 id，docs.bigmodel.cn 模型概览核对；上下文/输出上限只写
 *    文档明示的值）；
 *  - DeepSeek 官方支持 /models 拉取，静态表仅作拉取失败的兜底。
 * 预设落库复用 ProviderInstance 既有字段（customBaseURL / appendV1Suffix /
 * keyHelpUrl），不改 Room schema、不改 prefs 键名、不改 JSON 契约。
 */
object NovexProviderPresets {

    /** 导航参数用预设键（add_provider?preset=…）。 */
    const val PRESET_ZHIPU = "zhipu"
    const val PRESET_DEEPSEEK = "deepseek"

    // ---- 智谱（Z.ai / BigModel） -----------------------------------------

    /**
     * 智谱官方 OpenAI 兼容端点的完整基址。路径已是终态（/api/paas/v4），
     * 绝不能追加 /v1——预设必须以 appendV1Suffix=false 落库。
     */
    const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val ZHIPU_KEY_HELP_URL = "https://open.bigmodel.cn/usercenter/apikeys"
    const val ZHIPU_LABEL = "智谱"

    /** 智谱官方 host（思考席位与静态目录的命中判定用）。 */
    val ZHIPU_HOST = "open.bigmodel.cn"

    fun isZhipuBase(base: String?): Boolean =
        runCatching { URI(base.orEmpty().trim()).host?.lowercase() }.getOrNull() == ZHIPU_HOST

    /** DeepSeek 官方 host（api.deepseek.com），/models 兜底目录的命中判定用。 */
    fun isDeepSeekBase(base: String?): Boolean =
        runCatching { URI(base.orEmpty().trim()).host?.lowercase() }.getOrNull() == DEEPSEEK_HOST

    val DEEPSEEK_HOST = "api.deepseek.com"

    /**
     * 智谱静态模型目录：官方无 /models 端点，目录只能内置（第三方实践可用但
     * 未成文，保守取舍为仅静态）。
     * 事实来源 docs.bigmodel.cn（2026-10 核对）：glm-5.3 与 glm-5.3-flash
     * 上下文 1M / 最大输出 128K；thinking 能力页写明 5.3 系 API 档位仅
     * low/high/max（「其余输入将报错」，默认 max）且不再支持 disabled——两条
     * 都声明该档位枚举，解析器才会在 xhigh 等未声明档上向下吸附。目录只收
     * 文档确证的型号，型号一个不发明（glm-5.3-air 查无此 ID，不入表）。
     */
    val zhipuStaticModels: List<LLMModel> = listOf(
        LLMModel(
            id = "glm-5.3",
            displayName = "GLM-5.3",
            provider = "智谱",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
        ),
        LLMModel(
            id = "glm-5.3-flash",
            displayName = "GLM-5.3-Flash",
            provider = "智谱",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
            reasoningEffortValues = listOf("low", "high", "max"),
        ),
    )

    // ---- 深度求索（DeepSeek） --------------------------------------------

    /** DeepSeek 官方端点；/v1 后缀官方兼容，appendV1Suffix 保持默认开。 */
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEEPSEEK_KEY_HELP_URL = "https://platform.deepseek.com/api_keys"
    const val DEEPSEEK_LABEL = "深度求索"

    /** DeepSeek 官方支持 /models；此表仅在拉取失败时兜底。 */
    val deepSeekFallbackModels: List<LLMModel> = listOf(
        LLMModel(
            id = "deepseek-chat",
            displayName = "DeepSeek Chat",
            provider = "DeepSeek",
            supportsReasoning = null,
        ),
        LLMModel(
            id = "deepseek-reasoner",
            displayName = "DeepSeek Reasoner",
            provider = "DeepSeek",
            supportsReasoning = true,
        ),
    )
}
