package com.openminis.app.ui.settings

import com.openminis.app.data.NovexBulletinDefaults
import java.io.File
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
    fun `bulletin page is the only update surface - no popups remain`() {
        // 用户 2026-09-30 定稿：叠卡跳脸、更新对话框、已读叠卡 dedupe 全部退役；
        // 公告中心是唯一界面，冷启动只点亮红点。任何一层回流即红。
        val source = File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()
        assertTrue(source.contains("BulletinHubPage"))
        assertTrue(source.contains("object NovexUpdateHub"))
        assertTrue(source.contains("NovexBulletinMonitor.manualCheckUpdate"))
        assertTrue(source.contains("ic_phosphor_megaphone"))
        assertFalse(source.contains("BulletinStackFace"))
        assertFalse(source.contains("UpdateDialog("))
        assertFalse(source.contains("NovexUpdateAnnouncementStore"))
        assertFalse(source.contains("AlertDialog"))
        assertFalse(source.contains("AnnouncementDialog"))

        val monitor = File("src/main/java/com/openminis/app/data/NovexBulletinMonitor.kt").readText()
        assertFalse(monitor.contains("BulletinStackCard"))
        assertFalse(monitor.contains("dismissedUpdateVersion"))
        assertFalse(monitor.contains("fun dismissFront"))
        // 红点保留：未读公告或待装更新；公告展开即已读（叠卡已读路径退役后的替代）
        assertTrue(monitor.contains("val hasBadge: Boolean"))
        assertTrue(monitor.contains("NovexAnnouncementReadStore.markRead"))

        val hub = File("src/main/java/com/openminis/app/data/BulletinHub.kt").readText()
        // 名册唯一权威 + rev 修订 + releaseNotes 往期节
        assertTrue(hub.contains("releaseNotes"))
        assertTrue(hub.contains("reconcile"))
        assertTrue(hub.contains("fun fetchBody"))
    }

    @Test
    fun `hub page keeps the single card-group language`() {
        val entry = File("src/main/java/com/openminis/app/ui/bulletin/BulletinUi.kt").readText()
        // 组件来源：全部从 Novex 视觉模块取件；私造件/旧 Minis 层回流即红
        assertTrue(entry.contains("novex.android.ui.PillButton"))
        assertTrue(entry.contains("novex.android.ui.GhostIconButton"))
        assertTrue(entry.contains("novex.android.ui.SegmentedTabs"))
        assertFalse(entry.contains("private fun PillButton"))
        assertFalse(entry.contains("MinisButton"))
        // 更新页与公告页同一种列表语言：两 tab 共用 ExpandableBulletinRow；
        // 高亮卡/分节标题回流即红
        assertTrue(entry.contains("private fun ExpandableBulletinRow"))
        assertTrue(entry.contains("当前版本"))
        assertTrue(entry.contains("手动检查 · 结果就地显示，不弹窗"))
        assertTrue(entry.contains("新版本 "))
        assertFalse(entry.contains("往期更新"))
        // 公告中心=弹窗卡片：全屏页手法回流即红
        assertFalse(entry.contains("BackHandler"))
        assertFalse(entry.contains("statusBarsPadding"))
    }
}
