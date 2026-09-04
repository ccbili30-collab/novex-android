package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexCreativeLibraryTest {
    @Test
    fun `an artifact keeps its origin and cannot be deleted while content still references it`() {
        val artifact = CreativeArtifact(
            id = "artifact-1",
            kind = CreativeArtifactKind.IMAGE,
            title = "云岚地图",
            storageKey = "sha256/abc.png",
            origin = CreativeArtifactOrigin(
                conversationId = "conversation-1",
                branchId = "branch-1",
                messageId = "message-1",
                toolCallId = "tool-1",
            ),
        )
        val worldAttachment = CreativeArtifactAttachment(
            artifactId = artifact.id,
            owner = NovexContentAddress.world("world-1"),
            moduleId = "map-module",
            slot = "image",
        )
        val characterAttachment = CreativeArtifactAttachment(
            artifactId = artifact.id,
            owner = NovexContentAddress.characterVersion("version-1"),
            moduleId = "experience-module",
            slot = "illustration",
        )

        val library = NovexCreativeLibrary.empty()
            .apply(NovexCreativeLibraryCommand.RegisterArtifact(artifact))
            .apply(NovexCreativeLibraryCommand.AttachArtifact(worldAttachment))
            .apply(NovexCreativeLibraryCommand.AttachArtifact(characterAttachment))
        val withoutWorld = library.apply(
            NovexCreativeLibraryCommand.DetachArtifact(worldAttachment),
        )

        assertEquals(artifact.origin, withoutWorld.snapshot.artifacts.getValue(artifact.id).origin)
        assertEquals(listOf(characterAttachment), withoutWorld.snapshot.attachments)
        assertThrows(IllegalArgumentException::class.java) {
            withoutWorld.apply(NovexCreativeLibraryCommand.DeleteArtifact(artifact.id))
        }

        val deleted = withoutWorld
            .apply(NovexCreativeLibraryCommand.DetachArtifact(characterAttachment))
            .apply(NovexCreativeLibraryCommand.DeleteArtifact(artifact.id))
        assertTrue(deleted.snapshot.artifacts.isEmpty())
    }

    @Test
    fun `stored creative library rejects dangling content attachments when reopened`() {
        val dangling = CreativeArtifactAttachment(
            artifactId = "missing-artifact",
            owner = NovexContentAddress.world("world-1"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            NovexCreativeLibrary.open(
                NovexCreativeLibrarySnapshot(attachments = listOf(dangling)),
            )
        }
    }
}
