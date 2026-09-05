package com.openminis.app.novex.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexSourceCollectionTest {
    @Test
    fun batchImportKeepsEveryOriginalWhileDeduplicatingSnapshotsAndContainingFailures() {
        val collection = NovexSourceCollectionBuilder.create(
            ref = NovexResourceRef("novex://source-collections/research-1"),
            scopeRef = NovexResourceRef("novex://conversation-branches/branch-1"),
            title = "西幻资料",
            imports = listOf(
                imported("source-a", sha = "a".repeat(64), document = document("a".repeat(64))),
                imported("source-copy", sha = "a".repeat(64), document = document("a".repeat(64))),
                imported("source-broken", sha = "b".repeat(64), errorCode = "document.parse_failed"),
            ),
            nowMillis = 1_000,
        )

        assertEquals(3, collection.sources.size)
        assertEquals(NovexSourceStatus.READY, collection.sources[0].status)
        assertEquals(NovexSourceStatus.EXACT_DUPLICATE, collection.sources[1].status)
        assertEquals(collection.sources[0].ref, collection.sources[1].duplicateOf)
        assertEquals(NovexSourceStatus.FAILED, collection.sources[2].status)
        assertEquals("document.parse_failed", collection.sources[2].failureCode)
        assertEquals(1, collection.uniqueDocumentRefs.size)
        assertNull(collection.sources[0].duplicateOf)
    }

    @Test
    fun retrievalNeverPretendsToBeFullReviewAndDuplicatesDoNotIncreaseCoverage() {
        val sha = "c".repeat(64)
        val readable = document(sha, listOf("第一节", "第二节", "第三节"))
        val collection = NovexSourceCollectionBuilder.create(
            ref = NovexResourceRef("novex://source-collections/research-2"),
            scopeRef = NovexResourceRef("novex://conversation-branches/branch-1"),
            title = "长文资料",
            imports = listOf(
                imported("source-main", sha, document = readable),
                imported("source-copy", sha, document = readable),
                imported("source-scan", "d".repeat(64), document = ocrDocument("d".repeat(64))),
            ),
            nowMillis = 2_000,
        )
        val initial = NovexReviewLedger.start(collection)
        val afterRetrieval = initial.recordRead(
            documentRef = readable.ref,
            blockIds = listOf(readable.blocks[0].id),
            mode = NovexDocumentReadMode.RETRIEVAL,
        )
        val afterFirstReview = afterRetrieval.recordRead(
            documentRef = readable.ref,
            blockIds = listOf(readable.blocks[0].id),
            mode = NovexDocumentReadMode.FULL_REVIEW,
        )
        val completed = afterFirstReview.recordRead(
            documentRef = readable.ref,
            blockIds = readable.blocks.drop(1).map { it.id },
            mode = NovexDocumentReadMode.FULL_REVIEW,
        )

        assertEquals(3, initial.totalReadableBlocks)
        assertEquals(0, afterRetrieval.reviewedBlocks)
        assertEquals(1, afterFirstReview.reviewedBlocks)
        assertFalse(afterFirstReview.isComplete)
        assertEquals(NovexLearningTaskStatus.PARTIAL_FAILURE, completed.status)
        assertTrue(completed.isComplete)
        assertEquals(3, completed.reviewedBlocks)
        assertEquals(
            listOf(NovexResourceRef("novex://sources/source-scan")),
            completed.unreadableSourceRefs,
        )
    }

    @Test
    fun highlyOverlappingDocumentsAreMarkedAsPossibleVersionsButRemainIndependent() {
        val firstSha = "1".repeat(64)
        val secondSha = "2".repeat(64)
        val unrelatedSha = "3".repeat(64)
        val shared = "云岚书院位于群山之间，学生通过晨课、试炼和远行学习术法。"
        val collection = NovexSourceCollectionBuilder.create(
            ref = NovexResourceRef("novex://source-collections/versions"),
            scopeRef = NovexResourceRef("novex://conversation-branches/branch-1"),
            title = "版本资料",
            imports = listOf(
                imported("rules-v1", firstSha, document = document(firstSha, listOf(shared.repeat(5)))),
                imported(
                    "rules-v2",
                    secondSha,
                    document = document(secondSha, listOf(shared.repeat(5) + "新增：夜间禁止独自进入后山。")),
                ),
                imported(
                    "other",
                    unrelatedSha,
                    document = document(unrelatedSha, listOf("星舰依靠折跃引擎穿过深空。".repeat(8))),
                ),
            ),
            nowMillis = 3_000,
        )

        assertEquals(NovexSourceStatus.READY, collection.sources[1].status)
        assertEquals(collection.sources[0].ref, collection.sources[1].possibleVersionOf)
        assertTrue(requireNotNull(collection.sources[1].similarityPercent) >= 80)
        assertNull(collection.sources[2].possibleVersionOf)
        assertEquals(3, collection.uniqueDocumentRefs.size)
    }

    private fun imported(
        id: String,
        sha: String,
        document: NovexDocumentSnapshot? = null,
        errorCode: String? = null,
    ) = NovexSourceImportResult(
        ref = NovexResourceRef("novex://sources/$id"),
        title = "$id.docx",
        sha256 = sha,
        document = document,
        failureCode = errorCode,
    )

    private fun document(sha: String, paragraphs: List<String> = listOf("正文")): NovexDocumentSnapshot {
        return NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = "资料.docx",
            format = NovexDocumentFormat.DOCX,
            status = NovexDocumentStatus.READY,
            blocks = paragraphs.mapIndexed { index, paragraph ->
                val source = NovexDocumentSourceAnchor("word/document.xml", index)
                NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha, source),
                    kind = NovexDocumentBlockKind.PARAGRAPH,
                    order = index,
                    text = paragraph,
                    source = source,
                )
            },
        )
    }

    private fun ocrDocument(sha: String) = NovexDocumentSnapshot(
        ref = NovexResourceRef("novex://documents/$sha"),
        sha256 = sha,
        parserVersion = "fixture-v1",
        title = "扫描资料.pdf",
        format = NovexDocumentFormat.PDF,
        status = NovexDocumentStatus.OCR_REQUIRED,
        blocks = emptyList(),
        warnings = listOf(NovexDocumentWarning("document.ocr_required", "需要光学字符识别")),
    )
}
