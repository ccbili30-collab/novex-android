package com.openminis.app.novex.domain

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Rebuildable file projection. Learning state remains authoritative; generated files are read-only to model tools. */
class NovexLearningWorkspaceProjection(
    private val store: NovexConversationWorkspaceStore,
    private val documents: NovexDocumentSnapshotStore,
) {
    fun publish(state: NovexLearningState, scope: NovexConversationWorkspaceScope,
        originalFiles: Map<NovexResourceRef, File> = emptyMap()): List<NovexWorkspaceEntry> {
        val prefix = "learning/${hash(state.collection.ref.value.toByteArray()).take(24)}"
        val existing = store.inspect(scope).entries.associateBy { it.workspaceRef }
        val entries = mutableListOf<NovexWorkspaceEntry>()
        fun put(area: NovexWorkspaceArea, path: String, bytes: ByteArray, mime: String, refs: List<NovexResourceRef>): NovexWorkspaceEntry {
            val ref = NovexWorkspaceFileRef.create(scope, area, "$prefix/$path")
            val sha = hash(bytes)
            val previous = existing[ref]
            val entry = if (previous?.sha256 == sha) previous else store.importArtifact(scope, area,
                "$prefix/$path", bytes, mime, NovexWorkspaceProvenance(scope.conversationId, scope.writeBranchId,
                    messageId = scope.writeBranchId, sourceRefs = (listOf(state.collection.ref) + refs).distinct()))
            entries += entry
            return entry
        }
        val sources = JSONArray()
        val topics = linkedMapOf<String, MutableList<JSONObject>>()
        val parsedEntries = mutableMapOf<String, NovexWorkspaceEntry>()
        state.collection.sources.forEach { source ->
            val item = JSONObject().put("source_ref", source.ref.value).put("title", source.title)
                .put("status", source.status.name).put("failure", source.failureCode)
            val archived = existing.values.firstOrNull { it.workspaceRef.area == NovexWorkspaceArea.SOURCES &&
                it.workspaceRef.relativePath.startsWith("$prefix/") && it.sha256 == source.sha256 &&
                runCatching { hash(store.readBytes(scope, it.workspaceRef)) == source.sha256 }.getOrDefault(false) }
            archived?.let { item.put("original_workspace_ref", it.workspaceRef.value) }
            val missingKey = if (archived == null) "original_missing_reason" else "original_attachment_warning"
            val original = originalFiles[source.ref]
            if (original?.isFile == true && original.length() <= FileNovexConversationWorkspaceStore.MAX_ARTIFACT_BYTES) {
                val bytes = original.readBytes()
                if (hash(bytes) == source.sha256) {
                    val ext = source.title.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }.orEmpty()
                    val entry = put(NovexWorkspaceArea.SOURCES, "originals/${label(source.title.substringBeforeLast('.'))}-${source.sha256}.${ext.ifBlank { "bin" }}",
                        bytes, when (ext) { "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            "pdf" -> "application/pdf"; "txt", "md" -> "text/plain"; else -> "application/octet-stream" }, listOf(source.ref))
                    item.put("original_workspace_ref", entry.workspaceRef.value)
                } else item.put(missingKey, "原附件校验值已变化，不用它替换旧来源")
            } else item.put(missingKey, "原附件不可用或超过工作区单文件上限；解析正文不等于原附件")
            val ref = source.documentRef
            val expected = ref?.let { documentRef -> state.task?.preflight?.documentRevisions?.get(documentRef)
                ?: state.notes.firstNotNullOfOrNull { it.sourceRevisions[documentRef] } }
            val document = ref?.let { if (expected == null) documents.find(it) else documents.findRevision(it, expected) }
            if (document != null) {
                val revision = NovexSourceReadEvidence.documentRevision(document)
                val body = buildString {
                    append("# ${source.title}\n\n解析正文；原附件中的图片或排版可能未解析。\n")
                    append("来源：${document.ref.value}\n解析修订：$revision\n\n")
                    document.blocks.forEach { block ->
                        append("<!-- 内容块 ${block.id} -->\n").append(block.text).append("\n\n")
                        val heading = block.headingPath.joinToString(" / ").ifBlank { "未标章节 · ${source.title}" }
                        topics.getOrPut(heading) { mutableListOf() }.add(JSONObject().put("document_ref", document.ref.value)
                            .put("source_revision", revision).put("block_id", block.id))
                    }
                }
                val entry = put(NovexWorkspaceArea.DERIVED, "sources/${label(source.title)}-$revision.md", body.toByteArray(), "text/markdown", listOf(document.ref))
                parsedEntries[revision] = entry
                item.put("parsed_workspace_ref", entry.workspaceRef.value).put("source_revision", revision)
            } else item.put("parsed_missing_reason", "采用的解析修订不可读")
            sources.put(item)
        }
        val noteItems = JSONArray()
        fun note(note: NovexLearningNote, historical: Boolean) {
            val missing = mutableListOf<String>()
            val sourceFiles = note.sourceRevisions.mapNotNull { (ref, revision) ->
                val entry = parsedEntries[revision] ?: documents.findRevision(ref, revision)?.let { document ->
                    val body = "# ${document.title}\n\n历史解析正文，修订：$revision\n来源：${ref.value}\n\n" +
                        document.blocks.joinToString("\n\n") { "<!-- 内容块 ${it.id} -->\n${it.text}" }
                    put(NovexWorkspaceArea.DERIVED, "sources/${label(document.title)}-$revision.md", body.toByteArray(),
                        "text/markdown", listOf(ref)).also { parsedEntries[revision] = it }
                }
                if (entry == null) { missing += ref.value; null } else ref.value to entry.workspaceRef.value
            }.toMap()
            val metadata = JSONObject().put("note_ref", note.ref.value).put("title", note.title).put("level", note.level.wireName)
                .put("historical", historical).put("source_revisions", JSONObject(note.sourceRevisions.mapKeys { it.key.value }))
                .put("source_document_refs", JSONArray(note.sourceDocumentRefs.map { it.value }))
                .put("source_block_ids", JSONArray(note.sourceBlockIds)).put("input_note_refs", JSONArray(note.inputNoteRefs.map { it.value }))
                .put("parsed_workspace_refs", JSONObject(sourceFiles)).put("missing_source_revisions", JSONArray(missing))
                .put("source_revision_status", if (note.sourceRevisions.keys.containsAll(note.sourceDocumentRefs)) "recorded" else "legacy_unknown")
            val text = "# ${note.title}\n\n模型整理，尚未核验；${if (historical) "历史成果，不计入当前整理覆盖" else "已保存不代表事实已经核验"}。\n\n" +
                note.body + "\n\n## 来源记录\n\n```json\n${metadata.toString(2)}\n```\n"
            val id = hash((note.ref.value + "\n" + text).toByteArray())
            val entry = put(NovexWorkspaceArea.DERIVED, "${if (historical) "history" else "notes"}/${label(note.title)}-$id.md",
                text.toByteArray(), "text/markdown", listOf(note.ref) + note.sourceDocumentRefs)
            noteItems.put(metadata.put("workspace_ref", entry.workspaceRef.value))
        }
        state.notes.forEach { note(it, false) }
        state.historicalNotes.forEach { note(it, true) }
        val index = JSONObject().put("collection_ref", state.collection.ref.value).put("title", state.collection.title)
            .put("description", "主题入口按原文标题建立，未命名内容按文件归组；不是模型新增事实或全文已核验声明。此清单标明当前与历史成果，目录中旧文件不自动成为当前资料。")
            .put("reviewed_blocks", state.reviewLedger.reviewedBlocks).put("readable_blocks", state.reviewLedger.totalReadableBlocks)
            .put("last_failure", state.lastFailure).put("sources", sources).put("notes", noteItems)
            .put("topics", JSONArray(topics.map { (title, anchors) -> JSONObject().put("title", title).put("anchors", JSONArray(anchors)) }))
        put(NovexWorkspaceArea.DERIVED, "资料与主题索引.json", index.toString(2).toByteArray(), "application/json", emptyList())
        return entries.distinctBy { it.workspaceRef }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun label(value: String) = value.map { if (it.isLetterOrDigit() || it in " ._-（）·") it else '_' }
        .joinToString("").take(40).ifBlank { "资料" }
}
