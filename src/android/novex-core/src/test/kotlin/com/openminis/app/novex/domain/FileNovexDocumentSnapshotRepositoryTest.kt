package com.openminis.app.novex.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNovexDocumentSnapshotRepositoryTest {
    private val sha = "f".repeat(64)
    private val ref = NovexResourceRef("novex://documents/$sha")

    @Test fun `legacy parsing remains addressable after a new parsing and repository restart`() {
        val directory = kotlin.io.path.createTempDirectory("novex-source-revisions").toFile()
        try {
            val original = snapshot("parser-v1").let { it.copy(blocks = it.blocks.map { block -> block.copy(text = "旧解析保留借出和傍晚收回。") }) }
            File(directory, "$sha.json").writeText(NovexDocumentSnapshotJsonCodec.encode(original))
            val repository = FileNovexDocumentSnapshotRepository(directory)
            val oldRevision = NovexSourceReadEvidence.documentRevision(original)
            val first = NovexDocumentTools(repository).documentRead(NovexDocumentReadRequest(ref, maxChars = 3))
            val changed = snapshot("parser-v2").let { it.copy(blocks = it.blocks.map { block -> block.copy(text = "新解析正文变化，旧游标不得混用。") }) }
            repository.store(NovexDocumentSnapshotCacheKey(sha, changed.parserVersion), changed)
            val reopened = FileNovexDocumentSnapshotRepository(directory)
            assertEquals(original, reopened.findRevision(ref, oldRevision))
            assertEquals(original, reopened.find(NovexDocumentSnapshotCacheKey(sha, "parser-v1")))
            assertEquals(changed, reopened.find(ref))
            val tools = NovexDocumentTools(reopened)
            val cursor = first.data["next_cursor"] as String
            assertEquals(false, tools.documentRead(NovexDocumentReadRequest(ref, cursor = cursor)).ok)
            val continued = tools.documentRead(NovexDocumentReadRequest(ref, cursor = cursor, sourceRevision = oldRevision))
            assertTrue(continued.ok)
            assertTrue(continued.toJson().contains("借出和傍晚收回"))
            assertEquals("document.revision_not_found", tools.documentRead(NovexDocumentReadRequest(ref, sourceRevision = "0".repeat(64))).code)
        } finally { directory.deleteRecursively() }
    }

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
