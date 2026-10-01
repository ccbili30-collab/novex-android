package com.openminis.app.notification

import android.content.Context
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import novex.android.runtime.TaskDoneNotifier

/**
 * 会话收尾通知门面（P3.5b 重写）。实现（渠道装配、深链通知、前台
 * 清扫）在 [novex.android.runtime.TaskDoneNotifier]；构造签名与成员
 * 名是 MinisApp 装配点的冻结面，原名转发。
 */
class BackgroundTaskNotifier(
    context: Context,
    chatRepository: ChatRepository,
    backgroundSettings: BackgroundSettingsRepository,
    isAppForeground: () -> Boolean,
) {
    private val impl = TaskDoneNotifier(
        context = context,
        chatRepository = chatRepository,
        settings = backgroundSettings,
        appInBackground = { !isAppForeground() },
    )

    fun notifyTaskCompleted(sessionId: String, isError: Boolean = false) =
        impl.announceFinished(sessionId, isError)

    fun cancelAllCompletedNotifications() = impl.retractAllPosted()

    companion object {
        /** 冻结面：任务完成通知渠道 id（前台服务的清扫与互斥判定都对着它）。 */
        const val CHANNEL_ID = TaskDoneNotifier.CHANNEL_ID
    }
}
