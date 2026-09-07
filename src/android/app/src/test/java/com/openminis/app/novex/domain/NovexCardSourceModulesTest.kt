package com.openminis.app.novex.domain

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NovexCardSourceModulesTest {
    private fun source(): NovexDocumentSnapshot {
        val lines = listOf("全篇标题", "  篇首的原始空白  ") + (1..47).flatMap { listOf("第${it}章 · 模块$it", "  原文第 $it 章\n没有发生的饮水不可补写。  ") }
        return NovexDocumentSnapshot(NovexResourceRef("novex://documents/" + "a".repeat(64)), "a".repeat(64), "fixture", "文游原文", NovexDocumentFormat.TEXT,
            NovexDocumentStatus.READY, lines.mapIndexed { i, text -> NovexDocumentBlock("block_$i", NovexDocumentBlockKind.PARAGRAPH, i, text, source = NovexDocumentSourceAnchor("body", i)) })
    }
    @Test fun `47 source chapters and preamble preserve every block exactly once`() {
        val source = source()
        val revision = NovexDocumentTools(NovexDocumentSnapshotStore { source }).documentInspect(NovexDocumentInspectRequest(source.ref)).data.getValue("source_revision") as String
        val copier = NovexCardSourceModules(NovexDocumentSnapshotStore { source }) { it == source.ref }
        val modules = copier.chapters(source.ref.value, revision)
        assertEquals(48, modules.length())
        val saved = (0 until modules.length()).joinToString("\n") { modules.getJSONObject(it).getJSONObject("content_json").getString("text") }
        assertEquals(source.blocks.joinToString("\n") { it.text }, saved)
        val operations = NovexCardFileOperations(copier).create(JSONObject().put("kind", "game").put("name", "规则文游")
            .put("document_ref", source.ref.value).put("source_revision", revision))
        val changes = NovexManagementChangeCodec.decode(operations)
        assertEquals(1, changes.size)
        assertTrue(changes.single() is NovexManagedChange.CreateInteractiveFiction)
        assertEquals(48, (changes.single() as NovexManagedChange.CreateInteractiveFiction).modules.size)
    }
    @Test fun `partial parsing is not presented as whole source creation`() {
        val source = source().copy(status = NovexDocumentStatus.OCR_REQUIRED)
        val revision = NovexDocumentTools(NovexDocumentSnapshotStore { source }).documentInspect(NovexDocumentInspectRequest(source.ref)).data.getValue("source_revision") as String
        val copier = NovexCardSourceModules(NovexDocumentSnapshotStore { source }) { true }
        assertThrows(IllegalArgumentException::class.java) { copier.chapters(source.ref.value, revision) }
        assertEquals(source.blocks.first().text, copier.range(source.ref.value, revision, 1, 1).getString("text"))
    }
    @Test fun `wrong revision and another conversation source cannot be copied`() {
        val source = source()
        val copier = NovexCardSourceModules(NovexDocumentSnapshotStore { source }) { it == source.ref }
        assertThrows(IllegalArgumentException::class.java) { copier.chapters(source.ref.value, "b".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { copier.chapters("novex://documents/" + "c".repeat(64), NovexDocumentTools(NovexDocumentSnapshotStore { source }).documentInspect(NovexDocumentInspectRequest(source.ref)).data.getValue("source_revision") as String) }
    }
}
