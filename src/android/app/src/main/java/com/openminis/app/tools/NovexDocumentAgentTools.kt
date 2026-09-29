package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import novex.core.NovexDocumentSnapshotStore
import novex.core.NovexDocumentToolRouter
import novex.core.NovexDocumentTools
import novex.core.NovexToolCapability

/** Thin provider adapter around the Novex-owned document tool contract. */
class NovexDocumentAgentTools(
    snapshots: NovexDocumentSnapshotStore,
    isAllowed: (novex.core.NovexResourceRef) -> Boolean,
) {
    private val scopedSnapshots = object : NovexDocumentSnapshotStore {
        override fun find(requested: novex.core.NovexResourceRef) =
            if (isAllowed(requested)) snapshots.find(requested) else null
        override fun findRevision(requested: novex.core.NovexResourceRef, revision: String) =
            if (isAllowed(requested)) snapshots.findRevision(requested, revision) else null
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
