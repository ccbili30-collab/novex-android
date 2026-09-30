package novex.android.runtime

import android.app.Application
import android.os.Looper
import com.openminis.app.service.ToolOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/**
 * P3.5b 重写后的悬浮胶囊生命周期守护：
 *  - 无 SYSTEM_ALERT_WINDOW 权限时贴不出窗、已贴的收掉；
 *  - 有权限时 present() 贴窗（主线程队列消化后 attached），可反复
 *    present 原位刷新；
 *  - takeDown() 确定性拆窗；
 *  - 用户划掉路径（onDismissedByUser）回调宿主并拆窗。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class OverlayCapsuleLifecycleTest {

    private lateinit var capsule: OverlayCapsule

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        capsule = OverlayCapsule(app)
    }

    private fun digestMainThreadQueue() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runningSpec() = OverlayCapsule.Spec(
        toolKind = "shell_execute",
        toolHeadline = null,
        statusLine = "Running: shell",
        running = true,
        outcome = ToolOutcome.Unknown,
        replyExcerpt = null,
        targetSessionId = "sess-1",
    )

    private fun doneSpec() = OverlayCapsule.Spec(
        toolKind = null,
        toolHeadline = null,
        statusLine = "Idle",
        running = false,
        outcome = ToolOutcome.Success,
        replyExcerpt = "搞定了",
        targetSessionId = "sess-1",
    )

    @Test fun withoutOverlayPermissionNothingAttaches() {
        ShadowSettings.setCanDrawOverlays(false)
        capsule.present(runningSpec())
        digestMainThreadQueue()
        assertFalse(capsule.attached)
    }

    @Test fun withPermissionPresentAttachesAndRepeatedPresentsRefreshInPlace() {
        ShadowSettings.setCanDrawOverlays(true)
        capsule.present(runningSpec())
        digestMainThreadQueue()
        assertTrue("授权后首次 present 必须贴窗", capsule.attached)

        capsule.present(runningSpec().copy(statusLine = "step 2/3"))
        digestMainThreadQueue()
        assertTrue("重复 present 是原位刷新，不拆不叠", capsule.attached)

        capsule.takeDown()
        digestMainThreadQueue()
        assertFalse("takeDown 必须确定性拆窗", capsule.attached)
    }

    @Test fun losingPermissionTakesTheWindowDown() {
        ShadowSettings.setCanDrawOverlays(true)
        capsule.present(runningSpec())
        digestMainThreadQueue()
        assertTrue(capsule.attached)

        ShadowSettings.setCanDrawOverlays(false)
        capsule.present(runningSpec())
        digestMainThreadQueue()
        assertFalse("无权限的 present 必须顺手收窗", capsule.attached)
    }

    @Test fun userDismissalInvokesHostCallbackAndTearsDown() {
        ShadowSettings.setCanDrawOverlays(true)
        var hostNotified = false
        capsule.onDismissedByUser = { hostNotified = true }
        capsule.present(runningSpec())
        digestMainThreadQueue()
        assertTrue(capsule.attached)

        capsule.present(doneSpec())
        digestMainThreadQueue()
        assertTrue(capsule.attached)

        capsule.onDismissedByUser?.invoke()
        capsule.takeDown()
        digestMainThreadQueue()
        assertTrue("划掉必须通知宿主清停留态", hostNotified)
        assertFalse(capsule.attached)
    }

    @Test fun relayoutWithoutAttachmentIsANoOp() {
        ShadowSettings.setCanDrawOverlays(true)
        capsule.relayoutForNewScreenMetrics()
        digestMainThreadQueue()
        assertFalse(capsule.attached)
    }
}
