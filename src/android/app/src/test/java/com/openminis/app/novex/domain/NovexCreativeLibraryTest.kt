package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `artifact revisions favorites and recycle bin preserve one durable identity`() {
        val artifact = CreativeArtifact(
            id = "artifact-1",
            kind = CreativeArtifactKind.DOCUMENT,
            title = "第一章",
            storageKey = "sha256/first.md",
            origin = CreativeArtifactOrigin("conversation-1", "branch-1"),
            createdAt = 10L,
        )
        val first = CreativeArtifactRevision(
            id = "revision-1",
            artifactId = artifact.id,
            number = 1,
            storageKey = artifact.storageKey,
            contentHash = "hash-1",
            mimeType = "text/markdown",
            sizeBytes = 12L,
            createdAt = 10L,
        )
        val second = first.copy(
            id = "revision-2",
            number = 2,
            storageKey = "sha256/second.md",
            contentHash = "hash-2",
            sizeBytes = 24L,
            createdAt = 20L,
        )

        val updated = NovexCreativeLibrary.empty()
            .apply(NovexCreativeLibraryCommand.RegisterArtifact(artifact, first))
            .apply(NovexCreativeLibraryCommand.AddRevision(artifact.id, second))
            .apply(NovexCreativeLibraryCommand.SetFavorite(artifact.id, true))
            .apply(NovexCreativeLibraryCommand.MoveToTrash(artifact.id, 30L))

        assertEquals(listOf(first, second), updated.snapshot.revisions.getValue(artifact.id))
        assertEquals(second.storageKey, updated.snapshot.artifacts.getValue(artifact.id).storageKey)
        assertTrue(updated.snapshot.artifacts.getValue(artifact.id).favorite)
        assertEquals(30L, updated.snapshot.artifacts.getValue(artifact.id).trashedAt)

        val restored = updated.apply(NovexCreativeLibraryCommand.RestoreArtifact(artifact.id))
        assertFalse(restored.snapshot.artifacts.getValue(artifact.id).isTrashed)
    }

    @Test
    fun `permanent deletion remains blocked in recycle bin while a module references the artifact`() {
        val artifact = CreativeArtifact(
            id = "artifact-1",
            kind = CreativeArtifactKind.MAP,
            title = "世界地图",
            storageKey = "sha256/map.png",
            origin = CreativeArtifactOrigin("conversation-1", "branch-1"),
        )
        val attachment = CreativeArtifactAttachment(
            artifactId = artifact.id,
            owner = NovexContentAddress.world("world-1"),
            moduleId = "map-module",
        )
        val library = NovexCreativeLibrary.empty()
            .apply(NovexCreativeLibraryCommand.RegisterArtifact(artifact))
            .apply(NovexCreativeLibraryCommand.AttachArtifact(attachment))
            .apply(NovexCreativeLibraryCommand.MoveToTrash(artifact.id, 100L))

        assertThrows(IllegalArgumentException::class.java) {
            library.apply(NovexCreativeLibraryCommand.DeleteArtifact(artifact.id))
        }
    }
}
