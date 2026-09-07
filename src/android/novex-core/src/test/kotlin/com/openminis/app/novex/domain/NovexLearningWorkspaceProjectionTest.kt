package com.openminis.app.novex.domain

import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexLearningWorkspaceProjectionTest {
    @Test fun `saved learning files survive reopen use exact source revision and stay inside their branch`() {
        val root = Files.createTempDirectory("learning-workspace").toFile()
        try {
            val file = root.resolve("资料.txt").apply { writeText("原文：已锁门，未确认是否被困。") }
            val sha = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
            val ref = NovexResourceRef("novex://documents/$sha")
            val blockId = NovexDocumentBlockId.from(sha, NovexDocumentSourceAnchor("text", 0))
            val doc = NovexDocumentSnapshot(ref, sha, "p1", "资料", NovexDocumentFormat.TEXT, NovexDocumentStatus.READY,
                listOf(NovexDocumentBlock(blockId, NovexDocumentBlockKind.PARAGRAPH, 0, file.readText(), listOf("门的状态"),
                    source = NovexDocumentSourceAnchor("text", 0))))
            val documents = FileNovexDocumentSnapshotRepository(root.resolve("documents"))
            documents.store(NovexDocumentSnapshotCacheKey(sha, doc.parserVersion), doc)
            val source = NovexResourceRef("novex://sources/a")
            val collection = NovexSourceCollectionBuilder.create(NovexResourceRef("novex://source-collections/a"),
                NovexResourceRef("novex://conversation-branches/a"), "测试资料", listOf(NovexSourceImportResult(source, "资料.txt", sha, doc)), 0)
            val note = NovexLearningNote(NovexResourceRef("novex://learning-notes/a"), NovexLearningNoteLevel.BLOCK,
                "门的状态", "已锁门；未确认是否被困。", listOf(ref), listOf(blockId), sourceRevisions = mapOf(ref to NovexSourceReadEvidence.documentRevision(doc)))
            val state = NovexLearningState(collection, NovexReviewLedger.start(collection).recordRead(ref, listOf(blockId), NovexDocumentReadMode.FULL_REVIEW), listOf(note))
            val reparse = doc.copy(parserVersion = "p2", blocks = doc.blocks.map { it.copy(text = "后来重新解析的正文") })
            documents.store(NovexDocumentSnapshotCacheKey(sha, reparse.parserVersion), reparse)
            val scope = NovexConversationWorkspaceScope("conversation", listOf("attachment"), "attachment")
            var store = FileNovexConversationWorkspaceStore(root.resolve("workspace"))
            val projection = NovexLearningWorkspaceProjection(store, documents)
            val first = projection.publish(state, scope, mapOf(source to file))
            assertTrue(first.any { it.workspaceRef.area == NovexWorkspaceArea.SOURCES })
            assertTrue(first.filter { it.workspaceRef.area != NovexWorkspaceArea.SOURCES }.all { it.workspaceRef.area == NovexWorkspaceArea.DERIVED })
            val again = projection.publish(state, scope, mapOf(source to file))
            assertEquals(first, again)
            store = FileNovexConversationWorkspaceStore(root.resolve("workspace"))
            val index = first.single { it.workspaceRef.relativePath.endsWith("资料与主题索引.json") }
            val payload = JSONObject(store.readBytes(scope, index.workspaceRef).toString(Charsets.UTF_8))
            assertEquals("门的状态", payload.getJSONArray("topics").getJSONObject(0).getString("title"))
            assertEquals(NovexSourceReadEvidence.documentRevision(doc), payload.getJSONArray("sources").getJSONObject(0).getString("source_revision"))
            assertTrue(store.inspect(NovexConversationWorkspaceScope("conversation", listOf("sibling"), "sibling")).entries.isEmpty())
            val original = first.single { it.workspaceRef.area == NovexWorkspaceArea.SOURCES }
            assertArrayEquals(file.readBytes(), store.readBytes(scope, original.workspaceRef))
            file.writeText("附件后来被覆盖")
            projection.publish(state, scope, mapOf(source to file))
            val changed = JSONObject(store.readBytes(scope, index.workspaceRef).toString(Charsets.UTF_8))
            assertTrue(changed.getJSONArray("sources").getJSONObject(0).getString("original_attachment_warning").contains("校验值"))
            assertEquals(original.workspaceRef.value, changed.getJSONArray("sources").getJSONObject(0).getString("original_workspace_ref"))
            assertEquals("原文：已锁门，未确认是否被困。", store.readBytes(scope, original.workspaceRef).toString(Charsets.UTF_8))
        } finally { root.deleteRecursively() }
    }
}
