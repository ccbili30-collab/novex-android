package com.openminis.app.data.attachments

import com.openminis.app.novex.domain.NovexSourceCollectionPromptReceipt

private val AGENT_ATTACHMENT_ENVELOPES = listOf(
    "user-attached-files",
    "novex-document-receipts",
)

/** Removes model-only attachment receipts from user-visible text, titles and exports. */
fun stripAgentAttachmentMetadata(text: String): String {
    var cleaned = text
    AGENT_ATTACHMENT_ENVELOPES.forEach { tag ->
        while (true) {
            val startTag = "<$tag>"
            val start = cleaned.indexOf(startTag)
            if (start < 0) break
            val endTag = "</$tag>"
            val end = cleaned.indexOf(endTag, start)
            // Only a complete application-owned envelope is metadata. A user can
            // legitimately type a tag-like fragment; truncating everything after
            // an unmatched opener loses visible conversation content and exports.
            if (end < 0) break
            cleaned = cleaned.substring(0, start) + cleaned.substring(end + endTag.length)
        }
    }
    cleaned = NovexSourceCollectionPromptReceipt.stripFrom(cleaned)
    return cleaned.trim()
}

/** True when a persisted text part is metadata that must remain model-visible but UI-hidden. */
fun containsAgentAttachmentMetadata(text: String): Boolean =
    AGENT_ATTACHMENT_ENVELOPES.any { tag -> text.contains("<$tag>") } ||
        NovexSourceCollectionPromptReceipt.contains(text)
