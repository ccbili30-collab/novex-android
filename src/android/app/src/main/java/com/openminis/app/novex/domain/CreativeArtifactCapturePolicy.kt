package com.openminis.app.novex.domain

import java.io.File
import org.json.JSONObject

data class CreativeArtifactCapture(
    val kind: CreativeArtifactKind,
    val title: String,
    val sourcePath: String?,
    val imageBytes: ByteArray? = null,
    val mimeType: String? = null,
)

/** Converts only explicit successful file-producing tools into durable-library candidates. */
object CreativeArtifactCapturePolicy {
    fun fromToolResult(
        toolName: String,
        argsJson: String,
        success: Boolean,
        imageBytes: ByteArray? = null,
        imageMimeType: String? = null,
        imageHostPath: String? = null,
    ): CreativeArtifactCapture? {
        if (!success) return null
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        return when (toolName) {
            "generate_image" -> {
                val bytes = imageBytes ?: return null
                CreativeArtifactCapture(
                    kind = CreativeArtifactKind.IMAGE,
                    title = args.optString("tool_title").ifBlank { "生成图片" },
                    sourcePath = imageHostPath,
                    imageBytes = bytes,
                    mimeType = imageMimeType ?: "image/png",
                )
            }
            "file_write", "file_edit" -> {
                val path = args.optString("path").ifBlank { args.optString("file_path") }.trim()
                if (path.isEmpty()) return null
                CreativeArtifactCapture(
                    kind = kindForPath(path),
                    title = args.optString("tool_title").ifBlank { File(path).name },
                    sourcePath = path,
                )
            }
            else -> null
        }
    }

    private fun kindForPath(path: String): CreativeArtifactKind = when (File(path).extension.lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "svg" -> CreativeArtifactKind.IMAGE
        "novexworld", "novexcharacter", "novexgame" -> CreativeArtifactKind.CARD_ARCHIVE
        "md", "markdown", "txt", "docx", "odt", "rtf", "html", "htm", "pdf" ->
            CreativeArtifactKind.DOCUMENT
        else -> CreativeArtifactKind.OTHER
    }
}
