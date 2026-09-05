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
                    enumValues = listOf("world", "character_version", "game", "artifact"),
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
                    description = "JSON array (maximum 20) of change objects. Use inspect first to obtain ids. " +
                        "Module operations: add_module requires {operation, subject_kind, subject_id, " +
                        "module_type, name, content_json}; module_type must use one of the stable module types returned by inspect for that subject kind. " +
                        "update_module requires {operation, module_id, name?, " +
                        "content_json?}; move_module requires {operation, module_id, to_index}; delete_module " +
                        "requires {operation, module_id}. Reference operations add_reference and remove_reference " +
                        "require {operation, module_id, target_kind, target_id, position?}. Create operations: " +
                        "create_world requires {operation, name, overview?}; create_character requires {operation, " +
                        "name, profile_json}; create_character_version requires {operation, source_version_id, " +
                        "label, profile_json}; create_game requires {operation, name, summary?, launch_mode?, " +
                        "player_identity?}. World links link_character_version and unlink_character_version require " +
                        "{operation, world_id, version_id, position?}. Artifact operations attach_artifact and " +
                        "detach_artifact require {operation, artifact_id, subject_kind, subject_id, module_id?, " +
                        "slot?}. subject_kind is world, character_version, or game. content_json and profile_json " +
                        "accept a JSON object or its serialized JSON string.",
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
