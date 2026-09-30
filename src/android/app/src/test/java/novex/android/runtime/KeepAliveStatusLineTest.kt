package novex.android.runtime

import android.app.Application
import android.app.NotificationManager
import com.openminis.app.R
import org.junit.Assert.assertEquals
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
 * P3.5b 重写后的保活通知装配守护：渠道齐备、运行态标题/停止键/进度
 * 条、完成态的静止文案（换标题、撤停止键）、计时锚点兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class KeepAliveStatusLineTest {

    private lateinit var app: Application
    private lateinit var statusLine: KeepAliveStatusLine

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        LiveSessionHub.attach(app)
        statusLine = KeepAliveStatusLine(app)
        statusLine.serviceBootAnchorMs = 0L
        statusLine.ensureChannels()
    }

    @Test fun bothChannelsExistWithFrozenIds() {
        val manager = app.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel("agent_status"))
        assertNotNull(manager.getNotificationChannel("overlay_permission_nudge"))
    }

    @Test fun runningToolGivesToolTitleStopActionAndIndefiniteProgress() {
        LiveSessionHub.markStreaming("k1")
        LiveSessionHub.pushToolSignal("Running: shell", "shell_execute", true, null)
        val notification = statusLine.build(1, "Running: shell")
        LiveSessionHub.markStreamEnded("k1")

        assertEquals("Minis is using Shell", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        assertEquals(
            app.getString(R.string.bg_service_stop_action),
            notification.actions?.firstOrNull()?.title,
        )
        assertTrue(
            "工具在跑必须是不定进度",
            notification.extras.getBoolean("android.progressIndeterminate"),
        )
    }

    @Test fun idleBetweenToolsKeepsGenericTitle() {
        LiveSessionHub.markStreaming("k2")
        LiveSessionHub.pushToolSignal("Idle", null, false, null)
        val notification = statusLine.build(1, "Idle")
        LiveSessionHub.markStreamEnded("k2")
        assertEquals(
            app.getString(R.string.bg_service_notification_title),
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE),
        )
        assertEquals(
            "工具不在跑不得画进度",
            false,
            notification.extras.getBoolean("android.progressIndeterminate"),
        )
    }

    @Test fun settledRunSwapsToCompletedCopyAndDropsStopAction() {
        LiveSessionHub.markStreaming("k3")
        LiveSessionHub.pushToolSignal("done", "web_search", false, null)
        LiveSessionHub.closeToolRun(com.openminis.app.service.ToolOutcome.Success)
        LiveSessionHub.markStreamEnded("k3")

        val notification = statusLine.build(0, "Idle")
        assertEquals(
            app.getString(R.string.bg_service_notification_title_completed),
            notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE),
        )
        assertNull("跑完了还挂停止键只会诱导无效点击", notification.actions)
    }

    @Test fun bootstrapRowIsOngoingWithAppTitle() {
        val bootstrap = statusLine.bootstrapNotification()
        assertEquals(
            app.getString(R.string.app_name),
            bootstrap.extras.getCharSequence(android.app.Notification.EXTRA_TITLE),
        )
        assertTrue(
            "占位通知必须是 ongoing",
            (bootstrap.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0,
        )
    }
}
