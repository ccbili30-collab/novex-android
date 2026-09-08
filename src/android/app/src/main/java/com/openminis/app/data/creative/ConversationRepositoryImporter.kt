package com.openminis.app.data.creative

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.openminis.app.data.attachments.NovexDocumentSnapshotExtractor
import com.openminis.app.novex.domain.*
import java.io.File
import java.io.FilterInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ConversationImportStatus(val running: Boolean = false, val stopping: Boolean = false,
    val saved: Int = 0, val reused: Int = 0, val current: String = "", val message: String = "",
    val issues: List<String> = emptyList(), val issueCount: Int = 0)

/** User-driven file imports survive leaving the page; no chat messages or model calls are created. */
class ConversationRepositoryImporter(private val context: Context, private val store: NovexConversationWorkspaceStore,
    private val artifacts: CreativeArtifactRepository) {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ConcurrentHashMap<String, MutableStateFlow<ConversationImportStatus>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stops = ConcurrentHashMap<String, AtomicBoolean>()
    private val prefs = context.getSharedPreferences("conversation-import-status", Context.MODE_PRIVATE)
    private val importer = NovexWorkspaceImport(store)
    private val extractor = NovexDocumentSnapshotExtractor(FileNovexDocumentSnapshotRepository(
        File(context.filesDir, "novex/derived/document-snapshots")))

    fun status(id: String): StateFlow<ConversationImportStatus> = state(id)
    private fun state(id: String) = states.getOrPut(id) { MutableStateFlow(ConversationImportStatus(
        message = prefs.getString(id, "").orEmpty().let { if (it == "running") "上次导入已中断，已保存文件仍保留；重新选择文件即可继续。" else it })) }
    fun stop(id: String) {
        if (!state(id).value.running) return
        stops[id]?.set(true)
        state(id).value = state(id).value.copy(stopping = true, message = "正在停止导入，已保存文件会保留")
    }

    suspend fun stopAndJoin(id: String) {
        if (state(id).value.running) stop(id)
        jobs[id]?.join()
    }

    @Synchronized fun start(id: String, files: List<Uri> = emptyList(), folder: Uri? = null) {
        val state = state(id)
        if (state.value.running || files.isEmpty() && folder == null) return
        val stopped = AtomicBoolean(false)
        stops[id] = stopped
        state.value = ConversationImportStatus(running = true, message = "正在准备导入")
        prefs.edit().putString(id, "running").commit()
        val job = worker.launch(start = CoroutineStart.LAZY) {
            fun issue(name: String, message: String) {
                val old = state.value
                state.value = old.copy(issues = (old.issues + "$name：$message").take(50), issueCount = old.issueCount + 1)
            }
            fun permission(uri: Uri) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            suspend fun importOne(uri: Uri, path: String, mime: String?) {
                if (stopped.get()) return
                state.value = state.value.copy(current = path, message = "正在导入")
                var originalSaved = false
                try {
                    val stream = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开，请重新选择文件" }
                    val saved = stream.use { input -> importer.save(id, path, mime?.takeUnless { it == "application/octet-stream" } ?: guessMime(path), object : FilterInputStream(input) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (stopped.get()) throw InterruptedException("已停止")
                            return super.read(b, off, len)
                        }
                    }) }
                    // Register immediately, before parsing. The original remains visible even if parsing fails.
                    val bridge = WorkspaceCreativeArtifactBridge(store, artifacts)
                    val record = bridge.registerImported(saved.entry)
                    originalSaved = true
                    state.value = state.value.let { it.copy(saved = it.saved + if (saved.reused) 0 else 1,
                        reused = it.reused + if (saved.reused) 1 else 0) }
                    if (!stopped.get() && !readableText(saved.entry.mimeType)) {
                        val original = artifacts.file(record.artifact.id)
                        val snapshot = extractor.extract(context, original, mime, path.substringAfterLast('/'))
                        if (snapshot != null && snapshot.blocks.isNotEmpty()) {
                            importer.saveParsed(saved.entry, snapshot.blocks.joinToString("\n\n") { it.text })
                            snapshot.warnings.forEach { issue(path, "原件已保存；${it.message}") }
                        } else {
                            issue(path, "原件已保存，目前无法提取可供人工智能阅读的正文")
                        }
                    }
                } catch (error: Exception) {
                    if (!stopped.get()) issue(path, (if (originalSaved) "原件已保存，解析未完成；" else "") + importFailure(error))
                }
            }
            try {
                if (folder != null) {
                    permission(folder)
                    val rootId = DocumentsContract.getTreeDocumentId(folder)
                    val rootUri = DocumentsContract.buildDocumentUriUsingTree(folder, rootId)
                    val rootName = context.contentResolver.query(rootUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "资料"
                    val pending = java.util.ArrayDeque<Pair<String, String>>()
                    pending.add(rootId to rootName)
                    val visited = mutableSetOf<String>()
                    while (pending.isNotEmpty() && !stopped.get()) {
                        val (documentId, path) = pending.removeFirst()
                        if (!visited.add(documentId)) continue
                        try {
                            val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, documentId)
                            val rows = requireNotNull(context.contentResolver.query(children, arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)) { "无法读取文件夹" }
                            rows.use { values ->
                                while (values.moveToNext() && !stopped.get()) {
                                    val childId = values.getString(0)
                                    val childPath = "$path/${values.getString(1) ?: "未命名文件"}"
                                    val type = values.getString(2)
                                    if (type == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(childId to childPath)
                                    else importOne(DocumentsContract.buildDocumentUriUsingTree(folder, childId), childPath, type)
                                }
                            }
                        } catch (error: Exception) { if (!stopped.get()) issue(path, importFailure(error)) }
                    }
                } else files.distinct().forEach { uri ->
                    if (!stopped.get()) {
                        try {
                            permission(uri)
                            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) it.getString(0) else null
                            } ?: "未命名文件"
                            importOne(uri, name, context.contentResolver.getType(uri))
                        } catch (error: Exception) { if (!stopped.get()) issue("未能打开的文件", importFailure(error)) }
                    }
                }
            } catch (error: Exception) { if (!stopped.get()) issue("导入", importFailure(error)) }
            finally {
                val old = state.value
                val summary = "${if (stopped.get()) "已停止" else "导入结束"}：新增 ${old.saved} 个，已有 ${old.reused} 个" +
                    if (old.issueCount > 0) "，${old.issueCount} 项提示" else ""
                val message = summary + if (stopped.get()) "；重新选择可继续" else ""
                prefs.edit().putString(id, message + if (old.issueCount > 0) "。部分文件未导入或无法解析，可重新选择后重试。" else "").commit()
                stops.remove(id, stopped)
                state.value = old.copy(running = false, stopping = false, current = "", message = message)
            }
        }
        jobs[id] = job
        job.start()
    }

    private fun importFailure(error: Exception): String = when {
        error is SecurityException -> "没有读取权限，请重新选择文件或文件夹"
        error is IllegalArgumentException && error.message?.contains("64 MB") == true -> "文件超过 64 MB，请拆分后重试"
        error is IllegalArgumentException && error.message?.contains("相对路径") == true -> "文件夹层级或名称过长，请缩短后重试"
        error is java.io.IOException -> "文件读取或保存失败，请检查文件及设备剩余空间后重试"
        else -> "未完成，已保存文件仍保留；请重新选择资料后重试"
    }

    private fun readableText(mime: String) = mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/yaml")

    private fun guessMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "txt", "md", "csv", "json", "yaml", "yml", "xml", "html" -> "text/plain"
        else -> "application/octet-stream"
    }
}
