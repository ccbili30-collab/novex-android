package com.openminis.app.data

/** The release lane baked into an APK at build time. */
enum class UpdateChannel(
    val wireName: String,
    val assetName: String,
) {
    STABLE("stable", "novex.apk"),
    // The custom extension deliberately keeps legacy stable clients from
    // treating preview-only GitHub releases as installable APK updates. The
    // payload is still a signed APK and is staged with an .apk name on-device.
    PREVIEW("preview", "novex-preview.novex"),
    ;

    companion object {
        fun fromWireName(value: String): UpdateChannel =
            entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) } ?: STABLE
    }
}

internal data class PublishedUpdate(
    val tagName: String,
    val versionName: String,
    val releaseName: String,
    val changelog: String,
    val isPrerelease: Boolean,
    val assets: Map<String, PublishedAsset>,
)

internal data class PublishedAsset(
    val url: String,
    val sizeBytes: Long,
)

/**
 * Owns every rule that decides whether a published release belongs to an
 * installed update channel. Network adapters only translate their payloads
 * into [PublishedUpdate]; they do not make channel decisions themselves.
 */
internal object UpdateReleasePolicy {
    fun selectUpgrade(
        channel: UpdateChannel,
        localVersion: String,
        releases: List<PublishedUpdate>,
    ): PublishedUpdate? = eligibleReleases(channel, releases)
        .asSequence()
        .filter { it.assets.containsKey(channel.assetName) }
        .filter { compareVersions(it.versionName, localVersion) > 0 }
        .maxWithOrNull { left, right -> compareVersions(left.versionName, right.versionName) }

    fun highestEligible(
        channel: UpdateChannel,
        releases: List<PublishedUpdate>,
    ): PublishedUpdate? = eligibleReleases(channel, releases)
        .maxWithOrNull { left, right -> compareVersions(left.versionName, right.versionName) }

    fun eligibleReleases(
        channel: UpdateChannel,
        releases: List<PublishedUpdate>,
    ): List<PublishedUpdate> = when (channel) {
        UpdateChannel.STABLE -> releases.filterNot { it.isPrerelease }
        // Preview is an independent installation and only follows preview
        // candidates. A final release never moves users across channels even
        // if a release was accidentally published with both asset names.
        UpdateChannel.PREVIEW -> releases.filter { it.isPrerelease }
    }

    fun normalizeTag(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    fun isPrereleaseVersion(version: String): Boolean =
        normalizeTag(version).substringBefore('+').contains('-')

    fun compareVersions(left: String, right: String): Int {
        val a = SemanticVersion.parse(normalizeTag(left))
        val b = SemanticVersion.parse(normalizeTag(right))
        if (a != null && b != null) return a.compareTo(b)
        return compareLegacy(normalizeTag(left), normalizeTag(right))
    }

    private fun compareLegacy(left: String, right: String): Int {
        val a = left.split('.', '-')
        val b = right.split('.', '-')
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index).orEmpty()
            val y = b.getOrNull(index).orEmpty()
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val comparison = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (comparison != 0) return comparison
        }
        return 0
    }
}

private data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }

        if (prerelease.isEmpty() && other.prerelease.isNotEmpty()) return 1
        if (prerelease.isNotEmpty() && other.prerelease.isEmpty()) return -1

        for (index in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        private val pattern = Regex(
            """^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""",
        )

        fun parse(value: String): SemanticVersion? {
            val match = pattern.matchEntire(value) ?: return null
            return SemanticVersion(
                major = match.groupValues[1].toIntOrNull() ?: return null,
                minor = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return null,
                patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null,
                prerelease = match.groupValues[4]
                    .takeIf { it.isNotEmpty() }
                    ?.split('.')
                    .orEmpty(),
            )
        }
    }
}
