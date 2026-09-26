package novex.content

/** 模块默认携带规则；不包含权限或对话状态。未配置不等于自动全文携带。 */
sealed interface ModuleUse {
    data object Always : ModuleUse
    data object Manual : ModuleUse
    data class Keywords(val words: List<String>, val caseSensitive: Boolean, val requireAll: Boolean) : ModuleUse {
        init { require(words.isNotEmpty() && words.all { it.isNotBlank() } && words.distinct().size == words.size) { "触发词不能为空或重复" } }
    }
}

/**
 * [T-stage1-tags] 路由解析的唯一计算式（净眼 P3 防漂移）：显式标签优先；
 * 未设 routing 但已设 use 触发规则 → STANDBY（尊重作者显式意图）；
 * 完全未配置 → DEFAULT（"存量全标默认"，能塞就塞）。ModuleAdoption、
 * read_card 回显、模块选项对话框与开局激活协议共用本式。
 */
fun ContentModule.effectiveRouting(): ModuleRouting =
    routing ?: if (use != null) ModuleRouting.STANDBY else ModuleRouting.DEFAULT

/** [T-stage1-tags] 时间性缺省即 CONSTANT（安全侧：可重注入）。 */
fun ContentModule.effectiveTemporality(): ModuleTemporality =
    temporality ?: ModuleTemporality.CONSTANT
