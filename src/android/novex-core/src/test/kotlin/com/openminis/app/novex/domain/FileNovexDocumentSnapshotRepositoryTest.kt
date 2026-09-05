package com.openminis.app.novex.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNovexDocumentSnapshotRepositoryTest {
    private val sha = "f".repeat(64)
    private val ref = NovexResourceRef("novex://documents/$sha")

    @Test
    fun storedSnapshotCanBeReadByReferenceAfterRepositoryRecreation() {
        val directory = kotlin.io.path.createTempDirectory("novex-snapshot-store").toFile()
        val snapshot = snapshot("parser-v1")

        FileNovexDocumentSnapshotRepository(directory).store(
            NovexDocumentSnapshotCacheKey(sha, "parser-v1"),
            snapshot,
        )
        val reopened = FileNovexDocumentSnapshotRepository(directory)

        assertEquals(snapshot, reopened.find(ref))
        assertEquals(snapshot, reopened.find(NovexDocumentSnapshotCacheKey(sha, "parser-v1")))
        assertNull(reopened.find(NovexDocumentSnapshotCacheKey(sha, "parser-v2")))
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun corruptDerivedSnapshotIsIgnoredAndCanBeRebuiltWithoutTouchingTheSource() {
        val directory = kotlin.io.path.createTempDirectory("novex-snapshot-corrupt").toFile()
        File(directory, "$sha.json").writeText("not json")
        val repository = FileNovexDocumentSnapshotRepository(directory)

        assertNull(repository.find(ref))
        repository.store(NovexDocumentSnapshotCacheKey(sha, "parser-v1"), snapshot("parser-v1"))

        assertEquals("世界资料", repository.find(ref)?.title)
    }

    private fun snapshot(parserVersion: String): NovexDocumentSnapshot {
        val source = NovexDocumentSourceAnchor("compatibility-text", 0)
        return NovexDocumentSnapshot(
            ref = ref,
            sha256 = sha,
            parserVersion = parserVersion,
            title = "世界资料",
            format = NovexDocumentFormat.TEXT,
            status = NovexDocumentStatus.READY,
            blocks = listOf(
                NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha, source),
                    kind = NovexDocumentBlockKind.PARAGRAPH,
                    order = 0,
                    text = "正文",
                    source = source,
                ),
            ),
        )
    }
}
