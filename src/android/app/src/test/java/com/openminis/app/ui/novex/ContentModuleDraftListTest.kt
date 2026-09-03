package com.openminis.app.ui.novex

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleScope
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentModuleDraftListTest {
    @Test
    fun worldAndCharacterDraftsUseOneOrderedEditingInterface() {
        val world = ContentModuleDraftList.empty(ContentModuleScope.WORLD)
            .add(ContentModuleType.MAP, moduleId = "map")
            .add(ContentModuleType.CUSTOM, moduleId = "custom-a")
            .add(ContentModuleType.CUSTOM, moduleId = "custom-b")
            .move("custom-b", 0)
            .toggle("map")
            .update(
                moduleId = "map",
                name = "九峰地图",
                document = ContentModuleDocument.SingleImage("九峰环湖"),
            )

        assertEquals(listOf("custom-b", "map", "custom-a"), world.modules.map { it.id })
        assertEquals("九峰地图", world.modules[1].name)
        assertFalse("map" in world.expandedModuleIds)
        assertEquals(
            "九峰环湖",
            (ContentModuleDocumentCodec.decode(world.modules[1].type, world.modules[1].contentJson)
                as ContentModuleDocument.SingleImage).description,
        )

        val source = module("quote", ContentModuleType.QUOTES)
        val character = ContentModuleDraftList.fromSaved(
            scope = ContentModuleScope.CHARACTER_VERSION,
            modules = listOf(source),
            moduleId = { "copied-${it.id}" },
        ).remove("copied-quote")

        assertTrue(character.modules.isEmpty())
        assertFalse("copied-quote" in character.expandedModuleIds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scopeStillRejectsModulesThatBelongToTheOtherContentType() {
        ContentModuleDraftList.empty(ContentModuleScope.CHARACTER_VERSION)
            .add(ContentModuleType.MAP, moduleId = "map")
    }

    private fun module(id: String, type: ContentModuleType) = ContentModuleEntity(
        id = id,
        ownerType = ModuleOwnerType.CHARACTER_VERSION,
        ownerId = "character-version",
        type = type,
        name = id,
        contentJson = "{}",
        position = 0,
        collapsed = true,
        createdAt = 1,
        updatedAt = 1,
    )
}
