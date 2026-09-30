package novex.android.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MinisApp
import com.openminis.app.R

/**
 * 保活前台服务的通知装配线：两条渠道、常驻状态行（普通与可提升
 * ProgressStyle 两种形态）、悬浮窗权限提醒，都在这里拼装。
 *
 * 渠道 id、通知 id、图标与文案资源、以及 Android 16 提升通知的
 * extras 键（框架未导出的 "android.requestPromotedOngoing"）全部是
 * 冻结面——系统与用户已经认得这些标识，改动等于换了一个通知。
 */
internal class KeepAliveStatusLine(private val context: Context) {

    /** 计时锚点兜底：无任务锚时（纯在场）用服务自身的开机钟。 */
    var serviceBootAnchorMs: Long = 0L

    // ── 渠道 ─────────────────────────────────────────────────────────

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.bg_service_channel_name),
            NotificationManager.IMPORTANCE_LOW, // 静音：每个工具跳一下就响会烦死人
        ).apply {
            description = context.getString(R.string.bg_service_channel_description)
            setShowBadge(false)
        }
        // 提醒渠道走 DEFAULT：要的是弹出来让用户看见"还差一步授权"。
        val nudge = NotificationChannel(
            CHANNEL_NUDGE,
            context.getString(R.string.bg_overlay_nudge_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.bg_overlay_nudge_channel_description)
            setShowBadge(true)
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(nudge)
    }

    /** onCreate 里的占位通知：先把前台身份占上，再慢慢装内容。 */
    fun bootstrapNotification(): Notification =
        Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("正在准备后台任务")
            .setOngoing(true)
            .build()

    // ── 常驻状态行 ───────────────────────────────────────────────────

    /**
     * 组装当前状态行。计时的两个锚（本轮起点、上轮终点）取自
     * [LiveSessionHub]：服务活得比任务长，拿服务启动钟计时会把
     * "在聊天里坐了多久"报成"任务跑了多久"，且连跑两轮会累计。
     */
    fun build(sessionCount: Int, toolStatus: String): Notification {
        val hub = LiveSessionHub
        val finishedAt = hub.lastRunFinishedAtMs.value
        val streamingNow = hub.streamingIds.value.isNotEmpty()
        val settled = finishedAt != null && !streamingNow
        val clockEnd = if (settled) finishedAt!! else SystemClock.elapsedRealtime()
        val clockStart = hub.currentRunStartedAtMs.value ?: serviceBootAnchorMs
        val elapsed = ((clockEnd - clockStart).coerceAtLeast(0L) / 1000L).toInt()
        val clockText = "%d:%02d".format(elapsed / 60, elapsed % 60)

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent().setClassName(context, "com.openminis.app.NovexLaunchActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopRun = PendingIntent.getService(
            context,
            1,
            Intent(context, com.openminis.app.service.AgentForegroundService::class.java)
                .setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sessionLabel = context.resources.getQuantityString(
            R.plurals.bg_service_sessions, sessionCount, sessionCount,
        )

        val toolKind = hub.toolKind.value
        val toolBusy = hub.toolBusy.value
        // 静止态的标题/图标必须换下"进行中"的那套——完成了还显示扳手
        // 和工具名，读起来像还在跑。
        val title = when {
            settled -> context.getString(R.string.bg_service_notification_title_completed)
            toolKind != null -> longToolLabel(toolKind)
            else -> context.getString(R.string.bg_service_notification_title)
        }
        val body = if (settled) {
            context.getString(
                R.string.bg_service_notification_text_completed, sessionLabel, clockText,
            )
        } else {
            context.getString(
                R.string.bg_service_notification_text, sessionLabel, toolStatus, clockText,
            )
        }

        val minisApp = (context.applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }
        val islandOn = LiveUpdatesProbe.engaged(
            context,
            minisApp?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true,
        )
        if (islandOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            return buildPromoted(
                title = title,
                body = body,
                chipText = clockText,
                icon = iconFor(toolKind, settled),
                toolBusy = toolBusy,
                settled = settled,
                openApp = openApp,
                stopRun = stopRun,
            )
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(iconFor(toolKind, settled))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (!settled) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.bg_service_stop_action),
                stopRun,
            )
        }
        if (toolBusy) {
            // 工具大多是开放式时长（无 N/M 可报），一律不定进度条；
            // 不跑工具时显式撤条，空档期别留个假进度。
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 就地重发常驻行（灵动岛开关翻转时立刻换形态用）。 */
    fun repost(build: () -> Notification) {
        try {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(NOTIFICATION_ONGOING, build())
        } catch (t: Throwable) {
            Log.w(TAG, "repost failed: ${t.message}")
        }
    }

    // ── Android 16 可提升（Live Updates / 灵动岛）形态 ────────────────

    /**
     * androidx.core 1.15 没有这套 API，只能下到原生 Builder。提升的
     * 硬性条件由本方法逐项满足：ongoing、有标题、ProgressStyle、非
     * 汇总、非 colorized、渠道非 MIN。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildPromoted(
        title: String,
        body: String,
        chipText: String,
        icon: Int,
        toolBusy: Boolean,
        settled: Boolean,
        openApp: PendingIntent,
        stopRun: PendingIntent,
    ): Notification {
        // 进度段不可省：空 ProgressStyle 过不了 hasPromotableCharacteristics，
        // 通知会静默跌回普通常驻行。但也不能真画成位置滑条——智能体
        // 运行只有"在跑/跑完"两态，画 0% 滑条像卡死的下载。保段、去
        // 滑条外观、去小飞机图标，两态由标题/图标/计时承载。
        val style = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
            .setStyledByProgress(false)
            .setProgressTrackerIcon(null)
            .setProgressIndeterminate(toolBusy && !settled)
            .setProgress(if (settled) 100 else 0)

        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(style)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setColorized(false) // colorized 与汇总都会取消提升资格
            .setShortCriticalText(chipText)

        if (!settled) {
            // 跑完了还挂"停止"只会诱导一次无效点击。
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    context.getString(R.string.bg_service_stop_action),
                    stopRun,
                ).build(),
            )
        }

        // 公开的 setRequestPromotedOngoing 还没进 SDK stub；而系统判定
        // 读的是 extras 里的这个布尔（经反编译框架核实——FLAG 是系统
        // 决定提升后才置的，不是请求本身）。
        builder.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED, true) })

        val notification = builder.build()
        if (!notification.hasPromotableCharacteristics()) {
            // 不致命：照常以普通常驻行发出，只是不上胶囊。逐项 dump
            // 便于定位是哪个先决条件挂了。
            val flags = notification.flags
            Log.w(
                TAG,
                "promoted row lacks promotable characteristics — diag: " +
                    "requested=${notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED)} " +
                    "ongoingFlag=${(flags and Notification.FLAG_ONGOING_EVENT) != 0} " +
                    "hasTitle=${!notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty()} " +
                    "icon!=null=${notification.smallIcon != null} " +
                    "groupSummary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0} " +
                    "template=${notification.extras.getString(Notification.EXTRA_TEMPLATE)}",
            )
        } else {
            Log.d(TAG, "promoted row OK — hasPromotableCharacteristics=true")
        }
        return notification
    }

    // ── 悬浮窗权限提醒 ───────────────────────────────────────────────

    /** 一次性高优先级提醒：开关开了但 SYSTEM_ALERT_WINDOW 没授予。 */
    fun postOverlayPermissionNudge() {
        val toSettings = PendingIntent.getActivity(
            context,
            2,
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = context.getString(R.string.bg_overlay_nudge_body)
        val nudge = NotificationCompat.Builder(context, CHANNEL_NUDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.bg_overlay_nudge_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(toSettings)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try {
            ContextCompat.getSystemService(context, NotificationManager::class.java)
                ?.notify(NOTIFICATION_NUDGE, nudge)
            Log.d(TAG, "overlay permission nudge posted (SAW missing while toggle is ON)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge failed: ${e.message}", e)
        }
    }

    // ── 图标与文案映射（措辞与站内浮条对齐，冻结） ─────────────────────

    private fun longToolLabel(kind: String): String = when (kind) {
        "shell_execute" -> "Minis is using Shell"
        "file_read" -> "Minis is reading File"
        "file_write" -> "Minis is using Editor"
        "file_edit" -> "Minis is editing File"
        "browser_use" -> "Minis is using Browser"
        "read_image" -> "Minis is reading Image"
        "memory_write", "memory_get" -> "Minis is using Memory"
        "web_search" -> "Minis is using Search"
        else -> "Minis is using $kind"
    }

    private fun iconFor(kind: String?, settled: Boolean): Int =
        if (settled) R.drawable.ic_notification_completed else toolIcon(kind)

    private fun toolIcon(kind: String?): Int = when (kind) {
        "shell_execute" -> android.R.drawable.ic_menu_edit
        "file_read", "read_image" -> android.R.drawable.ic_menu_view
        "file_write", "file_edit" -> android.R.drawable.ic_menu_edit
        "browser_use" -> android.R.drawable.ic_menu_compass
        "memory_write", "memory_get" -> android.R.drawable.ic_menu_save
        "web_search" -> android.R.drawable.ic_menu_search
        else -> android.R.drawable.ic_menu_manage
    }

    companion object {
        private const val TAG = "KeepAliveStatusLine"

        /** 冻结面：渠道/通知标识与动作串。 */
        const val CHANNEL_ONGOING = "agent_status"
        const val CHANNEL_NUDGE = "overlay_permission_nudge"
        const val NOTIFICATION_ONGOING = 9001
        const val NOTIFICATION_NUDGE = 9002
        const val ACTION_STOP = "com.openminis.app.STOP_AGENT_SERVICE"
        const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    }
}
