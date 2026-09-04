package com.openminis.app.data.creative

import com.openminis.app.novex.domain.CreativeArtifact
import com.openminis.app.novex.domain.CreativeArtifactAttachment
import com.openminis.app.novex.domain.CreativeArtifactKind
import com.openminis.app.novex.domain.CreativeArtifactOrigin
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexContentKind
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
