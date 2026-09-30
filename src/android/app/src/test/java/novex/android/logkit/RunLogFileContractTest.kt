package novex.android.logkit

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P3.5b 重写后的日志文件契约守护（日志管理屏读路径）：
 *  - 列表按前缀过滤、名字新→旧排序、条数上限；
 *  - 读取与总量统计；
 *  - 清空后列表与总量归零（写出器句柄一并重置，不留僵尸写入）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class RunLogFileContractTest {

    private lateinit var app: Application
    private lateinit var logsDir: File

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        RunLog.prime(app)
        RunLog.boot(app)
        logsDir = File(app.filesDir, "logs").apply { mkdirs() }
    }

    private fun plant(name: String, text: String = "line"): File =
        File(logsDir, name).apply { writeText(text) }

    @Test fun listingFiltersByPrefixSortsNewestFirstAndCapsCount() {
        plant("minis-2026-09-28.log")
        plant("minis-2026-09-30.log")
        plant("minis-2026-09-29.log")
        plant("crash-2026-09-30_01-02-03.log")
        plant("native-crash-2026-09-30_03-04-05.log")
        plant("notes.txt") // 非 .log 不得出现

        val daily = RunLog.listFiles("minis-", limit = 2)
        assertEquals(listOf("minis-2026-09-30.log", "minis-2026-09-29.log"), daily.map { it.name })

        val crashes = RunLog.listFiles("crash-", limit = 100)
        assertEquals(listOf("crash-2026-09-30_01-02-03.log"), crashes.map { it.name })

        val native = RunLog.listFiles("native-crash-", limit = 100)
        assertEquals(listOf("native-crash-2026-09-30_03-04-05.log"), native.map { it.name })
    }

    @Test fun factsCarrySizeAndMtimeAndTotalSumsThem() {
        plant("minis-2026-09-30.log", "12345")
        val facts = RunLog.listFiles("minis-", limit = 10)
        assertEquals(1, facts.size)
        assertEquals(5L, facts[0].bytes)
        assertTrue(facts[0].modifiedAt > 0)
        assertTrue(RunLog.bytesTotal() >= 5L)
    }

    @Test fun readWholeFileReturnsContentOrNullForMissing() {
        plant("minis-2026-09-30.log", "hello")
        assertEquals("hello", RunLog.readWholeFile("minis-2026-09-30.log"))
        assertNull(RunLog.readWholeFile("minis-1999-01-01.log"))
    }

    @Test fun wipeClearsEverything() {
        plant("minis-2026-09-30.log")
        plant("crash-2026-09-30_01-02-03.log")
        RunLog.wipeFiles()
        assertTrue(RunLog.listFiles().isEmpty())
        assertEquals(0L, RunLog.bytesTotal())
    }
}
