package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeneratedImageArtifactPolicyTest {
    @Test
    fun successfulGeneratedImageBecomesAVisibleArtifact() {
        val artifact = generatedImageArtifact(
            AssistantBlock(
                id = "image-1",
                kind = "tool_use",
                toolName = "generate_image",
                toolTitle = "生成插画",
                toolStatus = ToolBlockStatus.SUCCESS,
                imageFilePath = "/data/user/0/com.noven.player/files/generated/result.png",
            ),
        )

        assertEquals(
            GeneratedImageArtifact(
                filePath = "/data/user/0/com.noven.player/files/generated/result.png",
                title = "生成插画",
            ),
            artifact,
        )
    }

    @Test
    fun failedOrMissingGeneratedImageDoesNotCreateABlankArtifact() {
        assertNull(
            generatedImageArtifact(
                AssistantBlock(
                    id = "image-2",
                    kind = "tool_use",
                    toolName = "generate_image",
                    toolStatus = ToolBlockStatus.FAILED,
                    imageFilePath = "/tmp/failed.png",
                ),
            ),
        )
        assertNull(
            generatedImageArtifact(
                AssistantBlock(
                    id = "image-3",
                    kind = "tool_use",
                    toolName = "generate_image",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ),
            ),
        )
    }

    @Test
    fun unrelatedToolScreenshotIsNotDuplicatedAsGeneratedContent() {
        assertNull(
            generatedImageArtifact(
                AssistantBlock(
                    id = "browser-1",
                    kind = "tool_use",
                    toolName = "browser_use",
                    toolStatus = ToolBlockStatus.SUCCESS,
                    imageFilePath = "/tmp/browser.jpg",
                ),
            ),
        )
    }
}
