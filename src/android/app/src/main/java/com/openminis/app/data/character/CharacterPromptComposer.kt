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
            persona?.let {
                append("<当前玩家身份>\n")
                appendField("名称", it.name)
                appendField("身份描述", it.description)
                appendField("外貌", it.appearance)
                appendField("能力", it.abilities)
                appendField("性格倾向", it.personality)
                appendField("与角色的关系", it.relationship)
                appendField("角色对玩家的称呼", it.preferredAddress)
                appendField("玩家边界", it.boundaries)
                append("该区块描述用户在本次故事中的身份，不代表用户已经做出任何未明确输入的行为。\n")
                append("</当前玩家身份>\n")
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
                appendField("内容边界", it.contentBoundary)
                appendField("角色专属指令", it.systemPrompt)
                appendField("回复后置要求", it.postHistoryInstructions)
                appendField("角色专属知识", it.knowledge)
                append("你应持续以该角色的身份与表达方式回应，但不得伪造用户的行动、想法或台词。\n")
                append("当前会话的记忆读取与写入只属于该世界、玩家身份与角色卡的组合；不要混入其他对话的记忆。\n")
                append("</当前角色卡>")
            }
        }.trim()
    }

    private fun StringBuilder.appendField(label: String, value: String) {
        if (value.isNotBlank()) append(label).append("：").append(value.trim()).append('\n')
    }
}

/** Builds the role-chat system prompt without the Nova assistant identity. */
object CharacterSystemPromptComposer {
    fun compose(
        characterSnapshot: String,
        personaSnapshot: String?,
        worldSnapshot: String?,
        enabledTools: Set<String>,
        memoryContext: String? = null,
        conversationPrompt: String? = null,
    ): String {
        val roleContext = requireNotNull(
            CharacterPromptComposer.compose(characterSnapshot, personaSnapshot, worldSnapshot),
        ) { "角色卡内容无效" }
        val toolRule = when {
            enabledTools.isEmpty() -> "本次角色对话未启用结构化工具。只输出可见的角色回复，不模拟工具调用。"
            else -> "本次只允许使用这些工具：${enabledTools.sorted().joinToString(", ")}。未列出的工具不可调用。"
        }
        return buildString {
            append("""
<角色对话协议>
这是角色卡对话，不是 Nova 助手对话。不得采用 Nova 的助手身份、通用人格或任务执行口吻。
世界观提供共同事实，玩家身份描述用户是谁，角色卡是本次对话最具体、最高的用户可编辑身份要求。
角色卡未填写的字段不构成要求。不得替玩家说话、决定行动或虚构内心。
$toolRule
</角色对话协议>

""".trimIndent())
            memoryContext?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append("<角色长期记忆>\n").append(it).append("\n</角色长期记忆>\n\n")
            }
            append(conversationPrompt ?: roleContext)
        }
    }
}

/** Keeps character conversations from inheriting the general Nova tool set. */
object CharacterToolPolicy {
    private val roleToolNames = setOf("present_choices", "generate_image")
    private val attachedReadOnlyToolNames = setOf("document_inspect", "document_read")

    fun allowedToolNames(character: CharacterCard?, availableToolNames: Set<String>): Set<String> {
        if (character == null) return availableToolNames
        val configured = character.allowedTools
            .asSequence()
            .filter { it in roleToolNames && it in availableToolNames }
            .toSet()
        return configured + attachedReadOnlyToolNames.filter { it in availableToolNames }
    }
}
