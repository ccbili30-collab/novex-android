package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-announcement-system] Gitee 道公告源守护：索引解析（路径校验/标题
 * 必填/上限截断/非法整体 null）与 raw URL 拼接。网络层由 okhttp 默认
 * 行为覆盖，不在此测。
 */
class GiteeAnnouncementSourceTest {

    private fun index(vararg items: String) = """{"announcements":[${
        items.joinToString(",")
    }]}"""

    @Test fun `parses entries in listed order`() {
        val body = index(
            """{"file":"announcements/2026-09-28-b.md","title":"第二条","date":"2026-09-28"}""",
            """{"file":"announcements/2026-09-27-a.md","title":"发布站上线","date":"2026-09-27"}""",
        )
        val parsed = GiteeAnnouncementSource.parseAnnouncementsIndex(body)!!
        assertEquals(2, parsed.size)
        assertEquals("发布站上线", parsed[1].title)
        assertEquals("announcements/2026-09-27-a.md", parsed[1].file)
    }

    @Test fun `rejects paths outside announcements dir and non markdown`() {
        val body = index(
            """{"file":"update.json","title":"越权路径","date":"d"}""",
            """{"file":"announcements/../../secret.md","title":"目录穿越","date":"d"}""",
            """{"file":"announcements/note.txt","title":"非 md","date":"d"}""",
            """{"file":"announcements/ok.md","title":"合法","date":"d"}""",
        )
        val parsed = GiteeAnnouncementSource.parseAnnouncementsIndex(body)!!
        assertEquals(listOf("announcements/ok.md"), parsed.map { it.file })
    }

    @Test fun `skips blank titles and caps at five entries`() {
        val eight = (1..8).joinToString(",") {
            """{"file":"announcements/$it.md","title":"$it","date":"d$it"}"""
        }
        val blankTitle = """{"file":"announcements/x.md","title":"","date":"d"}"""
        val body = """{"announcements":[$eight,$blankTitle]}"""
        val parsed = GiteeAnnouncementSource.parseAnnouncementsIndex(body)!!
        assertEquals(GiteeAnnouncementSource.MAX_ENTRIES, parsed.size)
        assertEquals("1", parsed.first().title) // 索引顺序即优先级，截尾不掐头
    }

    @Test fun `malformed bodies return null not crash`() {
        assertNull(GiteeAnnouncementSource.parseAnnouncementsIndex("不是 JSON"))
        assertNull(GiteeAnnouncementSource.parseAnnouncementsIndex("""{"announcements":"x"}"""))
        assertNull(GiteeAnnouncementSource.parseAnnouncementsIndex("""{"announcements":[]}"""))
    }

    @Test fun `raw url joins base and file for both hosts`() {
        assertEquals(
            "https://gitee.com/ccbili/novex/raw/main/announcements/a.md",
            GiteeAnnouncementSource.rawUrl(GiteeAnnouncementSource.GITEE_RAW_BASE, "announcements/a.md"),
        )
        assertEquals(
            "https://raw.githubusercontent.com/ccbili30-collab/novex/main/announcements/a.md",
            GiteeAnnouncementSource.rawUrl(GiteeAnnouncementSource.GITHUB_MIRROR_RAW_BASE, "announcements/a.md"),
        )
    }

    @Test fun `channel field parses and invalid values fall back to common`() {
        val body = """{"announcements":[
            {"file":"announcements/a.md","title":"通用","date":"d"},
            {"file":"announcements/b.md","title":"预览限定","date":"d","channel":"preview"},
            {"file":"announcements/c.md","title":"稳定限定","date":"d","channel":"stable"},
            {"file":"announcements/e.md","title":"非法通道当通用","date":"d","channel":"beta"}
        ]}"""
        val parsed = GiteeAnnouncementSource.parseAnnouncementsIndex(body)!!
        assertEquals(4, parsed.size)
        assertEquals(null, parsed[0].channel)
        assertEquals("preview", parsed[1].channel)
        assertEquals("stable", parsed[2].channel)
        assertEquals(null, parsed[3].channel)
    }

    @Test fun `common announcements reach both channels while lane ones stay in lane`() {
        fun entry(file: String, channel: String?) =
            GiteeAnnouncementSource.AnnouncementEntry(file, "t", "d", channel)
        val entries = listOf(
            entry("announcements/common.md", null),
            entry("announcements/p.md", "preview"),
            entry("announcements/s.md", "stable"),
        )
        assertEquals(
            listOf("announcements/common.md", "announcements/p.md"),
            GiteeAnnouncementSource.filterForChannel(entries, UpdateChannel.PREVIEW).map { it.file },
        )
        assertEquals(
            listOf("announcements/common.md", "announcements/s.md"),
            GiteeAnnouncementSource.filterForChannel(entries, UpdateChannel.STABLE).map { it.file },
        )
    }

    @Test fun `unread filter keeps order and keys on announcement id`() {
        fun ann(id: String) = NovexAnnouncement(versionName = "d", title = id, markdown = "m", id = id)
        val list = listOf(ann("a.md"), ann("b.md"), ann("c.md"))
        val unread = NovexAnnouncementReadStore.unread(list, readIds = setOf("b.md"))
        assertEquals(listOf("a.md", "c.md"), unread.map { it.id })
        // 全已读 → 空（跳脸不发生）
        assertEquals(emptyList<NovexAnnouncement>(), NovexAnnouncementReadStore.unread(list, setOf("a.md", "b.md", "c.md")))
    }
}
