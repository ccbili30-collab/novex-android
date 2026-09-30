@file:Suppress("unused")

package com.openminis.app.deeplink

import novex.android.navlink.PendingLinkFx

/**
 * 深链挂起副作用的旧路径门面（P3.5c 真重写收编）。
 *
 * 状态实现在 [PendingLinkFx]。嵌套类型 [ChatAction] / [PendingChatInput]
 * 被 UI 以 `DeepLinkCoordinator.ChatAction` / 待消费 `PendingChatInput`
 * 形态钉死，无法经 typealias 转发——正典留此、实现侧反向引用；三个
 * 一次性信号的置放与消费语义均为冻结面。
 */
object DeepLinkCoordinator {

    enum class ChatAction { OPEN_CAMERA, OPEN_CREATION_TOOL, ORGANIZE_IMPORTED_CARD }

    data class PendingChatInput(val sessionId: String, val text: String)

    val pendingLogsTab get() = PendingLinkFx.pendingLogsTab
    fun setPendingLogsTab(tab: String?) = PendingLinkFx.setPendingLogsTab(tab)
    fun consumePendingLogsTab(): String? = PendingLinkFx.consumePendingLogsTab()

    val pendingChatAction get() = PendingLinkFx.pendingChatAction
    fun setPendingChatAction(action: ChatAction) = PendingLinkFx.setPendingChatAction(action)
    fun consumePendingChatAction(): ChatAction? = PendingLinkFx.consumePendingChatAction()

    val pendingChatInput get() = PendingLinkFx.pendingChatInput
    fun setPendingChatInput(sessionId: String, text: String) =
        PendingLinkFx.setPendingChatInput(sessionId, text)

    /** 仅当目标正是 [sessionId] 时取走文本；不匹配的原样留存。 */
    fun consumePendingChatInput(sessionId: String): String? =
        PendingLinkFx.consumePendingChatInput(sessionId)
}
