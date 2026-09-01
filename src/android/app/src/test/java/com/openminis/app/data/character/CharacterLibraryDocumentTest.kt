package com.openminis.app.data.character

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLibraryDocumentTest {
    @Test
    fun profileEditingPreservesImportedPromptFields() {
        val imported = JSONObject()
            .put("name", "伊薇")
            .put("systemPrompt", "始终使用第一人称")
            .put("tags", org.json.JSONArray(listOf("侦探")))
        val edited = CharacterVersionProfile.fromJson(imported.toString())
            .copy(gender = "女", age = "24", occupation = "调查员")
        val saved = JSONObject(edited.toJson())

        assertEquals("始终使用第一人称", saved.getString("systemPrompt"))
        assertEquals("女", saved.getString("gender"))
        assertEquals("24", saved.getString("age"))
        assertEquals("调查员", saved.getString("occupation"))
    }

    @Test
    fun structuredDocumentRoundTripsOriginalVariantsAndModules() {
        val document = CharacterLibraryDocument(
            name = "伊薇",
            versions = listOf(
                CharacterVersionDocument(
                    CharacterVersionKind.ORIGINAL,
                    "本体",
                    CharacterVersionProfile("伊薇").toJson(),
                    listOf(CharacterModuleDocument(ContentModuleType.QUOTES, "多形态语录", "{\"text\":\"你好\"}")),
                ),
                CharacterVersionDocument(
                    CharacterVersionKind.VARIANT,
                    "赛博分身",
                    CharacterVersionProfile("伊薇", occupation = "黑客").toJson(),
                ),
            ),
        )

        val decoded = CharacterLibraryDocumentCodec.decode(
            CharacterLibraryDocumentCodec.encode(document).toString(),
        )
        assertEquals("伊薇", decoded.name)
        assertEquals(listOf(CharacterVersionKind.ORIGINAL, CharacterVersionKind.VARIANT), decoded.versions.map { it.kind })
        assertEquals(ContentModuleType.QUOTES, decoded.versions.first().modules.single().type)
    }

    @Test
    fun tavernCardBecomesOneOriginalWithOptionalModulesOnlyWhenPopulated() {
        val card = CharacterCard(
            id = "legacy",
            name = "伊薇",
            greeting = "晚上好",
            background = "出生于雾港",
            systemPrompt = "保留我",
            createdAt = 1,
            updatedAt = 2,
        )
        val document = CharacterLibraryDocumentCodec.fromTavernCard(card)

        assertEquals(CharacterVersionKind.ORIGINAL, document.versions.single().kind)
        assertEquals(
            listOf(ContentModuleType.QUOTES, ContentModuleType.WORLD_EXPERIENCE),
            document.versions.single().modules.map { it.type },
        )
        assertTrue(document.versions.single().profileJson.contains("systemPrompt"))
    }
}
