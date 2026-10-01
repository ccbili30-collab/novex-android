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
    fun `about row opens the bulletin updates tab instead of a dialog`() {
        // 用户 2026-09-30：关于页「检查更新」不再弹 AlertDialog，直接开公告
        // 中心「更新」页签就地检查；叠卡跳脸同步退役，回流即红。
        assertTrue(source.contains("NovexUpdateHub.open(tab = 1)"))
        assertTrue(source.contains("NovexUpdateHub.pendingTab"))
        // 入口是 megaphone 公告图标；铃铛入口随底栏「消息」退役。
        assertTrue(source.contains("ic_phosphor_megaphone"))
        assertFalse(source.contains("BulletinEntryIcon("))
        assertFalse(source.contains("ic_phosphor_bell"))
        // 旧入口形态整体退役
        assertFalse(source.contains("LaunchedEffect(detectedUpdate?.versionName"))
        assertFalse(source.contains("openHomeAction"))
        assertFalse(source.contains("fun NovexUpdateAction"))
    }

    @Test
    fun `hub singleton is shared between entry host and about row`() {
        // hub 是单例：我的页入口、根宿主、关于页检查更新开的是同一个页面，
        // 各自 remember 持有者回流即红。
        assertTrue(source.contains("object NovexUpdateHub"))
        assertFalse(source.contains("remember { NovexUpdateHub()"))
    }
}
