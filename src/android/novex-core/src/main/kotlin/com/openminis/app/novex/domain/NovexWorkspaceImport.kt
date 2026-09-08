package com.openminis.app.novex.domain

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Native import, independent of messages or model execution. Existing originals are never overwritten. */
class NovexWorkspaceImport(private val store: NovexConversationWorkspaceStore) {
    data class Saved(val entry: NovexWorkspaceEntry, val reused: Boolean)

    fun save(conversationId: String, path: String, mimeType: String, input: InputStream): Saved {
        val scope = scope(conversationId)
        val safePath = path.split('/').joinToString("/") { segment ->
            segment.map { if (it.isISOControl() || it == '\\') '_' else it }.joinToString("")
                .replace("..", "_").ifBlank { "未命名文件" }.let { if (it == "." || it == "..") "未命名文件" else it }
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("导入已停止")
            val size = input.read(buffer)
            if (size < 0) break
            require(output.size().toLong() + size <= FileNovexConversationWorkspaceStore.MAX_ARTIFACT_BYTES) { "文件超过 64 MB，未导入；请拆分后重试" }
            output.write(buffer, 0, size)
        }
        val bytes = output.toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entries = store.inspect(scope).entries.filter { it.workspaceRef.area == NovexWorkspaceArea.SOURCES }
        var candidate = "imports/$safePath"
        var suffix = 2
        val fileName = safePath.substringAfterLast('/')
        val parent = safePath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
        val stem = parent + if (extension == null) fileName else fileName.substringBeforeLast('.')
        while (true) {
            val existing = entries.firstOrNull { it.workspaceRef.relativePath == candidate } ?: break
            if (existing.sha256 == hash) return Saved(existing, true)
            candidate = "imports/$stem (${suffix++})" + (extension?.let { ".$it" } ?: "")
        }
        return Saved(store.importArtifact(scope, NovexWorkspaceArea.SOURCES, candidate, bytes, mimeType,
            NovexWorkspaceProvenance(conversationId, scope.writeBranchId)), false)
    }

    fun saveParsed(original: NovexWorkspaceEntry, text: String) {
        val scope = scope(original.workspaceRef.conversationId)
        // Original reference scopes the cache even when two documents have identical bytes.
        val key = MessageDigest.getInstance("SHA-256").digest(original.workspaceRef.value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        store.importArtifact(scope, NovexWorkspaceArea.DERIVED, "${NovexWorkspaceBrowser.PARSED_PREFIX}$key.txt",
            text.toByteArray(Charsets.UTF_8), "text/plain", NovexWorkspaceProvenance(scope.conversationId,
                scope.writeBranchId, sourceRefs = listOf(original.workspaceRef.asResourceRef())))
    }

    companion object {
        fun scope(conversationId: String) = NovexConversationWorkspaceScope(conversationId, emptyList(), NovexConversationWorkspaceScope.ROOT_BRANCH)
    }
}
