package com.openminis.app.ui.chat

import com.openminis.app.novex.domain.NovexDocumentPromptReceipt
import com.openminis.app.novex.domain.NovexDocumentSnapshot
import com.openminis.app.novex.domain.NovexResourceRef
import com.openminis.app.novex.domain.NovexSourceCollectionPromptReceipt

internal data class UserAttachedFilePromptMeta(
    val linuxPath: String,
    val size: Long,
    val modifiedIso: String,
    val documentSnapshot: NovexDocumentSnapshot? = null,
)

/**
 * Describes attachments without injecting extracted document bodies.
 * Parsed documents use stable Novex references and bounded outline receipts;
 * unparsed files retain metadata compatibility until the workspace layer replaces it.
 */
internal fun buildUserAttachedFilesPrompt(
    metas: List<UserAttachedFilePromptMeta>,
    sourceCollectionRef: NovexResourceRef? = null,
    sourceCount: Int = 0,
): String? {
    require((sourceCollectionRef == null) == (sourceCount == 0)) {
        "资料集引用与来源数量必须同时提供"
    }
    require(sourceCount >= 0) { "资料来源数量不能为负数" }
    if (metas.isEmpty() && sourceCollectionRef == null) return null
    val documents = metas.mapNotNull(UserAttachedFilePromptMeta::documentSnapshot)
    val ordinaryFiles = metas.filter { it.documentSnapshot == null }
    return buildString {
        if (ordinaryFiles.isNotEmpty()) {
            append("<user-attached-files>\n")
            ordinaryFiles.forEach { meta ->
                append("  <file path=\"")
                append(escapeXmlAttribute(meta.linuxPath))
                append("\" url=\"minis://")
                append(escapeXmlAttribute(meta.linuxPath.removePrefix("/var/minis/")))
                append("\" size=\"")
                append(meta.size)
                append("\" modified=\"")
                append(escapeXmlAttribute(meta.modifiedIso))
                append("\" />\n")
            }
            append("</user-attached-files>")
            if (documents.isNotEmpty()) append('\n')
        }
        if (documents.isNotEmpty()) append(NovexDocumentPromptReceipt.build(documents))
        if (sourceCollectionRef != null) {
            if (isNotEmpty()) append('\n')
            append(NovexSourceCollectionPromptReceipt.build(sourceCollectionRef, sourceCount))
        }
    }
}

private val NOVEX_DOCUMENT_REF_PATTERN = Regex(
    "ref=\\\"(novex://documents/[0-9a-fA-F]{64})\\\"",
)

/** Recovers capability state from a persisted receipt without trusting arbitrary paths. */
internal fun novexDocumentRefsInPrompt(prompt: String): Set<String> {
    if (!prompt.contains("<novex-document-receipts>")) return emptySet()
    return NOVEX_DOCUMENT_REF_PATTERN.findAll(prompt)
        .map { match -> match.groupValues[1].lowercase() }
        .toSet()
}

/** Recovers only validated branch-scoped source collection capabilities. */
internal fun novexSourceCollectionRefsInPrompt(prompt: String): Set<String> =
    NovexSourceCollectionPromptReceipt.refsIn(prompt).mapTo(linkedSetOf()) { it.value }

private fun escapeXmlAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
