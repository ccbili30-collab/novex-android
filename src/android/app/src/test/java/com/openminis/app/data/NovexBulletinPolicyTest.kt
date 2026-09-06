package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexBulletinPolicyTest {
    @Test
    fun `latest announcement is first and prior announcements remain archived`() {
        val bulletin = NovexBulletinPolicy.build(
            channel = UpdateChannel.STABLE,
            releases = sampleReleases(),
        )

        assertEquals(listOf("0.2.14", "0.2.9"), bulletin.announcements.map { it.versionName })
        assertEquals("公告", bulletin.announcements.first().title)
        assertTrue(bulletin.announcements.first().markdown.contains("迎来 AGI 时代"))
        assertEquals("特别致哀", bulletin.announcements.last().title)
        assertTrue(bulletin.announcements.last().markdown.contains("愿山河无恙"))
    }

    @Test
    fun `version history keeps technical notes without repeating announcement bodies`() {
        val bulletin = NovexBulletinPolicy.build(
            channel = UpdateChannel.STABLE,
            releases = sampleReleases(),
        )

        assertEquals(listOf("0.2.14", "0.2.13", "0.2.9"), bulletin.releaseNotes.map { it.versionName })
        assertTrue(bulletin.releaseNotes.first().changelog.contains("修复发送消息后画面错误跳转"))
        assertFalse(bulletin.releaseNotes.first().changelog.contains("迎来 AGI 时代"))
        assertTrue(bulletin.releaseNotes.last().changelog.contains("首页入口调整"))
        assertFalse(bulletin.releaseNotes.last().changelog.contains("特别致哀"))
    }

    @Test
    fun `github release json feeds the same bulletin policy`() {
        val releases = UpdateChecker.parsePublishedReleases(
            """
                [{
                  "tag_name":"v0.2.14",
                  "name":"Novex 0.2.14",
                  "body":"## 公告\n\n新的公告\n\n## 修复\n\n- 修复一项问题。",
                  "draft":false,
                  "prerelease":false,
                  "assets":[]
                }]
            """.trimIndent(),
        )

        val bulletin = NovexBulletinPolicy.build(UpdateChannel.STABLE, releases)

        assertEquals("新的公告", bulletin.announcements.single().markdown)
        assertEquals("## 修复\n\n- 修复一项问题。", bulletin.releaseNotes.single().changelog)
    }

    private fun sampleReleases(): List<PublishedUpdate> = listOf(
        release(
            version = "0.2.13",
            body = "## 压缩与对话控制\n\n- 修复压缩显示。",
        ),
        release(
            version = "0.2.9",
            body = """
                **特别致哀**

                今年以来，灾害侵袭祖国多地。

                **愿山河无恙，愿人间皆安**

                ---

                - 首页入口调整。
            """.trimIndent(),
        ),
        release(
            version = "0.2.14",
            body = """
                ## 公告

                2026 年 9 月 4 日，让我们恭喜全人类迎来 AGI 时代！

                ## 对话定位修复

                - 修复发送消息后画面错误跳转。
            """.trimIndent(),
        ),
    )

    private fun release(version: String, body: String) = PublishedUpdate(
        tagName = "v$version",
        versionName = version,
        releaseName = "Novex $version",
        changelog = body,
        isPrerelease = false,
        assets = emptyMap(),
    )
}
