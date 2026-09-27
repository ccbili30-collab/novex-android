package com.openminis.app.ui.settings

import com.openminis.app.data.NovexBulletinDefaults
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexAnnouncementTest {
    @Test
    fun `bundled defaults stay empty after legacy retirement`() {
        // [T-bulletin-cache] 0.2.x 硬编码归档已退役（用户 2026-09-27 报告
        // "硬编码遗留"）：占位职责由磁盘缓存接管，defaults 必须为空哨兵，
        // 内容回流即红。
        assertTrue(NovexBulletinDefaults.value.announcements.isEmpty())
        assertTrue(NovexBulletinDefaults.value.releaseNotes.isEmpty())
    }

    @Test
    fun `home action opens announcement and announcement can check updates`() {
        val source = File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()

        assertTrue(source.contains("R.drawable.ic_phosphor_bell"))
        assertTrue(source.contains("R.drawable.ic_phosphor_download_simple"))
        assertFalse(source.contains("R.drawable.ic_phosphor_megaphone"))
        assertFalse(source.contains("Icons.Outlined.Campaign"))
        assertFalse(source.contains("Icons.Outlined.FileDownload"))
        assertTrue(source.contains("AnnouncementDialog"))
        assertTrue(source.contains("UpdateChecker.fetchBulletin()"))
        assertTrue(source.contains("onCheckUpdate"))
        assertTrue(source.contains("检查更新"))
        assertTrue(source.contains("往期公告"))
        // [T-announcement-v2] 公告/更新改为弹窗内 tab 切换（旧"版本更新"小节退役）
        assertTrue(source.contains("\"公告\""))
        assertTrue(source.contains("\"更新\""))
        assertTrue(source.contains("暂无更新说明。"))
        assertTrue(source.contains("faceAnnouncements"))
        assertTrue(source.contains("NovexAnnouncementReadStore.markRead"))
        assertTrue(source.contains("MarkdownText("))
        assertTrue(source.contains("ReleaseNotesList"))
        assertTrue(source.contains("包含的往期更新"))
        assertFalse(source.contains("text = update.changelog.ifBlank"))
        assertFalse(source.contains("if (detectedUpdate == null) \"公告\""))
    }

    @Test
    fun `version center renders hero announcements with block body`() {
        val source = File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()
        // [T-announcement-hero] 发布公告上 hero 横幅，正文走块级渲染；页签下划线式；检查更新带刷新图标
        assertTrue(source.contains("AnnouncementHero("))
        assertTrue(source.contains("AnnouncementBody("))
        assertTrue(source.contains("BulletinTab("))
        assertTrue(source.contains("版本中心"))
        assertTrue(source.contains("R.drawable.ic_phosphor_arrow_clockwise"))
        // 渲染层：分节符号按裁决只给亮点类节标题（sparkle/gear），封面走 AsyncImage
        val renderer = File("src/main/java/com/openminis/app/ui/settings/AnnouncementContent.kt").readText()
        assertTrue(renderer.contains("R.drawable.ic_phosphor_sparkle"))
        assertTrue(renderer.contains("R.drawable.ic_phosphor_gear"))
        assertTrue(renderer.contains("AsyncImage("))
        // 解析层：cover 路径护栏在位
        val parser = File("src/main/java/com/openminis/app/data/GiteeAnnouncementSource.kt").readText()
        assertTrue(parser.contains("sanitizeCover"))
    }

    @Test
    fun `dismissed update restores announcement action until user checks again`() {
        assertEquals(
            NovexHomeAction.UPDATE,
            resolveNovexHomeAction(detectedVersion = "0.2.10", dismissedVersion = null),
        )
        assertEquals(
            NovexHomeAction.ANNOUNCEMENT,
            resolveNovexHomeAction(detectedVersion = "0.2.10", dismissedVersion = "0.2.10"),
        )
        assertEquals(
            NovexHomeAction.ANNOUNCEMENT,
            resolveNovexHomeAction(detectedVersion = null, dismissedVersion = null),
        )
    }
}
