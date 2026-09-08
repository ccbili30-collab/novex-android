package com.openminis.app.novex.domain

/** Host reads user-provided attachments when the model has no tool interface. */
class NovexReadOnlyAttachmentContext(private val documents: NovexDocumentSnapshotStore) {
    fun candidates(mode: NovexExecutionMode, providedRefs: List<NovexResourceRef>): List<NovexContextCandidate> {
        if (mode != NovexExecutionMode.READ_ONLY) return emptyList()
        return providedRefs.distinct().mapIndexed { index, ref ->
            val document = documents.find(ref)
            val text = if (document == null) "本对话提供的这份附件无法恢复，未提供正文。" else buildString {
                if (document.blocks.isEmpty()) appendLine("该附件尚未解析出可读正文，不能声称已经阅读。")
                document.warnings.forEach { appendLine("解析说明：${it.message}") }
                document.blocks.forEach { appendLine(it.text) }
            }
            NovexContextCandidate(sourceId = ref.value,
                label = "附件 · ${document?.title ?: "未能恢复的文件"}", content = text,
                kind = ContextSourceKind.ATTACHED_DOCUMENT, alwaysInclude = true,
                position = Int.MIN_VALUE + 100 + index)
        }
    }
}
