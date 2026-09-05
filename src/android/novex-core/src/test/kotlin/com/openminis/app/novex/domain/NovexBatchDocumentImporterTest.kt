package com.openminis.app.novex.domain

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NovexBatchDocumentImporterTest {
    @Test
    fun parsingUsesBoundedParallelismAndOneBrokenFileDoesNotCancelTheBatch() = runTest {
        var active = 0
        var peakActive = 0
        val importer = NovexBatchDocumentImporter(
            maxParallelism = 2,
            worker = NovexDocumentImportWorker { request ->
                active += 1
                peakActive = maxOf(peakActive, active)
                try {
                    delay(10)
                    if (request.originalName == "broken.docx") error("broken fixture")
                    snapshot(request)
                } finally {
                    active -= 1
                }
            },
        )
        val requests = listOf("a.docx", "broken.docx", "b.docx", "c.docx")
            .mapIndexed(::request)

        val results = importer.importAll(requests)

        assertEquals(requests.map { it.sourceRef }, results.map { it.sourceRef })
        assertEquals(2, peakActive)
        assertEquals(3, results.count { it.snapshot != null })
        assertEquals("document.parse_failed", results.single { it.snapshot == null }.failureCode)
        assertTrue(results.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun unsupportedInputReturnsAStableFailureOutcomeAndLeavesTheOriginalUntouched() = runTest {
        val request = request(0, "archive.bin")
        val before = request.file.readBytes()
        val importer = NovexBatchDocumentImporter(
            maxParallelism = 1,
            worker = NovexDocumentImportWorker { null },
        )

        val result = importer.importAll(listOf(request)).single()

        assertNull(result.snapshot)
        assertEquals("document.unsupported", result.failureCode)
        assertEquals(before.toList(), request.file.readBytes().toList())
        val sourceImport = result.toSourceImportResult()
        assertEquals(request.sourceRef, sourceImport.ref)
        assertNotNull(sourceImport.failureCode)
    }

    @Test
    fun cancellingTheBatchIsNeverDowngradedIntoAnOrdinaryFileFailure() = runTest {
        val importer = NovexBatchDocumentImporter(
            maxParallelism = 1,
            worker = NovexDocumentImportWorker { throw CancellationException("user paused") },
        )

        try {
            importer.importAll(listOf(request(0, "long.docx")))
            fail("取消必须终止整批任务")
        } catch (_: CancellationException) {
            // Expected: pause/cancel must stop work instead of fabricating a damaged document.
        }
    }

    private fun request(index: Int, name: String): NovexBatchDocumentRequest {
        val directory = kotlin.io.path.createTempDirectory("novex-batch-import").toFile().apply {
            deleteOnExit()
        }
        return NovexBatchDocumentRequest(
            sourceRef = NovexResourceRef("novex://sources/source-$index"),
            file = File(directory, name).apply {
                writeText("fixture-$index-$name")
                deleteOnExit()
            },
            mimeType = null,
            originalName = name,
        )
    }

    private fun snapshot(request: NovexBatchDocumentRequest): NovexDocumentSnapshot {
        val sha = request.file.sha256ForTest()
        val anchor = NovexDocumentSourceAnchor("fixture", 0)
        return NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = request.originalName,
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = listOf(
                NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha, anchor),
                    kind = NovexDocumentBlockKind.PARAGRAPH,
                    order = 0,
                    text = "正文",
                    source = anchor,
                ),
            ),
        )
    }

    private fun File.sha256ForTest(): String = MessageDigest.getInstance("SHA-256")
        .digest(readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
