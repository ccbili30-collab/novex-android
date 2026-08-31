package com.openminis.app.data.character

import org.json.JSONObject

object CharacterPromptComposer {
    fun compose(characterSnapshot: String?, personaSnapshot: String?): String? {
        val character = characterSnapshot?.let { runCatching { CharacterCard.fromJson(JSONObject(it)) }.getOrNull() }
        val persona = personaSnapshot?.let { runCatching { PlayerPersona.fromJson(JSONObject(it)) }.getOrNull() }
        if (character == null && persona == null) return null
        return buildString {
            character?.let {
                append("<当前角色卡>\n")
                appendField("名称", it.name)
                appendField("简介", it.summary)
                appendField("人格与表达", it.personality)
                appendField("角色背景", it.background)
                appendField("当前场景", it.scenario)
                appendField("开场白", it.greeting)
                appendField("示例对话", it.exampleDialogue)
                append("你应持续以该角色的身份与表达方式回应，但不得伪造用户的行动、想法或台词。\n")
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
