package com.openminis.app.data.creative

import com.openminis.app.novex.domain.CreativeArtifactKind

internal fun creativeArtifactExportName(record: CreativeArtifactRecord): String {
    val mimeType = record.revisions.maxByOrNull { it.number }?.mimeType.orEmpty()
    val sourceExtension = record.sourcePath
        ?.substringAfterLast('/', "")
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { it.matches(Regex("[a-z0-9]{1,12}")) }
    val extension = sourceExtension ?: extensionFor(record.artifact.kind, mimeType)
    val rawTitle = record.artifact.title.trim().ifBlank { "未命名成果" }
    val base = rawTitle.removeSuffix(".$extension")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-")
        .trim(' ', '.', '-')
        .ifBlank { "未命名成果" }
    return "$base.$extension"
}

internal fun creativeArtifactMimeType(record: CreativeArtifactRecord): String =
    record.revisions.maxByOrNull { it.number }?.mimeType?.takeIf(String::isNotBlank)
        ?: when (record.artifact.kind) {
            CreativeArtifactKind.IMAGE, CreativeArtifactKind.MAP -> "image/png"
            CreativeArtifactKind.DOCUMENT -> "text/plain"
            CreativeArtifactKind.CARD_ARCHIVE, CreativeArtifactKind.OTHER -> "application/octet-stream"
        }

internal fun nextAvailableCreativeArtifactName(
    requested: String,
    existingNames: Set<String>,
): String {
    if (requested !in existingNames) return requested
    val extension = requested.substringAfterLast('.', "").takeIf(String::isNotBlank)
    val base = if (extension == null) requested else requested.removeSuffix(".$extension")
    var suffix = 2
    while (true) {
        val candidate = if (extension == null) "$base ($suffix)" else "$base ($suffix).$extension"
        if (candidate !in existingNames) return candidate
        suffix += 1
    }
}

private fun extensionFor(kind: CreativeArtifactKind, mimeType: String): String = when (mimeType.lowercase()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/svg+xml" -> "svg"
    "text/markdown" -> "md"
    "text/plain" -> "txt"
    "text/html" -> "html"
    "application/pdf" -> "pdf"
    "application/json" -> "json"
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
    else -> when (kind) {
        CreativeArtifactKind.IMAGE, CreativeArtifactKind.MAP -> "png"
        CreativeArtifactKind.DOCUMENT -> "txt"
        CreativeArtifactKind.CARD_ARCHIVE -> "zip"
        CreativeArtifactKind.OTHER -> "bin"
    }
}
