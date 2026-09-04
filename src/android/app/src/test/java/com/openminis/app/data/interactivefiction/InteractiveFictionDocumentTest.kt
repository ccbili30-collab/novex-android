package com.openminis.app.data.interactivefiction

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwnerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveFictionDocumentTest {
    @Test
    fun fullTextKeepsProjectMetadataAndTheUsersModuleOrder() {
        val project = InteractiveFictionProjectEntity(
            id = "game-1",
            name = "云岚试炼",
            summary = "在书院中完成试炼。",
            launchMode = InteractiveFictionLaunchMode.FIXED_IDENTITY,
            playerIdentity = "新入门弟子",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val modules = listOf(
            module("opening", ContentModuleType.GAME_OPENING, "开局说明", "从山门开始。", 0),
            module("rules", ContentModuleType.GAME_NARRATIVE_RULES, "叙事规则", "尊重玩家选择。", 1),
        )

        val text = InteractiveFictionDocumentComposer.fullText(project, modules)

        assertTrue(text.indexOf("开局说明") < text.indexOf("叙事规则"))
        assertTrue(text.contains("云岚试炼"))
        assertTrue(text.contains("新入门弟子"))
        assertTrue(text.contains("从山门开始。"))
    }

    @Test
    fun everyLaunchModeHasOneStableUserFacingDescription() {
        assertEquals(
            listOf("固定玩家身份", "玩家自建身份", "先共创世界", "自由沙盒"),
            InteractiveFictionLaunchMode.entries.map { it.displayName },
        )
    }

    private fun module(
        id: String,
        type: ContentModuleType,
        name: String,
        text: String,
        position: Int,
    ) = ContentModuleEntity(
        id = id,
        ownerType = ModuleOwnerType.INTERACTIVE_FICTION,
        ownerId = "game-1",
        type = type,
        name = name,
        contentJson = ContentModuleDocumentCodec.encode(ContentModuleDocument.Article(text)),
        position = position,
        collapsed = true,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
