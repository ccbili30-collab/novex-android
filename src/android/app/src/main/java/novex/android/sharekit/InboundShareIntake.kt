package novex.android.sharekit

import android.content.Intent
import android.net.Uri
import com.openminis.app.share.PendingShare
import novex.android.logkit.RunLog
import java.io.File
import java.util.UUID

/**
 * 入站分享的提货与落盘（P3.5c 自 share/ShareReceiverActivity 的私逻
 * 辑真重写抽出）：ACTION_SEND / SEND_MULTIPLE / VIEW 三种进法的载荷
 * 提取、ContentResolver 流拷进暂存目录、provider 导出 JSON 的识别。
 * Activity 侧只留编排与对话框。
 *
 * 常量（冻结面）：内联文本上限 1000 字符（超出落成附件）；暂存文件名
 * 前缀 shared-image / shared-video / shared + 8 位随机后缀；provider 导出
 * JSON 的识别判据（providerType + credentialType + models[] 且 ≤1MB）。
 */
object InboundShareIntake {

    private const val CATEGORY = "InboundShareIntake"
    private const val INLINE_TEXT_LIMIT = 1000
    private const val PROVIDER_JSON_MAX_BYTES = 1_000_000L

    /**
     * ACTION_SEND：text/plain 且不长 → 内联条目；其余（含超长文本）走
     * EXTRA_STREAM 落附件。提不到载荷就一条不加。
     */
    fun drainSingleSend(
        intent: Intent,
        openStream: (Uri) -> java.io.InputStream?,
        stagingDir: File,
        out: MutableList<PendingShare.Item>,
    ) {
        val type = intent.type ?: ""
        if (type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
            if (text.length <= INLINE_TEXT_LIMIT) {
                out += PendingShare.Item(PendingShare.Item.Kind.INLINE_TEXT, text)
            } else {
                val name = "shared-text-${shortId()}.txt"
                File(stagingDir, name).writeText(text, Charsets.UTF_8)
                out += PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
            }
            return
        }
        val uri = parcelableExtra<Uri>(intent, Intent.EXTRA_STREAM) ?: return
        stageStream(uri, type, openStream, stagingDir)?.let { out += it }
    }

    /** ACTION_SEND_MULTIPLE：EXTRA_STREAM 逐个落附件。 */
    fun drainMultipleSend(
        intent: Intent,
        openStream: (Uri) -> java.io.InputStream?,
        stagingDir: File,
        out: MutableList<PendingShare.Item>,
    ) {
        val type = intent.type ?: ""
        val uris = parcelableArrayListExtra<Uri>(intent, Intent.EXTRA_STREAM) ?: return
        for (uri in uris) {
            stageStream(uri, type, openStream, stagingDir)?.let { out += it }
        }
    }

    /**
     * ACTION_VIEW：载荷在 intent.data（不像 SEND 走 EXTRA_STREAM）。
     * Telegram 与部分文件管理器会把 intent.type 留空，落盘前以
     * ContentResolver 解析出的类型兜底。
     */
    fun drainView(
        intent: Intent,
        openStream: (Uri) -> java.io.InputStream?,
        resolveType: (Uri) -> String?,
        stagingDir: File,
        out: MutableList<PendingShare.Item>,
    ) {
        val uri = intent.data
        if (uri == null) {
            RunLog.warning(CATEGORY, "ACTION_VIEW with no data uri")
            return
        }
        val type = intent.type ?: resolveType(uri) ?: ""
        RunLog.info(CATEGORY, "ACTION_VIEW uri=$uri type=$type")
        stageStream(uri, type, openStream, stagingDir)?.let { out += it }
    }

    /**
     * 读暂存文件内容，顶层 JSON 带 provider 导出形态（providerType +
     * credentialType + models[]——importInstanceJSON 需要的同一组必填
     * 字段）才返回原文；非 JSON、读不动、不像的都返回 null，调用方落
     * 正常附件流。
     */
    fun providerExportJsonOrNull(item: PendingShare.Item, stagingDir: File): String? {
        if (item.kind != PendingShare.Item.Kind.ATTACHMENT) return null
        if (!item.value.endsWith(".json", ignoreCase = true)) return null
        val file = File(stagingDir, item.value)
        // 防“病态大 JSON”：provider 导出就几 KB，过 1MB 的必定是别的东西。
        if (!file.isFile || file.length() > PROVIDER_JSON_MAX_BYTES) return null
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            RunLog.warning(CATEGORY, "providerExportJsonOrNull read failed: ${e.message}")
            return null
        }
        val obj = try {
            org.json.JSONObject(text)
        } catch (_: Exception) {
            return null
        }
        val looksLikeProvider = obj.optString("providerType").isNotEmpty() &&
            obj.optString("credentialType").isNotEmpty() &&
            obj.optJSONArray("models") != null
        return if (looksLikeProvider) text else null
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    /** 拷 ContentResolver 流进暂存目录；返回 (附件条目)。失败返回 null。 */
    private fun stageStream(
        uri: Uri,
        mimeType: String,
        openStream: (Uri) -> java.io.InputStream?,
        stagingDir: File,
    ): PendingShare.Item? {
        val extension = guessExtension(mimeType, uri)
        val prefix = when {
            mimeType.startsWith("image/") -> "shared-image"
            mimeType.startsWith("video/") -> "shared-video"
            else -> "shared"
        }
        val name = "$prefix-${shortId()}${if (extension.isNotEmpty()) ".$extension" else ""}"
        val dest = File(stagingDir, name)
        return try {
            openStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            PendingShare.Item(PendingShare.Item.Kind.ATTACHMENT, name)
        } catch (e: Exception) {
            RunLog.warning(CATEGORY, "stageStream($uri): ${e.message}")
            null
        }
    }

    private fun guessExtension(mimeType: String, uri: Uri): String {
        val fromMime = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType.substringBefore(';'))
        if (!fromMime.isNullOrBlank()) return fromMime
        return uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.length in 1..6 }
            ?: ""
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> parcelableExtra(intent: Intent, key: String): T? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, T::class.java)
        } else {
            intent.getParcelableExtra(key)
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> parcelableArrayListExtra(
        intent: Intent,
        key: String,
    ): ArrayList<T>? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, T::class.java)
        } else {
            intent.getParcelableArrayListExtra(key)
        }
}
