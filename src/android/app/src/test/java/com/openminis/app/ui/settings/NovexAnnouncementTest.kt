package com.openminis.app.ui.settings

import com.openminis.app.data.NovexBulletinDefaults
import java.io.File
import org.junit.Assert.assertEquals
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
    fun `stack geometry matches the reference photo - straight front tilted back`() {
        // 用户样张（2026-09-28）：前卡端正 0°；后卡微顺时针斜 + 右上错位，
        // 斜角刚好让左下角缩进前卡背后、只露上边和右边。改回歪卡/平移卡即红。
        val ui = File("src/main/java/com/openminis/app/ui/bulletin/BulletinUi.kt").readText()
        assertEquals(1, Regex("""rotate\(4f\)""").findAll(ui).count())
        assertTrue(ui.contains("offset(x = 26.dp, y = (-34).dp)"))
        assertFalse(ui.contains("rotate(-"))
        assertFalse(ui.contains("rotate(9f)"))
        // 公告中心=弹窗卡片（用户 2026-09-28 样张）：全屏页手法回流即红
        assertFalse(ui.contains("BackHandler"))
        assertFalse(ui.contains("statusBarsPadding"))
    }

    @Test
    fun `home entry is the bulletin stack face and hub page replaces dialogs`() {
        // [T-bulletin-v3]（2026-09-28 用户定稿：卡片交叠）入口=叠卡图形；
        // 跳脸=叠卡（公告在上、更新在下，单新单卡）；点开=公告中心全屏页。
        // 旧"铃铛/下载图标二选一 + 弹窗堆"整体退役，回流即红。
        val source = File("src/main/java/com/openminis/app/ui/settings/CheckUpdateSection.kt").readText()
        assertTrue(source.contains("BulletinEntryIcon"))
        assertTrue(source.contains("BulletinStackFace"))
        assertTrue(source.contains("BulletinHubPage"))
        assertTrue(source.contains("NovexBulletinMonitor.dismissFront"))
        val entry = File("src/main/java/com/openminis/app/ui/bulletin/BulletinUi.kt").readText()
        // 入口图标=原铃铛（交叠形态只属于跳脸的两张卡）
        assertTrue(entry.contains("ic_phosphor_bell"))
        assertFalse(source.contains("ic_phosphor_bell"))
        // 组件来源：全部从 Novex 视觉模块取件；私造件/旧 Minis 层回流即红
        assertTrue(entry.contains("novex.android.ui.PillButton"))
        assertTrue(entry.contains("novex.android.ui.GhostIconButton"))
        assertTrue(entry.contains("novex.android.ui.SegmentedTabs"))
        assertFalse(entry.contains("private fun PillButton"))
        assertFalse(entry.contains("MinisButton"))
        assertFalse(source.contains("AnnouncementDialog"))
        assertFalse(source.contains("faceAnnouncements"))
        assertFalse(source.contains("resolveNovexHomeAction"))
        // 更新页与公告页同一种列表语言（用户 2026-09-29 定稿）：两 tab 共用
        // ExpandableBulletinRow 的单一 CardGroup 名册；高亮卡/分节标题回流即红
        assertTrue(entry.contains("private fun ExpandableBulletinRow"))
        assertTrue(entry.contains("当前版本"))
        assertTrue(entry.contains("手动检查 · 结果就地显示，不弹窗"))
        assertTrue(entry.contains("新版本 "))
        assertTrue(entry.contains("去更新"))
        assertFalse(entry.contains("往期更新"))

        val monitor = File("src/main/java/com/openminis/app/data/NovexBulletinMonitor.kt").readText()
        // 公告关闭即已读；更新关闭只停跳脸，红点保留到安装
        assertTrue(monitor.contains("NovexAnnouncementReadStore.markRead"))
        assertTrue(monitor.contains("dismissedUpdateVersion"))
        assertTrue(monitor.contains("val hasBadge: Boolean"))

        val hub = File("src/main/java/com/openminis/app/data/BulletinHub.kt").readText()
        // 名册唯一权威 + rev 修订 + releaseNotes 往期节
        assertTrue(hub.contains("releaseNotes"))
        assertTrue(hub.contains("reconcile"))
        assertTrue(hub.contains("fun fetchBody"))
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)
}
