package com.openminis.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P4] MinisApp 骨架重写抽出的纯判定核的行为钉。初始化副作用序本身由
 * NovexStartupCoordinatorTest 钉（minimum 先于 runtime、共享单次执行、
 * 安全模式不启动任一初始化器）；这里钉两个从 Application 生命周期里
 * 提出来的决策函数，防止 T268 / FGS-timeout 语义在后续重构中漂移。
 */
class MinisAppMaintenanceTest {

    private val now = 1_000_000L

    // ─── T268 ghost alarm 重放判定 ─────────────────────────────────────────

    @Test
    fun `a past one-shot alarm is dropped`() {
        assertTrue(
            ghostAlarmAction("alarm", now - 5_000, "ONCE", now) == GhostAlarmAction.SKIP,
        )
    }

    @Test
    fun `a past timer is dropped`() {
        assertTrue(ghostAlarmAction("timer", now - 5_000, "ONCE", now) == GhostAlarmAction.SKIP)
    }

    @Test
    fun `a future timer replays through SET_TIMER`() {
        assertTrue(ghostAlarmAction("timer", now + 60_000, "ONCE", now) == GhostAlarmAction.REPLAY_TIMER)
    }

    @Test
    fun `a future one-shot alarm replays through SET_ALARM`() {
        assertTrue(ghostAlarmAction("alarm", now + 60_000, "ONCE", now) == GhostAlarmAction.REPLAY_ALARM)
    }

    @Test
    fun `a repeating alarm replays even when its trigger point is past`() {
        // 周期闹钟的下一轮还会响——即便触发点已过也要进系统 Clock。
        assertTrue(ghostAlarmAction("alarm", now - 60_000, "WEEKLY", now) == GhostAlarmAction.REPLAY_ALARM)
    }

    @Test
    fun `entries with no trigger timestamp are treated as future`() {
        // triggerAtMs=0：`in 1..now` 不含 0，按未过期处理（与基线一致）。
        assertTrue(ghostAlarmAction("alarm", 0L, "ONCE", now) == GhostAlarmAction.REPLAY_ALARM)
    }

    // ─── FGS 超时崩溃判定 ──────────────────────────────────────────────────

    // 嵌套类的二进制名以 `RemoteServiceException$ForegroundServiceDidNotStopInTimeException`
    // 结尾——精确复刻系统异常的类名尾缀口径。
    private open class RemoteServiceException(message: String?) : RuntimeException(message) {
        class ForegroundServiceDidNotStopInTimeException :
            RemoteServiceException("FGS did not stop within its timeout")
    }

    @Test
    fun `the exact timeout exception class is recognized`() {
        val t = RemoteServiceException.ForegroundServiceDidNotStopInTimeException()
        assertTrue(t.javaClass.name.endsWith("RemoteServiceException\$ForegroundServiceDidNotStopInTimeException"))
        assertTrue(isForegroundServiceTimeout(t))
    }

    @Test
    fun `a differently named exception without markers is not recognized`() {
        assertFalse(isForegroundServiceTimeout(RuntimeException("wrapped")))
    }

    @Test
    fun `message carrying both markers is recognized regardless of class`() {
        val t = RuntimeException(
            "foreground service of type mediaPlayback did not stop within its timeout: " +
                "com.openminis.app.service.AgentForegroundService",
        )
        assertTrue(isForegroundServiceTimeout(t))
    }

    @Test
    fun `only one marker in the message is not enough`() {
        assertFalse(isForegroundServiceTimeout(RuntimeException("foreground service of type mediaPlayback")))
        assertFalse(isForegroundServiceTimeout(RuntimeException("did not stop within its timeout")))
    }

    @Test
    fun `unrelated throwables and null messages are rejected`() {
        assertFalse(isForegroundServiceTimeout(IllegalStateException("boom")))
        assertFalse(isForegroundServiceTimeout(RuntimeException()))
    }
}
