package com.openminis.app.data

internal data class NovexAnnouncement(
    val versionName: String,
    val title: String,
    val markdown: String,
    /** [T-announcement-v2] 已读键：hub 道用索引文件名，GitHub 道默认=versionName。 */
    val id: String = versionName,
    /**
     * [T-announcement-hero] 索引可选元数据：[version] 有值=发布公告，
     * 面板渲染 hero 横幅；[badge] 徽标胶囊；[tagline] 一句话标语；
     * [coverUrl] 横幅图完整 URL（source 层按当前道拼好）。全可空=
     * 通用公告维持简排版，旧条目/旧缓存不受影响。
     */
    val version: String? = null,
    val badge: String? = null,
    val tagline: String? = null,
    val coverUrl: String? = null,
    /** hero 副行「日期 · 渠道」用；通用公告为 null。 */
    val channel: String? = null,
)

internal data class NovexBulletin(
    val announcements: List<NovexAnnouncement>,
    val releaseNotes: List<UpdateChecker.ReleaseNote>,
    /** [T-announcement-v2] 真=来自网络活源；内置归档回落为 false——跳脸只认活源新公告。 */
    val live: Boolean = false,
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
    // [T-bulletin-cache]（用户 2026-09-27 报告"硬编码遗留"）旧 MinisApp 时代
    // 内置归档（0.2.x 公告/致哀/更新说明）退役：占位职责由磁盘缓存接管，
    // 无缓存时展示空态。此对象保留为"失败回落"的空哨兵，内容必须为空
    // ——NovexAnnouncementTest 守护不得回流。
    val value: NovexBulletin = NovexBulletin(announcements = emptyList(), releaseNotes = emptyList())
}
