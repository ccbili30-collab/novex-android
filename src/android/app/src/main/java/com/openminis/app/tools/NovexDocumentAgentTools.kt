package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.novex.domain.NovexDocumentSnapshotStore
import com.openminis.app.novex.domain.NovexDocumentToolRouter
import com.openminis.app.novex.domain.NovexDocumentTools
import com.openminis.app.novex.domain.NovexToolCapability

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
            NovexAgentToolCatalogAdapter.definitions(NovexToolCapability.DOCUMENTS)
    }
}
