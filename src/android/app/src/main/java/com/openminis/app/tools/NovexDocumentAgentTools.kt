package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.novex.domain.NovexDocumentSnapshotStore
import com.openminis.app.novex.domain.NovexDocumentToolRouter
import com.openminis.app.novex.domain.NovexDocumentTools
import com.openminis.app.novex.domain.NovexToolCapability
import com.openminis.app.novex.domain.NovexToolCatalog
import com.openminis.app.novex.domain.NovexToolParameter
import com.openminis.app.novex.domain.NovexToolParameterKind

/** Thin provider adapter around the Novex-owned document tool contract. */
class NovexDocumentAgentTools(
    snapshots: NovexDocumentSnapshotStore,
    isAllowed: (com.openminis.app.novex.domain.NovexResourceRef) -> Boolean,
) {
    private val scopedSnapshots = NovexDocumentSnapshotStore { requested ->
        snapshots.find(requested).takeIf { isAllowed(requested) }
    }
    private val router = NovexDocumentToolRouter(NovexDocumentTools(scopedSnapshots))

    fun definitions(): List<AgentToolDefinition> = providerDefinitions()

    fun execute(name: String, argumentsJson: String): ToolExecutionResult {
        val result = router.execute(name, argumentsJson)
        return ToolExecutionResult(
            output = result.toJson(),
            success = result.ok,
            toolTitle = when (name) {
                NovexDocumentToolRouter.DOCUMENT_INSPECT -> "检查文档"
                NovexDocumentToolRouter.DOCUMENT_READ -> "读取文档"
                else -> "文档工具"
            },
        )
    }

    companion object {
        /** Translate the Novex-owned catalog at the provider boundary; never duplicate schemas here. */
        fun providerDefinitions(): List<AgentToolDefinition> =
            NovexToolCatalog.forCapabilities(setOf(NovexToolCapability.DOCUMENTS)).map { definition ->
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
}
