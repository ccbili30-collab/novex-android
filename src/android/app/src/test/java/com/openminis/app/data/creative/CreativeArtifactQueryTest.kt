package com.openminis.app.data.creative

import novex.core.CreativeArtifact
import novex.core.CreativeArtifactAttachment
import novex.core.CreativeArtifactKind
import novex.core.CreativeArtifactOrigin
import novex.core.CreativeArtifactRevision
import novex.core.NovexContentAddress
import novex.core.NovexContentKind
import org.junit.Assert.assertEquals
import org.junit.Test

class CreativeArtifactQueryTest {
    @Test
    fun associationKindAndArtifactKindFiltersComposeWithoutDuplicatingRecords() {
        val worldMap = record(
            id = "world-map",
            kind = CreativeArtifactKind.MAP,
            attachments = listOf(
                CreativeArtifactAttachment("world-map", NovexContentAddress.world("world-1")),
                CreativeArtifactAttachment("world-map", NovexContentAddress.world("world-2")),
            ),
        )
        val characterImage = record(
            id = "character-image",
            kind = CreativeArtifactKind.IMAGE,
            attachments = listOf(
                CreativeArtifactAttachment(
                    "character-image",
                    NovexContentAddress.characterVersion("version-1"),
                ),
            ),
        )

        val filtered = filterCreativeArtifactRecords(
            records = listOf(worldMap, characterImage),
            query = CreativeArtifactQuery(
                kinds = setOf(CreativeArtifactKind.MAP),
                ownerKinds = setOf(NovexContentKind.WORLD),
            ),
        )

        assertEquals(listOf("world-map"), filtered.map { it.artifact.id })
    }

    @Test
    fun unattachedFilterOnlyReturnsConversationFilesNotMountedIntoContent() {
        val loose = record("loose", CreativeArtifactKind.DOCUMENT)
        val game = record(
            id = "game",
            kind = CreativeArtifactKind.DOCUMENT,
            attachments = listOf(
                CreativeArtifactAttachment("game", NovexContentAddress.interactiveFiction("game-1")),
            ),
        )

        assertEquals(
            listOf("loose"),
            filterCreativeArtifactRecords(
                listOf(loose, game),
                CreativeArtifactQuery(unattachedOnly = true),
            ).map { it.artifact.id },
        )
    }

    @Test
    fun moduleImageSelectionUsesLatestAttachedImageForTheRequestedOwner() {
        val owner = NovexContentAddress.world("world-1")
        val oldMap = record(
            id = "old-map",
            kind = CreativeArtifactKind.MAP,
            updatedAt = 10,
            attachments = listOf(CreativeArtifactAttachment("old-map", owner, "map-module")),
        )
        val latestMap = record(
            id = "latest-map",
            kind = CreativeArtifactKind.IMAGE,
            updatedAt = 20,
            attachments = listOf(CreativeArtifactAttachment("latest-map", owner, "map-module")),
        )
        val document = record(
            id = "notes",
            kind = CreativeArtifactKind.DOCUMENT,
            updatedAt = 30,
            attachments = listOf(CreativeArtifactAttachment("notes", owner, "map-module")),
        )
        val otherWorld = record(
            id = "other-world",
            kind = CreativeArtifactKind.IMAGE,
            updatedAt = 40,
            attachments = listOf(
                CreativeArtifactAttachment(
                    "other-world",
                    NovexContentAddress.world("world-2"),
                    "map-module",
                ),
            ),
        )

        assertEquals(
            mapOf("map-module" to "latest-map"),
            selectAttachedModuleImageIds(listOf(oldMap, latestMap, document, otherWorld), owner),
        )
    }

    @Test
    fun exportNameUsesRevisionMimeTypeInsteadOfContentAddressedStorageKey() {
        val image = record("map-image", CreativeArtifactKind.IMAGE).let { record ->
            record.copy(
                artifact = record.artifact.copy(title = "云岚地图"),
                revisions = listOf(
                    CreativeArtifactRevision(
                        id = "revision-1",
                        artifactId = record.artifact.id,
                        number = 1,
                        storageKey = "sha256/abcdef",
                        contentHash = "abcdef",
                        mimeType = "image/png",
                        sizeBytes = 3,
                    ),
                ),
            )
        }

        assertEquals("云岚地图.png", creativeArtifactExportName(image))
    }

    @Test
    fun deviceDirectoryKeepsExistingFilesAndAddsANumberedCopy() {
        assertEquals(
            "云岚地图 (3).png",
            nextAvailableCreativeArtifactName(
                requested = "云岚地图.png",
                existingNames = setOf("云岚地图.png", "云岚地图 (2).png"),
            ),
        )
    }

    private fun record(
        id: String,
        kind: CreativeArtifactKind,
        attachments: List<CreativeArtifactAttachment> = emptyList(),
        updatedAt: Long = 1L,
    ) = CreativeArtifactRecord(
        artifact = CreativeArtifact(
            id = id,
            kind = kind,
            title = id,
            storageKey = "sha256/$id",
            origin = CreativeArtifactOrigin("conversation-1", "branch-1"),
            updatedAt = updatedAt,
        ),
        revisions = emptyList(),
        attachments = attachments,
        sourcePath = null,
    )
}
