package novex.android.thinking

/**
 * 思考规则（自定义层）的值类型（P3.2 自有实现，替换上游 provider/thinking 包的
 * 规则模型）。内置规则不构造成本类型实例——解析器内置表直接引用同一
 * [ThinkingWireFormat] 词表；持久化只发生在 [Kind.CUSTOM] 规则上。
 *
 * 解析语义（与被替换实现一致）：规则表自上而下第一命中即胜出、停止；命中规则
 * wireFormat 为 null 表示「该规则对形态无意见」，落入回退层。
 */
data class ThinkingContract(
    val kind: Kind,
    val scope: Scope,
    /**
     * null 表示「本规则对线形态无意见」——解析落入下一回退层（阶段 B）。
     */
    val wireFormat: ThinkingWireFormat?,
    /** 回放合同（字段名 × 时机）；回放执行点在适配器的消息装配。 */
    val reasoningEcho: ReasoningEchoPolicy? = null,
    /** 人类可读标识，进入解析 trace。 */
    val label: String,
    /**
     * 稳定身份。自定义规则携带持久化 UUID；内置规则不传，由 scope 派生。
     *
     * 只按 label 派生是真实的坑：五条 OpenAI 原生规则（o 前缀与 gpt-5 前缀等）
     * 刻意共享 label "openai-native"，纯 label id 会把它们折叠成一个——Compose
     * items(key=) 按 key 去重，首行会渲染五次（iOS 在 SwiftUI ForEach 上踩过
     * 同一坑）。scope 入 id 使「匹配面不同的规则」不可能撞车。
     */
    val id: String = "",
) {
    /** 用户可否编辑/删除。内置规则只可覆盖。 */
    val isEditable: Boolean get() = kind == Kind.CUSTOM

    /** 内置规则的确定性 id（含 scope）；自定义规则保留自身 id。 */
    val stableId: String
        get() {
            if (id.isNotEmpty()) return id
            val sk = if (scope is Scope.ModelPattern) "modelPattern" else "allModels"
            val sp = (scope as? Scope.ModelPattern)?.pattern ?: "*"
            return "builtin:$label:$sk:$sp"
        }

    enum class Kind {
        /** 用户自建规则。恒排在内置规则之上。 */
        CUSTOM,

        /** 特定厂商的成文形态（DeepSeek 官方、Venice、Ark…）。 */
        OFFICIAL_VENDOR,

        /** providerType 的兜底。scope 恒 AllModels，保证解析不会落空。 */
        PROVIDER_TYPE_DEFAULT,
    }

    sealed interface Scope {
        data object AllModels : Scope

        /** 对模型 id 的通配匹配，`*` 是唯一通配符。 */
        data class ModelPattern(val pattern: String) : Scope

        fun matches(modelId: String): Boolean = when (this) {
            is AllModels -> true
            is ModelPattern -> glob(
                pattern.lowercase().replace('.', '-'),
                modelId.lowercase().replace('.', '-'),
            )
        }

        companion object {
            /**
             * 最小 glob：`*` 匹配任意字符序列，其余字面。手写而非正则，语义与
             * 被替换实现逐字符一致。
             *
             * [matches] 的 `.`→`-` 归一不是装饰：目录把 Claude id 拼作连字符
             * （claude-opus-4-8）而第三方代理回点（claude-opus-4.8），一种拼写
             * 写的规则集在另一种拼写下全不命中——iOS 5aa9dc64 归一后才好。MiMo
             * 反向同理：文档写 mimo-2.5、线上 API 是 mimo-v2.5（72968c4f）。
             */
            fun glob(pattern: String, input: String): Boolean {
                val parts = pattern.split("*")
                if (parts.size == 1) return input == pattern

                var cursor = 0
                for ((i, part) in parts.withIndex()) {
                    if (part.isEmpty()) continue
                    if (i == 0) {
                        if (!input.startsWith(part)) return false
                        cursor = part.length
                        continue
                    }
                    if (i == parts.size - 1 && !pattern.endsWith("*")) {
                        if (!input.endsWith(part)) return false
                        if (input.length - cursor < part.length) return false
                        continue
                    }
                    val found = input.indexOf(part, cursor)
                    if (found < 0) return false
                    cursor = found + part.length
                }
                return true
            }
        }
    }
}
