package novex.android.sharekit

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.openminis.app.data.attachments.stripAgentAttachmentMetadata
import com.openminis.app.data.repository.ChatRepository
import novex.android.data.chat.MessageRow
import novex.android.data.chat.SessionRow
import novex.android.logkit.RunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 会话流式导出（P3.5c 自 share/ChatExporter 真重写）。
 *
 * 老做法把整个会话一次性读进内存拼整串再塞 EXTRA_TEXT，几百条消息就是
 * 卡顿、花屏加 OOM。本导出器：
 *  - 按 [PAGE] 分页拉取（[ChatRepository.loadMessagePageRaw]），写完一批
 *    放一批，峰值内存有界；
 *  - 产物先流进 `cacheDir/export-staging/<uuid>/`，再连同一份 `session.json`
 *    元数据旁车打进单个 zip；
 *  - 成品挪进 `cacheDir/shared/`（file_provider_paths.xml 已声明），经
 *    FileProvider 以 content Uri 交出；成败都清暂存。
 *
 * 包内布局（冻结面）：messages.{json|txt} + session.json；zip 名
 * `<标题净化>-<会话id前8位>.zip`（非 [A-Za-z0-9_-] 折 _、截 64）。
 * [progress] 是 StateFlow，进度浮层可直接订阅。
 */
object ChatZipExporter {

    private const val PAGE = 50
    private const val CATEGORY = "ChatZipExporter"

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val done: Int, val total: Int) : Progress
        data class Done(val zipUri: Uri, val summary: Summary) : Progress
        data class Failed(val throwable: Throwable) : Progress
    }

    /** 完成摘要：边流式边累计，UI 免重读载荷即可渲染预览。 */
    data class Summary(
        val format: String,              // "json" | "text"
        val messageCount: Int,
        val firstCreatedAt: Long?,       // ms，空会话为 null
        val lastCreatedAt: Long?,
        val imageAttachments: Int,
        val videoAttachments: Int,
        val estimatedBytes: Long,
    )

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * 流式导出 [session]（"json" | "text"），返回可给 ACTION_SEND 的 zip
     * content Uri。失败抛，调用方作用域决定怎么呈现。
     */
    suspend fun exportToZip(
        context: Context,
        session: SessionRow,
        repository: ChatRepository,
        format: String,
    ): Pair<Uri, Summary> = withContext(Dispatchers.IO) {
        val staging = freshStagingDir(context)
        try {
            val extension = if (format == "json") "json" else "txt"
            val transcript = File(staging, "messages.$extension")
            val summary = Transcription(repository, session).writeTo(transcript, format == "json")

            val sidecar = File(staging, "session.json")
            sidecar.writeText(sidecarJson(session, summary).toString(2), Charsets.UTF_8)

            val zip = packageIntoSharedDir(context, session, staging, extension)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
            _progress.value = Progress.Done(uri, summary)
            RunLog.info(
                CATEGORY,
                "exportToZip ok: ${zip.absolutePath} (${zip.length()} bytes, ${summary.messageCount} msgs)",
            )
            uri to summary
        } catch (t: Throwable) {
            _progress.value = Progress.Failed(t)
            RunLog.error(CATEGORY, "exportToZip failed: ${t.message}")
            throw t
        } finally {
            runCatching { staging.deleteRecursively() } // zip 本体在 shared/ 下
        }
    }

    // ── 内部 ───────────────────────────────────────────────────────────

    private fun freshStagingDir(context: Context): File {
        val dir = File(File(context.cacheDir, "export-staging"), UUID.randomUUID().toString())
        check(dir.mkdirs() || dir.isDirectory) { "export-staging mkdir failed: ${dir.absolutePath}" }
        return dir
    }

    /** 打 zip 到 shared/ 并返回成品文件。 */
    private fun packageIntoSharedDir(
        context: Context,
        session: SessionRow,
        staging: File,
        extension: String,
    ): File {
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val readableTitle = (session.title ?: "conversation")
            .replace(Regex("[^A-Za-z0-9_-]+"), "_")
            .take(64)
            .ifEmpty { "conversation" }
        val zip = File(shared, "${readableTitle}-${session.id.take(8)}.zip")
        zip.delete() // 同名旧包直接作废

        ZipOutputStream(FileOutputStream(zip).buffered()).use { archive ->
            for (name in listOf("messages.$extension", "session.json")) {
                archive.putNextEntry(ZipEntry(name))
                FileInputStream(File(staging, name)).use { it.copyTo(archive, bufferSize = 16 * 1024) }
                archive.closeEntry()
            }
        }
        return zip
    }

    private fun sidecarJson(session: SessionRow, summary: Summary): JSONObject = JSONObject().apply {
        put("id", session.id)
        put("title", session.title ?: "")
        put("model_id", session.modelId)
        put("created_at", session.createdAt)
        put("message_count", summary.messageCount)
        summary.firstCreatedAt?.let { put("first_created_at", it) }
        summary.lastCreatedAt?.let { put("last_created_at", it) }
        put("image_attachments", summary.imageAttachments)
        put("video_attachments", summary.videoAttachments)
        put("format", summary.format)
    }

    /**
     * 一次转录：分页遍历 + 双格式发射器 + 摘要累计三件事合一。
     * 每处理完一页 flush 并发进度。
     */
    private class Transcription(
        private val repository: ChatRepository,
        private val session: SessionRow,
    ) {
        private var total = 0
        private var done = 0
        private var firstAt: Long? = null
        private var lastAt: Long? = null
        private var images = 0
        private var videos = 0
        private var bytes = 0L

        suspend fun writeTo(out: File, asJson: Boolean): Summary {
            total = repository.messageCount(session.id)
            _progress.value = Progress.Running(0, total)
            BufferedWriter(OutputStreamWriter(FileOutputStream(out), Charsets.UTF_8)).use { sink ->
                val emit: (MessageRow) -> Unit =
                    if (asJson) jsonEmitter(sink) else textEmitter(sink)
                if (!asJson) sink.write((session.title ?: "Conversation") + "\n\n")
                else sink.write("[")
                paginate { page ->
                    page.forEach(emit)
                    sink.flush()
                    _progress.value = Progress.Running(done, total)
                }
                if (asJson) sink.write("]")
            }
            return Summary(
                format = if (asJson) "json" else "text",
                messageCount = done,
                firstCreatedAt = firstAt,
                lastCreatedAt = lastAt,
                imageAttachments = images,
                videoAttachments = videos,
                estimatedBytes = bytes,
            )
        }

        /** 从 offset 0 起按 [PAGE] 翻页；空页或短页即止。 */
        private suspend fun paginate(consume: (List<MessageRow>) -> Unit) {
            if (total <= 0) return
            var cursor = 0
            while (cursor < total) {
                val page = repository.loadMessagePageRaw(session.id, cursor, PAGE)
                if (page.isEmpty()) break
                consume(page)
                cursor += page.size
                if (page.size < PAGE) break
            }
        }

        /** 手搓 JSON 数组元素（`{…}`），分隔逗号由 done 计数推断。 */
        private fun jsonEmitter(sink: BufferedWriter): (MessageRow) -> Unit = { message ->
            if (done > 0) sink.write(",")
            val rendered = JSONObject()
                .put("id", message.id)
                .put("role", message.role)
                .put("content", message.partsJson)
                .put("created_at", message.createdAt)
                .toString()
            sink.write(rendered)
            tally(message, rendered.length.toLong())
        }

        /** `You: …` / `Assistant: …` 段落。 */
        private fun textEmitter(sink: BufferedWriter): (MessageRow) -> Unit = { message ->
            val speaker = if (message.role == "user") "You" else "Assistant"
            val text = humanReadableText(message.partsJson)
            sink.write("$speaker: $text\n\n")
            tally(message, text.length.toLong() + speaker.length + 4)
        }

        private fun tally(message: MessageRow, payloadChars: Long) {
            bytes += payloadChars
            if (firstAt == null) firstAt = message.createdAt
            lastAt = message.createdAt
            val (img, vid) = attachmentCounts(message.partsJson)
            images += img
            videos += vid
            done += 1
        }

        /** 人类可读文本：拼 text part，剥模型向的附件清单元数据。 */
        private fun humanReadableText(partsJson: String): String = try {
            val parts = JSONArray(partsJson)
            buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.optString("type") != "text") continue
                    // 持久化的 <user-attached-files> XML 清单是给模型看的
                    // 元数据，不是聊天内容（JSON 导出保 parts_json 全保真）。
                    val value = stripAgentAttachmentMetadata(part.optString("value"))
                    if (value.isEmpty()) continue
                    if (isNotEmpty()) append('\n')
                    append(value)
                }
            }
        } catch (_: Throwable) {
            partsJson
        }

        /** 尽力而为的 (图片数, 视频数)。 */
        private fun attachmentCounts(partsJson: String): Pair<Int, Int> = try {
            var img = 0
            var vid = 0
            val parts = JSONArray(partsJson)
            for (i in 0 until parts.length()) {
                when (parts.optJSONObject(i)?.optString("type")) {
                    "image", "image_url" -> img++
                    "video", "video_url" -> vid++
                }
            }
            img to vid
        } catch (_: Throwable) {
            0 to 0
        }
    }
}
