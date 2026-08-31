package com.openminis.app.data.character

import org.json.JSONObject

object CharacterPromptComposer {
    fun compose(
        characterSnapshot: String?,
        personaSnapshot: String?,
        worldSnapshot: String? = null,
    ): String? {
        val character = characterSnapshot?.let { runCatching { CharacterCard.fromJson(JSONObject(it)) }.getOrNull() }
        val persona = personaSnapshot?.let { runCatching { PlayerPersona.fromJson(JSONObject(it)) }.getOrNull() }
        val world = worldSnapshot?.let { runCatching { StoryWorld.fromJson(JSONObject(it)) }.getOrNull() }
        if (character == null && persona == null && world == null) return null
        return buildString {
            world?.let {
                append("<当前世界观>\n")
                appendField("名称", it.name)
                appendField("世界设定", it.description)
                append("世界观提供故事事实和边界；不得将其中未发生的事件描述为用户已经完成的行为。\n")
                append("</当前世界观>\n")
            }
            character?.let {
                append("<当前角色卡>\n")
                appendField("名称", it.name)
                appendField("简介", it.summary)
                appendField("人格与表达", it.personality)
                appendField("角色背景", it.background)
                appendField("当前场景", it.scenario)
                appendField("开场白", it.greeting)
                appendField("示例对话", it.exampleDialogue)
                appendField("角色专属指令", it.systemPrompt)
                appendField("回复后置要求", it.postHistoryInstructions)
                appendField("角色专属知识", it.knowledge)
                append("你应持续以该角色的身份与表达方式回应，但不得伪造用户的行动、想法或台词。\n")
                append("当前会话的记忆读取与写入只属于该角色；不要把其他角色或普通对话的记忆混入。\n")
                append("</当前角色卡>\n")
            }
            persona?.let {
                append("<当前玩家身份>\n")
                appendField("名称", it.name)
                appendField("身份描述", it.description)
                appendField("与角色的关系", it.relationship)
                appendField("角色对玩家的称呼", it.preferredAddress)
                append("该区块描述用户在本次故事中的身份，不代表用户已经做出任何未明确输入的行为。\n")
                append("</当前玩家身份>")
            }
        }.trim()
    }

    private fun StringBuilder.appendField(label: String, value: String) {
        if (value.isNotBlank()) append(label).append("：").append(value.trim()).append('\n')
    }
}
