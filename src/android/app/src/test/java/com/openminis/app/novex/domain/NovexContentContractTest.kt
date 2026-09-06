package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.toPlainText
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NovexContentContractTest {
    @Test
    fun `every advertised module example is writable and readable with its structure intact`() {
        val catalog = NovexManagementInspection(emptyList(), null, null, emptyList(), null)
            .toToolJson().getJSONObject("module_type_catalog")
        listOf("world", "character_version", "game").forEach { owner ->
            val definitions = catalog.getJSONArray(owner)
            repeat(definitions.length()) { index ->
                val definition = definitions.getJSONObject(index)
                val example = definition.getJSONObject("content_example")
                val value = JSONObject().put("operation", "add_module")
                    .put("subject_kind", owner).put("subject_id", "example")
                    .put("module_type", definition.getString("value"))
                    .put("name", definition.getString("label")).put("content_json", example)
                val change = NovexManagementChangeCodec.decode("[$value]").single() as NovexManagedChange.AddModule
                val document = ContentModuleDocumentCodec.decode(change.type, change.contentJson)
                assertTrue("$owner/${definition.getString("value")}", document.toPlainText().isNotBlank())
                assertEquals(document, ContentModuleDocumentCodec.decode(change.type, ContentModuleDocumentCodec.encode(document)))
            }
        }
    }

    @Test
    fun `unrenderable object is rejected with a usable document example before a plan is stored`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            NovexManagementChangeCodec.decode(
                """[{"operation":"add_module","subject_kind":"world","subject_id":"w1","module_type":"custom","name":"规则","content_json":{"rules":["玩家不是世界中心"]}}]""",
            )
        }
        assertTrue(failure.message.orEmpty().contains("kind"))
        assertTrue(failure.message.orEmpty().contains("article"))
        assertTrue(failure.message.orEmpty().contains("text"))
    }
}
