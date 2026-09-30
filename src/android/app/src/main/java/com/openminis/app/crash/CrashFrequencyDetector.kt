package com.openminis.app.crash

import android.app.Activity
import android.app.Application
import android.content.Context
import java.io.File
import novex.android.crashguard.CrashBurstGuard
import novex.android.crashguard.CrashShareFlow

/**
 * 崩溃风暴检测与分享门面（P3.5b 重写）。检测窗/安全模式/时间戳账
 * 本在 [novex.android.crashguard.CrashBurstGuard]，对话框与外发流程
 * 在 [novex.android.crashguard.CrashShareFlow]；本对象保留历史 API
 * 原名转发——MainActivity / ChatViewModel / SessionListViewModel /
 * MinisApp / TranscriptPager 都以全限定名引用它。
 *
 * 零应用内依赖、入口全兜 Throwable 的契约不变：它必须能活在半初始
 * 化的进程里。
 */
object CrashFrequencyDetector {

    @JvmStatic
    fun isSafeMode(): Boolean = CrashBurstGuard.isSafeMode()

    @JvmStatic
    fun registerSafeModeClearedListener(listener: () -> Unit): () -> Unit =
        CrashBurstGuard.registerSafeModeCleared(listener)

    @JvmStatic
    fun shouldForceHomeOnLaunch(context: Context): Boolean =
        CrashBurstGuard.shouldForceHomeOnLaunch(context)

    fun checkAtLaunch(app: Application) = CrashBurstGuard.scanAtLaunch(app)

    fun maybeShowOnActivity(
        activity: Activity,
        onClosed: (() -> Unit)? = null,
        saveLauncher: ((zip: File, onSaveDone: () -> Unit) -> Unit)? = null,
    ) = CrashShareFlow.offer(activity, onClosed, saveLauncher)
}
