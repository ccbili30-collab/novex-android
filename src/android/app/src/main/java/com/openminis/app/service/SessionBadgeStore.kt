package com.openminis.app.service

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import novex.android.runtime.SessionBadges

/**
 * 会话角标门面（P3.5b 重写）。队列算法与 SharedPreferences 落盘在
 * [novex.android.runtime.SessionBadges]；嵌套枚举 [SessionBadgeState]
 * 是 UI 层全限定引用的类型（`SessionBadgeStore.SessionBadgeState.*`），
 * 无法经 typealias 转发，故钉在此处、由实现侧反向引用。
 *
 * 持久化事实面（冻结）：prefs `session_badge_store`、键
 * `badge_state_by_session`、编码 `id=STATE,STATE;id=STATE`。
 */
object SessionBadgeStore {

    /** 角标状态值；名字即落盘编码，不得改。 */
    enum class SessionBadgeState {
        /** 流式期间被后台打断；用户回到该会话前持续显示。 */
        PAUSED,

        /** 预留给同步状态角标（对齐 iOS iCloud 位）；尚无生产者。 */
        ICLOUD_SYNCING,
    }

    val byId: StateFlow<Map<String, List<SessionBadgeState>>> get() = SessionBadges.byId

    fun init(context: Context) = SessionBadges.attach(context)

    fun headFor(sessionId: String): SessionBadgeState? = SessionBadges.headOf(sessionId)

    fun push(sessionId: String, state: SessionBadgeState) = SessionBadges.promote(sessionId, state)

    fun remove(sessionId: String, state: SessionBadgeState) = SessionBadges.drop(sessionId, state)

    fun clear(sessionId: String) = SessionBadges.wipe(sessionId)

    fun reconcileInterruptedSessions(interruptedIds: Set<String>) =
        SessionBadges.reconcileAgainst(interruptedIds)
}
