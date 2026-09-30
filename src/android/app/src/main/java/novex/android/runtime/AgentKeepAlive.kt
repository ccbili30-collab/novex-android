package novex.android.runtime

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.crash.CrashFrequencyDetector
import com.openminis.app.crash.ProcessExitEvidence
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.service.ToolOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 保活前台服务的全部行为逻辑，宿主 [AgentForegroundService] 只剩一副
 * Manifest 壳（组件名与 mediaPlayback 类型是冻结面），生命周期回调
 * 逐个转发到这里。
 *
 * 三块职责：
 *  1. **前台身份**——onCreate 里第一时间 startForeground 占位（5 秒
 *     死线属于 startForegroundService，onStartCommand 里才补就迟了），
 *     之后每次状态跳动重发正式状态行；
 *  2. **唤醒锁**——部分 OEM 在熄屏后对前台服务照样降频，长流式会半路
 *     停摆，服务存活期间持一把 PARTIAL_WAKE_LOCK 兜底，onDestroy
 *     确定性释放；
 *  3. **悬浮胶囊观察者**——把前台/工具信号/开关等 15 路流合成一帧
 *     决策（见 [OverlayInputs]），驱动 [OverlayCapsule]。收集协程挂
 *     在服务私有的 scope 上，服务一死整棵树连根拔。
 */
internal class AgentKeepAlive(private val host: AgentForegroundService) {

    private val statusLine = KeepAliveStatusLine(host)
    private var bootAnchorMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private var capsule: OverlayCapsule? = null
    private val capsuleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var idleTimer: Job? = null

    /**
     * 上一帧是否"忙"。配合它做**边沿判定**：只有"忙→闲"这一下且当
     * 时不在前台，才允许挂起一条完成态胶囊。lastOutcome/lastReply
     * 跨轮存活，任何不看边沿、直接读它们的规则都会在用户之后每次回
     * 桌面时把旧胶囊再弹一遍——这是被修过的老回归。
     */
    private var completionLingered = false
    private var previousBusy = false

    /** 本服务生命周期内提醒只发一次（防十个工具十连响）。 */
    private var nudgePosted = false

    // ── 生命周期转发 ─────────────────────────────────────────────────

    fun onCreated() {
        ProcessExitEvidence.record(host, "agent-service.create")
        bootAnchorMs = SystemClock.elapsedRealtime()
        statusLine.serviceBootAnchorMs = bootAnchorMs
        statusLine.ensureChannels()
        enterForeground(statusLine.bootstrapNotification())
        ProcessExitEvidence.record(host, "agent-service.foreground-registered")
        Log.i(TAG, "startForeground satisfied ${SystemClock.elapsedRealtime() - bootAnchorMs}ms after onCreate")
        // 安全模式（崩溃风暴刚触发）：MinisApp 跳过了仓库 lateinit 初始化，
        // 系统粘性重启的本次服务不得去碰它们——否则在崩溃检测器上再叠
        // 一层 UninitializedPropertyAccessException 崩溃。前台身份已占，
        // 剩下的交给 onStartCommand 收摊。
        if (CrashFrequencyDetector.isSafeMode()) {
            Log.w(TAG, "safe-mode on — overlay/wake-lock bring-up skipped")
            return
        }
        holdCpu()
        watchOverlayInputs()
        Log.d(TAG, "created")
    }

    fun onStartCommand(intent: Intent?, startId: Int): Int {
        ProcessExitEvidence.record(
            host,
            "agent-service.command startId=$startId stop=${intent?.action == KeepAliveStatusLine.ACTION_STOP}",
        )
        // 安全模式：前台通知已占（onCreate），这里撤下并自停；崩溃
        // 分享对话框接管后续 UX。
        if (CrashFrequencyDetector.isSafeMode()) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    host.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    host.stopForeground(true)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "safe-mode foreground teardown failed: ${t.message}")
            }
            host.stopSelf()
            return Service.START_NOT_STICKY
        }
        if (intent?.action == KeepAliveStatusLine.ACTION_STOP) {
            // 通知栏「停止」：光 stopSelf 会留下还在飞的生成循环（用户
            // 看到通知没了、工具还在后台跑）。先扇出全部取消回调。
            LiveSessionHub.cancelEveryStream()
            host.stopSelf()
            return Service.START_NOT_STICKY
        }

        val sessionCount = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0
        val toolStatus = intent?.getStringExtra(EXTRA_TOOL_STATUS) ?: "Idle"
        enterForeground(statusLine.build(sessionCount, toolStatus))
        // 系统可能在可选运行时齐备之前就把服务还原了；每次状态跳动
        // 再试一次装配观察者（内部幂等，不会叠收集器）。
        watchOverlayInputs()
        return Service.START_STICKY
    }

    /**
     * 用户从最近任务划掉。部分 OEM 会顺势杀掉本服务；只要还有流在
     * 跑就要重锚前台身份。纯"人在看聊天然后划掉"不再是留活理由——
     * 用户已明确逐出，先清在场再评估。
     */
    fun onTaskRemoved() {
        LiveSessionHub.dropPresence()
        val streaming = LiveSessionHub.streamingIds.value
        if (streaming.isEmpty()) {
            Log.d(TAG, "task removed with nothing streaming — stopping")
            host.stopSelf()
            return
        }
        Log.d(TAG, "task removed with ${streaming.size} streaming — re-anchoring")
        // 重发当前状态行，让系统重新认下"活的前台服务"，防 OEM 把
        // 我们降级成普通后台进程在 ~60s 内回收。
        enterForeground(statusLine.build(streaming.size, LiveSessionHub.toolStatus.value))
    }

    /** 旋转/分屏 resize 时把胶囊夹回新屏界内（Service 收的是裸配置事件）。 */
    fun onScreenGeometryChanged(newConfig: Configuration) {
        capsule?.relayoutForNewScreenMetrics()
    }

    fun onDestroyed() {
        releaseCpu()
        try {
            capsule?.takeDown()
        } catch (_: Throwable) {}
        capsule = null
        capsuleScope.cancel()
        Log.d(TAG, "destroyed")
    }

    // ── 前台身份与唤醒锁 ─────────────────────────────────────────────

    private fun enterForeground(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            host.startForeground(
                KeepAliveStatusLine.NOTIFICATION_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            host.startForeground(KeepAliveStatusLine.NOTIFICATION_ONGOING, notification)
        }
    }

    private fun holdCpu() {
        if (wakeLock != null) return
        try {
            val power = host.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "minis:inference").apply {
                setReferenceCounted(false)
                // 不设超时：释放点在 onDestroy（流式清零时服务自停），
                // 挂超时反而可能在长任务中途先松手。
                acquire()
            }
            Log.d(TAG, "wake lock held (PARTIAL)")
        } catch (e: Exception) {
            Log.w(TAG, "wake lock acquire failed: ${e.message}")
        }
    }

    private fun releaseCpu() {
        try {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "wake lock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "wake lock release failed: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    // ── 悬浮胶囊观察者 ───────────────────────────────────────────────

    /** 一帧合成输入：15 路源流的快照。 */
    private data class OverlayInputs(
        val appForeground: Boolean,
        val toolKind: String?,
        val toolStatus: String,
        val toolExecuting: Boolean,
        val overlayWanted: Boolean,
        val outcome: ToolOutcome,
        val replyExcerpt: String?,
        val sessionId: String?,
        val toolHeadline: String?,
        val cameraHold: Boolean,
        val lastToolKind: String?,
        val lastToolHeadline: String?,
        val lastToolStatus: String?,
        val anyStreaming: Boolean,
        val islandWanted: Boolean,
    )

    /**
     * 装配胶囊与其驱动收集器。要求可选运行时就绪（安全模式进程里
     * 碰 MinisApp 的 lateinit 会二次崩溃）；未就绪时静默返回，等下
     * 一次状态跳动重试。
     */
    private fun watchOverlayInputs() {
        if (capsule != null) return
        val app = (host.applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() } ?: return
        capsule = OverlayCapsule(host.applicationContext).apply {
            // 用户划掉（× 或点进会话）：清完成停留标记，并让中枢忘掉
            // 摘要，下一次流发射时 AND 闸门自然落到"不显示"。
            onDismissedByUser = {
                completionLingered = false
                LiveSessionHub.forgetOverlayDigest()
            }
        }
        val settings = app.backgroundSettingsRepository
        val hub = LiveSessionHub

        capsuleScope.launch {
            combine(
                app.isAppForegroundFlow,
                hub.toolKind,
                hub.toolStatus,
                hub.toolBusy,
                settings.backgroundOverlayEnabled,
                hub.lastOutcome,
                hub.replyExcerpt,
                hub.currentSessionId,
                hub.toolHeadline,
                hub.cameraHoldActive,
                hub.lastToolKind,
                hub.lastToolHeadline,
                hub.lastToolStatus,
                hub.streamingIds,
                settings.dynamicIslandEnabled,
            ) { raw: Array<Any?> ->
                OverlayInputs(
                    appForeground = raw[0] as Boolean,
                    toolKind = raw[1] as String?,
                    toolStatus = raw[2] as String,
                    toolExecuting = raw[3] as Boolean,
                    overlayWanted = raw[4] as Boolean,
                    outcome = raw[5] as ToolOutcome,
                    replyExcerpt = raw[6] as String?,
                    sessionId = raw[7] as String?,
                    toolHeadline = raw[8] as String?,
                    cameraHold = raw[9] as Boolean,
                    lastToolKind = raw[10] as String?,
                    lastToolHeadline = raw[11] as String?,
                    lastToolStatus = raw[12] as String?,
                    anyStreaming = (raw[13] as Set<*>).isNotEmpty(),
                    islandWanted = raw[14] as Boolean,
                )
            }.distinctUntilChanged().collect { frame -> steerCapsule(frame) }
        }
    }

    /** 单帧决策：显示哪种形态、还是收窗。 */
    private fun steerCapsule(frame: OverlayInputs) {
        val window = capsule ?: return
        val permitted = window.overlayPermissionGranted()

        // 互斥铁律：灵动岛生效（开关开 + 设备可发布）时悬浮胶囊必须
        // 让路，即便用户把胶囊开关也开着——同一时刻两块实时状态面板
        // 只会互相叠影。能力在此现查，两个开关任一翻转都即时生效。
        if (LiveUpdatesProbe.engaged(host, frame.islandWanted)) {
            if (window.attached) window.takeDown()
            idleTimer?.cancel()
            idleTimer = null
            completionLingered = false
            previousBusy = frame.anyStreaming || frame.toolExecuting
            // 通知行也趁势换成可提升形态（等下一次工具跳动就迟了）。
            // 只有确实有流在跑（即前台身份在手）时才重发，凭空 notify
            // 会贴出一条非前台通知。
            if (LiveSessionHub.streamingIds.value.isNotEmpty()) {
                statusLine.repost {
                    statusLine.build(
                        LiveSessionHub.streamingIds.value.size,
                        LiveSessionHub.toolStatus.value,
                    )
                }
            }
            Log.d(
                TAG,
                "steerCapsule: island engaged — capsule yields (islandWanted=${frame.islandWanted})",
            )
            return
        }

        // 「忙」= 有流在生成，或有工具在执行。
        val busy = frame.anyStreaming || frame.toolExecuting
        // 完成停留只在"忙→闲"边沿且当时后台、全条件就绪时挂起。
        if (previousBusy && !busy && !frame.appForeground && frame.overlayWanted &&
            permitted && !frame.cameraHold
        ) {
            completionLingered = true
        }
        previousBusy = busy
        val wanted = frame.overlayWanted && permitted && !frame.appForeground &&
            !frame.cameraHold && (busy || completionLingered)
        Log.d(
            TAG,
            "steerCapsule fg=${frame.appForeground} on=${frame.overlayWanted} " +
                "perm=$permitted streaming=${frame.anyStreaming} tool=${frame.toolExecuting} " +
                "camera=${frame.cameraHold} linger=$completionLingered " +
                "kind=${frame.toolKind} head=${frame.toolHeadline} want=$wanted " +
                "attached=${window.attached}",
        )

        // 开关开了但系统没授悬浮窗：趁忙时提醒一次（带节流）。
        if (frame.overlayWanted && !permitted && busy && !frame.appForeground && !nudgePosted) {
            nudgePosted = true
            try {
                statusLine.postOverlayPermissionNudge()
            } catch (t: Throwable) {
                Log.w(TAG, "overlay nudge failed: ${t.message}", t)
            }
        }

        // 回前台 / 关开关 / 无权限 / 相机占屏：立即收窗。注意刻意不
        // 清中枢的摘要态——用户只是看了一眼聊天，没说不要提醒；再切
        // 后台若仍有流或停留未消化，胶囊应当回来。前台时清掉停留
        // （回复已在应用内看过，不再挂旧完成态）；关开关/无权限/相机
        // 则连停留一起留着，重新打开时接着上次的上下文。
        if (frame.appForeground || !frame.overlayWanted || !permitted || frame.cameraHold) {
            if (frame.appForeground) completionLingered = false
            idleTimer?.cancel()
            idleTimer = null
            if (window.attached) window.takeDown()
            return
        }

        if (wanted) {
            idleTimer?.cancel()
            idleTimer = null
            // 纯流式（还没轮到工具）时工具名/标题/状态可能是空或 Idle，
            // 回退到上一轮工具快照，别给用户一个光杆转圈。
            val kind = frame.toolKind ?: frame.lastToolKind
            val headline = frame.toolHeadline ?: frame.lastToolHeadline
            val status = if (!frame.toolStatus.equals("Idle", ignoreCase = true)) {
                frame.toolStatus
            } else {
                frame.lastToolStatus ?: frame.toolStatus
            }
            window.present(
                OverlayCapsule.Spec(
                    toolKind = kind,
                    toolHeadline = headline,
                    statusLine = status,
                    running = busy,
                    outcome = if (busy) ToolOutcome.Unknown else frame.outcome,
                    replyExcerpt = if (busy) null else frame.replyExcerpt,
                    targetSessionId = frame.sessionId,
                ),
            )
            return
        }

        // 不忙、且本轮没有挂起的完成停留（用户已划掉或本来就在前台
        // 收的尾）——胶囊若还挂着就撤下。
        if (window.attached) window.takeDown()
    }

    private companion object {
        private const val TAG = "AgentKeepAlive"
        private const val EXTRA_SESSION_COUNT = "session_count"
        private const val EXTRA_TOOL_STATUS = "tool_status"
    }
}
