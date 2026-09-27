package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-bulletin-cache] 公告缓存守护：落盘/读回往返（含 id 键与更新说明）、
 * 缺失/损坏静默 null、空内容不落盘读不回。
 */
class BulletinCacheTest {

    @get:Rule val temporary = TemporaryFolder()

    @Test fun `save then load round trips announcements and notes`() {
        val dir = temporary.newFolder()
        val bulletin = NovexBulletin(
            announcements = listOf(
                NovexAnnouncement(versionName = "2026-09-27", title = "发布站上线", markdown = "正文", id = "announcements/a.md"),
            ),
            releaseNotes = listOf(
                UpdateChecker.ReleaseNote(versionName = "3.0.5-beta.85", releaseName = "Novex 3.0.5-beta.85", changelog = "说明"),
            ),
            live = true,
        )
        BulletinCache.save(dir, bulletin)
        val loaded = BulletinCache.load(dir)!!
        assertEquals("announcements/a.md", loaded.announcements.single().id)
        assertEquals("发布站上线", loaded.announcements.single().title)
        assertEquals("3.0.5-beta.85", loaded.releaseNotes.single().versionName)
    }

    @Test fun `missing and corrupted cache load null silently`() {
        val dir = temporary.newFolder()
        assertNull(BulletinCache.load(dir))
        val file = File(File(dir, "novex"), "bulletin-cache.json")
        file.parentFile.mkdirs()
        file.writeText("不是 JSON")
        assertNull(BulletinCache.load(dir))
    }

    @Test fun `empty bulletin is not persisted as cache`() {
        val dir = temporary.newFolder()
        BulletinCache.save(dir, NovexBulletin(announcements = emptyList(), releaseNotes = emptyList(), live = true))
        // 空内容落盘后 load 侧 takeIf 拒绝——面板显示空态而非假缓存
        assertNull(BulletinCache.load(dir))
    }
}
