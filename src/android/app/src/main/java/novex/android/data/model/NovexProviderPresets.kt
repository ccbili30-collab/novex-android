package novex.android.data.model

import java.net.URI

/*
 * 官方供应商接入预设（provider onboarding）：「选择接入方式」三卡（智谱 /
 * 深度求索 / 自定义）中前两卡的数据事实——base URL、取钥链接与静态模型目录。
 *
 * 这里只放厂商成文事实，不放推断：
 *  - 智谱没有公开 /models 端点，目录只能静态内置（glm-5.3 系三个 id，
 *    docs.bigmodel.cn 模型概览核对；上下文/输出上限只写文档明示的值）；
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
     * 智谱静态模型目录：官方无 /models 端点，目录只能内置。
     * 事实来源 docs.bigmodel.cn（2026-10 核对）：glm-5.3 与 glm-5.3-flash
     * 上下文 1M / 最大输出 128K；glm-5.3 文档声明 reasoning_effort 取
     * low/high/max 且 thinking 不支持 disabled。未文档化的字段留空（null），
     * 交给既有推断链，不发明数值。
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
            id = "glm-5.3-air",
            displayName = "GLM-5.3-Air",
            provider = "智谱",
            supportsReasoning = true,
        ),
        LLMModel(
            id = "glm-5.3-flash",
            displayName = "GLM-5.3-Flash",
            provider = "智谱",
            contextWindow = 1_000_000,
            maxOutputTokens = 128_000,
            supportsReasoning = true,
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
