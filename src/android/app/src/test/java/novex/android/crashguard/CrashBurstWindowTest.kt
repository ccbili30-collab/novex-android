package novex.android.crashguard

import android.app.Application
import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3.5b 重写后的崩溃风暴窗口守护（阈值 2 / 1 小时窗 / 24h 硬抑制 /
 * 强制回落首页宽限 / 安全模式边沿回调）。文件系统走真实 host fs，
 * mtime 用 setLastModified 精确摆位。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class CrashBurstWindowTest {

    private lateinit var app: Application
    private lateinit var logsDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        logsDir = File(app.filesDir, "logs").apply { mkdirs() }
        // 复位单例状态（安全模式位是进程级）。
        CrashBurstGuard.finishSafeMode()
    }

    private fun plantCrashLog(name: String, ageMinutesAgo: Long): File {
        val f = File(logsDir, name)
        f.writeText("stack")
        val mtime = System.currentTimeMillis() - ageMinutesAgo * 60_000L
        assertTrue(f.setLastModified(mtime))
        return f
    }

    private fun writePref(key: String, value: Long) {
        app.getSharedPreferences("crash_freq_prefs", Context.MODE_PRIVATE)
            .edit().putLong(key, value).apply()
    }

    @Test fun twoFreshCrashesTripSafeModeAndForceHomeGrace() {
        plantCrashLog("crash-2026-09-30_10-00-00.log", 5)
        plantCrashLog("native-crash-2026-09-30_10-05-00.log", 3)

        CrashBurstGuard.scanAtLaunch(app)

        assertTrue("达到阈值必须进安全模式", CrashBurstGuard.isSafeMode())
        assertTrue("风暴后一小时内冷启动必须落首页", CrashBurstGuard.shouldForceHomeOnLaunch(app))
        val pending = CrashBurstGuard.pendingBurstFiles
        assertNotNull(pending)
        assertEquals(2, pending!!.size)
    }

    @Test fun singleCrashOrStaleCrashDoNotTrip() {
        plantCrashLog("crash-a.log", 5)
        CrashBurstGuard.scanAtLaunch(app)
        assertFalse(CrashBurstGuard.isSafeMode())
        assertNull(CrashBurstGuard.pendingBurstFiles)

        plantCrashLog("crash-old.log", 90) // 1.5h 前的旧崩溃，出窗
        plantCrashLog("crash-b.log", 90)
        CrashBurstGuard.scanAtLaunch(app)
        assertFalse("出窗的旧文件不计入风暴", CrashBurstGuard.isSafeMode())
    }

    @Test fun unrelatedFilesAreIgnored() {
        plantCrashLog("minis-2026-09-30.log", 2) // 日志不是崩溃报告
        plantCrashLog("crash-only.log", 2)
        CrashBurstGuard.scanAtLaunch(app)
        assertFalse(CrashBurstGuard.isSafeMode())
    }

    @Test fun recentDismissCheckpointFiltersFilesOlderThanIt() {
        plantCrashLog("crash-first.log", 30)
        plantCrashLog("crash-second.log", 50)
        writePref("dismissed_at", System.currentTimeMillis() - 20 * 60_000L) // 20 分钟前看过

        CrashBurstGuard.scanAtLaunch(app)
        assertFalse("用户已看过的那批不算新风暴", CrashBurstGuard.isSafeMode())
    }

    @Test fun agedOutDismissCheckpointStopsFiltering() {
        plantCrashLog("crash-first.log", 30)
        plantCrashLog("crash-second.log", 50)
        writePref("dismissed_at", System.currentTimeMillis() - 2 * 60 * 60_000L) // 2h 前，已出窗

        CrashBurstGuard.scanAtLaunch(app)
        assertTrue("界标自身过窗后，活窗内的文件按新崩溃论", CrashBurstGuard.isSafeMode())
    }

    @Test fun suppressWindowBeatsFreshCrashes() {
        writePref("suppress_until", System.currentTimeMillis() + 60 * 60_000L)
        plantCrashLog("crash-x.log", 1)
        plantCrashLog("crash-y.log", 1)

        CrashBurstGuard.scanAtLaunch(app)
        assertFalse("24h 硬抑制期内不弹", CrashBurstGuard.isSafeMode())
        assertNull(CrashBurstGuard.pendingBurstFiles)
    }

    @Test fun safeModeClearedEdgeFiresListenersExactlyOnce() {
        var fired = 0
        val unsubscribe = CrashBurstGuard.registerSafeModeClearedListener { fired++ }
        plantCrashLog("crash-p.log", 4)
        plantCrashLog("crash-q.log", 2)
        CrashBurstGuard.scanAtLaunch(app)
        assertTrue(CrashBurstGuard.isSafeMode())

        CrashBurstGuard.finishSafeMode()
        assertFalse(CrashBurstGuard.isSafeMode())
        assertEquals("ON→OFF 边沿恰好回调一次", 1, fired)

        CrashBurstGuard.finishSafeMode() // 已经是 OFF，再收尾不得重复回调
        assertEquals(1, fired)
        unsubscribe()
    }

    @Test fun forceHomeGraceIsSelfExpiring() {
        writePref("force_home_until", System.currentTimeMillis() - 1000L)
        assertFalse("宽限过期后回落用户偏好", CrashBurstGuard.shouldForceHomeOnLaunch(app))
    }
}
