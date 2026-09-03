package com.openminis.app.ui.settings

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.WorldEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldEditorDraftStateTest {
    @Test
    fun newWorldUsesTheFirstAvailableDefaultName() {
        assertEquals("我的世界", nextDefaultWorldName(emptyList()))
        assertEquals("我的世界（1）", nextDefaultWorldName(listOf("我的世界")))
        assertEquals(
            "我的世界（2）",
            nextDefaultWorldName(listOf("我的世界", "我的世界（1）", "云岚书院")),
        )
        assertEquals(
            "我的世界（1）",
            nextDefaultWorldName(listOf("我的世界", "我的世界（2）")),
        )
    }

    @Test
    fun draftKeepsRichModuleContentInMemoryAndBuildsOneOrderedSaveCommand() {
        val savedMap = ContentModuleEntity(
            id = "map",
            ownerType = ModuleOwnerType.WORLD,
            ownerId = "world-1",
            type = ContentModuleType.MAP,
            name = "地图",
            contentJson = ContentModuleDocumentCodec.encode(
                ContentModuleDocument.SingleImage("九峰环湖"),
            ),
            position = 0,
            createdAt = 1,
            updatedAt = 1,
        )
        val savedFaction = savedMap.copy(
            id = "faction",
            type = ContentModuleType.FACTION,
            name = "势力",
            contentJson = ContentModuleDocumentCodec.encode(
                ContentModuleDocument.Collection(emptyList()),
            ),
            position = 1,
        )
        val original = WorldEntity(
            id = "world-1",
            name = "云岚书院",
            overview = "旧概述",
            tagsJson = "[\"仙侠\"]",
            createdAt = 1,
            updatedAt = 1,
        )

        val draft = WorldEditorDraftState.from(original, listOf(savedMap, savedFaction))
            .copy(name = "云岚书院·新章", overview = "新概述", tagsText = "仙侠、学院")
            .editModules {
                update(
                    moduleId = "faction",
                    name = "四方势力",
                    document = ContentModuleDocument.Collection(emptyList()),
                ).move("faction", 0)
                    .remove("map")
                    .add(ContentModuleType.REGION, "地区", moduleId = "region")
            }

        assertEquals("旧概述", original.overview)
        assertEquals(listOf("faction", "region"), draft.modules.map { it.id })
        assertEquals("四方势力", draft.modules.first().name)
        assertTrue(ContentModuleDocumentCodec.decode(draft.modules.first().contentJson) is ContentModuleDocument.Collection)

        val command = draft.toSaveCommand(now = 20)
        assertEquals("world-1", command.worldId)
        assertEquals("云岚书院·新章", command.name)
        assertEquals("[\"仙侠\",\"学院\"]", command.tagsJson)
        assertEquals(listOf("faction", "region"), command.modules.map { it.id })
        assertFalse(draft.isBlank)
    }

    @Test
    fun newWorldDraftStartsWithOnlyTheRequiredOverviewAndNoOptionalModulesOrImages() {
        val draft = WorldEditorDraftState.create()

        assertEquals("我的世界", draft.name)
        assertEquals("", draft.overview)
        assertTrue(draft.modules.isEmpty())
        assertTrue(draft.imageChanges.isEmpty())
    }
}
