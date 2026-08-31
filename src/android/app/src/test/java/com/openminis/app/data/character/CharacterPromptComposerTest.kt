package com.openminis.app.data.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterPromptComposerTest {
    @Test
    fun `character and player snapshots round trip without visual paths entering prompt`() {
        val character = CharacterCard(
            id = "role-1",
            name = "艾琳",
            summary = "夜港向导",
            personality = "克制、敏锐",
            background = "在夜港长大",
            scenario = "雨夜酒馆",
            greeting = "你终于来了。",
            exampleDialogue = "用户：这里安全吗？\n艾琳：没有地方绝对安全。",
            avatarPath = "/private/avatar.png",
            defaultBackgroundPath = "/private/background.jpg",
            createdAt = 1,
            updatedAt = 2,
        )
        val persona = PlayerPersona(
            id = "persona-1",
            name = "林墨",
            description = "调查员",
            relationship = "艾琳的旧友",
            preferredAddress = "林先生",
            avatarPath = "/private/player.png",
            isDefault = true,
            createdAt = 3,
            updatedAt = 4,
        )
        val world = StoryWorld(
            name = "永夜港",
            description = "天空终年无日，港口由潮汐钟管理。",
            backgroundPath = "/private/world.jpg",
            createdAt = 1,
            updatedAt = 2,
        )

        val prompt = CharacterPromptComposer.compose(
            character.toJson().toString(),
            persona.toJson().toString(),
            world.toJson().toString(),
        )!!

        assertTrue(prompt.contains("<当前世界观>"))
        assertTrue(prompt.contains("名称：永夜港"))
        assertTrue(prompt.contains("<当前角色卡>"))
        assertTrue(prompt.contains("名称：艾琳"))
        assertTrue(prompt.contains("<当前玩家身份>"))
        assertTrue(prompt.contains("名称：林墨"))
        assertTrue(prompt.contains("角色对玩家的称呼：林先生"))
        assertFalse(prompt.contains("/private/"))
        assertTrue(prompt.contains("只属于该世界、玩家身份与角色卡的组合"))
    }

    @Test
    fun `empty snapshots do not add a prompt layer`() {
        assertNull(CharacterPromptComposer.compose(null, null))
    }

    @Test
    fun `snapshot keeps old content after library object changes`() {
        val original = CharacterCard(
            id = "role-1",
            name = "旧名字",
            personality = "旧人格",
            createdAt = 1,
            updatedAt = 1,
        )
        val snapshot = original.toJson().toString()
        val edited = original.copy(name = "新名字", personality = "新人格", updatedAt = 2)

        val prompt = CharacterPromptComposer.compose(snapshot, null)!!
        assertTrue(prompt.contains("旧名字"))
        assertTrue(prompt.contains("旧人格"))
        assertFalse(prompt.contains(edited.name))
        assertEquals("新名字", edited.name)
    }

    @Test
    fun `role card is the final and most specific editable identity layer`() {
        val character = CharacterCard(
            id = "role-1",
            name = "艾琳",
            systemPrompt = "始终以艾琳的身份回应",
            createdAt = 1,
            updatedAt = 1,
        )
        val persona = PlayerPersona(
            id = "persona-1",
            name = "玩家",
            description = "旅人",
            createdAt = 1,
            updatedAt = 1,
        )

        val prompt = CharacterPromptComposer.compose(
            character.toJson().toString(),
            persona.toJson().toString(),
        )!!

        assertTrue(prompt.indexOf("<当前玩家身份>") < prompt.indexOf("<当前角色卡>"))
        assertTrue(prompt.trim().endsWith("</当前角色卡>"))
        assertFalse(prompt.contains("你是 Novex"))
    }

    @Test
    fun `role system prompt excludes Novax identity and defaults to no tools`() {
        val character = CharacterCard(
            id = "role-1",
            name = "艾琳",
            createdAt = 1,
            updatedAt = 1,
        )

        val prompt = CharacterSystemPromptComposer.compose(
            characterSnapshot = character.toJson().toString(),
            personaSnapshot = null,
            worldSnapshot = null,
            enabledTools = emptySet(),
        )

        assertTrue(prompt.contains("这是角色卡对话，不是 Novax 助手对话"))
        assertTrue(prompt.contains("未启用结构化工具"))
        assertFalse(prompt.contains("shell_execute"))
        assertFalse(prompt.contains("你是 Novex，一名"))
        assertTrue(prompt.trim().endsWith("</当前角色卡>"))
    }

    @Test
    fun `role tool policy defaults closed and never exposes general Novax tools`() {
        val available = setOf("present_choices", "generate_image", "shell_execute", "read_file")
        val closed = CharacterCard(id = "closed", name = "艾琳", createdAt = 1, updatedAt = 1)
        val enabled = closed.copy(
            id = "enabled",
            allowedTools = listOf("present_choices", "generate_image", "shell_execute"),
        )

        assertTrue(CharacterToolPolicy.allowedToolNames(closed, available).isEmpty())
        assertEquals(
            setOf("present_choices", "generate_image"),
            CharacterToolPolicy.allowedToolNames(enabled, available),
        )
        assertEquals(available, CharacterToolPolicy.allowedToolNames(null, available))
    }

    @Test
    fun `world and optional player fields survive snapshot round trip`() {
        val card = CharacterCard(
            id = "role-1",
            name = "艾琳",
            worldId = "world-7",
            contentBoundary = "不要替玩家决定行动",
            allowedTools = listOf("present_choices", "shell_execute"),
            createdAt = 1,
            updatedAt = 2,
        )
        val player = PlayerPersona(
            id = "player-1",
            name = "",
            worldId = "world-7",
            appearance = "黑色斗篷",
            abilities = "辨认古文字",
            boundaries = "不替玩家发言",
            createdAt = 1,
            updatedAt = 2,
        )

        val restoredCard = CharacterCard.fromJson(card.toJson())
        val restoredPlayer = PlayerPersona.fromJson(player.toJson())

        assertEquals("world-7", restoredCard.worldId)
        assertEquals(listOf("present_choices"), restoredCard.allowedTools)
        assertEquals("不要替玩家决定行动", restoredCard.contentBoundary)
        assertEquals("world-7", restoredPlayer.worldId)
        assertEquals("黑色斗篷", restoredPlayer.appearance)
        assertEquals("辨认古文字", restoredPlayer.abilities)
        assertEquals("不替玩家发言", restoredPlayer.boundaries)
    }
}
