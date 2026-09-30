package novex.android.navlink

import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.deeplink.DeepLinkCoordinator.ChatAction
import com.openminis.app.deeplink.DeepLinkCoordinator.PendingChatInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 深链的“活过单次导航”的挂起副作用状态（P3.5c 自
 * deeplink/DeepLinkCoordinator 真重写）：日志页签、聊天快捷动作、
 * 合成器预填——设置一次、被恰好在场的屏幕消费一次的信号。
 *
 * 嵌套类型（[ChatAction] / [PendingChatInput]）是 UI 以
 * `DeepLinkCoordinator.ChatAction` 形态钉住的冻结面，正典留在旧路径，
 * 本对象反向引用。
 *
 * 消费语义（冻结面）：页签与快捷动作谁消费谁清空；预填带目标会话
 * id——投递路径丢失时陈旧条目不被下一个无关聊天误吃，会话号不匹配
 * 原样留在原地（草稿 id 唯一，后来的 setPendingChatInput 直接覆写，
 * 永不误投）。
 */
object PendingLinkFx {

    /** minis://settings/logs?tab=… 的页签；日志屏到场时读走。 */
    private val _pendingLogsTab = MutableStateFlow<String?>(null)
    val pendingLogsTab: StateFlow<String?> = _pendingLogsTab.asStateFlow()

    fun setPendingLogsTab(tab: String?) { _pendingLogsTab.value = tab }
    fun consumePendingLogsTab(): String? = take(_pendingLogsTab)

    /** 新开的 ChatScreen 首次合成时应自动触发的快捷动作。 */
    private val _pendingChatAction = MutableStateFlow<ChatAction?>(null)
    val pendingChatAction: StateFlow<ChatAction?> = _pendingChatAction.asStateFlow()

    fun setPendingChatAction(action: ChatAction) { _pendingChatAction.value = action }
    fun consumePendingChatAction(): ChatAction? = take(_pendingChatAction)

    /** 给指定新开 ChatScreen 的合成器预填（如创作页的“和 AI 一起创作”输入）。 */
    private val _pendingChatInput = MutableStateFlow<PendingChatInput?>(null)
    val pendingChatInput: StateFlow<PendingChatInput?> = _pendingChatInput.asStateFlow()

    fun setPendingChatInput(sessionId: String, text: String) {
        _pendingChatInput.value = PendingChatInput(sessionId, text)
    }

    /** 仅当目标正是 [sessionId] 时取走文本；不匹配的原样留存。 */
    fun consumePendingChatInput(sessionId: String): String? {
        val aimed = _pendingChatInput.value?.takeIf { it.sessionId == sessionId } ?: return null
        _pendingChatInput.value = null
        return aimed.text
    }

    /** 一次性信号的标准取法：读走即清空。 */
    private fun <T> take(slot: MutableStateFlow<T?>): T? =
        slot.value.also { slot.value = null }
}
