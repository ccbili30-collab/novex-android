package com.openminis.app.novex.domain

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/** Paged inventory and bounded keyword retrieval share the same branch visibility boundary. */
class NovexWorkspaceBrowser(private val scope: NovexConversationWorkspaceScope,
    private val store: NovexConversationWorkspaceStore,
    private val projectText: (NovexWorkspaceEntry, String) -> NovexWorkspaceTextProjection? = { _, _ -> null }) {
    fun browse(area: NovexWorkspaceArea?, path: String?, query: String?, cursor: String?,
        limit: Int, searchContent: Boolean = false): NovexToolResult {
        require(limit in 1..if (searchContent) 25 else 500) { if (searchContent) "搜索每批应返回 1 到 25 个命中文件" else "每页数量应在 1 到 500 之间" }
        require(query == null || query.length in 1..200) { "查找词应在 1 到 200 字之间" }
        require(!searchContent || !query.isNullOrBlank()) { "搜索正文需要提供关键词" }
        val prefix = path?.trim('/')?.takeIf { it.isNotBlank() }
        require(prefix == null || prefix.split('/').none { it == ".." || it == "." } && '\\' !in prefix) { "请使用仓库中的相对文件夹路径" }
        val all = store.inspect(scope).entries
        val candidates = all.filter { entry ->
            (area == null || entry.workspaceRef.area == area) &&
                (prefix == null || entry.workspaceRef.relativePath.startsWith("$prefix/")) &&
                (searchContent || query == null || entry.workspaceRef.relativePath.contains(query, ignoreCase = true)) &&
                // Parsed copies are available through their originals, not duplicate search results.
                !entry.workspaceRef.relativePath.startsWith(PARSED_PREFIX)
        }
        val signature = digest(buildString {
            // Tool replies add message ancestors without changing the visible files.
            // Bind paging to the effective inventory, not those empty branches.
            append(scope.conversationId).append(area).append(prefix).append(query).append(searchContent)
            all.forEach { append(it.workspaceRef.value).append(it.sha256) }
        })
        val start = if (cursor == null) 0 else runCatching {
            val value = JSONObject(String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8))
            require(value.getString("signature") == signature)
            value.getInt("offset").also { require(it in 0..candidates.size) }
        }.getOrElse { return NovexToolResult.failure("workspace.invalid_cursor", "目录、文件或查找条件已变化，请从第一页重新查看") }
        val unreadable = mutableListOf<String>()
        val entries = mutableListOf<Map<String, Any?>>()
        var examined = 0
        var scannedBytes = 0L
        val began = System.nanoTime()
        for (original in candidates.drop(start).take(if (searchContent) 1000 else limit)) {
            val readable = readableEntry(original, all)
            if (searchContent && examined > 0 && (entries.size >= limit ||
                    scannedBytes + (readable?.byteCount ?: 0) > 8 * 1_048_576L || System.nanoTime() - began > 2_000_000_000L)) break
            examined++
            val payload = linkedMapOf<String, Any?>(
                "workspace_ref" to original.workspaceRef.value, "path" to original.workspaceRef.relativePath,
                "area" to original.workspaceRef.area.wireName, "mime_type" to original.mimeType,
                "byte_count" to original.byteCount, "sha256" to original.sha256,
                "artifact_ref" to original.artifactRef?.value, "source_branch" to original.provenance.branchId,
                "source_message" to original.provenance.messageId, "source_refs" to original.provenance.sourceRefs.map { it.value },
                "created_at_millis" to original.createdAtMillis, "updated_at_millis" to original.updatedAtMillis,
                "readable_workspace_ref" to readable?.workspaceRef?.value,
                "reading_note" to if (readable != null && readable != original) "解析文本；不包含未识别的图片或排版，不代表原件已通读" else null,
            )
            if (!searchContent) { entries += payload; continue }
            if (readable == null) { unreadable += original.workspaceRef.value; continue }
            scannedBytes += readable.byteCount
            val originalText = runCatching { store.readBytes(scope, readable.workspaceRef).also {
                require(digestBytes(it) == readable.sha256)
            }.toString(Charsets.UTF_8) }.getOrNull()
            if (originalText == null) { unreadable += original.workspaceRef.value; continue }
            val projection = projectText(readable, originalText)
            val text = projection?.text ?: originalText
            val match = text.indexOf(query!!, ignoreCase = true)
            if (match < 0) continue
            payload["char_offset"] = match
            payload["snippet"] = text.substring(maxOf(0, match - 100), minOf(text.length, match + query.length + 220))
            payload["readable_sha256"] = if (projection == null) readable.sha256 else digest(text)
            if (projection != null) payload["reading_note"] = projection.label
            entries += payload
        }
        val next = start + examined
        val data = linkedMapOf<String, Any?>("scope" to "current_conversation_branch", "entries" to entries, "total_entries" to candidates.size,
            "examined_entries" to examined, "truncated" to (next < candidates.size), "unreadable_refs" to unreadable.take(limit), "unreadable_count" to unreadable.size)
        if (next < candidates.size) data["next_cursor"] = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject().put("signature", signature).put("offset", next).toString().toByteArray(Charsets.UTF_8))
        if (!searchContent) data["areas"] = NovexWorkspaceArea.entries.map { value -> mapOf(
            "name" to value.wireName, "model_writable" to value.modelWritable, "entry_count" to all.count { it.workspaceRef.area == value }) }
        return NovexToolResult.success(if (searchContent) "workspace.searched" else "workspace.ready",
            if (searchContent) "本批检查 ${examined} 个文件，命中 ${entries.size} 个；每个文件返回首个片段，搜索不等于通读" else "工作区共有 ${candidates.size} 个符合条件的文件",
            data = data, affectedRefs = emptyList())
    }

    companion object {
        const val PARSED_PREFIX = "import-parsed/"
        fun readableEntry(original: NovexWorkspaceEntry, entries: List<NovexWorkspaceEntry>): NovexWorkspaceEntry? =
            original.takeIf { isTextMimeType(it.mimeType) } ?: entries.firstOrNull {
                it.workspaceRef.area == NovexWorkspaceArea.DERIVED && it.workspaceRef.relativePath.startsWith(PARSED_PREFIX) &&
                    original.workspaceRef.asResourceRef() in it.provenance.sourceRefs }
        private fun digest(value: String) = digestBytes(value.toByteArray(Charsets.UTF_8))
        private fun digestBytes(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
