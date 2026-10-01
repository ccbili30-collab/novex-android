package novex.android.navlink

import android.net.Uri
import com.openminis.app.deeplink.DeepLinkAction
import com.openminis.app.deeplink.DeepLinkCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * minis:// 深链解析（P3.5c 真重写的行为验收）：URI 表、settings 路径
 * 别名、快捷动作名、未知路径回落与 logs 页签的挂起语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class LinkParserTest {

    private fun parse(raw: String?) = LinkParser.parse(raw?.let(Uri::parse))

    @Test
    fun `null and non-minis schemes are unknown`() {
        assertEquals(DeepLinkAction.Unknown, parse(null))
        assertEquals(DeepLinkAction.Unknown, parse("https://example.com/session/1"))
        assertEquals(DeepLinkAction.Unknown, parse("minisx://share"))
    }

    @Test
    fun `top level hosts`() {
        assertEquals(DeepLinkAction.OpenShare, parse("minis://share"))
        assertEquals(DeepLinkAction.NewChat, parse("minis://action/new_chat"))
        assertEquals(DeepLinkAction.NewCameraChat, parse("minis://action/camera_chat"))
        assertEquals(DeepLinkAction.Unknown, parse("minis://action/voice_chat")) // 已退役
        assertEquals(DeepLinkAction.Unknown, parse("minis://whatever"))
    }

    @Test
    fun `session links take exactly one segment`() {
        assertEquals(DeepLinkAction.OpenSession("session-42"), parse("minis://session/session-42"))
        assertEquals(DeepLinkAction.Unknown, parse("minis://session"))
        assertEquals(DeepLinkAction.Unknown, parse("minis://session/"))
        // 原固定捷径（多段路径）不再解析。
        assertEquals(DeepLinkAction.Unknown, parse("minis://session/abc/preview/index.html"))
    }

    @Test
    fun `settings home and child pages`() {
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("settings"),
            parse("minis://settings"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("providers"),
            parse("minis://settings/providers"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("provider/inst-9"),
            parse("minis://settings/providers/inst-9"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("model_groups"),
            parse("minis://settings/model-groups"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("model_groups"),
            parse("minis://settings/model_groups"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("model_group/g1"),
            parse("minis://settings/model-groups/g1"),
        )
    }

    @Test
    fun `settings path aliases collapse to one route`() {
        for (path in listOf("usage", "usage-stats", "usage_stats")) {
            assertEquals(
                DeepLinkAction.OpenSettingsScreen("usage_stats"),
                parse("minis://settings/$path"),
            )
        }
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("shared_folders"),
            parse("minis://settings/shared-folders"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("shared_folders"),
            parse("minis://settings/shared_folders"),
        )
    }

    @Test
    fun `unknown settings path lands on settings home`() {
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("settings"),
            parse("minis://settings/open_terminal"),
        )
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("settings"),
            parse("minis://settings/permissions"),
        )
    }

    @Test
    fun `logs tab is parked on the coordinator and consumed once`() {
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("logs"),
            parse("minis://settings/logs?tab=logs"),
        )
        assertEquals("logs", DeepLinkCoordinator.consumePendingLogsTab())
        assertNull(DeepLinkCoordinator.consumePendingLogsTab())

        // 无 tab 参数时挂起 null（屏幕读到 null 即默认页签）。
        parse("minis://settings/logs")
        assertNull(DeepLinkCoordinator.consumePendingLogsTab())
    }

    @Test
    fun `chat input pending is only delivered to its target session`() {
        DeepLinkCoordinator.setPendingChatInput("draft-1", "预填文本")
        assertNull(DeepLinkCoordinator.consumePendingChatInput("draft-2"))
        assertEquals("预填文本", DeepLinkCoordinator.consumePendingChatInput("draft-1"))
        assertNull(DeepLinkCoordinator.consumePendingChatInput("draft-1"))
    }

    @Test
    fun `chat action pending is consumed exactly once`() {
        DeepLinkCoordinator.setPendingChatAction(DeepLinkCoordinator.ChatAction.OPEN_CAMERA)
        assertEquals(
            DeepLinkCoordinator.ChatAction.OPEN_CAMERA,
            DeepLinkCoordinator.consumePendingChatAction(),
        )
        assertNull(DeepLinkCoordinator.consumePendingChatAction())
    }
}
