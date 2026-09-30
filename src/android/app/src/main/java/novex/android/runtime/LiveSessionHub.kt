package novex.android.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openminis.app.R
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.service.ToolOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 会话活跃度的中枢：前台服务该不该跑、跑起来后通知栏写什么，全部
 * 由这里一处裁决。旧路径 [com.openminis.app.service.SessionActivityTracker]
 * 门面把各调用点转到这里。
 *
 * 「会话活着」有两层互相独立的含义，对应两个集合：
 *  - **在流式**（streaming）：至少一个会话的生成循环在飞（模型调用、
 *    工具执行中）；
 *  - **在场**（presence）：用户正坐在某个聊天界面里（写框已挂载），
 *    与流是否在飞无关。在场集合里 `__new__` 前缀的是未发送草稿——
 *    打开新对话不构成后台任务，不得凭空制造前台服务启动义务。
 *
 * 服务在「任一集合非空」期间保持运行（进程稳在 adj=200，读、写、
 * 流式三种姿势都不掉档）；两集合同时清空才停。状态同步收敛到
 * [syncService] 单点：每次占据状态变动后调它，由它比对上一拍与这一
 * 拍的期望值决定起、刷、停，替代在每个入口重复写边沿判断。
 */
object LiveSessionHub {

    private const val TAG = "LiveSessionHub"
    private const val DRAFT_PREFIX = "__new__"
    private const val IDLE_LABEL = "Idle"
    private const val REPLY_EXCERPT_CAP = 72

    // ── 对外状态面（门面按原名转发；.value 均为同步读） ──────────────

    private val streaming = MutableStateFlow<Set<String>>(emptySet())
    val streamingIds: StateFlow<Set<String>> = streaming.asStateFlow()

    private val presence = MutableStateFlow<Set<String>>(emptySet())
    val presenceIds: StateFlow<Set<String>> = presence.asStateFlow()

    private val toolStatusText = MutableStateFlow(IDLE_LABEL)
    val toolStatus: StateFlow<String> = toolStatusText.asStateFlow()

    private val runFinishedAt = MutableStateFlow<Long?>(null)
    val lastRunFinishedAtMs: StateFlow<Long?> = runFinishedAt.asStateFlow()

    private val runStartedAt = MutableStateFlow<Long?>(null)
    val currentRunStartedAtMs: StateFlow<Long?> = runStartedAt.asStateFlow()

    private val liveToolKind = MutableStateFlow<String?>(null)
    val toolKind: StateFlow<String?> = liveToolKind.asStateFlow()

    private val liveToolHeadline = MutableStateFlow<String?>(null)
    val toolHeadline: StateFlow<String?> = liveToolHeadline.asStateFlow()

    private val toolExecuting = MutableStateFlow(false)
    val toolBusy: StateFlow<Boolean> = toolExecuting.asStateFlow()

    private val finishedOutcome = MutableStateFlow(ToolOutcome.Unknown)
    val lastOutcome: StateFlow<ToolOutcome> = finishedOutcome.asStateFlow()

    private val finishedToolKind = MutableStateFlow<String?>(null)
    val lastToolKind: StateFlow<String?> = finishedToolKind.asStateFlow()

    private val finishedToolHeadline = MutableStateFlow<String?>(null)
    val lastToolHeadline: StateFlow<String?> = finishedToolHeadline.asStateFlow()

    private val finishedToolStatus = MutableStateFlow<String?>(null)
    val lastToolStatus: StateFlow<String?> = finishedToolStatus.asStateFlow()

    private val replyDigest = MutableStateFlow<String?>(null)
    val replyExcerpt: StateFlow<String?> = replyDigest.asStateFlow()

    private val replyForSession = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = replyForSession.asStateFlow()

    private val cameraHold = MutableStateFlow(false)
    val cameraHoldActive: StateFlow<Boolean> = cameraHold.asStateFlow()

    // ── 私有账本 ─────────────────────────────────────────────────────

    private var appContext: Context? = null

    /** 每个在流式会话注册的取消回调；通知栏「停止」按键靠它扇出。 */
    private val streamStoppers = mutableMapOf<String, () -> Unit>()

    /** setInactive 之前由 markStreamError 打的标：这一轮流以失败收场。 */
    private val errorPending = mutableSetOf<String>()

    /** 会话结束时回调（MinisApp 接到后台任务通知器上）。 */
    private var onStreamEnded: ((sessionId: String, failed: Boolean) -> Unit)? = null

    /** 上一拍服务是否被期望运行；边沿检测就比对这个值。 */
    private var serviceExpected = false

    // ── 装配 ─────────────────────────────────────────────────────────

    /** 记下应用级 Context（进程启动时一次）。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    fun setCompletionListener(listener: ((sessionId: String, failed: Boolean) -> Unit)?) {
        onStreamEnded = listener
    }

    fun setCameraHold(active: Boolean) {
        cameraHold.value = active
    }

    // ── 占据状态流转 ─────────────────────────────────────────────────

    /**
     * 会话开始流式。[onStop] 是该会话循环的取消入口，被通知栏的
     * 「停止」按键收集，按键一按全体扇出。
     */
    fun markStreaming(sessionId: String, onStop: (() -> Unit)? = null) {
        val firstRun = streaming.value.isEmpty()
        streaming.value = streaming.value + sessionId
        if (onStop != null) {
            synchronized(streamStoppers) { streamStoppers[sessionId] = onStop }
        }
        // 本轮驱动的会话换了人：回复摘要与上一轮工具快照全部清空，
        // 免得新会话头上顶着旧会话的尾巴开场。
        replyForSession.value = sessionId
        replyDigest.value = null
        finishedToolKind.value = null
        finishedToolHeadline.value = null
        finishedToolStatus.value = null
        if (firstRun) {
            // 新一轮作废上一轮的「已完成」静止态：计时器从零走起。
            runFinishedAt.value = null
            runStartedAt.value = SystemClock.elapsedRealtime()
        }
        Log.d(TAG, "streaming+ $sessionId (total ${streaming.value.size})")
        syncService()
    }

    /**
     * 会话流式结束。集合清空时把工具信号归位、落完成时间戳；服务
     * 期望值随后交给 [syncService] 裁决。完成回调最后发——等通知器
     * 读占据状态时看到的是已收敛的终态。
     */
    fun markStreamEnded(sessionId: String) {
        val wasLive = sessionId in streaming.value
        streaming.value = streaming.value - sessionId
        synchronized(streamStoppers) { streamStoppers.remove(sessionId) }
        val failed = synchronized(errorPending) { errorPending.remove(sessionId) }
        Log.d(TAG, "streaming- $sessionId (total ${streaming.value.size})")

        if (streaming.value.isEmpty()) {
            toolStatusText.value = IDLE_LABEL
            liveToolKind.value = null
            liveToolHeadline.value = null
            toolExecuting.value = false
            // 只有真在流式的会话才允许制造"完成"事实——对从未启动过的
            // 会话补刀 setInactive 不得伪造完成时间戳。
            if (wasLive) runFinishedAt.value = SystemClock.elapsedRealtime()
            // 回复摘要与工具收尾快照刻意保留：悬浮胶囊要靠它们渲染
            // "刚跑完"的终态（对勾/叉 + 回复节选），直到用户点开或划掉。
            if (failed) finishedOutcome.value = ToolOutcome.Error
        }
        syncService()
        if (wasLive) onStreamEnded?.invoke(sessionId, failed)
    }

    /** 流以失败告终的预告：必须在 [markStreamEnded] 之前打标。 */
    fun flagStreamFailure(sessionId: String) {
        synchronized(errorPending) { errorPending.add(sessionId) }
    }

    /** 用户进入某个聊天界面（写框挂载）。幂等；纯草稿不起服务。 */
    fun markPresent(sessionId: String) {
        if (sessionId in presence.value) return
        presence.value = presence.value + sessionId
        Log.d(TAG, "present+ $sessionId (total ${presence.value.size})")
        // 只有草稿在场时服务期望仍为假，syncService 自然什么都不做——
        // 打开新对话不得凭空制造前台服务的启动义务。
        syncService()
    }

    /** 用户离开某个聊天界面。 */
    fun markAbsent(sessionId: String) {
        if (sessionId !in presence.value) return
        presence.value = presence.value - sessionId
        Log.d(TAG, "present- $sessionId (total ${presence.value.size})")
        syncService()
    }

    /**
     * 清光在场标记。只在 MainActivity 真被销毁时调——Home 键、锁屏
     * 恰恰是这个机制要扛住的场景，必须保住在场状态。
     */
    fun dropPresence() {
        if (presence.value.isEmpty()) return
        presence.value = emptySet()
        Log.d(TAG, "present- (all)")
        syncService()
    }

    /** 逐个触发所有在流式会话的取消回调（通知栏「停止」按键）。 */
    fun cancelEveryStream() {
        val stoppers = synchronized(streamStoppers) { streamStoppers.values.toList() }
        Log.d(TAG, "cancelEveryStream -> ${stoppers.size} session(s)")
        for (stop in stoppers) {
            try {
                stop()
            } catch (e: Exception) {
                Log.w(TAG, "stream stopper threw: ${e.message}")
            }
        }
    }

    fun isStreaming(sessionId: String): Boolean = sessionId in streaming.value

    // ── 工具信号 ─────────────────────────────────────────────────────

    /** 旧式只改状态文案的入口：工具名与运行位不动。 */
    fun pushStatusText(status: String) {
        toolStatusText.value = status
        if (streaming.value.isNotEmpty()) syncService()
    }

    /** 完整工具信号更新；[headline] 为模型给的 tool_title（空串视为无）。 */
    fun pushToolSignal(status: String, kind: String?, executing: Boolean, headline: String?) {
        toolStatusText.value = status
        liveToolKind.value = kind
        liveToolHeadline.value = headline?.takeIf { it.isNotBlank() }
        toolExecuting.value = executing
        // 新工具开跑：上一轮的收尾定性作废，别把旧结果画进新一轮。
        if (executing) finishedOutcome.value = ToolOutcome.Unknown
        if (streaming.value.isNotEmpty()) syncService()
    }

    /**
     * 工具收尾：保留身份与状态快照（供胶囊渲染"刚做完什么"），撤下
     * 运行位。对空转调用（本来就没在跑也没工具名）直接忽略。
     */
    fun closeToolRun(outcome: ToolOutcome = ToolOutcome.Unknown) {
        if (!toolExecuting.value && liveToolKind.value == null) return
        finishedToolKind.value = liveToolKind.value
        finishedToolHeadline.value = liveToolHeadline.value
        finishedToolStatus.value = toolStatusText.value
            ?.takeIf { it.isNotBlank() && !it.equals(IDLE_LABEL, ignoreCase = true) }
        liveToolKind.value = null
        liveToolHeadline.value = null
        toolExecuting.value = false
        finishedOutcome.value = outcome
        if (streaming.value.isNotEmpty()) syncService()
    }

    // ── 回复摘要（悬浮胶囊用） ───────────────────────────────────────

    /**
     * 发布该会话最近一条助手回复的节选：折行合并、截到
     * [REPLY_EXCERPT_CAP] 字加省略号；空文本不动任何状态。
     */
    fun publishReply(sessionId: String, fullText: String?) {
        val collapsed = fullText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString(" ")
            ?.takeIf { it.isNotBlank() } ?: return
        val digest = if (collapsed.length > REPLY_EXCERPT_CAP) {
            collapsed.substring(0, REPLY_EXCERPT_CAP).trimEnd() + "…"
        } else {
            collapsed
        }
        replyForSession.value = sessionId
        replyDigest.value = digest
    }

    /** 用户划掉悬浮胶囊：摘要与收尾快照清零（流式本身不受影响）。 */
    fun forgetOverlayDigest() {
        replyDigest.value = null
        finishedOutcome.value = ToolOutcome.Unknown
        finishedToolKind.value = null
        finishedToolHeadline.value = null
        finishedToolStatus.value = null
    }

    // ── 服务裁决 ─────────────────────────────────────────────────────

    /** 服务期望 = 有流式，或在场集合里存在非草稿会话。 */
    private fun serviceWanted(): Boolean =
        streaming.value.isNotEmpty() ||
            presence.value.any { !it.startsWith(DRAFT_PREFIX) }

    /** 与上一拍比对：边沿起/停，非边沿且仍在期望中则刷通知。 */
    private fun syncService() {
        val wanted = serviceWanted()
        when {
            wanted && !serviceExpected -> launchService()
            !wanted && serviceExpected -> haltService()
            wanted -> refreshService()
        }
        serviceExpected = wanted
    }

    private fun launchService() {
        val context = appContext ?: run {
            Log.w(TAG, "no context yet, cannot start keep-alive service")
            return
        }
        AgentForegroundService.startService(context, headlineSessionCount(), headlineStatusText())
    }

    private fun refreshService() {
        val context = appContext ?: return
        AgentForegroundService.startService(context, headlineSessionCount(), headlineStatusText())
    }

    private fun haltService() {
        val context = appContext ?: run {
            Log.w(TAG, "no context yet, cannot stop keep-alive service")
            return
        }
        AgentForegroundService.stopService(context)
        Log.d(TAG, "all sessions settled, service stopped")
    }

    /**
     * 通知栏的会话计数：有流式报流式数，否则报在场数。两桶分开计数
     * 避免"既在场又在流式"的会话被算两遍。
     */
    private fun headlineSessionCount(): Int =
        streaming.value.ifEmpty { presence.value }.size

    /**
     * 通知栏状态行：流式中有工具文案用工具文案；没有就按会话数给
     * "N 个任务进行中"本地化文案；纯在场给"在对话中"；都没有 Idle。
     */
    private fun headlineStatusText(): String {
        val ctx = appContext
        val live = streaming.value.size
        return when {
            live > 0 -> {
                val tool = toolStatusText.value
                if (tool.isNotBlank() && tool != IDLE_LABEL) {
                    tool
                } else if (ctx != null) {
                    if (live == 1) {
                        ctx.getString(R.string.notif_one_task_running)
                    } else {
                        ctx.getString(R.string.notif_n_tasks_running, live)
                    }
                } else {
                    if (live == 1) "1 task running" else "$live tasks running"
                }
            }
            presence.value.isNotEmpty() ->
                ctx?.getString(R.string.notif_in_session) ?: "In session"
            else -> IDLE_LABEL
        }
    }
}
