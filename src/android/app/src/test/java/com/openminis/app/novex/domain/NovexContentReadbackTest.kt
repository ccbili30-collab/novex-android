package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleCollectionItem
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same persisted document is read by module pages and model context loading. */
class NovexContentReadbackTest {
    @Test
    fun `editing a typed module preserves extension fields and rich content`() {
        val original = com.openminis.app.data.character.ContentModuleEntity(
            id = "module", ownerType = com.openminis.app.data.character.ModuleOwnerType.WORLD,
            ownerId = "world", type = ContentModuleType.FACTION, name = "势力", position = 0,
            contentJson = """{"kind":"collection","version":1,"layout_hint":"custom-layout","items":[{"id":"i","name":"书院","description":"旧规","extra":{"origin":"source"}}]}""",
            createdAt = 1, updatedAt = 1,
        )
        val document = ContentModuleDocumentCodec.decode(original.type, original.contentJson) as ContentModuleDocument.Collection
        val draft = com.openminis.app.ui.novex.ContentModuleDraftList.fromSaved(
            com.openminis.app.data.character.ContentModuleScope.WORLD, listOf(original),
        ).update("module", "四大势力", document.copy(items = document.items.map { it.copy(description = "新规") }))
        val saved = org.json.JSONObject(draft.modules.single().contentJson)
        assertEquals("collection", saved.getString("kind"))
        assertEquals("custom-layout", saved.getString("layout_hint"))
        assertEquals("source", saved.getJSONArray("items").getJSONObject(0).getJSONObject("extra").getString("origin"))
        assertEquals("书院\n新规", ContentModuleDocumentCodec.decode(draft.modules.single().contentJson).toPlainText())
    }

    @Test
    fun `unrecognized historical object remains visible as preserved data rather than empty text`() {
        val raw = """{"rules":["不得替玩家选择"]}"""
        val restored = ContentModuleDocumentCodec.decode(ContentModuleType.CUSTOM, raw)
        org.junit.Assert.assertTrue(restored is ContentModuleDocument.Unsupported)
        assertEquals(raw, (restored as ContentModuleDocument.Unsupported).contentJson)
    }

    @Test
    fun `saved collection exposes complete descriptions to page and context readers`() {
        val document = ContentModuleDocument.Collection(listOf(
            ContentModuleCollectionItem(
                id = "academy",
                name = "云岚书院",
                summary = "守护文脉",
                description = "书院不得干预凡人继承。\n违者交由四院共同裁决。",
            ),
            ContentModuleCollectionItem(
                id = "hidden-rule",
                description = "没有名称的条目也必须保留全部设定。",
            ),
        ))

        val restored = ContentModuleDocumentCodec.decode(
            ContentModuleType.FACTION,
            ContentModuleDocumentCodec.encode(document),
        )

        assertEquals(
            "云岚书院：守护文脉\n书院不得干预凡人继承。\n违者交由四院共同裁决。\n没有名称的条目也必须保留全部设定。",
            restored.toPlainText(),
        )
    }
}
