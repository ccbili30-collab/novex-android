package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart

internal const val PRESENT_CHOICES_TOOL = "present_choices"

/**
 * UI-only tools finish the current model turn. They remain renderable in the
 * persisted chat, but are removed from provider history and never receive a
 * synthetic tool result.
 */
internal fun isSuccessfulTerminalUiTool(name: String, success: Boolean): Boolean =
    name == PRESENT_CHOICES_TOOL && success

internal fun withoutTerminalUiToolUses(
    parts: List<AgentContentPart>,
    terminalIds: Set<String>,
): List<AgentContentPart> = parts.filterNot {
    it is AgentContentPart.ToolUse && it.id in terminalIds && it.name == PRESENT_CHOICES_TOOL
}
