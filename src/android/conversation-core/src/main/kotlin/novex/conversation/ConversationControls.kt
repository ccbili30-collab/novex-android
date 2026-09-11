package novex.conversation

/** 快捷操作属于对话，不需要世界、角色或旧文游对象。此处不执行网络或保存。 */
sealed interface ControlBehavior {
    data class Action(val instruction: String) : ControlBehavior
    data class View(val stateKeys: List<String> = emptyList()) : ControlBehavior
}

data class ControlDefinition(
    val key: String,
    val label: String,
    val behavior: ControlBehavior,
    val enabled: Boolean = true,
)

/** 注册编号由调用方生成并随对话保存；消息编号定位当前回复路径上的注册。 */
data class ControlRegistration(
    val id: String,
    val messageId: String?,
    val definitions: List<ControlDefinition>,
)

data class ControlHandle(val conversationId: String, val registrationId: String, val key: String)
data class VisibleControl(val handle: ControlHandle, val definition: ControlDefinition)

sealed interface ControlInvocation {
    /** 交给正常发送入口，不表示消息已发送或模型已执行。 */
    data class SendInstruction(val text: String) : ControlInvocation
    /** 空字段列表沿用旧行为：展示当前全部状态。它不是发消息。 */
    data class ShowState(val title: String, val stateKeys: List<String>) : ControlInvocation
}

/** 纯对话逻辑；导出的快照是待保存数据，不是保存成功回执。 */
class ConversationControls private constructor(
    val conversationId: String,
    private val registrations: List<ControlRegistration>,
) {
    fun snapshot(): List<ControlRegistration> = registrations.map(::detached)

    /** 完整批次验证通过后替换同一消息上的注册，不静默截断或忽略坏条目。 */
    fun register(registration: ControlRegistration): ConversationControls {
        validate(registration)
        require(registrations.none { it.id == registration.id }) { "注册编号已存在" }
        return restore(conversationId,
            registrations.filterNot { it.messageId == registration.messageId } + registration)
    }

    /** 路径由消息树提供，从祖先到当前消息；不根据卡片或模式猜分支。 */
    fun visible(activePath: List<String>): List<VisibleControl> {
        require(activePath.all(String::isNotBlank) && activePath.distinct().size == activePath.size) { "消息路径无效" }
        val rank = activePath.withIndex().associate { it.value to it.index }
        val visible = registrations.filter { it.messageId == null || it.messageId in rank }
            .flatMap { batch -> batch.definitions.map { batch to it } }
        val winners = visible.groupBy { it.second.key }.mapValues { (_, values) ->
            values.maxBy { (batch, _) -> batch.messageId?.let { rank.getValue(it) } ?: -1 }
        }
        return visible.filter { (batch, item) -> item.enabled && winners.getValue(item.key).first.id == batch.id }
            .map { (batch, item) -> VisibleControl(ControlHandle(conversationId, batch.id, item.key), detached(item)) }
    }

    /** 点击时重新解析当前分支，拒绝旧菜单、其他对话或已被替换的操作。 */
    fun invoke(handle: ControlHandle, activePath: List<String>): ControlInvocation {
        require(handle.conversationId == conversationId) { "快捷操作不属于当前对话" }
        val control = visible(activePath).singleOrNull { it.handle == handle }
            ?: throw IllegalArgumentException("快捷操作已变化或不在当前分支，请重新展开")
        return when (val behavior = control.definition.behavior) {
            is ControlBehavior.Action -> ControlInvocation.SendInstruction(behavior.instruction)
            is ControlBehavior.View -> ControlInvocation.ShowState(control.definition.label, behavior.stateKeys.toList())
        }
    }

    companion object {
        fun empty(conversationId: String) = restore(conversationId, emptyList())

        /** 用于对话重开；字节编解码和正式共同保存由存储层负责。 */
        fun restore(conversationId: String, registrations: List<ControlRegistration>): ConversationControls {
            require(conversationId.isNotBlank()) { "对话编号不能为空" }
            registrations.forEach(::validate)
            require(registrations.map { it.id }.distinct().size == registrations.size) { "注册编号重复" }
            require(registrations.map { it.messageId }.distinct().size == registrations.size) { "同一消息存在多份注册" }
            return ConversationControls(conversationId, registrations.map(::detached))
        }
    }
}

private fun validate(registration: ControlRegistration) {
    require(registration.id.isNotBlank()) { "注册编号不能为空" }
    require(registration.messageId == null || registration.messageId.isNotBlank()) { "消息编号不能为空" }
    require(registration.definitions.isNotEmpty()) { "至少需要一个快捷操作" }
    require(registration.definitions.map { it.key }.distinct().size == registration.definitions.size) { "操作标识重复" }
    registration.definitions.forEach { item ->
        require(item.key.isNotBlank() && item.label.isNotBlank()) { "操作标识和名称不能为空" }
        when (val behavior = item.behavior) {
            is ControlBehavior.Action -> require(behavior.instruction.isNotBlank()) { "动作指令不能为空" }
            is ControlBehavior.View -> require(behavior.stateKeys.all(String::isNotBlank)) { "状态字段名不能为空" }
        }
    }
}

private fun detached(item: ControlDefinition): ControlDefinition = item.copy(behavior = when (val behavior = item.behavior) {
    is ControlBehavior.Action -> behavior.copy()
    is ControlBehavior.View -> behavior.copy(stateKeys = behavior.stateKeys.toList())
})
private fun detached(batch: ControlRegistration) = batch.copy(definitions = batch.definitions.map(::detached))
