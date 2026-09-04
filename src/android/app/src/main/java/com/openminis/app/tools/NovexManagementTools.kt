package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/**
 * Model-facing contracts for managed Novex content.
 *
 * Inspection is read-only, proposal is inert, and apply accepts only a stored
 * proposal id. The executor obtains confirmation from the latest real user turn;
 * there is intentionally no boolean or confirmation-text tool argument.
 */
object NovexManagementTools {
    const val INSPECT = "novex_inspect_content"
    const val PROPOSE = "novex_propose_content_changes"
    const val APPLY = "novex_apply_content_changes"

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = INSPECT,
            description = "Read Novex worlds, character versions, games and modules mounted in the current " +
                "conversation management workspace. Omit all parameters to list mounted subjects. This tool " +
                "never changes content and cannot inspect unmounted subjects.",
            parameters = mapOf(
                "subject_kind" to AgentToolParam(
                    type = "string",
                    description = "Mounted subject kind.",
                    enumValues = listOf("world", "character_version", "game"),
                ),
                "subject_id" to AgentToolParam("string", "Mounted subject id; required with subject_kind."),
                "module_id" to AgentToolParam("string", "Optional module id owned by the selected mounted subject."),
            ),
            propertyOrdering = listOf("subject_kind", "subject_id", "module_id"),
        ),
        AgentToolDefinition(
            name = PROPOSE,
            description = "Validate and present a structured Novex change plan without writing anything. Use only " +
                "for subjects mounted with edit access, or for a global create explicitly requested by the user. " +
                "After this succeeds, stop and wait for the user to send the exact confirmation phrase shown in " +
                "the result. Never call the apply tool in the same assistant turn.",
            parameters = mapOf(
                "changes" to AgentToolParam(
                    type = "string",
                    description = "JSON array (maximum 20) of changes. Operations: add_module, update_module, " +
                        "move_module, delete_module, add_reference, remove_reference, create_world, " +
                        "create_character, create_character_version, create_game, link_character_version, " +
                        "unlink_character_version, attach_artifact, detach_artifact. Use inspect first to obtain ids.",
                ),
            ),
            required = listOf("changes"),
            propertyOrdering = listOf("changes"),
        ),
        AgentToolDefinition(
            name = APPLY,
            description = "Apply a previously validated Novex proposal atomically. The app, not tool arguments, " +
                "checks that the latest real user message exactly matches that proposal's confirmation phrase. " +
                "Never invent confirmation and never retry a rejected proposal without the real user.",
            parameters = mapOf(
                "proposal_id" to AgentToolParam("string", "Proposal id returned by novex_propose_content_changes."),
            ),
            required = listOf("proposal_id"),
            propertyOrdering = listOf("proposal_id"),
        ),
    )
}
