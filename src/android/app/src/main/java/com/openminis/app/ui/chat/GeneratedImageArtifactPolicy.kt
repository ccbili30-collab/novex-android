package com.openminis.app.ui.chat

import kotlin.math.roundToInt

/** A completed local image result that should be shown as chat content. */
internal data class GeneratedImageArtifact(
    val filePath: String,
    val title: String,
)

/** Pixel size of the visible image surface after proportional fitting. */
internal data class GeneratedImageFrame(
    val widthPx: Int,
    val heightPx: Int,
)

/**
 * Fit the frame inside the chat width and height cap without changing the
 * image's aspect ratio. The frame itself hugs the image, so portrait results
 * never sit inside a full-width white slab.
 */
internal fun generatedImageFrame(
    maxWidthPx: Int,
    maxHeightPx: Int,
    imageWidthPx: Int,
    imageHeightPx: Int,
): GeneratedImageFrame? {
    if (maxWidthPx <= 0 || maxHeightPx <= 0) return null
    if (imageWidthPx <= 0 || imageHeightPx <= 0) return null
    val scale = minOf(
        maxWidthPx.toDouble() / imageWidthPx.toDouble(),
        maxHeightPx.toDouble() / imageHeightPx.toDouble(),
    )
    return GeneratedImageFrame(
        widthPx = (imageWidthPx * scale).roundToInt().coerceAtLeast(1),
        heightPx = (imageHeightPx * scale).roundToInt().coerceAtLeast(1),
    )
}

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
