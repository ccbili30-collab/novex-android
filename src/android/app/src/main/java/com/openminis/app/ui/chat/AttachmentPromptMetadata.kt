package com.openminis.app.ui.chat

internal data class UserAttachedFilePromptMeta(
    val linuxPath: String,
    val size: Long,
    val modifiedIso: String,
    val extractedTextPath: String? = null,
    val extractedFormat: String? = null,
    val extractedText: String? = null,
)

private const val MAX_INLINE_EXTRACTED_CHARS_PER_FILE = 48_000
private const val MAX_INLINE_EXTRACTED_CHARS_TOTAL = 96_000

/**
 * Makes extracted office-document text available in the user turn itself.
 * The on-disk path remains as a paging fallback for large documents, but a
 * small DOCX no longer depends on the Linux sandbox starting successfully.
 */
internal fun buildUserAttachedFilesPrompt(
    metas: List<UserAttachedFilePromptMeta>,
): String? {
    if (metas.isEmpty()) return null
    var remainingInlineBudget = MAX_INLINE_EXTRACTED_CHARS_TOTAL
    return buildString {
        append("<user-attached-files>\n")
        for (meta in metas) {
            val originalText = meta.extractedText.orEmpty()
            val inlineLength = minOf(
                originalText.length,
                MAX_INLINE_EXTRACTED_CHARS_PER_FILE,
                remainingInlineBudget,
            )
            val inlineText = originalText.take(inlineLength)
            val truncated = inlineLength < originalText.length
            remainingInlineBudget -= inlineLength

            append("  <file path=\"")
            append(escapeXmlAttribute(meta.linuxPath))
            append("\" url=\"minis://")
            append(escapeXmlAttribute(meta.linuxPath.removePrefix("/var/minis/")))
            append("\" size=\"")
            append(meta.size)
            append("\" modified=\"")
            append(escapeXmlAttribute(meta.modifiedIso))
            meta.extractedTextPath?.let { path ->
                append("\" extracted_text_path=\"")
                append(escapeXmlAttribute(path))
                append("\" extracted_format=\"")
                append(escapeXmlAttribute(meta.extractedFormat.orEmpty()))
                append("\" extracted_text_truncated=\"")
                append(truncated)
            }
            if (inlineText.isEmpty()) {
                append("\" />\n")
            } else {
                append("\">\n")
                append("    <extracted_text><![CDATA[")
                append(inlineText.replace("]]>", "]]]]><![CDATA[>"))
                if (truncated) {
                    append("\n\n[正文已截断；剩余内容请按 extracted_text_path 分页读取]")
                }
                append("]]></extracted_text>\n")
                append("  </file>\n")
            }
        }
        append("</user-attached-files>")
    }
}

private fun escapeXmlAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
