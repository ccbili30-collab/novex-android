package com.openminis.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import novex.android.data.NovexMainDatabase
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.notification.BackgroundTaskNotifier
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.startup.NovexStartupCoordinator
import com.openminis.app.startup.NovexCrashBootstrap
import com.openminis.app.startup.NovexStartupMetrics
import com.openminis.app.ui.MinisImageFetcher
import kotlinx.coroutines.launch

/** [P3.4 净眼] 一次性旧 alarm 清扫的完成标志键（minis_maintenance_prefs）。 */
private const val KEY_RETIRED_ALARM_SWEEP_DONE = "retired_alarm_sweep_done_v1"

/**
 * T268 ghost alarm 重放判定（纯函数，行为钉见 MinisAppMaintenanceTest）。
 *
 * T266 之前的老安装把闹钟/计时器写进 minis 自己的 prefs + AlarmManager；
 * T266 退役该路径后这些条目成了只在应用内生效的幽灵。重放规则：
 *  - 过期的 timer 没有可恢复的东西（OS 不会再触发）→ 跳过；
 *  - 过期的一次性（ONCE）闹钟同样跳过；
 *  - 其余（未来 timer / 未来闹钟 / 周期闹钟即便触发点已过——下一轮还会
 *    响）按原类型经系统 Clock 应用的 SET_TIMER / SET_ALARM 意图重放。
 */
internal enum class GhostAlarmAction { SKIP, REPLAY_TIMER, REPLAY_ALARM }

internal fun ghostAlarmAction(
    type: String,
    triggerAtMs: Long,
    repeatMode: String,
    now: Long,
): GhostAlarmAction {
    val alreadyPast = triggerAtMs in 1L..now
    if (alreadyPast && type == "timer") return GhostAlarmAction.SKIP
    if (alreadyPast && repeatMode == "ONCE") return GhostAlarmAction.SKIP
    return if (type == "timer") GhostAlarmAction.REPLAY_TIMER else GhostAlarmAction.REPLAY_ALARM
}

/**
 * T-android-fgs-timeout-crash 的判定核（纯函数，行为钉见
 * MinisAppMaintenanceTest）：这个 Throwable 是不是
 * RemoteServiceException$ForegroundServiceDidNotStopInTimeException？
 * 异常类名在一些 OS 上被包一层，故同时认「消息同时包含 foreground
 * service of type 与 did not stop within its timeout」的消息面判定。
 */
internal fun isForegroundServiceTimeout(throwable: Throwable): Boolean {
    if (throwable.javaClass.name.endsWith("RemoteServiceException\$ForegroundServiceDidNotStopInTimeException")) {
        return true
    }
    val message = throwable.message ?: return false
    return message.contains("foreground service of type") &&
        message.contains("did not stop within its timeout")
}

class MinisApp : Application(), ImageLoaderFactory, novex.android.CardImportProvider {

    // ─── 初始化器次序契约（冻结面） ────────────────────────────────────────
    // onCreate → attachBaseContext 已装 ACRA → :acra 进程早退 → 日志上下文
    // → wire 抓取目录 → 安全模式探测 → 协调器；两段初始化（minimum/runtime）
    // 的内部次序分别见 [initializeMinimumSubsystems] 与
    // [initializeRuntimeSubsystems]——各初始化器的先后与副作用等价，
    // 重排结构不得改变初始化效果顺序。

    override fun prepareCardImport(store: novex.storage.CardStore, input: java.io.InputStream, name: String, kind: novex.content.CardKind): novex.storage.CardDraft =
        com.openminis.app.cards.LegacyArchiveImport(store, cacheDir.toPath().resolve("legacy-card-incoming")).prepare(input, name, kind)

    private val startupScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    lateinit var startupCoordinator: NovexStartupCoordinator
        private set

    private val postHomeLock = Any()
    private val cardDirectoryMigrationStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var postHomeReady = false

    /**
     * T-android-safemode-lateinit-crash: true once the heavy subsystem
     * block in [onCreate] has fully run (DB + every repository assigned).
     *
     * Safe-mode makes [onCreate] early-return BEFORE those assignments,
     * and that early return is permanent for the life of the process —
     * the Application object is never re-created just because the user
     * dismissed the crash dialog. Callers must therefore not use
     * `CrashFrequencyDetector.isSafeMode()` as a proxy for "are the
     * repositories usable": that flag flips back to false on dismiss
     * while the repositories stay unassigned forever.
     *
     * This flag is the authoritative signal instead — it only ever goes
     * false → true, and only after every lateinit below is assigned.
     */
    @Volatile
    var subsystemsInitialized: Boolean = false
        private set

    /** 子系统未就绪时把 lateinit 读转成 null（见各 OrNull 属性）。 */
    private inline fun <T> ifReady(read: () -> T): T? = if (subsystemsInitialized) read() else null

    /**
     * [T-android-safemode-lateinit-crash-147] Null-safe view of
     * [chatRepository] for code that can run BEFORE (or entirely without)
     * MainActivity.
     *
     * GH#147: a user installed a third-party skill and the app then crashed on
     * every launch with `lateinit property chatRepository has not been
     * initialized`. MainActivity has guarded this since b72dc591, but that
     * guard only covers the UI entry point. Alarms, notification taps, the
     * notification-listener service and the scheduled-task runner can all
     * start the process with NO Activity at all: Android creates the
     * Application, `onCreate` early-returns under safe-mode, and the component
     * then reads a lateinit that will never be assigned for the life of the
     * process.
     *
     * Reading this property instead of the lateinit turns a hard crash into a
     * null the caller can handle — the process stays alive, the crash-burst
     * detector is not re-tripped, and the user is not locked out.
     *
     * Deliberately checks [subsystemsInitialized] rather than
     * `::chatRepository.isInitialized`: the latter would report true midway
     * through onCreate, when chatRepository is assigned but the repositories
     * assigned after it are not — callers would then trip over the NEXT
     * uninitialized field instead. One flag, one meaning: "everything the app
     * layer needs is ready".
     */
    val chatRepositoryOrNull: ChatRepository?
        get() = ifReady { chatRepository }

    /**
     * [T-android-share-launch-crash] Same contract as [chatRepositoryOrNull],
     * for the share-import path.
     *
     * ShareReceiverActivity is reachable from the system share sheet at any
     * time, including in a process whose [onCreate] early-returned under
     * safe-mode. Reading the `providerRepository` lateinit there would throw
     * UninitializedPropertyAccessException from a dialog callback and crash the
     * app — writing another crash log and feeding the very burst detector that
     * put the process in safe-mode. Returning null instead lets the caller show
     * "import failed", which it already does for the missing-Application case.
     */
    val providerRepositoryOrNull: ProviderRepository?
        get() = ifReady { providerRepository }

    /**
     * [T-android-safemode-lateinit-crash-147] True when the app-layer
     * dependencies are usable. Prefer this over
     * `CrashFrequencyDetector.isSafeMode()`, which flips back to false the
     * moment the user dismisses the crash dialog while the repositories stay
     * unassigned forever (that mismatch was the b72dc591 crash loop).
     */
    fun subsystemsReady(): Boolean = subsystemsInitialized

    lateinit var database: NovexMainDatabase
        private set
    lateinit var novexWorkspace: novex.core.NovexWorkspace
        private set
    val novexWorkGroups: novex.core.NovexWorkGroups by lazy {
        com.openminis.app.data.creative.RoomNovexWorkGroups(database) { address ->
            com.openminis.app.cards.IntegratedCatalog(this).contains(address)
        }
    }
    val novexSnapshotMediaStore by lazy {
        novex.android.adapter.NovexSnapshotMediaStore(java.io.File(filesDir, "novex/adopted-media"))
    }
    lateinit var creativeArtifactRepository: com.openminis.app.data.creative.CreativeArtifactRepository
        private set
    lateinit var creativeArtifactDeviceDirectory: com.openminis.app.data.creative.CreativeArtifactDeviceDirectory
        private set
    lateinit var chatRepository: ChatRepository
        private set
    val conversationWorkspaceStore by lazy {
        novex.core.FileNovexConversationWorkspaceStore(java.io.File(filesDir, "novex/conversation-workspaces"))
    }
    val conversationRepositoryImporter by lazy {
        com.openminis.app.data.creative.ConversationRepositoryImporter(this, conversationWorkspaceStore, creativeArtifactRepository)
    }
    val conversationDeletion by lazy {
        val files = conversationWorkspaceStore
        novex.core.NovexConversationDeletion(
            chatRepository, novexWorkspace, files,
            com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(files, creativeArtifactRepository),
            novex.core.NovexToolExecution(
                novex.core.NovexOperationJournal(java.io.File(filesDir, "novex-operations"))),
            { id ->
                conversationRepositoryImporter.stopAndJoin(id)
                com.openminis.app.ui.chat.ChatViewModelStore.stopAndJoin(id)
            },
            com.openminis.app.service.SessionBadgeStore::clear,
            com.openminis.app.ui.chat.ChatViewModelStore::finishDeletion,
        )
    }
    lateinit var providerRepository: ProviderRepository
        private set
    lateinit var envVarRepository: EnvVarRepository
        private set
    lateinit var skillRepository: SkillRepository
        private set
    lateinit var memoryRepository: MemoryRepository
        private set
    lateinit var backgroundSettingsRepository: BackgroundSettingsRepository
        private set
    lateinit var backgroundTaskNotifier: BackgroundTaskNotifier
        private set

    /**
     * T180-bg-notif: foreground-Activity counter, mutated by the
     * ActivityLifecycleCallbacks registered in [onCreate]. Read by
     * [BackgroundTaskNotifier] to decide whether to suppress completion
     * notifications (no notification while the user is already looking
     * at the app).
     */
    @Volatile
    private var foregroundActivityCount: Int = 0

    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    /**
     * T-bg-overlay phase 2: live "is the app foreground?" stream so the
     * AgentForegroundService can react to background ↔ foreground
     * transitions and toggle the floating tool-status overlay. Same
     * source as [isAppForeground] (started/stopped balanced count) —
     * just exposed as a StateFlow for collectors.
     */
    // Initial true: prevents overlay flash before first Activity onStart emits foreground=true
    // (T-overlay-startup-flash). ActivityLifecycleCallbacks below will flip to the real value
    // on the next lifecycle tick.
    private val _isAppForegroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isAppForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _isAppForegroundFlow

    /**
     * App-wide network monitor. Mirrors iOS NetworkMonitor.shared — observes
     * connectivity transitions, evicts shared OkHttp connection pools, and
     * refreshes the sandbox's /etc/resolv.conf when DNS servers change.
     */
    val networkMonitor: NetworkMonitor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        NetworkMonitor()
    }

    // [P3.3 裁军] Application-scoped BrowserTabPool（minis-browser-use 壳工具
    // 伴奏池）随内置浏览器全家（browser/ + ui/browser/）整体退役删除。

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        NovexStartupMetrics.reportProcessStart()
        // T283: install ACRA before any app-level singleton runs so a crash
        // anywhere from onCreate forward is captured. CrashFileSender
        // (registered via META-INF/services/org.acra.sender.ReportSenderFactory)
        // writes filesDir/logs/crash-<stamp>.log — same dir + .log extension
        // that AppLogger.listLogFiles already filters for, so reports surface
        // in LogManagementScreen with no extra UI.
        NovexCrashBootstrap.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        // T287-followup: ACRA spawns a separate reporter process named
        // "<package>:acra" (declared by the library's manifest) to send
        // the crash report after the main process dies. Application
        // subclasses run in EVERY process of the package, so without
        // this early-return the :acra process would also try to bind
        // the abstract socket / boot the database / register offload
        // handlers — racing the next main-process spawn for resources
        // it doesn't need. Symptom: main process gets EADDRINUSE on
        // LocalServerSocket and stays in a permanent restart loop on
        // the splash screen. Skip everything except ACRA.init (already
        // done in attachBaseContext, which is what makes the :acra
        // process do its job).
        if (NovexCrashBootstrap.isReporterProcess()) {
            Log.i("MinisApp", "skipping app init in :acra reporter process")
            return
        }

        // T-android-safemode-lateinit-crash: hand AppLogger a Context before
        // any early-return below can skip AppLogger.init(). This costs
        // nothing (no I/O, no prefs, no capture) and is what lets the in-app
        // log/crash list still find filesDir/logs on a safe-mode launch —
        // the exact launch where the user is trying to read the crash files.
        AppLogger.primeContext(this)

        // [T-provider-wire-capture] 工具轮请求原文抓取目录（诊断中转翻译层
        // 是否吞 tool_result）；未初始化时 record() 静默跳过。
        com.openminis.app.provider.ProviderWireCapture.captureDir = filesDir

        // T-android-crash-freq-share: local fallback for Crashlytics (#458).
        // Scan filesDir/logs/ for crash-*.log + native-crash-*.log files
        // touched in the last hour; if THRESHOLD+ are present, stash the
        // list so MainActivity.onCreate can prompt to share them.
        // Hard short-circuit: when checkAtLaunch flips safe-mode ON, skip
        // every heavy subsystem (DB, repositories, offload server, PRoot
        // bind mounts, network monitor, …). The only thing MainActivity
        // will do is pop the share-or-dismiss dialog and finish. Without
        // this guard, anything from `chatRepository = ChatRepository(...)`
        // onward is a potential re-crash trigger on a loop — the whole
        // point of safe-mode is to stop the bleeding before another
        // segfault rewrites the log files.
        val safeMode = NovexCrashBootstrap.detectSafeMode(this)
        startupCoordinator = NovexStartupCoordinator(
            scope = startupScope,
            safeMode = safeMode,
            initializeMinimum = ::initializeMinimumSubsystems,
            initializeRuntime = ::initializeRuntimeSubsystems,
        )
        if (safeMode) {
            Log.w("MinisApp", "safe-mode ON — skipping app subsystem init")
            return
        }
    }

    // ─── 第一段：数据层最小可用 ─────────────────────────────────────────────

    private fun initializeMinimumSubsystems() {
        database = NovexMainDatabase.getInstance(this)
        novexWorkspace = novex.android.adapter.NovexWorkspaceFactory.createDeferred(
            database,
            java.io.File(filesDir, "novex-media"),
        )
        creativeArtifactRepository = com.openminis.app.data.creative.CreativeArtifactRepository(
            database,
            com.openminis.app.data.creative.CreativeArtifactFileStore(
                java.io.File(filesDir, "novex-artifacts"),
            ),
            cardWorkspace = novexWorkspace,
        )
        creativeArtifactDeviceDirectory = com.openminis.app.data.creative.CreativeArtifactDeviceDirectory(this)
        chatRepository = ChatRepository(database.chatDao())
    }

    /** Start diagnostics and update checks only after the Novex home is usable. */
    fun startPostHomeMaintenance() {
        startupScope.launch {
            ensurePostHomeMaintenance()
            sweepRetiredAlarmsOnce()
            if (cardDirectoryMigrationStarted.compareAndSet(false, true)) {
                runCatching {
                    creativeArtifactRepository.migrateCardImages() + novexWorkspace.migrateCardDirectories()
                }.onSuccess { count ->
                    if (count > 0) Log.w("NovexCardDirectories", "$count card directories need retry")
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    Log.w("NovexCardDirectories", "Card directory migration will retry next launch")
                }
            }
        }
    }

    /**
     * [P3.3 裁军→P3.4 净眼] 一次性清扫已删 receiver 的存量 alarm。
     *
     * 定时任务（scheduled/，P3.3 退役）与上游助理闹钟（offload/
     * AlarmReceiver，P2.5/R2 退役）的 receiver 类已删，但老用户设备上
     * AlarmManager 里的 PendingIntent 还挂着——receiver 不存在后广播静默
     * 丢弃，纯留垃圾。两家调度器都把精确重建 PendingIntent 所需的键
     * （taskId / requestCode）落在各自的 SharedPreferences JSON 里，照原
     * 形重建（FLAG_NO_CREATE 不新建）逐个 cancel，再清掉存档。
     *
     * 幂等：SharedPreferences 一次性标志位，跑过即跳过；标志与存档清除
     * 在同一次成功路径里落盘。任何异常吞掉（下轮重试），绝不影响维护链。
     */
    private fun sweepRetiredAlarmsOnce() {
        runCatching {
            val flagPrefs = getSharedPreferences("minis_maintenance_prefs", Context.MODE_PRIVATE)
            if (flagPrefs.getBoolean(KEY_RETIRED_ALARM_SWEEP_DONE, false)) return@runCatching
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

            var cancelled = 0
            // ① 定时任务（P3.3 删 scheduled/）：requestCode = taskId.hashCode()
            // 正数化，action=FIRE（filterEquals 含 action，缺了匹配不上）。
            val sweptTasks = collectStoredTaskIds()
            for (id in sweptTasks) {
                val intent = Intent().setClassName(this, "com.openminis.app.scheduled.ScheduledTaskAlarmReceiver")
                    .setAction("com.openminis.app.scheduled.FIRE")
                cancelled += cancelStoredBroadcast(alarmManager, id.hashCode() and 0x7FFFFFFF, intent)
            }
            // ② 上游助理闹钟/计时器（R2 删 offload/）：requestCode 原样存档。
            val sweptOffload = collectStoredRequestCodes()
            for (requestCode in sweptOffload) {
                val intent = Intent().setClassName(this, "com.openminis.app.offload.AlarmReceiver")
                cancelled += cancelStoredBroadcast(alarmManager, requestCode, intent)
            }

            // 清存档 + 落一次性标志（同一次提交，崩溃也不留半程状态）。
            getSharedPreferences("minis_scheduled_tasks_prefs", Context.MODE_PRIVATE)
                .edit().remove("tasks_json").apply()
            getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
                .edit().remove("alarms_json").apply()
            flagPrefs.edit().putBoolean(KEY_RETIRED_ALARM_SWEEP_DONE, true).apply()
            if (cancelled > 0 || sweptTasks.isNotEmpty() || sweptOffload.isNotEmpty()) {
                AppLogger.info(
                    "MinisApp",
                    "retired alarm sweep: cancelled=$cancelled " +
                        "(scheduled=${sweptTasks.size} ids, offload=${sweptOffload.size} ids)",
                )
            }
        }.onFailure { Log.w("MinisApp", "retired alarm sweep failed (will retry next launch): ${it.message}") }
    }

    /** 定时任务存档：minis_scheduled_tasks_prefs.tasks_json 里逐项的字符串 id。 */
    private fun collectStoredTaskIds(): List<String> =
        readStoredJsonArray("minis_scheduled_tasks_prefs", "tasks_json") { obj ->
            obj.optString("id").takeIf { it.isNotEmpty() }
        }

    /** 闹钟存档：minis_alarms_prefs.alarms_json 里逐项的整型 requestCode。 */
    private fun collectStoredRequestCodes(): List<Int> =
        readStoredJsonArray("minis_alarms_prefs", "alarms_json") { obj ->
            obj.optInt("requestCode", 0).takeIf { it != 0 }
        }

    /** prefs 里的 JSON 数组存档逐项抽取（键名与形态是历史契约，解析失败得空）。 */
    private inline fun <T> readStoredJsonArray(prefsName: String, key: String, pick: (org.json.JSONObject) -> T?): List<T> =
        getSharedPreferences(prefsName, Context.MODE_PRIVATE).getString(key, null)
            ?.let { raw ->
                runCatching {
                    val arr = org.json.JSONArray(raw)
                    List(arr.length()) { i -> arr.optJSONObject(i) }.mapNotNull { obj -> obj?.let(pick) }
                }.getOrDefault(emptyList())
            }
            ?: emptyList()

    /** 照原形重建（FLAG_NO_CREATE）并撤销一个存量广播 PendingIntent；返回撤销数。 */
    private fun cancelStoredBroadcast(
        alarmManager: android.app.AlarmManager,
        requestCode: Int,
        intent: Intent,
    ): Int {
        val pending = android.app.PendingIntent.getBroadcast(
            this, requestCode, intent,
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE,
        ) ?: return 0
        alarmManager.cancel(pending)
        pending.cancel()
        return 1
    }

    private fun ensurePostHomeMaintenance() = synchronized(postHomeLock) {
        if (postHomeReady) return@synchronized
        AppLogger.init(this)
        runCatching { com.openminis.app.diagnostics.LaunchCycleBeacon.recordLaunch(this) }
            .onFailure { Log.w("MinisApp", "LaunchCycleBeacon.recordLaunch failed: ${it.message}") }
        com.openminis.app.diagnostics.HangDetector.start(this)
        // [T-dual-update-source] 用户选的更新源要先于冷启动检查注水（默认 Gitee）
        com.openminis.app.data.UpdateSourceStore.hydrate(this)
        // [T-reading-view-global] 阅读视图偏好注水（默认翻页）
        novex.android.ReadingViewPrefs.hydrate(this)
        // [T-bulletin-v3] 冷启动唯一主动拉取：更新+名册+未读正文，只改状态不弹窗
        com.openminis.app.data.NovexBulletinMonitor.attachContext(this)
        com.openminis.app.data.NovexBulletinMonitor.coldStartOnAppStart()
        postHomeReady = true
    }

    // ─── 第二段：旧运行时与通知面 ───────────────────────────────────────────

    private fun initializeRuntimeSubsystems() {
        ensurePostHomeMaintenance()
        initializeLegacyCrashAndPreferenceServices()
        providerRepository = ProviderRepository(this)
        envVarRepository = EnvVarRepository(this)
        // [T-android-safemode-lateinit-crash-147] SkillRepository parses
        // third-party content (skills imported from external hubs), which
        // makes it the realistic source of a throw in this block. Its own
        // init is now fully guarded, so construction cannot escape here — see
        // the comment there for why an exception at this point permanently
        // breaks the Application and produces the GH#147 crash loop.
        skillRepository = SkillRepository(this)
        memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))

        // Only dependencies used by the first Activity frame stay on the
        // launch path. Model refresh is initialized after
        // Application.onCreate returns.
        SessionActivityTracker.init(this)
        com.openminis.app.service.SessionBadgeStore.init(this)
        backgroundSettingsRepository = BackgroundSettingsRepository(this)
        backgroundTaskNotifier = BackgroundTaskNotifier(
            context = this,
            chatRepository = chatRepository,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        SessionActivityTracker.setCompletionListener { sessionId, isError ->
            backgroundTaskNotifier.notifyTaskCompleted(sessionId, isError)
        }
        registerForegroundTracking()
        // [P3.3 裁军] OffloadPermissionManager.init（Shizuku/特权后门面）随
        // offload/ 整包与 SystemPermissions/Offload/Shizuku 权限屏一并退役。

        initializeDeferredRuntime()
        subsystemsInitialized = true
        NovexStartupMetrics.reportRuntimeReady()
    }

    /** Legacy-only guards are installed before any native runtime is loaded. */
    private fun initializeLegacyCrashAndPreferenceServices() {
        com.openminis.app.data.FastModePrefs.prime(this)
        com.openminis.app.data.AutoCompactPrefs.prime(this)
        runCatching { com.openminis.app.crash.NativeCrashHandler.install(java.io.File(filesDir, "logs")) }
            .onFailure { Log.w("MinisApp", "NativeCrashHandler install failed: ${it.message}") }
        installForegroundServiceTimeoutGuard()
    }

    /**
     * T-android-fgs-timeout-crash: 在 ACRA 之前串一层 UncaughtExceptionHandler
     * 拦 FGS 超时崩溃——进程反正是 SystemServer 判死的，拦下来能做的是把
     * 前台服务显式停掉（通知干净撤下而不是留僵尸行），再交给前手（ACRA
     * 落盘流程原样走完）。判定核见顶层 [isForegroundServiceTimeout]。
     */
    private fun installForegroundServiceTimeoutGuard() {
        try {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    if (isForegroundServiceTimeout(throwable)) {
                        Log.w(
                            "MinisApp",
                            "FGS timeout caught; stopping service before deferring to ACRA: ${throwable.message}",
                        )
                        runCatching {
                            stopService(Intent().setClassName(
                                this,
                                "com.openminis.app.service.AgentForegroundService",
                            ))
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MinisApp", "FGS-timeout handler internal failure: ${t.message}")
                }
                priorHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("MinisApp", "install FGS-timeout handler failed: ${t.message}")
        }
    }

    private fun registerForegroundTracking() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = foregroundActivityCount == 0
                foregroundActivityCount++
                if (wasBackgrounded) {
                    _isAppForegroundFlow.value = true
                    // T298: 用户回到前台——托盘里「任务完成」通知已经没有
                    // 意义，用户正要直接看那个结果。
                    backgroundTaskNotifier.cancelAllCompletedNotifications()
                    // [T-android-session-paused-badge-hardkill] 前台往返是
                    // 软路径；硬杀后冷启动的对账在 initializeDeferredRuntime。
                    reconcileInterruptedBadges()
                }
            }

            override fun onActivityResumed(activity: Activity) {
                com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                if (foregroundActivityCount == 0) {
                    _isAppForegroundFlow.value = false
                    // [P3.3 裁军] ConfigConfirmationGate.notifyPending() 随
                    // minis-config 体系退役删除。
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * [T-android-session-paused-badge-hardkill] 用 DB 的中断会话集对账
     * PAUSED 徽标：排除正在流式输出的会话（「活跃 ⇒ 永不暂停」），其余
     * 补挂。前台往返与冷启动两处共用。
     */
    private fun reconcileInterruptedBadges() {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val interrupted = runCatching { chatRepository.interruptedSessionIds() }
                .getOrElse { emptySet() }
            val active = SessionActivityTracker.activeSessions.value
            com.openminis.app.service.SessionBadgeStore
                .reconcileInterruptedSessions(interrupted - active)
        }
    }

    private fun initializeDeferredRuntime() {

        // [T-soul-md] Seed SOUL.md with the default content on first launch
        // so the Soul settings page and chat bubble identity have a real
        // file to read. Safe no-op on subsequent launches — never
        // overwrites existing user edits. Cache refresh primes the
        // synchronous metadata read-path (chat header / system prompt).
        com.openminis.app.agent.SoulStore.ensureExists(this)
        com.openminis.app.agent.SoulStore.refreshCache(this)
        com.openminis.app.data.character.CharacterCardStore.initialize(this)
        // Copy the v1 card library into the normalized catalog. The old
        // SharedPreferences payload stays in place throughout the preview
        // rollout, so a failed import never removes the currently working UI.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                com.openminis.app.data.character.LegacyCharacterCatalogMigrator
                    .migrate(this@MinisApp, database)
            }.onFailure { error ->
                Log.e("MinisApp", "legacy character catalog migration failed", error)
            }
        }

        // [P3.3 裁军] minis-config 体系（ConfigRegistry/审计日志/确认门/
        // 权限开关，config/ 整族 19f）随自定义配置面退役整体删除；iOS 侧
        // 对应的 minis-config CLI 不再映射到 Android。

        // Initialize models.dev registry (loads from bundled asset, refreshes in background)
        ModelsDevApi.init(this)

        // Privacy Mode store + redactor wiring. Mirrors iOS
        // EnvVarPrivacyStore.init / EnvVarRedactor static handoff.
        com.openminis.app.data.EnvVarPrivacyStore.init(this)
        com.openminis.app.data.EnvVarRedactor.envVarRepository = envVarRepository

        // Start network monitoring — mirrors iOS NetworkMonitor.shared.start().
        networkMonitor.start(this)

        // Register global /var/minis/{memory,skills,shared} bind mounts
        // up-front so direct file I/O tools (file_read, skills, markdown
        // assets) resolve these paths without a sandbox.
        // [P3.3 裁军] mcp-servers 桶随 MCP 集成面退役（MCPRepository 已删，
        // 该桶零消费方）；memory/skills/shared 三桶原样保留。
        novex.android.ContentPaths.registerGlobalMounts(this)

        // [T-android-session-paused-badge-hardkill] 冷启动对账：生命周期
        // 回调的推送只在优雅的后台→前台往返时触发；硬杀（强退/进程死亡）
        // 永远走不到，重启后徽标会缺。持久化的消息尾是权威事实源——
        // 在后台线程扫一遍对账。init() 之后跑，与恢复的队列合并。
        reconcileInterruptedBadges()

        // [P3.3 裁军] ConfigConfirmationGate 后台通知器（config-confirm 门）
        // 与语音识别适配层（SpeechRecognitionManager.init）随各自体系退役。

        // Refresh model lists once per calendar day (mirrors iOS MinisApp.swift).
        // Runs per-instance in parallel; `autoRefreshModels` skips instances with custom models.
        providerRepository.refreshAllModelsIfNeeded(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        )

        // [P3.3 裁军] 调试面板（DebugServer，debug/ 整包 12f）按用户裁决退役；
        // ACRA（crash/）与 AppLogger（logging/）不在裁刀范围内，全部保留。

        // T268: one-shot migration of pre-T266 internal alarms into the
        // system Clock app. Pre-T266 builds wrote alarms into Minis's own
        // SharedPreferences + AlarmManager; T266 retired that path but old
        // installs still have ghost entries that fire only inside Minis.
        // Replay each future-dated entry through the same SET_ALARM /
        // SET_TIMER intents the new path uses, then clear prefs so the
        // migration runs at most once. Wrapped in runCatching so an
        // unexpected prefs shape never blocks app launch.
        runCatching { migrateGhostAlarms() }
            .onFailure { Log.w("MinisApp", "ghost alarm migration failed: ${it.message}") }
    }

    /**
     * T268: replay any pre-T266 internal alarm/timer entries from
     * minis_alarms_prefs through SET_ALARM / SET_TIMER, then clear the
     * prefs blob so subsequent launches no-op. Past-dated entries are
     * dropped (the OS never re-fires them anyway). Idempotent: if the
     * blob is missing or empty the function returns immediately.
     *
     * Silent migration rather than an in-app dialog — Application has no
     * Activity context to host one, and the user-visible outcome (alarms
     * reappear in their Clock app) is what they want regardless of any
     * prompt. AlarmOffloadManager's PendingIntents are left in place; the
     * OS will fire them once more if scheduled, but T268 also clears the
     * prefs blob that AlarmOffloadHandler previously read, so list/cancel
     * commands will no longer surface them.
     */
    private fun migrateGhostAlarms() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            when (ghostAlarmAction(entry.optString("type"), triggerAt, entry.optString("repeatMode", "ONCE"), now)) {
                GhostAlarmAction.SKIP -> skipped++
                GhostAlarmAction.REPLAY_TIMER -> {
                    if (replayGhostTimer(entry, triggerAt, now)) migrated++ else skipped++
                }
                GhostAlarmAction.REPLAY_ALARM -> {
                    if (replayGhostAlarm(entry)) migrated++ else skipped++
                }
            }
        }
        // Clear the blob unconditionally — entries we couldn't replay are
        // still useless ghosts, and leaving the blob would re-trigger
        // migration on every launch.
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }

    /** 重放一条 timer：剩余秒数优先，回落存档时长；两者皆尽则放弃。 */
    private fun replayGhostTimer(entry: org.json.JSONObject, triggerAt: Long, now: Long): Boolean {
        val storedSecs = entry.optInt("durationSec", -1)
        val remaining = ((triggerAt - now) / 1000L).toInt()
        if (remaining <= 0 && storedSecs <= 0) return false
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else storedSecs)
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { startActivity(intent) }.isSuccess
    }

    /** 重放一条闹钟：时/分/标签照存档，跳过 UI 直接入系统 Clock。 */
    private fun replayGhostAlarm(entry: org.json.JSONObject): Boolean {
        val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { startActivity(intent) }.isSuccess
    }

    /**
     * Coil global ImageLoader — registers [MinisImageFetcher] so `minis://`
     * URIs in Markdown images (e.g. `![alt](minis://attachments/x.png)`)
     * resolve to local files under /var/minis/.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache(
                // [T-memory-cap-and-storage] 用户实测内存 1-5G（GH#206 同病：位图
                // 像素堆积在 GC 够不到的 native/graphics 区）。Coil 默认按可用
                // 内存比例给缓存（largeHeap 再放大）——改为固定 128MB 封顶，
                // 到顶丢最旧；磁盘文件不受影响。
                coil.memory.MemoryCache.Builder(this)
                    .maxSizeBytes(128 * 1024 * 1024)
                    .build()
            )
            .components {
                add(MinisImageFetcher.Factory())
                add(MinisImageFetcher.UriFactory())
                // T-image-cache-mtime-35133: include File.lastModified() in
                // memory + disk cache key so Grok-style in-place rewrites of
                // minis://attachments/foo.jpg invalidate Coil's cached bitmap.
                add(MinisImageFetcher.MtimeKeyer())
                add(MinisImageFetcher.StringMtimeKeyer())
            }
            .build()

    /** 压力分档：这些级别上丢弃公式位图缓存；COMPLETE 再加拆离屏 KaTeX WebView。 */
    private val formulaCacheDropLevels = setOf(
        TRIM_MEMORY_RUNNING_LOW, TRIM_MEMORY_RUNNING_CRITICAL,
        TRIM_MEMORY_BACKGROUND, TRIM_MEMORY_MODERATE, TRIM_MEMORY_COMPLETE,
    )

    private inline fun evictQuietly(what: String, evict: () -> Unit) {
        runCatching(evict).onFailure { Log.w("MinisApp", "$what failed: ${it.message}") }
    }

    /**
     * [GH#206] Respond to system memory pressure.
     *
     * The app previously implemented NOTHING here (no `onTrimMemory`, no
     * `onLowMemory` anywhere in the codebase), so every warning the system sent
     * on the way to an OOM was ignored and the only remaining lever was killing
     * the process. The reported failure had the app pinned at a ~1.94 GB native
     * heap while ART ran 478 concurrent-copying GCs in 30 minutes without
     * recovering — expected, because the memory sat in NATIVE bitmap pixels that
     * Java GC cannot reclaim. These caches are exactly that memory, and every
     * entry is reconstructible by re-rendering, so dropping them is free apart
     * from a re-render.
     *
     * Deliberately staged rather than "clear everything on any signal":
     *   - RUNNING_LOW and above (and any BACKGROUND-class level, which means we
     *     are on the LRU list and cheap to kill): drop the formula bitmaps.
     *   - COMPLETE: additionally tear down the offscreen KaTeX WebView, which
     *     is itself a large native allocation. It is recreated on next render.
     *
     * RUNNING_MODERATE is intentionally NOT acted on: it fires routinely on
     * healthy devices, and re-rendering formulas on every mild dip would trade a
     * real user-visible cost for little memory.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level !in formulaCacheDropLevels) return
        Log.i("MinisApp", "onTrimMemory(level=$level): releasing formula bitmap caches")
        evictQuietly("KatexWebViewPool.evictAll") { com.openminis.app.ui.chat.KatexWebViewPool.evictAll() }
        evictQuietly("KaTeXRendererCache.evictAll") { com.openminis.app.ui.markdown.KaTeXRendererCache.evictAll() }

        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.i("MinisApp", "onTrimMemory(level=$level): tearing down the offscreen KaTeX WebView")
            evictQuietly("KatexWebViewPool.releaseWebView") { com.openminis.app.ui.chat.KatexWebViewPool.releaseWebView() }
        }
    }

    override fun onTerminate() {
        // onTerminate is called only on emulators or when the system
        // explicitly tears down — real devices usually skip it. Still
        // worth marking the beacon: a present clean_exit on a real
        // device proves we shut down voluntarily; its absence is the
        // signal we care about for [LaunchCycleBeacon].
        runCatching { com.openminis.app.diagnostics.LaunchCycleBeacon.recordCleanExit(this) }
            .onFailure { Log.w("MinisApp", "LaunchCycleBeacon.recordCleanExit failed: ${it.message}") }
        super.onTerminate()
    }

}
