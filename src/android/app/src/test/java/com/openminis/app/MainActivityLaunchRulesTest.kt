package com.openminis.app

import android.net.Uri
import com.openminis.app.deeplink.DeepLinkAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [P4] MainActivity 冷启动深链裁决的行为钉（T166/T183 语义）：
 *  - 显式深链（通知/快捷方式/浏览器 minis:// 链接）永远赢过保存态恢复；
 *  - 没有显式深链时，恢复进程死亡前所在的会话（合成 OpenSession）；
 *  - 两者皆无才落 Unknown（走正常启动目的地分发）。
 *
 * 解析本身在 novex.android.navlink.LinkParser（P3.5c 已重写收编并自有
 * 测试）；这里钉的是 MainActivity 侧的优先级裁决。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class MainActivityLaunchRulesTest {

    private fun resolve(raw: String?, restored: String?) =
        resolveLaunchDeepLink(raw?.let(Uri::parse), restored)

    @Test
    fun `an explicit session deep link wins over a restored session`() {
        assertEquals(
            DeepLinkAction.OpenSession("session-42"),
            resolve("minis://session/session-42", "older-session"),
        )
    }

    @Test
    fun `a restored session synthesizes an OpenSession deep link`() {
        assertEquals(
            DeepLinkAction.OpenSession("survivor"),
            resolve(null, "survivor"),
        )
    }

    @Test
    fun `nothing to resolve yields Unknown`() {
        assertEquals(DeepLinkAction.Unknown, resolveLaunchDeepLink(null, null))
    }

    @Test
    fun `an unrecognized uri falls back to the restored session`() {
        // parse() 对未知路径返回 Unknown——裁决回落到保存态恢复。
        assertEquals(
            DeepLinkAction.OpenSession("survivor"),
            resolve("minis://views/retired-screen", "survivor"),
        )
    }

    @Test
    fun `an unrecognized uri without a restored session stays Unknown`() {
        assertEquals(DeepLinkAction.Unknown, resolve("minis://whatever", null))
    }

    @Test
    fun `settings deep links survive the resolution`() {
        assertEquals(
            DeepLinkAction.OpenSettingsScreen("providers"),
            resolve("minis://settings/providers", "survivor"),
        )
    }

    @Test
    fun `quick-action deep links survive the resolution`() {
        assertEquals(
            DeepLinkAction.NewChat,
            resolve("minis://action/new_chat", "survivor"),
        )
        assertEquals(
            DeepLinkAction.NewCameraChat,
            resolve("minis://action/camera_chat", null),
        )
    }
}
