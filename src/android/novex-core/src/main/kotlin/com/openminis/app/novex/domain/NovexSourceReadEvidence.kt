package com.openminis.app.novex.domain

import java.security.MessageDigest
import org.json.JSONArray

/** Evidence is produced from returned source slices, before compact response formatting. */
internal object NovexSourceReadEvidence {
    fun document(snapshot: NovexDocumentSnapshot, returned: List<Map<String, Any?>>, method: String): List<Map<String, Any?>> {
        var total = 0
        val offsets = snapshot.blocks.associate { block ->
            val offset = total
            total = Math.addExact(total, block.text.length)
            block.id to offset
        }
        val revision = sha256((snapshot.sha256 + "\n" + snapshot.parserVersion + "\n" +
            JSONArray(snapshot.blocks.map { listOf(it.id, it.text) }).toString()).toByteArray(Charsets.UTF_8))
        val ranges = returned.map { block ->
            val offset = (block["source"] as Map<*, *>)["char_offset"] as Int
            val start = offsets.getValue(block["id"] as String) + offset
            start to start + (block["text"] as String).length
        }
        val label = if (snapshot.status == NovexDocumentStatus.READY) "文档解析文本 · ${snapshot.title}"
            else "文档已提取部分（源文件未完整解析）· ${snapshot.title}"
        return listOf(source(snapshot.ref.value, label, revision, total, method, ranges))
    }

    fun source(id: String, label: String, revision: String, total: Int, method: String,
        ranges: List<Pair<Int, Int>>): Map<String, Any?> {
        val merged = mutableListOf<Pair<Int, Int>>()
        ranges.sortedBy { it.first }.forEach { range ->
            require(range.first >= 0 && range.second in range.first..total) { "返回的阅读范围超出原文" }
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.second) merged[merged.lastIndex] = previous.first to maxOf(previous.second, range.second)
            else merged += range
        }
        return mapOf("source_id" to id, "label" to label, "revision" to revision,
            "total_characters" to total, "method" to method,
            "ranges" to merged.ifEmpty { listOf(0 to 0) }.map { mapOf("start" to it.first, "end" to it.second) })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
