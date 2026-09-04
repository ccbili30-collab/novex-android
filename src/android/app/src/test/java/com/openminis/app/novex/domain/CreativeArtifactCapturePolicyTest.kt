package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreativeArtifactCapturePolicyTest {
    @Test
    fun `successful image generation becomes an image artifact candidate`() {
        val capture = CreativeArtifactCapturePolicy.fromToolResult(
            toolName = "generate_image",
            argsJson = """{"tool_title":"绘制云岚地图"}""",
            success = true,
            imageBytes = byteArrayOf(1, 2, 3),
            imageMimeType = "image/png",
            imageHostPath = "/tmp/generated.png",
        )

        assertEquals(CreativeArtifactKind.IMAGE, capture?.kind)
        assertEquals("绘制云岚地图", capture?.title)
        assertEquals("/tmp/generated.png", capture?.sourcePath)
    }

    @Test
    fun `file writes and edits share the source path so later edits create revisions`() {
        val write = CreativeArtifactCapturePolicy.fromToolResult(
            toolName = "file_write",
            argsJson = """{"path":"/var/minis/workspace/chapter-1.md","tool_title":"写第一章"}""",
            success = true,
        )
        val edit = CreativeArtifactCapturePolicy.fromToolResult(
            toolName = "file_edit",
            argsJson = """{"path":"/var/minis/workspace/chapter-1.md","tool_title":"修改第一章"}""",
            success = true,
        )

        assertEquals(CreativeArtifactKind.DOCUMENT, write?.kind)
        assertEquals(write?.sourcePath, edit?.sourcePath)
    }

    @Test
    fun `failed tools and ordinary shell output never become artifacts`() {
        assertNull(
            CreativeArtifactCapturePolicy.fromToolResult(
                toolName = "file_write",
                argsJson = """{"path":"/tmp/nope.txt"}""",
                success = false,
            ),
        )
        assertNull(
            CreativeArtifactCapturePolicy.fromToolResult(
                toolName = "shell_execute",
                argsJson = """{"command":"echo hi"}""",
                success = true,
            ),
        )
    }
}
