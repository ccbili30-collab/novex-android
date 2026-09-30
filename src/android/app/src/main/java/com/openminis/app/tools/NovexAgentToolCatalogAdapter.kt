package com.openminis.app.tools

import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.AgentToolParam
import novex.core.NovexToolCapability
import novex.core.NovexToolCatalog
import novex.core.NovexToolParameter
import novex.core.NovexToolParameterKind

/** Single provider adapter for every Novex-owned model tool definition. */
internal object NovexAgentToolCatalogAdapter {
    fun definitions(capability: NovexToolCapability): List<AgentToolDefinition> =
        NovexToolCatalog.forCapabilities(setOf(capability)).map { definition ->
            AgentToolDefinition(
                name = definition.name,
                description = definition.description,
                parameters = definition.parameters.associateTo(linkedMapOf()) { parameter ->
                    parameter.name to parameter.toAgentParameter()
                },
                required = definition.parameters.filter(NovexToolParameter::required).map { it.name },
                propertyOrdering = definition.parameters.map { it.name },
            )
        }

    private fun NovexToolParameter.toAgentParameter(): AgentToolParam = when (kind) {
        NovexToolParameterKind.STRING -> AgentToolParam("string", description)
        NovexToolParameterKind.INTEGER -> AgentToolParam("integer", description)
        NovexToolParameterKind.BOOLEAN -> AgentToolParam("boolean", description)
        NovexToolParameterKind.STRING_LIST -> AgentToolParam(
            type = "array",
            description = description,
            items = AgentToolParam("string", "列表项"),
        )
        NovexToolParameterKind.PAGE_RANGE -> AgentToolParam(
            type = "object",
            description = description,
            properties = linkedMapOf(
                "first" to AgentToolParam("integer", "起始页码，从一开始"),
                "last" to AgentToolParam("integer", "结束页码，不小于起始页"),
            ),
            required = listOf("first", "last"),
        )
    }
}
