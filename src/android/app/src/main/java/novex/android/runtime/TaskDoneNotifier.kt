package novex.android.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 会话收尾通知：一轮生成在后台跑完时贴一条"点开直达该会话"的通知。
 * 触发链在 [LiveSessionHub] 的完成回调上（MinisApp 装配），不经
 * ChatViewModel 的任何 finally 块——中枢是"还在不在流"的唯一权威。
 *
 * 两条静默规则：用户关了任务通知则不发；应用正在前台也不发（用户
 * 就看着聊天，何必打断）。正文不摘要模型回复——从 parts_json 里抽
 * 纯文本太脆，深链点开就是全文，更贴用户意图。
 */
internal class TaskDoneNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val settings: BackgroundSettingsRepository,
    private val appInBackground: () -> Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /** 会话收尾（活跃→静止翻转）时调用；查标题、贴通知，全部离主线程。 */
    fun announceFinished(sessionId: String, failed: Boolean = false) {
        if (!settings.taskNotificationsEnabled.value) return
        if (!appInBackground()) return
        scope.launch {
            try {
                val stored = chatRepository.sessionById(sessionId)?.title?.takeIf { it.isNotBlank() }
                val baseTitle = stored
                    ?: context.getString(R.string.notif_task_completed_default_title)
                val title = if (failed) "❌ $baseTitle" else baseTitle
                val body = if (failed) {
                    context.getString(R.string.notif_task_failed_body)
                } else {
                    context.getString(R.string.notif_task_completed_body)
                }
                post(sessionId, title, body)
            } catch (t: Throwable) {
                Log.w(TAG, "announceFinished failed: ${t.message}")
            }
        }
    }

    private fun post(sessionId: String, title: String, body: String) {
        val manager = NotificationManagerCompat.from(context)
        // 13+ 需运行时通知权限（设置页开关时另走申请流），没授就静默跳过。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !manager.areNotificationsEnabled()) {
            return
        }

        val openChat = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$sessionId")).apply {
                // NEW_TASK：从后台协程发起、无 Activity 上下文；
                // CLEAR_TOP：MainActivity 是 singleTask，走 onNewIntent
                // 路由深链而不是再起一份。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notice = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openChat)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        try {
            // 通知 id = 会话 hash：同会话多条自然去重，也不与常驻行撞号。
            manager.notify(sessionId.hashCode(), notice)
        } catch (se: SecurityException) {
            Log.i(TAG, "post denied (POST_NOTIFICATIONS not granted)")
        }
    }

    /**
     * 应用回到前台时清掉本渠道的存量通知——用户已看到结果，托盘里
     * 再留着就是过期打扰。逐条对渠道 id 撤（id 是会话 hash，没有单
     * 一编号可 cancel；cancelAll 会连前台服务横幅一起掀）。
     */
    fun retractAllPosted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        try {
            manager.activeNotifications?.forEach { row ->
                if (row.notification?.channelId == CHANNEL_ID) {
                    manager.cancel(row.tag, row.id)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "retractAllPosted failed: ${t.message}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_task_completed_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_task_completed_channel_description)
                setShowBadge(true)
            },
        )
    }

    internal companion object {
        const val TAG = "TaskDoneNotifier"

        /** 冻结面：任务完成通知渠道 id。 */
        const val CHANNEL_ID = "minis_task_completed"
    }
}
