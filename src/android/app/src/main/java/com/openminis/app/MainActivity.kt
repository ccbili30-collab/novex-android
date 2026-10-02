package com.openminis.app

import android.app.AlertDialog
import android.app.LocaleManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import com.openminis.app.deeplink.DeepLinkHandler
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.navigation.AppNavigation
import com.openminis.app.ui.navigation.Routes
import com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE
import com.openminis.app.ui.settings.KEY_LANGUAGE
import com.openminis.app.ui.settings.PREF_APPEARANCE
import com.openminis.app.ui.settings.getAppearancePrefs
import com.openminis.app.ui.settings.keepScreenAwakeEnabled
import com.openminis.app.ui.theme.NovexAppTheme
import com.openminis.app.ui.theme.ThemeVariantMode
import com.openminis.app.ui.theme.relativeLuminance
import kotlinx.coroutines.launch

private const val KEY_CURRENT_CHAT_SESSION_ID = "minis.current_chat_session_id"

/**
 * T166 冷启动深链裁决（纯函数，行为钉见 MainActivityLaunchRulesTest）：
 * 用户点进来的真深链永远赢过保存态恢复；否则恢复上次所在会话；都没有
 * 才落 Unknown。AppNavigation 拿到结果作 initialDeepLink。
 */
internal fun resolveLaunchDeepLink(
    intentData: Uri?,
    restoredSessionId: String?,
): DeepLinkAction {
    val explicit = DeepLinkHandler.parse(intentData)
    if (explicit !is DeepLinkAction.Unknown) return explicit
    return restoredSessionId?.let { DeepLinkAction.OpenSession(it) } ?: DeepLinkAction.Unknown
}

class MainActivity : ComponentActivity() {

    private var navController: NavHostController? = null

    /**
     * T166: id of the chat the user is currently inside, mirrored from
     * the nav back-stack so [onSaveInstanceState] can persist it across
     * process death. Restored value is fed into [AppNavigation] as the
     * initial deep-link so the user lands back where they were.
     */
    private var currentChatSessionId: String? = null

    /**
     * T166: id of the chat to re-open on first composition after a
     * process-death restart. Set in [onCreate] from the saved state
     * bundle, consumed exactly once by [AppNavigation] via the
     * synthesised deep-link.
     */
    private var restoredChatSessionId: String? = null

    // [P3.3 裁军] permissionLauncher/settingsLauncher（OffloadPermissionManager
    // 的系统权限桥）随 offload/ 整包退役删除。

    /**
     * State shuttled between the "Save to..." path of the safe-mode
     * crash-share dialog and the `crashSaveLauncher` registered in
     * [onCreate]. The lambda fires after the launcher's result callback
     * completes so the caller (which passes `finish()` for safe-mode)
     * can tear down the Activity once the file is on disk and the
     * follow-on ACTION_VIEW has been dispatched.
     */
    private var pendingCrashZip: java.io.File? = null
    private var pendingCrashSaveOnClosed: (() -> Unit)? = null

    // T-n01-andmenu-l10n: on Android 12 and below `LocaleManager.applicationLocales`
    // does not exist, so the saved language pick has no effect on framework
    // strings — including the labels of the system text-selection ActionMode
    // ("Cut" / "Copy" / "Paste"). Override the base Configuration so the
    // framework picks the right locale on those devices. No-op on 13+.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.openminis.app.i18n.LocaleWrap.wrap(newBase))
    }

    /**
     * T-android-safemode-lateinit-crash: escape hatch for a process whose
     * [MinisApp.onCreate] early-returned under safe-mode.
     *
     * That early return is irreversible within the process — the
     * repositories stay unassigned no matter what the safe-mode flag says
     * afterwards — so there is nothing this Activity can do to become
     * usable. Killing the process is the recovery: the next launch runs
     * Application.onCreate from the top, and by then the burst detector's
     * 24h suppress window (set by the dismiss the user just performed)
     * keeps safe-mode from tripping again, so init completes normally.
     *
     * We deliberately do NOT auto-relaunch an Activity here. Doing so from
     * a dying process races the system's own task restart on some OEM
     * builds (MIUI included) and can produce two tasks. Exiting cleanly
     * and letting the user's next tap start the app is predictable.
     */
    private fun finishAndRestartProcess() {
        try {
            android.widget.Toast.makeText(
                this,
                getString(R.string.crash_safe_mode_restart_needed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "restart toast failed: ${t.message}")
        }
        finish()
        // Let the toast render before tearing the process down. The delay
        // runs on the main looper of a process we are about to kill, which
        // is fine — nothing else is scheduled on it at this point.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { android.os.Process.killProcess(android.os.Process.myPid()) },
            1200L,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.openminis.app.crash.ProcessExitEvidence.collect(this)
        com.openminis.app.crash.ProcessExitEvidence.record(this, "activity.create restored=${savedInstanceState != null}")

        val crashSaveLauncher = registerCrashShareSaveLauncher()

        // Safe-mode short-circuit: if CrashFrequencyDetector tripped in
        // MinisApp.onCreate (≥THRESHOLD recent crash files), the
        // Application skipped all heavy init — no DB, no repos, no
        // offload server. We must NOT call setContent() / ChatViewModel /
        // any code that touches MinisApp's lateinit deps; doing so would
        // throw UninitializedPropertyAccessException and overwrite the
        // very crash logs we're trying to ship.
        //
        // Pop the share/dismiss dialog directly and finish() on close so
        // the user's next launch starts fresh. The dialog UI uses
        // AlertDialog (system-level) which doesn't touch MinisApp state.
        // T-android-safemode-lateinit-crash: gate on the Application's own
        // "did init actually run" flag, NOT on isSafeMode(). The two are
        // not equivalent, and the difference was a hard crash loop:
        // finishClose() sets safe-mode back to false as soon as the user
        // dismisses the share dialog, but MinisApp.onCreate already
        // early-returned and never re-runs for the life of the process.
        // Any MainActivity created after that dismissal (launcher icon —
        // including the MainActivityIconDark alias — notification tap, or
        // MIUI restoring the task) therefore sailed past the old
        // isSafeMode() check and hit `app.chatRepository` inside
        // setContent(), throwing UninitializedPropertyAccessException on
        // the first Compose frame (onAttachedToWindow →
        // setOnViewTreeOwnersAvailable). That crash wrote another
        // crash-*.log, which re-tripped the burst detector on the next
        // launch — a self-sustaining loop the user could not escape.
        //
        // `subsystemsInitialized` only goes true after every repository is
        // assigned, so it stays false for exactly as long as composing is
        // genuinely unsafe.
        val minisApp = application as? MinisApp
        if (minisApp != null && !minisApp.subsystemsInitialized && !minisApp.startupCoordinator.safeMode) {
            // 数据层还没起（首帧延迟初始化路径）：先画自有的启动面，
            // 等首帧真正画出来再拉起运行时，避免在系统 splash 上卡住。
            showLaunchSurfaceThen {
                lifecycleScope.launch {
                    val result = minisApp.startupCoordinator.ensureRuntime()
                    if (result.isSuccess) recreate() else showInitializationFailure(result.exceptionOrNull())
                }
            }
            return
        }
        if (minisApp == null || !minisApp.subsystemsInitialized) {
            offerCrashShareDialogAndRestart(crashSaveLauncher)
            return
        }

        // T166: if we were killed by LMK while the user was inside a
        // chat, restore the sessionId now so the synthesised deep-link
        // re-opens it before any composable is composed. ChatViewModel
        // rehydrates messages from SQLite via session id, so the user
        // lands in the same chat with the same history rendered.
        // Process death loses streamJob coroutine state — any in-flight
        // stream is treated as cancelled and the user can tap Retry.
        // Honour the crash-frequency force-home grace: when the previous
        // boot tripped the burst detector we don't want LMK-restore to
        // drop the user straight back into the chat that may have been
        // the crash trigger. AppNavigation also reads this flag for its
        // own launch-mode resolution; gating restoredChatSessionId here
        // covers the path where saved-state would override the
        // launch-mode dispatch entirely.
        restoredChatSessionId = savedInstanceState?.getString(KEY_CURRENT_CHAT_SESSION_ID)
            ?.takeUnless { com.openminis.app.crash.CrashFrequencyDetector.shouldForceHomeOnLaunch(this) }

        // [P3.3 裁军] 系统权限桥两段（pendingAndroidPermission /
        // pendingSettingsGate 收集器）随 OffloadPermissionManager 退役删除。

        // Apply saved language before composing UI
        applySavedLanguage()

        // T51: ShareReceiverActivity re-launches MainActivity with the
        // `shared_content=true` extra after persisting a PendingShare to
        // share_prefs. Process it now so ShareCoordinator's in-memory
        // buffer is populated before any ChatScreen composes.
        //
        // [T-android-share-launch-crash] Deliberately UNCONDITIONAL, matching
        // iOS `checkForPendingShare()` which runs on every launch
        // (MinisApp.swift:279) rather than keying off a launch parameter.
        // Gating on the extra made the share unrecoverable in exactly the case
        // the OEM-crash fallback creates: when ShareReceiverActivity cannot
        // start MainActivity at all, the extra is never delivered, so a user
        // who then opens the app from the launcher had their already-persisted
        // share sit unread — the toast tells them to open the app, and opening
        // it did nothing. Reading it here is the safety net that makes that
        // advice true.
        //
        // Safe to call on every launch: processPendingShare returns
        // immediately when share_prefs holds no record (the overwhelmingly
        // common case), and consumes-and-clears the record when it does, so a
        // share is never injected twice. Staleness is still enforced inside
        // (LAUNCH_MAX_AGE_MS), so an abandoned record cannot resurface later.
        com.openminis.app.share.ShareCoordinator.processPendingShare(this)

        // Wire FLAG_KEEP_SCREEN_ON to (Appearance → Keep Screen Awake) AND
        // SessionActivityTracker.activeSessions. Lock held iff toggle is on
        // AND at least one session is currently running a task. Mirrors iOS
        // KeepScreenAwakeController (AIChatViewModel.swift:320), which holds
        // UIApplication.isIdleTimerDisabled under the same conditions.
        // Re-evaluates on every prefs change (the existing prefs listener
        // below covers the toggle) and on every activeSessions transition.
        lifecycleScope.launch {
            SessionActivityTracker.activeSessions.collect { active ->
                applyKeepScreenAwakeFlag(active.isNotEmpty())
            }
        }

        // [P3.3 裁军] 调试面板（debug/ 整包）退役：DebugRPCHandler 截图
        // 注册随之删除。

        // Non-null and fully initialized — proven by the guard above.
        val app = requireNotNull(application as? MinisApp)

        // Parse deep link from launch intent — 见顶层 resolveLaunchDeepLink
        // 的裁决规则（真深链 > 保存态会话恢复 > Unknown）。T166.
        val launchDeepLink = resolveLaunchDeepLink(intent?.data, restoredChatSessionId)
        val novexStartRoute = intent?.getStringExtra(EXTRA_NOVEX_START_ROUTE)

        setContent {
            val prefs = remember { getAppearancePrefs(this) }

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == KEY_KEEP_SCREEN_AWAKE) {
                        applyKeepScreenAwakeFlag(
                            SessionActivityTracker.activeSessions.value.isNotEmpty()
                        )
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            NovexAppTheme { appearance ->
                val activeThemeColors = appearance.themeColors.variant(
                    if (appearance.darkTheme) ThemeVariantMode.Dark else ThemeVariantMode.Light
                )
                val backgroundIsDark = relativeLuminance(activeThemeColors.background) < 0.5

                SideEffect {
                    val barStyle = if (backgroundIsDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
                }
                val navController = rememberNavController().also { this.navController = it }

                DisposableEffect(navController) {
                    // T166: 把导航栈上的会话进出镜像给 SessionActivityTracker
                    // ——只有 route 实际变化才做 setAbsent/setPresent 对账，
                    // 重组不触发。
                    val job = lifecycleScope.launch {
                        navController.currentBackStackEntryFlow.collect { entry ->
                            com.openminis.app.crash.ProcessExitEvidence.record(
                                this@MainActivity,
                                "navigation.destination=${entry.destination.route}",
                            )
                            val isChatRoute = entry.destination.route == Routes.CHAT
                            val sid = entry.arguments?.getString("sessionId").takeIf { isChatRoute }
                            val previous = currentChatSessionId
                            if (sid != previous) {
                                previous?.let { SessionActivityTracker.setAbsent(it) }
                                sid?.let { SessionActivityTracker.setPresent(it) }
                                currentChatSessionId = sid
                            }
                        }
                    }
                    onDispose { job.cancel() }
                }

                AppNavigation(
                    chatRepository = app.chatRepository,
                    providerRepository = app.providerRepository,
                    envVarRepository = app.envVarRepository,
                    skillRepository = app.skillRepository,
                    memoryRepository = app.memoryRepository,
                    navController = navController,
                    initialDeepLink = launchDeepLink,
                    initialRoute = novexStartRoute,
                )

                // [P3.3 裁军] minis-config 根级确认弹窗
                // （ConfigConfirmDialogHost）随 config/ 体系退役删除。
            }
        }
    }

    // ─── 安全模式 / 崩溃分享路径 ────────────────────────────────────────────

    /**
     * Register the crash-share "Save to..." launcher BEFORE the
     * safe-mode early-return below — ActivityResultLauncher must be
     * registered before STARTED, and the safe-mode path needs it.
     * The launcher pairs with `pendingCrashZip` to copy the zip into
     * whatever URI the user picked in the system file picker, then
     * opens that URI with ACTION_VIEW so they see it land.
     */
    private fun registerCrashShareSaveLauncher(): ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val targetUri = result.data?.data
            val zip = pendingCrashZip
            pendingCrashZip = null
            if (result.resultCode != android.app.Activity.RESULT_OK || targetUri == null || zip == null) {
                settlePendingCrashSave()
                return@registerForActivityResult
            }
            try {
                copyCrashZipInto(zip, targetUri)
                openSavedCrashZip(targetUri)
            } catch (t: Throwable) {
                android.util.Log.w("MainActivity", "crash zip save failed: ${t.message}")
            } finally {
                settlePendingCrashSave()
            }
        }

    private fun settlePendingCrashSave() {
        pendingCrashSaveOnClosed?.invoke()
        pendingCrashSaveOnClosed = null
    }

    /** 把崩溃 zip 复制到用户在 SAF 选择的目标；流打不开视为失败。 */
    private fun copyCrashZipInto(zip: java.io.File, targetUri: Uri) {
        val out = contentResolver.openOutputStream(targetUri)
            ?: throw java.io.IOException("openOutputStream returned null")
        out.use { sink -> java.io.FileInputStream(zip).use { source -> source.copyTo(sink) } }
    }

    /**
     * Auto-open the saved file so the user sees where it landed. ACTION_VIEW
     * with the SAF URI hands control to whatever app the system associates
     * with .zip / application/zip (file managers, archive viewers).
     */
    private fun openSavedCrashZip(targetUri: Uri) {
        try {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(targetUri, "application/zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(view)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "ACTION_VIEW after save failed: ${t.message}")
        }
    }

    /**
     * 子系统不可用（安全模式早退 / 非 MinisApp 进程）：弹崩溃分享对话框。
     * 关闭即 [finishAndRestartProcess]——
     * T-android-safemode-lateinit-crash: plain finish() here is a
     * dead end on the second launch. maybeShowOnActivity invokes
     * onClosed immediately when pendingShareFiles is null, which is
     * exactly the state after the user dismissed the dialog on
     * the previous launch — the app would close the instant it was
     * tapped, reading as "Minis won't open at all". The process
     * still holds a permanently uninitialized Application, so the
     * only real recovery is a fresh process: tell the user, then
     * exit hard so the next tap gets a clean init.
     */
    private fun offerCrashShareDialogAndRestart(
        crashSaveLauncher: ActivityResultLauncher<Intent>,
    ) {
        android.util.Log.w(
            "MainActivity",
            "app subsystems not initialized (safeMode=" +
                "${com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()}) — " +
                "showing crash share dialog and finishing",
        )
        com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(
            activity = this,
            onClosed = { finishAndRestartProcess() },
            saveLauncher = { zip, onSaveDone ->
                pendingCrashZip = zip
                pendingCrashSaveOnClosed = onSaveDone
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                    putExtra(Intent.EXTRA_TITLE, zip.name)
                }
                try {
                    crashSaveLauncher.launch(intent)
                } catch (t: Throwable) {
                    android.util.Log.w("MainActivity", "CREATE_DOCUMENT launch failed: ${t.message}")
                    pendingCrashZip = null
                    pendingCrashSaveOnClosed = null
                    onSaveDone()
                }
            },
        )
    }

    /**
     * Draw a tiny app-owned frame before constructing the legacy navigation
     * graph. Android keeps the system splash visible until the Activity draws;
     * previously the first draw waited for the entire Compose tree.
     */
    private fun showLaunchSurfaceThen(showContent: () -> Unit) {
        var handedOff = false
        val launchSurfaceNight =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = object : FrameLayout(this) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                if (!handedOff) {
                    handedOff = true
                    post {
                        android.util.Log.i("MainActivity", "Novex launch surface drawn; composing app")
                        showContent()
                    }
                }
            }
        }.apply {
            // home-v2 Canvas，与 NovexLaunchActivity 一致：跟随夜间模式，
            // 深色主题下不再闪浅底。
            setBackgroundColor(
                if (launchSurfaceNight) Color.rgb(15, 17, 18)
                else Color.rgb(244, 245, 244)
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24.dpPx(), 72.dpPx(), 24.dpPx(), 24.dpPx())
        }
        column.addView(TextView(this).apply {
            text = "Novex"
            textSize = 28f
            setTextColor(if (launchSurfaceNight) Color.rgb(242, 243, 243) else Color.rgb(22, 22, 26))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        repeat(3) { index ->
            column.addView(FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 12.dpPx().toFloat()
                    setColor(
                        if (launchSurfaceNight) {
                            if (index == 0) Color.rgb(38, 41, 43) else Color.rgb(26, 29, 31)
                        } else {
                            if (index == 0) Color.rgb(237, 240, 246) else Color.rgb(243, 244, 247)
                        }
                    )
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (index == 0) 54.dpPx() else 72.dpPx(),
            ).apply {
                topMargin = if (index == 0) 30.dpPx() else 14.dpPx()
            })
        }
        surface.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        setContentView(surface)
    }

    private fun Int.dpPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun showInitializationFailure(error: Throwable?) {
        AlertDialog.Builder(this)
            .setTitle("Novex 初始化失败")
            .setMessage(error?.message ?: "无法准备应用数据，请重新启动后重试。")
            .setCancelable(false)
            .setPositiveButton("重新启动") { _, _ -> finishAndRestartProcess() }
            .setNegativeButton("关闭") { _, _ -> finish() }
            .show()
    }

    // ─── 会话镜像与生命周期 ────────────────────────────────────────────────

    /**
     * T166: persist the current chat sessionId so an LMK kill while in
     * chat restarts back to the same session (see `restoredChatSessionId`
     * in [onCreate]). Called by the OS in the same lifecycle phase that
     * Activity Recreation uses, so a configuration change also goes
     * through this path — which is exactly what we want; the state is
     * cheap and re-read is idempotent.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentChatSessionId?.let { outState.putString(KEY_CURRENT_CHAT_SESSION_ID, it) }
    }

    /**
     * T166: when the Activity is genuinely torn down (not just paused),
     * release any presence we held. Pause / Home / lock-screen do NOT
     * trigger this — those are exactly the cases the FG service exists
     * to bias OOM through.
     */
    override fun onDestroy() {
        com.openminis.app.crash.ProcessExitEvidence.record(
            this,
            "activity.destroy changingConfiguration=$isChangingConfigurations finishing=$isFinishing",
        )
        currentChatSessionId?.let { SessionActivityTracker.setAbsent(it) }
        currentChatSessionId = null
        super.onDestroy()
    }

    override fun finish() {
        com.openminis.app.crash.ProcessExitEvidence.record(this, "activity.finish")
        val enteredFromNovexSettings = intent?.getStringExtra(EXTRA_NOVEX_START_ROUTE) == "settings"
        super.finish()
        if (enteredFromNovexSettings) {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.novex_enter_from_right, R.anim.novex_exit_to_left)
        }
    }

    // ─── 屏幕常亮与语言 ────────────────────────────────────────────────────

    /**
     * Apply (or release) the activity window's `FLAG_KEEP_SCREEN_ON` based on
     * the user's "Keep Screen Awake" toggle and whether any session has an
     * active task right now. Idempotent — flipping with the same desired
     * state is a no-op at the WindowManager level.
     */
    private fun applyKeepScreenAwakeFlag(hasActiveSession: Boolean) {
        val want = keepScreenAwakeEnabled(this) && hasActiveSession
        if (want) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        AppLogger.info(
            "KeepScreenAwake",
            "screen-on lock ${if (want) "acquired (active sessions present)" else "released"} " +
                "(toggle=${keepScreenAwakeEnabled(this)}, active=$hasActiveSession)",
        )
    }

    private fun applySavedLanguage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val code = getSharedPreferences(PREF_APPEARANCE, MODE_PRIVATE).getString(KEY_LANGUAGE, "") ?: ""
        val localeManager = getSystemService(LocaleManager::class.java) ?: return
        val desired = if (code.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        if (localeManager.applicationLocales.toLanguageTags() != desired.toLanguageTags()) {
            localeManager.applicationLocales = desired
        }
    }

    // ─── 热启动分发 ────────────────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // T51: warm-start share — ShareReceiverActivity re-launches with
        // FLAG_ACTIVITY_CLEAR_TOP, which delivers the new intent here when
        // MainActivity is already alive. Process the buffered share before
        // touching the deep-link path so the share coordinator sees it.
        if (intent.getBooleanExtra("shared_content", false)) {
            com.openminis.app.share.ShareCoordinator.processPendingShare(this)
        }
        intent.getStringExtra(EXTRA_NOVEX_START_ROUTE)?.let { route ->
            navController?.navigate(route) { launchSingleTop = true }
        } ?: run { handleDeepLink(intent.data) }
    }

    private fun handleDeepLink(uri: Uri?) {
        val nav = navController ?: return
        when (val action = DeepLinkHandler.parse(uri)) {
            // T-double-chat-fix (secondary): mirror AppNavigation's
            // OpenSession options so a runtime deep-link (notification /
            // shortcut / onNewIntent) can't stack a duplicate chat on
            // top of the same chat that's already showing.
            is DeepLinkAction.OpenSession -> openSessionFromDeepLink(nav, action.sessionId)
            // T183: any settings screen reachable by route string.
            is DeepLinkAction.OpenSettingsScreen -> nav.navigate(action.route)
            // [P3.3 裁军] OpenPermissionSettings（权限屏路由）、OpenHtmlPreview
            // （HTML 预览捷径）、OpenAlarmList（系统闹钟列表跳转）随对应功能
            // 退役删除；minis://views/alarm 等旧深链现在落入 Unknown。
            // App-icon quick actions (mirrors iOS QuickActionRouter):
            // new_chat / camera_chat open a fresh draft chat; camera seeds
            // DeepLinkCoordinator.pendingChatAction so ChatScreen auto-fires
            // the camera on first compose. voice_chat 快捷方式随语音退役。
            is DeepLinkAction.NewChat, is DeepLinkAction.NewCameraChat -> openFreshDraftChat(nav, action)
            else -> {}
        }
    }

    private fun openSessionFromDeepLink(nav: NavHostController, sessionId: String) {
        nav.navigate(Routes.chat(sessionId)) {
            popUpTo(Routes.SESSION_LIST) {
                inclusive = false
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    private fun openFreshDraftChat(nav: NavHostController, action: DeepLinkAction) {
        if (action is DeepLinkAction.NewCameraChat) {
            DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
        }
        nav.navigate(Routes.chat("__new__${java.util.UUID.randomUUID()}")) {
            popUpTo(Routes.SESSION_LIST) { inclusive = false }
            launchSingleTop = true
        }
    }
}
