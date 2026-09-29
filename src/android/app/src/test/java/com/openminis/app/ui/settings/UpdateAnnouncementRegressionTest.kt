package com.openminis.app.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAnnouncementRegressionTest {
    private val source by lazy {
        File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()
    }

    @Test
    fun `update reaches users through the stack card not auto dialog`() {
        // [T-bulletin-v3] 更新走叠卡（公告在上、更新在下）；"检测到更新自动
        // 弹一次窗"与"铃铛/下载图标二选一"整体退役，回流即红。
        assertTrue(source.contains("BulletinStackFace"))
        assertTrue(source.contains("onUpdateAction"))
        assertTrue(source.contains("NovexUpdateAnnouncementStore"))
        // 入口是 megaphone 公告图标；BulletinEntryIcon 的铃铛入口随底栏「消息」退役。
        assertTrue(source.contains("ic_phosphor_megaphone"))
        assertFalse(source.contains("LaunchedEffect(detectedUpdate?.versionName"))
        assertFalse(source.contains("openHomeAction"))
        assertFalse(source.contains("BulletinEntryIcon("))
    }
}
