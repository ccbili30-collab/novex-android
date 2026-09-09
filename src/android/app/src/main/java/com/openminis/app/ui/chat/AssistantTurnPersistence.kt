package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject

/** One provider turn only. UI channels never change the verbatim text sent back to the model. */
internal fun buildTurnParts(
    blocks: List<AssistantBlock>,
    startIndex: Int,
    toolInputs: Map<String, String>,
): List<AgentContentPart> = blocks.drop(startIndex).mapNotNull { block ->
    when {
        block.isText && block.content.isNotEmpty() -> AgentContentPart.Text(block.content)
        block.kind == "tool_use" && block.toolName.isNotBlank() -> AgentContentPart.ToolUse(
            block.id, block.toolName,
            runCatching { JSONObject(toolInputs[block.id] ?: "{}") }.getOrElse { JSONObject() },
            thoughtSignature = block.thoughtSignature,
        )
        else -> null
    }
}

/** Shared by durable turns and live list previews. Extra metadata is ignored by provider replay. */
internal fun encodeAssistantTurnParts(
    parts: List<AgentContentPart>,
    metadata: Map<String, AssistantBlock>,
): String {
    val textMetadata = metadata.values.filter { it.isText && it.content.isNotEmpty() }.iterator()
    val fallbackExecution = parts.any { it is AgentContentPart.ToolUse }
    val result = JSONArray()
    parts.forEach { part ->
        when (part) {
            is AgentContentPart.Text -> {
                val execution = if (textMetadata.hasNext()) textMetadata.next().executionText else fallbackExecution
                result.put(JSONObject().put("type", "text").put("value", part.text).put("execution", execution))
            }
            is AgentContentPart.ToolUse -> if (part.name.isNotBlank()) {
                val meta = metadata[part.id]
                result.put(JSONObject().put("type", if (part.name == "present_choices") "uiToolUse" else "toolUse")
                    .put("value", JSONObject()
                        .put("toolUseId", part.id).put("name", part.name).put("input", part.input.toString())
                        .put("description", meta?.toolTitle.orEmpty()).put("pageURL", meta?.browserURL.orEmpty())
                        .put("executionInput", meta?.executionArgs ?: JSONObject.NULL)
                        .put("imageFilePath", meta?.imageFilePath.orEmpty())
                        .put("thoughtSignature", part.thoughtSignature ?: JSONObject.NULL)))
            }
            else -> Unit // Tool results have their own durable rows.
        }
    }
    metadata.values.lastOrNull { it.toolName == NovexCardCreationTask.MARKER }?.let { task ->
        result.put(JSONObject().put("type", "novexCardTask").put("value", JSONObject(task.toolArgs)))
    }
    metadata.values.filter { it.toolName == NOVEX_STORY_IMAGE }.forEach { block ->
        readStoryImage(block)?.let { result.put(JSONObject().put("type", NOVEX_STORY_IMAGE).put("value", com.openminis.app.novex.domain.NovexSnapshotMediaCodec.encode(it))) }
    }
    return result.toString()
}

/** A terminal presentation turn contains the user's answer and buttons, not background operations. */
internal fun isCompletedPresentationTurn(blocks: List<AssistantBlock>): Boolean {
    val tools = blocks.filter { it.kind == "tool_use" }
    return tools.any { it.toolName == "present_choices" } && tools.all {
        it.toolName in setOf("present_choices", "render_panel", "panel", "present_system_panel", com.openminis.app.tools.NovexIllustrationTools.SELECT) &&
            it.toolStatus == ToolBlockStatus.SUCCESS
    }
}
