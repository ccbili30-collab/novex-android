package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

/** Provider-facing names for confirmed, scoped Novex memory operations. */
object NovexMemoryAgentTools {
    const val INSPECT = "novex_inspect_memory"
    const val PROPOSE = "novex_propose_memory_changes"
    const val APPLY = "novex_apply_memory_changes"
    val TOOL_NAMES = setOf(INSPECT, PROPOSE, APPLY)

    fun definitions(): List<AgentToolDefinition> = listOf(
        AgentToolDefinition(
            name = INSPECT,
            description = "Inspect durable memories in the current Novex memory scope. Role memory is isolated by " +
                "world, player identity and character version. Results follow the active conversation branch and " +
                "use stable novex:// references. This tool never writes.",
            parameters = mapOf(
                "keywords" to AgentToolParam("string", "Optional space-separated terms; every term must match content or tags."),
                "limit" to AgentToolParam("integer", "Maximum entries to return, from 1 to 500. Default 100."),
            ),
            propertyOrdering = listOf("keywords", "limit"),
        ),
        AgentToolDefinition(
            name = PROPOSE,
            description = "Validate a memory change plan without writing. Stop after success and wait for the user " +
                "to send the exact confirmation phrase. Never call apply in the same assistant turn. Never propose " +
                "credentials, access tokens, private keys or other secrets as durable memory.",
            parameters = mapOf(
                "changes" to AgentToolParam(
                    "string",
                    "JSON array of 1 to 20 changes. add uses {operation,content,tags?}; replace uses " +
                        "{operation,memory_ref,expected_revision,content,tags?}; remove uses " +
                        "{operation,memory_ref,expected_revision}. Inspect before replacing or removing.",
                ),
            ),
            required = listOf("changes"),
            propertyOrdering = listOf("changes"),
        ),
        AgentToolDefinition(
            name = APPLY,
            description = "Atomically apply one stored memory proposal. The app checks the latest real user message; " +
                "confirmation is never accepted as a tool argument.",
            parameters = mapOf(
                "proposal_id" to AgentToolParam("string", "Proposal id returned by novex_propose_memory_changes."),
            ),
            required = listOf("proposal_id"),
            propertyOrdering = listOf("proposal_id"),
        ),
    )
}
