package novex.android.thinking

/**
 * 一条思考规则（自定义层的值类型；P3.2 真重写版）。
 *
 * 解析模型：规则表自上而下，第一条 scope 命中者胜出、停止；命中的 [wireFormat]
 * 为 null 表示「本规则对线形态不表态」，解析落入回退层（形状默认为根级档位）。
 * 内置规则不落库、不进本类型的 CUSTOM 位；它们由解析器的座次表给出，仅供 UI
 * 展示时物化。
 */
data class ThinkingContract(
    val kind: Kind,
    val scope: Scope,
    val wireFormat: ThinkingWireFormat?,
    val reasoningEcho: ReasoningEchoPolicy? = null,
    val label: String,
    val id: String = "",
) {
    /** 自定义规则才可编辑/删除；内置规则只能被上位覆盖。 */
    val isEditable: Boolean get() = kind == Kind.CUSTOM

    /**
     * 稳定身份：自定义规则带持久化 UUID；内置规则由 label+scope 派生。
     *
     * scope 必须进 id：五条 OpenAI 原生规则刻意共用 label "openai-native"，
     * 纯 label id 会把五条折叠成一条，列表渲染按 key 去重后首行出现五次。
     */
    val stableId: String
        get() = id.ifEmpty {
            val shape = (scope as? Scope.ModelPattern)?.let { "modelPattern:${it.pattern}" } ?: "allModels"
            "builtin:$label:$shape"
        }

    enum class Kind {
        /** 用户自建。恒排在内置之上。 */
        CUSTOM,

        /** 厂商成文形态。 */
        OFFICIAL_VENDOR,

        /** providerType 兜底，scope 恒 AllModels。 */
        PROVIDER_TYPE_DEFAULT,
    }

    /**
     * 规则的匹配面。
     *
     * 比较前双方做 `.`→`-` 归一：目录把 Claude id 写成连字符、第三方代理回点，
     * 一种拼写的规则集在另一种拼写下会整体失配（MiMo 的 v2.5/2.5 同理）。
     * 匹配实现为锚定正则（`*` → 任意序列，其余按字面转义）。
     */
    sealed interface Scope {
        data object AllModels : Scope
        data class ModelPattern(val pattern: String) : Scope

        fun matches(modelId: String): Boolean =
            this is AllModels || (this as? ModelPattern)?.let { Scope.globs(it.pattern, modelId) } == true

        companion object {
            private val cache = java.util.concurrent.ConcurrentHashMap<String, java.util.regex.Pattern>()

            /** pattern → 锚定正则；命中缓存避免每请求重编译。 */
            private fun compiled(pattern: String): java.util.regex.Pattern = cache.computeIfAbsent(pattern) { raw ->
                val escaped = raw.split('*').joinToString(".*") { java.util.regex.Pattern.quote(it) }
                java.util.regex.Pattern.compile(escaped)
            }

            /** 双方归一（小写 + 点换连字符）后锚定整串匹配。 */
            fun globs(pattern: String, modelId: String): Boolean =
                compiled(pattern).matcher(canon(modelId)).matches()

            private fun canon(id: String): String = id.lowercase().replace('.', '-')
        }
    }
}
