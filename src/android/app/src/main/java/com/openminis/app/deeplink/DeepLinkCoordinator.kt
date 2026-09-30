package com.openminis.app.deeplink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds pending deep-link side-effects that outlive a single navigation event.
 * Mirrors iOS DeepLinkCoordinator.
 *
 * [P3.3 裁军] pendingHtmlPreview（HTML 预览固定捷径）与 ChatAction.
 * START_VOICE（voice_chat 快捷动作）随内置浏览器/语音全家退役删除。
 */
object DeepLinkCoordinator {

    /**
     * Optional `?tab=…` from `minis://settings/logs?tab=…`. The Logs screen
     * reads this on appear to land on the right segmented-control tab.
     * Cleared by the screen after consumption. Mirrors iOS
     * DeepLinkCoordinator.pendingLogsTab. ("config-audit" tab 已随
     * minis-config 体系退役，仅剩默认日志页。)
     */
    private val _pendingLogsTab = MutableStateFlow<String?>(null)
    val pendingLogsTab: StateFlow<String?> = _pendingLogsTab.asStateFlow()

    fun setPendingLogsTab(tab: String?) { _pendingLogsTab.value = tab }
    fun consumePendingLogsTab(): String? {
        val current = _pendingLogsTab.value
        _pendingLogsTab.value = null
        return current
    }

    /**
     * App-icon quick-action that a freshly-opened ChatScreen should auto-
     * trigger on first compose. Mirrors iOS `pendingChatAction` on
     * AIChatViewModel. Set by [com.openminis.app.MainActivity] /
     * [com.openminis.app.ui.navigation.AppNavigation] when the launch
     * intent carries `minis://action/camera_chat`; consumed exactly once by
     * ChatScreen so re-entering the same chat later doesn't fire the action
     * again.
     */
    enum class ChatAction { OPEN_CAMERA, OPEN_CREATION_TOOL, ORGANIZE_IMPORTED_CARD }

    private val _pendingChatAction = MutableStateFlow<ChatAction?>(null)
    val pendingChatAction: StateFlow<ChatAction?> = _pendingChatAction.asStateFlow()

    fun setPendingChatAction(action: ChatAction) {
        _pendingChatAction.value = action
    }

    fun consumePendingChatAction(): ChatAction? {
        val current = _pendingChatAction.value
        _pendingChatAction.value = null
        return current
    }

    /**
     * Pending composer prefill for a specific freshly-opened ChatScreen (e.g.
     * the creation tab's "和 AI 一起创作" input). Carries the TARGET session
     * id: if the navigation that should have delivered it gets lost during
     * the runtime hand-off, a stale entry must not be eaten by whatever
     * unrelated chat happens to open next. Consumed exactly once, and only
     * when the opening session matches; a mismatched entry is left in place
     * (draft ids are unique, so the next [setPendingChatInput] overwrites it
     * — it can never be mis-delivered).
     */
    data class PendingChatInput(val sessionId: String, val text: String)

    private val _pendingChatInput = MutableStateFlow<PendingChatInput?>(null)
    val pendingChatInput: StateFlow<PendingChatInput?> = _pendingChatInput.asStateFlow()

    fun setPendingChatInput(sessionId: String, text: String) {
        _pendingChatInput.value = PendingChatInput(sessionId, text)
    }

    /** Returns the pending text only when it targets [sessionId]; a mismatch is left untouched. */
    fun consumePendingChatInput(sessionId: String): String? {
        val current = _pendingChatInput.value ?: return null
        if (current.sessionId != sessionId) return null
        _pendingChatInput.value = null
        return current.text
    }
}
