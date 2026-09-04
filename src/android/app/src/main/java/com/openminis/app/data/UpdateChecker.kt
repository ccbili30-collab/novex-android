package com.openminis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.openminis.app.BuildConfig
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub releases for an APK newer than [BuildConfig.VERSION_NAME] and
 * coordinates download → install. iOS has no equivalent (sideloading is not
 * permitted) so this is Android-only.
 *
 * Release selection is delegated to [UpdateReleasePolicy]. It preserves
 * semantic-version prerelease suffixes and requires the exact APK asset for
 * the channel baked into this build.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val OWNER = "ccbili30-collab"
    // T133: the public repo is OpenMinis/OpenMinis (org + repo share a name).
    // Previously pointed at OpenMinis/MinisApp, which is the private dev
    // mirror — every API call 404'd, which we mistranslated as "no release
    // published". The 0.1-preview release is published as a prerelease on
    // OpenMinis/OpenMinis with a MinisApp-*.apk asset attached.
    private const val REPO = "novex-android"
    private const val RELEASES_API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=100"
    private const val RELEASES_ATOM_URL = "https://github.com/$OWNER/$REPO/releases.atom"
    /**
     * Sub-directory of `filesDir` where we stage downloaded update APKs. We
     * moved off `cacheDir/shared/` (the original location) so the OS can't
     * evict a freshly-downloaded APK between the moment we hand the user off
     * to "install unknown apps" settings and the moment they return — the
     * eviction was a contributing factor to the "re-download after grant"
     * bug. See [PendingUpdateStore]. Exposed via `file_provider_paths.xml`
     * `<files-path name="updates" path="updates/" />`.
     */
    private const val UPDATES_DIR = "updates"

    sealed class CheckResult {
        data class UpdateAvailable(
            val tagName: String,
            val versionName: String,
            val releaseName: String,
            val changelog: String,
            val apkUrl: String,
            val apkSizeBytes: Long,
            val channel: UpdateChannel,
            val isPrerelease: Boolean,
            val releaseNotes: List<ReleaseNote>,
        ) : CheckResult()
        data object UpToDate : CheckResult()
        // The repo has zero non-draft releases (or 404'd entirely).
        data object NoReleaseAvailable : CheckResult()
        // A newer release exists but no .apk asset was attached. Distinct
        // from NoReleaseAvailable so the UI can say "newer release exists,
        // but it didn't ship an APK" instead of misleading "no release yet".
        data class NoApkAsset(val tagName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        // GitHub returned 403 / 451 — usually a geo-block or rate-limit in CN
        // without a VPN. UI surfaces a hint with a clickable Releases link.
        data object Forbidden : CheckResult()
        // DNS / connect / read timeout — network unreachable. UI nudges the
        // user to check connectivity and retry.
        data object NetworkUnreachable : CheckResult()
    }

    data class ReleaseNote(
        val versionName: String,
        val releaseName: String,
        val changelog: String,
    )

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentChannel: UpdateChannel
        get() = UpdateChannel.fromWireName(BuildConfig.UPDATE_CHANNEL)

    /** Loads the official announcement and release archive without requiring an update to exist. */
    internal suspend fun fetchBulletin(): NovexBulletin = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.warning(TAG, "bulletin HTTP ${response.code}; using bundled archive")
                    return@withContext NovexBulletinDefaults.value
                }
                val body = response.body?.string().orEmpty()
                val releases = runCatching { parsePublishedReleases(body) }
                    .onFailure {
                        AppLogger.warning(TAG, "bulletin parse failed: ${it.javaClass.simpleName}: ${it.message}")
                    }
                    .getOrDefault(emptyList())
                NovexBulletinPolicy.build(currentChannel, releases)
                    .takeIf { it.announcements.isNotEmpty() || it.releaseNotes.isNotEmpty() }
                    ?: NovexBulletinDefaults.value
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "bulletin fetch failed: ${e.javaClass.simpleName}: ${e.message}")
            NovexBulletinDefaults.value
        }
    }

    /**
     * Hit `repos/{owner}/{repo}/releases` (the list endpoint, NOT
     * `/releases/latest`), pick the highest-version non-draft release that
     * carries an APK asset, and decide whether the user should upgrade.
     *
     * T133: switched from `/releases/latest` to `/releases` because
     * `/releases/latest` excludes prereleases by GitHub design — our
     * `0.1 preview` release is flagged as a prerelease, so the old endpoint
     * 404'd and the UI falsely showed "No release published yet". The list
     * endpoint includes prereleases; we filter drafts client-side.
     *
     * All network work happens on [Dispatchers.IO]; safe to call from any
     * coroutine scope.
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val url = RELEASES_API_URL
        val localVer = normalizeTag(BuildConfig.VERSION_NAME)
        AppLogger.info(TAG, "GET $url (local=${BuildConfig.VERSION_NAME})")
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(req).execute().use { resp ->
                AppLogger.info(TAG, "HTTP ${resp.code}")
                if (resp.code == 404) {
                    return@withContext checkAtomFallback(localVer, CheckResult.NoReleaseAvailable)
                }
                // 403 = rate-limit or geo-blocked. 451 = legal block. Both
                // map to the same "open Releases in browser" hint — there's
                // nothing the app can do client-side.
                if (resp.code == 403 || resp.code == 451) {
                    AppLogger.warning(TAG, "GitHub API ${resp.code} — geo-block or rate-limit")
                    return@withContext checkAtomFallback(localVer, CheckResult.Forbidden)
                }
                if (!resp.isSuccessful) {
                    val msg = "GitHub API ${resp.code}"
                    AppLogger.warning(TAG, msg)
                    return@withContext checkAtomFallback(localVer, CheckResult.Error(msg))
                }
                val body = resp.body?.string()
                    ?: return@withContext checkAtomFallback(localVer, CheckResult.Error("empty body"))
                val candidates = runCatching { parsePublishedReleases(body) }.getOrNull()
                if (candidates == null) {
                    AppLogger.warning(TAG, "GitHub API returned malformed release data")
                    return@withContext checkAtomFallback(localVer, CheckResult.Error("invalid release data"))
                }
                if (candidates.isEmpty()) {
                    AppLogger.info(TAG, "releases list empty")
                    return@withContext CheckResult.NoReleaseAvailable
                }
                val channel = currentChannel
                val eligible = UpdateReleasePolicy.eligibleReleases(channel, candidates)
                AppLogger.info(
                    TAG,
                    "non-draft releases=${candidates.size} channel=${channel.wireName} " +
                        "eligible=${eligible.size} asset=${channel.assetName} " +
                        "(asset-bearing=${eligible.count { it.assets.containsKey(channel.assetName) }})",
                )
                if (eligible.isEmpty()) {
                    return@withContext CheckResult.NoReleaseAvailable
                }

                // Highest version we've seen at all (used for the "release
                // exists but is older or equal" → UpToDate decision and for
                // logging).
                val highest = UpdateReleasePolicy.highestEligible(channel, eligible) ?: eligible.first()
                AppLogger.info(
                    TAG,
                    "highest-eligible tag=${highest.tagName} parsed=${highest.versionName} " +
                        "prerelease=${highest.isPrerelease} asset=${highest.assets.containsKey(channel.assetName)}",
                )

                // First APK-bearing release with version > local. We pick the
                // highest such release so a stale older APK never shadows a
                // newer non-APK preview.
                val upgradeCandidate = UpdateReleasePolicy.selectUpgrade(channel, localVer, eligible)

                if (upgradeCandidate != null) {
                    val asset = requireNotNull(upgradeCandidate.assets[channel.assetName])
                    val releaseNotes = buildReleaseNotes(
                        channel = channel,
                        localVersion = localVer,
                        targetVersion = upgradeCandidate.versionName,
                        releases = candidates,
                    )
                    AppLogger.info(
                        TAG,
                        "Update available: $localVer → ${upgradeCandidate.versionName} " +
                            "(${upgradeCandidate.tagName}, channel=${channel.wireName})",
                    )
                    return@withContext CheckResult.UpdateAvailable(
                        tagName = upgradeCandidate.tagName,
                        versionName = upgradeCandidate.versionName,
                        releaseName = upgradeCandidate.releaseName,
                        changelog = upgradeCandidate.changelog,
                        apkUrl = asset.url,
                        apkSizeBytes = asset.sizeBytes,
                        channel = channel,
                        isPrerelease = upgradeCandidate.isPrerelease,
                        releaseNotes = releaseNotes,
                    )
                }

                // No newer-with-APK candidate exists. Decide between three
                // remaining states:
                //   1. Highest release ≤ local version → UpToDate.
                //   2. Highest release > local but no APK in the listing →
                //      NoApkAsset (mention the tag so the user can grab the
                //      release manually if they really want).
                //   3. Otherwise (all releases ≤ local) → UpToDate as well.
                val highestVsLocal = compareVersions(highest.versionName, localVer)
                if (highestVsLocal > 0 && !highest.assets.containsKey(channel.assetName)) {
                    AppLogger.info(
                        TAG,
                        "Release ${highest.tagName} > local but no ${channel.assetName} asset",
                    )
                    return@withContext CheckResult.NoApkAsset(highest.tagName)
                }

                AppLogger.info(TAG, "Up to date: local=$localVer highest=${highest.versionName}")
                CheckResult.UpToDate
            }
        } catch (e: UnknownHostException) {
            AppLogger.error(TAG, "check failed: UnknownHostException: ${e.message}")
            checkAtomFallback(localVer, CheckResult.NetworkUnreachable)
        } catch (e: ConnectException) {
            AppLogger.error(TAG, "check failed: ConnectException: ${e.message}")
            checkAtomFallback(localVer, CheckResult.NetworkUnreachable)
        } catch (e: SocketTimeoutException) {
            AppLogger.error(TAG, "check failed: SocketTimeoutException: ${e.message}")
            checkAtomFallback(localVer, CheckResult.NetworkUnreachable)
        } catch (e: IOException) {
            // Catch-all for okhttp connection plumbing (e.g.
            // "failed to connect", SSL handshake errors). Most of these in
            // the CN-no-VPN scenario are effectively "can't reach github".
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            checkAtomFallback(localVer, CheckResult.NetworkUnreachable)
        } catch (e: Exception) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * GitHub's API subdomain is frequently routed differently from github.com
     * by mobile VPN/proxy rules. If the API path fails, use the public Atom
     * feed on the main host before telling the user that GitHub is unreachable.
     */
    private fun checkAtomFallback(localVersion: String, originalFailure: CheckResult): CheckResult {
        AppLogger.info(TAG, "API path unavailable; trying $RELEASES_ATOM_URL")
        return try {
            val request = Request.Builder().url(RELEASES_ATOM_URL).build()
            client.newCall(request).execute().use { response ->
                AppLogger.info(TAG, "Atom HTTP ${response.code}")
                if (!response.isSuccessful) return@use originalFailure
                val body = response.body?.string() ?: return@use originalFailure
                parseAtomReleaseFeed(body, localVersion, currentChannel)
            }
        } catch (e: Exception) {
            AppLogger.warning(TAG, "Atom fallback failed: ${e.javaClass.simpleName}: ${e.message}")
            originalFailure
        }
    }

    /**
     * Parse GitHub's releases Atom feed without depending on Android XML APIs,
     * so the fallback remains unit-testable on the JVM. The feed does not list
     * assets; each Novex channel uses a fixed asset name, allowing a
     * deterministic browser-download URL after the tag is filtered.
     */
    internal fun parseAtomReleaseFeed(
        body: String,
        localVersion: String,
        channel: UpdateChannel = currentChannel,
    ): CheckResult {
        val entries = Regex("<entry>(.*?)</entry>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(body)
            .mapNotNull { match ->
                val entry = match.groupValues[1]
                val href = Regex(
                    "<link[^>]+rel=[\\\"']alternate[\\\"'][^>]+href=[\\\"']([^\\\"']+/releases/tag/([^\\\"']+))[\\\"']",
                    RegexOption.IGNORE_CASE,
                ).find(entry) ?: Regex(
                    "<link[^>]+href=[\\\"']([^\\\"']+/releases/tag/([^\\\"']+))[\\\"'][^>]+rel=[\\\"']alternate[\\\"']",
                    RegexOption.IGNORE_CASE,
                ).find(entry) ?: return@mapNotNull null
                val tag = href.groupValues[2]
                val title = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                    .find(entry)?.groupValues?.get(1)?.let(::atomContentToPlainText).orEmpty()
                val changelog = Regex(
                    "<content\\b[^>]*>(.*?)</content>",
                    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
                ).find(entry)?.groupValues?.get(1)
                    ?.let(::atomContentToMarkdown)
                    ?.takeIf { it.isNotBlank() }
                    ?: ATOM_CHANGELOG_FALLBACK
                PublishedUpdate(
                    tagName = tag,
                    versionName = normalizeTag(tag),
                    releaseName = title.ifEmpty { tag },
                    changelog = changelog,
                    isPrerelease = UpdateReleasePolicy.isPrereleaseVersion(tag),
                    assets = mapOf(
                        channel.assetName to PublishedAsset(
                            url = "https://github.com/$OWNER/$REPO/releases/download/$tag/${channel.assetName}",
                            sizeBytes = 0,
                        ),
                    ),
                )
            }
            .toList()

        val eligible = UpdateReleasePolicy.eligibleReleases(channel, entries)
        if (eligible.isEmpty()) return CheckResult.NoReleaseAvailable
        val highest = UpdateReleasePolicy.selectUpgrade(channel, localVersion, eligible)
            ?: return CheckResult.UpToDate
        val asset = requireNotNull(highest.assets[channel.assetName])
        val releaseNotes = buildReleaseNotes(
            channel = channel,
            localVersion = localVersion,
            targetVersion = highest.versionName,
            releases = entries,
        )
        return CheckResult.UpdateAvailable(
            tagName = highest.tagName,
            versionName = highest.versionName,
            releaseName = highest.releaseName,
            changelog = highest.changelog.ifBlank { ATOM_CHANGELOG_FALLBACK },
            apkUrl = asset.url,
            apkSizeBytes = 0,
            channel = channel,
            isPrerelease = highest.isPrerelease,
            releaseNotes = releaseNotes,
        )
    }

    private fun buildReleaseNotes(
        channel: UpdateChannel,
        localVersion: String,
        targetVersion: String,
        releases: List<PublishedUpdate>,
    ): List<ReleaseNote> = UpdateReleasePolicy.releaseHistory(
        channel = channel,
        localVersion = localVersion,
        targetVersion = targetVersion,
        releases = releases,
    ).map { release ->
        ReleaseNote(
            versionName = release.versionName,
            releaseName = release.releaseName,
            changelog = release.changelog
                .substringBefore("\n## 包含的往期更新")
                .trim()
                .ifBlank { "该版本未提供更新说明。" },
        )
    }

    /** Public so UI can deep-link users to manual download when GitHub is blocked. */
    const val RELEASES_URL: String = "https://github.com/ccbili30-collab/novex-android/releases"

    internal fun parsePublishedReleases(body: String): List<PublishedUpdate> {
        val releases = JSONArray(body)
        return buildList {
            for (index in 0 until releases.length()) {
                val release = releases.optJSONObject(index) ?: continue
                if (release.optBoolean("draft", false)) continue
                val tag = release.optString("tag_name")
                if (tag.isEmpty()) continue
                add(
                    PublishedUpdate(
                        tagName = tag,
                        versionName = normalizeTag(tag),
                        releaseName = release.optString("name").ifEmpty { tag },
                        changelog = release.optString("body", ""),
                        isPrerelease = release.optBoolean("prerelease", false),
                        assets = readUpdateAssets(release.optJSONArray("assets")),
                    ),
                )
            }
        }
    }

    /** Index installable assets by exact file name so channels cannot cross. */
    private fun readUpdateAssets(assets: JSONArray?): Map<String, PublishedAsset> {
        if (assets == null) return emptyMap()
        val result = linkedMapOf<String, PublishedAsset>()
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true) &&
                !name.endsWith(".novex", ignoreCase = true)
            ) continue
            val url = a.optString("browser_download_url").ifEmpty { null } ?: continue
            result[name.lowercase()] = PublishedAsset(url, a.optLong("size", 0))
        }
        return result
    }

    /** Strip only the conventional tag prefix; prerelease identity is significant. */
    private fun normalizeTag(tag: String): String = UpdateReleasePolicy.normalizeTag(tag)

    /** Convert GitHub Atom's entity-escaped HTML release body to readable text. */
    private fun atomContentToPlainText(raw: String): String {
        val html = decodeXmlEntities(raw)
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(?:p|div|h[1-6]|li)\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "• ")
            .replace(Regex("<[^>]+>"), "")
        return decodeXmlEntities(html)
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** Preserve GitHub release formatting when the REST API falls back to Atom HTML. */
    private fun atomContentToMarkdown(raw: String): String {
        val options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        var markdown = decodeXmlEntities(decodeXmlEntities(raw))
            .replace(Regex("<!--.*?-->", options), "")
            .replace(Regex("<(?:strong|b)\\b[^>]*>", RegexOption.IGNORE_CASE), "**")
            .replace(Regex("</(?:strong|b)\\s*>", RegexOption.IGNORE_CASE), "**")
            .replace(Regex("<(?:em|i)\\b[^>]*>", RegexOption.IGNORE_CASE), "*")
            .replace(Regex("</(?:em|i)\\s*>", RegexOption.IGNORE_CASE), "*")
            .replace(Regex("<(?:del|s)\\b[^>]*>", RegexOption.IGNORE_CASE), "~~")
            .replace(Regex("</(?:del|s)\\s*>", RegexOption.IGNORE_CASE), "~~")
            .replace(Regex("<code\\b[^>]*>", RegexOption.IGNORE_CASE), "`")
            .replace(Regex("</code\\s*>", RegexOption.IGNORE_CASE), "`")

        markdown = Regex(
            "<a\\b[^>]*href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>",
            options,
        ).replace(markdown) { match ->
            val label = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            "[$label](${decodeXmlEntities(match.groupValues[1])})"
        }

        for (level in 1..6) {
            markdown = markdown
                .replace(Regex("<h$level\\b[^>]*>", RegexOption.IGNORE_CASE), "\n${"#".repeat(level)} ")
                .replace(Regex("</h$level\\s*>", RegexOption.IGNORE_CASE), "\n")
        }

        return markdown
            .replace(Regex("<hr\\b[^>]*?/?>", RegexOption.IGNORE_CASE), "\n\n---\n\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
            .replace(Regex("</li\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(?:ul|ol)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(?:p|div|blockquote)\\s*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<(?:p|div|blockquote)\\b[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), "")
            .let(::decodeXmlEntities)
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n[ \\t]+"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeXmlEntities(value: String): String = value
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)

    private const val ATOM_CHANGELOG_FALLBACK =
        "更新说明暂未加载。请在网络恢复后重新检查，或前往发布页查看完整公告。"

    /**
     * Stream the APK from [url] into `${cacheDir}/shared/minis-update.apk`,
     * surfacing progress (0..1) through [onProgress] roughly every 64 KiB.
     * Returns the on-disk [File] on success so the caller can hand it to
     * [installApk]. The path is intentionally inside `shared/` because that's
     * the only sub-directory of cacheDir already exposed by FileProvider in
     * `file_provider_paths.xml`.
     */
    suspend fun download(
        context: Context,
        url: String,
        versionName: String? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            // Stage under filesDir (NOT cacheDir) so the OS doesn't evict
            // the APK mid-flow while the user is in system Settings granting
            // install permission — that eviction caused the "re-download
            // after grant" regression (T-android-update-resume-33637).
            val outDir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            // Filename keyed by version so a partial old-version download
            // can't accidentally satisfy a check for a newer version.
            val safeName = versionName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "minis-$it.apk" }
                ?: currentChannel.assetName
            val outFile = File(outDir, safeName)
            // A previous, possibly-aborted download could leave a stale APK
            // behind that the installer would happily try to consume. Wipe it.
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) {
                                val pct = ((totalRead * 100) / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            AppLogger.info(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            // Persist so a subsequent Activity recreate (e.g. after the user
            // returns from "install unknown apps" settings) can resume the
            // install without re-downloading. sha256 computed best-effort;
            // verify() falls back to size-only when null.
            val sha = runCatching { PendingUpdateStore.sha256(outFile) }
                .onFailure { AppLogger.warning(TAG, "sha256 compute failed: ${it.message}") }
                .getOrNull()
            if (versionName != null) {
                PendingUpdateStore.setPending(
                    context,
                    PendingUpdateStore.PendingUpdate(
                        targetVersionName = versionName,
                        apkPath = outFile.absolutePath,
                        apkSize = outFile.length(),
                        sha256 = sha,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            DownloadResult.Success(outFile)
        } catch (e: Exception) {
            AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Whether the OS will allow this app to launch a package-installer
     * intent. On Android 8+ the user must grant "install unknown apps" per
     * source-app; older releases inherit the system-wide setting.
     */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Send the user to the system "install unknown apps" preferences page
     * for this package. Caller should re-check [canInstall] after the user
     * returns.
     */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Hand [apk] to the system package installer via FileProvider.
     * Caller must ensure [canInstall] before calling, otherwise the system
     * silently bounces back to the launcher. Returns false on any
     * launch failure so callers can surface an error instead of closing
     * the dialog with no visible feedback.
     */
    /**
     * If a pending APK from a previous download is still on disk and intact,
     * returns the [File]. The caller is responsible for checking
     * [canInstall] and firing [installApk]. Returns null when nothing pending
     * or when the cached file failed integrity checks — in the latter case
     * the pending record is cleared so the UI falls through to a fresh
     * download.
     */
    fun resumablePendingFile(context: Context): File? {
        val pending = PendingUpdateStore.getPending(context) ?: return null
        // Only resume if the persisted target is still newer than the running
        // build — protects against the case where the user updated by some
        // other means since the download.
        if (compareVersions(pending.targetVersionName, normalizeTag(BuildConfig.VERSION_NAME)) <= 0) {
            AppLogger.info(TAG, "pending target ${pending.targetVersionName} <= local; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        val file = PendingUpdateStore.verify(pending)
        if (file == null) {
            AppLogger.info(TAG, "pending APK failed integrity; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        return file
    }

    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.info(TAG, "installApk launched apk=${apk.absolutePath} size=${apk.length()}")
            // Once the installer is in flight we don't want a subsequent
            // resume to re-fire the intent (would double-prompt). Clear the
            // pending record now; if the user backs out, the next "Check for
            // Updates" tap will re-discover and re-download.
            PendingUpdateStore.clearPending(context)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "installApk failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Semantic-version comparison with a legacy fallback for old tags. */
    private fun compareVersions(a: String, b: String): Int = UpdateReleasePolicy.compareVersions(a, b)
}
