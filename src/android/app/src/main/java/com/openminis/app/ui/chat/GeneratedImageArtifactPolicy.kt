package com.openminis.app.ui.chat

/** A completed local image result that should be shown as chat content. */
internal data class GeneratedImageArtifact(
    val filePath: String,
    val title: String,
)

/**
 * Keep tool transport data separate from presentation. Only a successful
 * generate_image result with a persisted host path becomes an image artifact.
 */
internal fun generatedImageArtifact(block: AssistantBlock): GeneratedImageArtifact? {
    if (block.toolName != "generate_image") return null
    if (block.toolStatus != ToolBlockStatus.SUCCESS) return null
    val filePath = block.imageFilePath?.trim().orEmpty()
    if (filePath.isEmpty()) return null
    return GeneratedImageArtifact(
        filePath = filePath,
        title = block.toolTitle.ifBlank { "Generated image" },
    )
}
