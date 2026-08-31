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

        val prompt = CharacterPromptComposer.compose(character.toJson().toString(), persona.toJson().toString())!!

        assertTrue(prompt.contains("<当前角色卡>"))
        assertTrue(prompt.contains("名称：艾琳"))
        assertTrue(prompt.contains("<当前玩家身份>"))
        assertTrue(prompt.contains("名称：林墨"))
        assertTrue(prompt.contains("角色对玩家的称呼：林先生"))
        assertFalse(prompt.contains("/private/"))
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
}
