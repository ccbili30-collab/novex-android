package com.openminis.app.novex.domain

/** Exact source excerpts travel beside generated notes, so a note cannot stand in for its evidence.
 * No notes, coverage, source files or model calls are mutated by this read projection. */
internal object NovexLearningNoteEvidence {
    fun read(state: NovexLearningState, notes: List<NovexLearningNote>, maxChars: Int,
        resolve: (NovexResourceRef, NovexResourceRef, String) -> NovexDocumentSnapshot?): Map<String, Any> {
        var remaining = maxChars
        val excerpts = mutableListOf<Map<String, Any>>()
        val unavailable = linkedSetOf<String>()
        val seen = mutableSetOf<String>()
        val loaded = mutableMapOf<Pair<NovexResourceRef, String>, NovexDocumentSnapshot?>()
        var pending = false
        val allowed = state.collection.uniqueDocumentRefs.toSet()
        // A directly matching section is more useful evidence than the broad overview alongside it.
        for (note in notes.sortedBy { it.sourceBlockIds.size }) for (ref in note.sourceDocumentRefs) {
            val revision = note.sourceRevisions[ref]
            if (ref !in allowed || revision == null) { unavailable += ref.value; continue }
            if (remaining <= 0) { pending = true; continue }
            val sourceKey = ref to revision
            if (sourceKey !in loaded && loaded.size >= 16) { pending = true; continue }
            if (sourceKey !in loaded) loaded[sourceKey] = resolve(state.collection.ref, ref, revision)?.takeIf {
                it.ref == ref && NovexSourceReadEvidence.documentRevision(it) == revision
            }
            val source = loaded[sourceKey]
            if (source == null) { unavailable += ref.value; continue }
            val ids = note.sourceBlockIds.toSet()
            if (ids.isEmpty()) { unavailable += ref.value; continue }
            val rangesByBlock = note.readRanges.filter { it.documentRef == ref }.groupBy { it.blockId }
            for (block in source.blocks.filter { it.id in ids }) {
                if (remaining <= 0 || excerpts.size >= 128) { pending = true; break }
                val ranges = rangesByBlock[block.id].orEmpty()
                    .map { it.start to it.end }.ifEmpty { listOf(0 to block.text.length) }
                for ((start, end) in ranges) {
                    if (!seen.add("${ref.value}|$revision|${block.id}|$start|$end")) continue
                    if (start < 0 || end < start || end > block.text.length || block.mediaRef != null) {
                        unavailable += ref.value; continue
                    }
                    if (remaining <= 0 || excerpts.size >= 128) { pending = true; break }
                    val until = minOf(end, start + remaining)
                    val text = block.text.substring(start, until)
                    remaining -= text.length
                    if (until < end) pending = true
                    excerpts += mapOf("document_ref" to ref.value, "source_revision" to revision,
                        "title" to source.title, "block_id" to block.id, "start" to start, "end" to until,
                        "source_range_end" to end, "text" to text, "truncated" to (until < end))
                }
            }
            if (source.blocks.none { it.id in ids }) unavailable += ref.value
        }
        val observations = excerpts.groupBy { it["document_ref"] to it["source_revision"] }.flatMap { (key, slices) ->
            val source = loaded[NovexResourceRef(key.first as String) to (key.second as String)] ?: return@flatMap emptyList()
            NovexSourceReadEvidence.document(source, slices.map { mapOf("id" to it["block_id"], "text" to it["text"],
                "source" to mapOf("char_offset" to it["start"])) }, "READ")
        }
        return mapOf("kind" to "exact_source_excerpts", "excerpts" to excerpts, "read_observations" to observations,
            "returned_characters" to (maxChars - remaining), "more_source_available" to pending,
            "unavailable_source_refs" to unavailable.toList(),
            "scope" to "仅以上返回区间为本次回读依据；笔记是未经事实核验的模型整理，不证明原文存在或不存在某事。其余内容沿 document_ref、source_revision、block_id 使用 document_read 继续读取。")
    }
}
