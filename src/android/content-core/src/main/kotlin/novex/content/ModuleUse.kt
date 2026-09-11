package novex.content

/** 模块默认携带规则；不包含权限或对话状态。未配置不等于自动全文携带。 */
sealed interface ModuleUse {
    data object Always : ModuleUse
    data object Manual : ModuleUse
    data class Keywords(val words: List<String>, val caseSensitive: Boolean, val requireAll: Boolean) : ModuleUse {
        init { require(words.isNotEmpty() && words.all { it.isNotBlank() } && words.distinct().size == words.size) { "触发词不能为空或重复" } }
    }
}
