package novex.conversation

import novex.content.*

/** 已明确采用的资料快照。管理范围不进入此清单，不通过管理权推导采用。 */
data class AdoptedSource(val cardId: String, val revision: String, val modules: List<ContentModule>)
data class MaterialScope(val adopted: List<AdoptedSource>, val managedCardIds: Set<String>)
enum class MessageRole { USER, ASSISTANT, TOOL }
data class TriggerMessage(val id: String, val role: MessageRole, val text: String)
/** 窗口大小与扫描角色显式传入，不在核心中写死产品默认值。 */
data class TriggerWindow(val count: Int, val roles: Set<MessageRole>) {
    init { require(count > 0 && roles.isNotEmpty()) { "需明确触发检查范围" } }
}
sealed interface UseOverride {
    data object Disabled : UseOverride
    data class Rule(val use: ModuleUse) : UseOverride
}
enum class AdoptionReason { ALWAYS, KEYWORD_MATCH, MANUAL_SELECTED, CONDITION_MISSED, MANUAL_NOT_SELECTED, DISABLED, UNCONFIGURED, AI_SELECTED, AI_NOT_SELECTED, RECOVERY_READ,
    /** [T-stage1-tags] 路由标签分流出的模块——开局激活协议（阶段 1 PR-B）接管其注入。 */
    ROUTED_ACTIVATION, ROUTED_SLOT,
    /** [T-stage1-activation] 已由开局资料包写入历史承载（净眼 P2-1 关闸）——材料流不再重复注入。 */
    ACTIVATION_CARRIED }
data class ModuleDecision(val cardId: String, val revision: String, val module: ContentModule,
                          val selected: Boolean, val reason: AdoptionReason, val matchedWords: List<String> = emptyList())
data class MaterialPlan(val decisions: List<ModuleDecision>) {
    val selected: List<ModuleDecision> get() = decisions.filter { it.selected }
}

/** 每轮只返回一份采用计划；不保存上轮命中，不追加隐藏历史，不代表已发送给模型。 */
object ModuleAdoption {
    fun plan(scope: MaterialScope, messages: List<TriggerMessage>, window: TriggerWindow,
             overrides: Map<String, UseOverride> = emptyMap(), manualForThisRequest: Set<String> = emptySet(),
             historyCarriedModuleIds: Set<String> = emptySet()): MaterialPlan {
        val texts = messages.filter { it.role in window.roles }.takeLast(window.count).map { it.text }
        val candidates = linkedMapOf<String, Pair<AdoptedSource, ContentModule>>()
        scope.adopted.forEach { source ->
            require(source.cardId.isNotBlank() && source.revision.isNotBlank()) { "资料来源或版本缺失" }
            source.modules.flattenModules().forEach { module ->
                val previous = candidates[module.id]
                require(previous == null || (previous.first.cardId == source.cardId && previous.first.revision == source.revision && previous.second == module)) {
                    "同一模块出现不同来源或版本，无法安全组装"
                }
                candidates.putIfAbsent(module.id, source to module)
            }
        }
        return MaterialPlan(candidates.values.map { (source, module) ->
            val override = overrides[module.id]
            fun decision(selected: Boolean, reason: AdoptionReason, words: List<String> = emptyList()) =
                ModuleDecision(source.cardId, source.revision, module, selected, reason, words)
            // [T-stage1-tags] 路由标签先于 use 判定：default=视同 Always
            // （开局/全程进，PR-B 落地后由激活协议写入历史承载）；per_turn/
            // style=路由到槽位，不进材料流（PR-B 消费）；standby=沿用 use
            // 三态触发。null（存量卡）解析：作者已设 use 触发规则（keywords/
            // manual/always）=尊重既有意图按 STANDBY 走规则；完全未配置
            // （use 也为 null）才取 DEFAULT"存量全标默认"（总纲——能塞就
            // 塞，AI 选择器管辖的未配置模块从此直接进材料流）。
            val effectiveRouting = module.effectiveRouting()
            if (override == UseOverride.Disabled) decision(false, AdoptionReason.DISABLED)
            else when (effectiveRouting) {
                // [T-stage1-activation]（净眼 P2-1）已由开局资料包写入历史的
                // default 模块不再走材料流（双重注入会翻倍上下文）；未激活
                // （激活失败/未触发）时材料流全进仍是保底。
                ModuleRouting.DEFAULT ->
                    if (module.id in historyCarriedModuleIds) decision(false, AdoptionReason.ACTIVATION_CARRIED)
                    else decision(true, AdoptionReason.ROUTED_ACTIVATION)
                ModuleRouting.PER_TURN, ModuleRouting.STYLE -> decision(false, AdoptionReason.ROUTED_SLOT)
                ModuleRouting.STANDBY -> when (val rule = (override as? UseOverride.Rule)?.use ?: module.use) {
                ModuleUse.Always -> decision(true, AdoptionReason.ALWAYS)
                ModuleUse.Manual -> if (module.id in manualForThisRequest) decision(true, AdoptionReason.MANUAL_SELECTED)
                    else decision(false, AdoptionReason.MANUAL_NOT_SELECTED)
                is ModuleUse.Keywords -> {
                    val matched = rule.words.filter { word -> texts.any { it.contains(word, ignoreCase = !rule.caseSensitive) } }
                    val selected = if (rule.requireAll) matched.size == rule.words.size else matched.isNotEmpty()
                    decision(selected, if (selected) AdoptionReason.KEYWORD_MATCH else AdoptionReason.CONDITION_MISSED, matched)
                }
                null -> decision(false, AdoptionReason.UNCONFIGURED)
                }
            }
        })
    }
}
