package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Copies parsed source blocks into ordinary native modules; never regenerates source text. */
class NovexCardSourceModules(
    private val documents: NovexDocumentSnapshotStore,
    private val isAllowed: (NovexResourceRef) -> Boolean,
) {
    fun document(reference: String, revision: String): NovexDocumentSnapshot {
        val ref = NovexResourceRef(reference)
        require(isAllowed(ref)) { "来源文档不在当前对话可读目录中" }
        require(revision.isNotBlank()) { "请先检查文档，并携带 source_revision（来源修订）" }
        return requireNotNull(documents.findRevision(ref, revision)) { "该文档修订不可用，请重新检查来源；没有写入卡片" }
    }

    fun chapters(reference: String, revision: String): JSONArray {
        val source = document(reference, revision)
        require(source.blocks.isNotEmpty()) { "来源没有可复制的正文" }
        val headings = NovexDocumentOutline.entries(source)
        val level = headings.minOfOrNull { it.level }
        val roots = headings.filter { it.level == level }
        val ranges = mutableListOf<Triple<String, Int, Int>>()
        if (roots.isEmpty()) ranges += Triple(source.title, 1, source.blocks.size)
        else {
            if (roots.first().firstBlock > 1) ranges += Triple("篇首资料", 1, roots.first().firstBlock - 1)
            roots.forEach { ranges += Triple(it.title, it.firstBlock, it.lastBlock) }
        }
        require(ranges.size <= 1000) { "来源章节超过单卡一千模块范围，请明确分卡范围" }
        return JSONArray(ranges.map { (name, first, last) ->
            JSONObject().put("module_type", "custom").put("name", name)
                .put("content_json", content(source, revision, first, last))
        })
    }

    fun range(reference: String, revision: String, first: Int, last: Int): JSONObject =
        content(document(reference, revision), revision, first, last)

    private fun content(source: NovexDocumentSnapshot, revision: String, first: Int, last: Int): JSONObject {
        require(first >= 1 && last >= first && last <= source.blocks.size) { "来源块范围越界，请使用文档目录的块序号" }
        val selected = source.blocks.subList(first - 1, last)
        require(selected.none { it.mediaRef != null }) { "范围包含媒体，请先单独处理附件，不能把图片冒充已复制正文" }
        return JSONObject().put("version", 1).put("kind", "article")
            .put("text", selected.joinToString("\n") { it.text })
            .put("_novexSource", JSONObject().put("document_ref", source.ref.value)
                .put("source_revision", revision).put("first_block", first).put("last_block", last)
                .put("coverage", "已解析文本；未解析附件与原文件版式不在此范围"))
    }
}
