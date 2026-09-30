package novex.android.runtime

import android.app.Application
import com.openminis.app.service.ToolOutcome
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P3.5b 重写后的保活中枢关键行为守护：
 *  - 流式边沿起服务、全空停服务、纯草稿在场不起服务；
 *  - 工具信号三入口的差别（旧式只改文案 / 完整信号 / 收尾快照）；
 *  - 回复摘要截断、划掉后的清零、运行计时锚点的起落；
 *  - 通知栏停止按键扇出到各会话的取消回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class LiveSessionHubServiceSwitchTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        LiveSessionHub.attach(app)
        // 吸干前序状态可能残留的服务命令，让每个用例从干净的命令队列出发。
        while (shadowOf(app).nextStartedService != null) { /* drain */ }
        while (shadowOf(app).nextStoppedService != null) { /* drain */ }
    }

    @Test fun streamingEdgeStartsServiceAndEmptySetStopsIt() {
        LiveSessionHub.markStreaming("s1")
        assertNotNull("首个流式会话必须起前台服务", shadowOf(app).nextStartedService)

        LiveSessionHub.markStreamEnded("s1")
        assertNotNull("流式与在场全空必须停服务", shadowOf(app).nextStoppedService)
    }

    @Test fun plainDraftPresenceNeverStartsTheService() {
        LiveSessionHub.markPresent("__new__draft-9")
        assertNull("纯草稿在场不得起前台服务", shadowOf(app).nextStartedService)
        LiveSessionHub.markAbsent("__new__draft-9")
    }

    @Test fun realPresenceKeepsServiceUntilUserLeaves() {
        LiveSessionHub.markPresent("chat-1")
        assertNotNull("真实在场必须起前台服务", shadowOf(app).nextStartedService)
        LiveSessionHub.markPresent("chat-1") // 幂等
        LiveSessionHub.markAbsent("chat-1")
        assertNotNull("离场后无流式无在场必须停服务", shadowOf(app).nextStoppedService)
    }

    @Test fun legacyStatusTextLeavesToolKindAndRunningBitAlone() {
        LiveSessionHub.pushToolSignal("Running: shell", "shell_execute", true, "装环境")
        LiveSessionHub.pushStatusText("Downloading packages")
        assertEquals("Downloading packages", LiveSessionHub.toolStatus.value)
        assertEquals("shell_execute", LiveSessionHub.toolKind.value)
        assertTrue(LiveSessionHub.toolBusy.value)
        assertEquals("装环境", LiveSessionHub.toolHeadline.value)
    }

    @Test fun startingAToolClearsPreviousOutcomeAndCloseSnapshotsIt() {
        LiveSessionHub.pushToolSignal("done", "shell_execute", false, null)
        LiveSessionHub.pushToolSignal("Running: browser", "browser_use", true, null)
        assertEquals(ToolOutcome.Unknown, LiveSessionHub.lastOutcome.value)

        LiveSessionHub.closeToolRun(ToolOutcome.Success)
        assertFalse(LiveSessionHub.toolBusy.value)
        assertNull(LiveSessionHub.toolKind.value)
        assertEquals(ToolOutcome.Success, LiveSessionHub.lastOutcome.value)
        assertEquals("browser_use", LiveSessionHub.lastToolKind.value)
        assertEquals("Running: browser", LiveSessionHub.lastToolStatus.value)
    }

    @Test fun idleStatusIsNotKeptAsCompletionSnapshot() {
        LiveSessionHub.pushToolSignal("Idle", "shell_execute", true, null)
        LiveSessionHub.closeToolRun(ToolOutcome.Error)
        assertNull("Idle 占位文案不得进收尾快照", LiveSessionHub.lastToolStatus.value)
    }

    @Test fun replyDigestIsSingleLinedAndCapped() {
        LiveSessionHub.publishReply("s1", "第一行\n\n第二行  \n第三行")
        assertEquals("第一行 第二行 第三行", LiveSessionHub.replyExcerpt.value)
        assertEquals("s1", LiveSessionHub.currentSessionId.value)

        val long = "x".repeat(100)
        LiveSessionHub.publishReply("s1", long)
        val digest = LiveSessionHub.replyExcerpt.value!!
        assertEquals(73, digest.length) // 72 字 + 省略号
        assertTrue(digest.endsWith("…"))

        LiveSessionHub.publishReply("s1", "   ") // 空白不得覆盖已有摘要
        assertEquals(digest, LiveSessionHub.replyExcerpt.value)
    }

    @Test fun forgettingTheDigestClearsCompletionStateButNotStreams() {
        LiveSessionHub.markStreaming("s9")
        LiveSessionHub.pushToolSignal("done", "shell_execute", false, null)
        LiveSessionHub.closeToolRun(ToolOutcome.Timeout)
        LiveSessionHub.forgetOverlayDigest()
        assertEquals(ToolOutcome.Unknown, LiveSessionHub.lastOutcome.value)
        assertNull(LiveSessionHub.lastToolKind.value)
        assertNull(LiveSessionHub.replyExcerpt.value)
        assertTrue("划掉胶囊不得影响流式本身", LiveSessionHub.isStreaming("s9"))
        LiveSessionHub.markStreamEnded("s9")
    }

    @Test fun runClockAnchorsRiseAndFallWithTheStreamingSet() {
        LiveSessionHub.markStreaming("r1")
        assertNotNull(LiveSessionHub.currentRunStartedAtMs.value)
        assertNull(LiveSessionHub.lastRunFinishedAtMs.value)

        LiveSessionHub.markStreamEnded("r1")
        assertNotNull("末位会话结束时落下完成时间戳", LiveSessionHub.lastRunFinishedAtMs.value)

        LiveSessionHub.markStreaming("r2")
        assertNull("新一轮必须作废上一轮的完成戳", LiveSessionHub.lastRunFinishedAtMs.value)
        LiveSessionHub.markStreamEnded("r2")
    }

    @Test fun stopActionFansOutToEveryRegisteredCanceller() {
        var cancelledA = false
        var cancelledB = false
        LiveSessionHub.markStreaming("a") { cancelledA = true }
        LiveSessionHub.markStreaming("b") { cancelledB = true }
        LiveSessionHub.cancelEveryStream()
        assertTrue(cancelledA)
        assertTrue(cancelledB)
        LiveSessionHub.markStreamEnded("a")
        LiveSessionHub.markStreamEnded("b")
    }

    @Test fun completionCallbackFiresOnlyForGenuinelyActiveSessions() {
        val endings = mutableListOf<Pair<String, Boolean>>()
        LiveSessionHub.setCompletionListener { id, failed -> endings += id to failed }
        LiveSessionHub.markStreaming("e1")
        LiveSessionHub.flagStreamFailure("e1")
        LiveSessionHub.markStreamEnded("e1")
        LiveSessionHub.markStreamEnded("never-started")
        assertEquals(listOf("e1" to true), endings)
        LiveSessionHub.setCompletionListener(null)
    }
}
