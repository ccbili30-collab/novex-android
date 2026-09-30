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
 * 触发链挂在 [LiveSessionHub] 的完成回调上（MinisApp 装配），绕开
 * ChatViewModel 的各路 finally——中枢是"还在不在流"的唯一权威。
 *
 * 两条静默规则：用户关了任务通知则不发；应用正在前台也不发（用户
 * 就看着聊天，何必打断）。正文不摘要模型回复——parts_json 抽纯文本
 * 太脆，深链点开就是全文，更贴用户意图。
 *
 * 组装分三层：[decide]（要不要发）→ [compose]（查标题、定文案）→
 * [post]（贴出去），主线程零负担。
 */
internal class TaskDoneNotifier(
    private val context: Context,
    private val chatRepository: ChatRepository,
    private val settings: BackgroundSettingsRepository,
    private val appInBackground: () -> Boolean,
) {

    /** 一条收尾通知的全部文案。 */
    private data class Copy(val headline: String, val body: String)

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        ensureChannel()
    }

    /** 会话收尾（活跃→静止翻转）时调用。 */
    fun announceFinished(sessionId: String, failed: Boolean = false) {
        if (!settings.taskNotificationsEnabled.value || !appInBackground()) return
        worker.launch {
            try {
                post(sessionId, compose(sessionId, failed))
            } catch (t: Throwable) {
                Log.w(TAG, "announceFinished failed: ${t.message}")
            }
        }
    }

    /**
     * 应用回到前台时清掉本渠道的存量通知——用户已看到结果，托盘里
     * 再留着就是过期打扰。逐条对渠道 id 撤（通知 id 是会话 hash，
     * 没有单一编号可 cancel；cancelAll 会连前台服务横幅一起掀）。
     */
    fun retractAllPosted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        runCatching {
            for (row in manager.activeNotifications) {
                if (row.notification?.channelId == CHANNEL_ID) manager.cancel(row.tag, row.id)
            }
        }.onFailure { Log.w(TAG, "retractAllPosted failed: ${it.message}") }
    }

    // ── 组装三层 ─────────────────────────────────────────────────────

    private fun compose(sessionId: String, failed: Boolean): Copy {
        val stored = chatRepository.sessionById(sessionId)?.title?.takeIf { it.isNotBlank() }
        val headline = when {
            failed -> "❌ ${stored ?: context.getString(R.string.notif_task_completed_default_title)}"
            stored != null -> stored
            else -> context.getString(R.string.notif_task_completed_default_title)
        }
        val body = context.getString(
            if (failed) R.string.notif_task_failed_body else R.string.notif_task_completed_body,
        )
        return Copy(headline, body)
    }

    private fun post(sessionId: String, copy: Copy) {
        val manager = NotificationManagerCompat.from(context)
        // 13+ 需运行时通知权限（设置页开关时另走申请流），没授就静默跳过。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !manager.areNotificationsEnabled()) {
            return
        }
        val notice = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(copy.headline)
            .setContentText(copy.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.body))
            .setContentIntent(deepLinkInto(sessionId))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            // 通知 id = 会话 hash：同会话多条自然去重，也不与常驻行撞号。
            manager.notify(sessionId.hashCode(), notice)
        }.onFailure {
            if (it is SecurityException) {
                Log.i(TAG, "post denied (POST_NOTIFICATIONS not granted)")
            } else {
                throw it
            }
        }
    }

    /** minis://session/<id> 深链：NEW_TASK（无 Activity 上下文）+ CLEAR_TOP（singleTask 复用实例）。 */
    private fun deepLinkInto(sessionId: String): PendingIntent = PendingIntent.getActivity(
        context,
        sessionId.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse("minis://session/$sessionId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 渠道 id / 名称文案 / 重要度是冻结面：系统设置页按这些认领渠道。
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_task_completed_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.notif_task_completed_channel_description)
        channel.setShowBadge(true)
        manager.createNotificationChannel(channel)
    }

    internal companion object {
        const val TAG = "TaskDoneNotifier"

        /** 冻结面：任务完成通知渠道 id。 */
        const val CHANNEL_ID = "minis_task_completed"
    }
}
