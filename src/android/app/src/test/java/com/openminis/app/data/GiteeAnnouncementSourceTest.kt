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
        val items = (1..8).joinToString(",") {
            """{"file":"announcements/$it.md","title":"$it","date":"d$it"}"""
        } + ""","""{"file":"announcements/x.md","title":"","date":"d"}"""
        val parsed = GiteeAnnouncementSource.parseAnnouncementsIndex("""{"announcements":[$items]}""")!!
        assertEquals(GiteeAnnouncementSource.MAX_ENTRIES, parsed.size)
        assertEquals("1", parsed.first().title) // 索引顺序即优先级，截尾不掐头
    }

    @Test fun `malformed bodies return null not crash`() {
        assertNull(GiteeAnnouncementSource.parseAnnouncementsIndex("不是 JSON"))
        assertNull(GiteeAnnouncementSource.parseAnnouncementsIndex("""{"announcements":"x"}"""))
        assertNull(GiteeAnnouncementSource.parseAnnouncementsIndex("""{"announcements":[]}"""))
    }

    @Test fun `raw url joins base and file`() {
        assertEquals(
            "https://gitee.com/ccbili/novex/raw/main/announcements/a.md",
            GiteeAnnouncementSource.rawUrl("announcements/a.md"),
        )
    }
}
