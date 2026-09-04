package com.openminis.app.data

internal data class NovexAnnouncement(
    val versionName: String,
    val title: String,
    val markdown: String,
)

internal data class NovexBulletin(
    val announcements: List<NovexAnnouncement>,
    val releaseNotes: List<UpdateChecker.ReleaseNote>,
)

/** Splits release bodies into durable announcements and ordinary version notes. */
internal object NovexBulletinPolicy {
    private val announcementHeading = Regex("(?m)^##[ \\t]+公告[ \\t]*$")
    private val nextHeading = Regex("(?m)^##[ \\t]+.+$")
    private val legacyTitle = Regex("(?m)^\\*\\*特别致哀\\*\\*[ \\t]*$")
    private val divider = Regex("(?m)^---[ \\t]*$")

    fun build(
        channel: UpdateChannel,
        releases: List<PublishedUpdate>,
    ): NovexBulletin {
        val eligible = UpdateReleasePolicy.eligibleReleases(channel, releases)
            .sortedWith { left, right ->
                UpdateReleasePolicy.compareVersions(right.versionName, left.versionName)
            }

        val announcements = eligible.mapNotNull(::splitRelease)
            .mapNotNull { split -> split.announcement }
            .distinctBy { "${it.title}\n${it.markdown}" }

        val releaseNotes = eligible.map { release ->
            val technicalNotes = splitRelease(release).technicalNotes
                .substringBefore("\n## 包含的往期更新")
                .trim()
                .ifBlank { "该版本未提供更新说明。" }
            UpdateChecker.ReleaseNote(
                versionName = release.versionName,
                releaseName = release.releaseName,
                changelog = technicalNotes,
            )
        }

        return NovexBulletin(
            announcements = announcements,
            releaseNotes = releaseNotes,
        )
    }

    private fun splitRelease(release: PublishedUpdate): SplitRelease {
        val body = release.changelog.replace("\r\n", "\n").trim()
        val modern = announcementHeading.find(body)
        if (modern != null) {
            val contentStart = modern.range.last + 1
            val followingHeading = nextHeading.find(body, contentStart)
            val contentEnd = followingHeading?.range?.first ?: body.length
            val announcementMarkdown = body.substring(contentStart, contentEnd).trim()
            val technicalNotes = buildString {
                append(body.substring(0, modern.range.first).trim())
                if (followingHeading != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(body.substring(followingHeading.range.first).trim())
                }
            }
            return SplitRelease(
                announcement = announcementMarkdown.takeIf { it.isNotBlank() }?.let {
                    NovexAnnouncement(
                        versionName = release.versionName,
                        title = "公告",
                        markdown = it,
                    )
                },
                technicalNotes = technicalNotes,
            )
        }

        val legacy = legacyTitle.find(body)
        if (legacy != null) {
            val contentStart = legacy.range.last + 1
            val separator = divider.find(body, contentStart)
            val contentEnd = separator?.range?.first ?: body.length
            val announcementMarkdown = body.substring(contentStart, contentEnd).trim()
            val technicalNotes = separator
                ?.let { body.substring(it.range.last + 1).trim() }
                .orEmpty()
            return SplitRelease(
                announcement = announcementMarkdown.takeIf { it.isNotBlank() }?.let {
                    NovexAnnouncement(
                        versionName = release.versionName,
                        title = "特别致哀",
                        markdown = it,
                    )
                },
                technicalNotes = technicalNotes,
            )
        }

        return SplitRelease(announcement = null, technicalNotes = body)
    }

    private data class SplitRelease(
        val announcement: NovexAnnouncement?,
        val technicalNotes: String,
    )
}

/** Immediate offline content while the official release list is loading or unavailable. */
internal object NovexBulletinDefaults {
    val value: NovexBulletin = NovexBulletinPolicy.build(
        channel = UpdateChannel.STABLE,
        releases = listOf(
            fallbackRelease(
                version = "0.2.14",
                body = """
                    ## 公告

                    2026 年 9 月 4 日，让我们恭喜全人类迎来 AGI 时代！

                    ## 对话定位修复

                    - 修复发送消息后画面错误跳到上一轮对话的问题。
                    - 新消息会按本轮消息准确定位，流式回复不再重复推动页面。
                """.trimIndent(),
            ),
            fallbackRelease(
                version = "0.2.9",
                body = """
                    **特别致哀**

                    今年以来，台风、暴雨、洪涝与地质灾害侵袭祖国多地。每一则伤亡消息背后，都是一个家庭难以承受的离别。

                    在西藏吉隆泥石流灾害发生之际，我们也一并向今年所有灾害中的遇难者致以沉痛哀悼，向遇难者家属和受灾群众致以深切慰问，向所有奋战在抢险救援一线的人们致以崇高敬意。

                    愿逝者安息，愿伤者康复，愿失联者早日归来，愿所有受灾群众平安渡过难关，重建家园。

                    **愿山河无恙，愿人间皆安**

                    ---

                    - 优化首页公告与稳定版更新入口。
                """.trimIndent(),
            ),
        ),
    )

    private fun fallbackRelease(version: String, body: String) = PublishedUpdate(
        tagName = "v$version",
        versionName = version,
        releaseName = "Novex $version",
        changelog = body,
        isPrerelease = false,
        assets = emptyMap(),
    )
}
