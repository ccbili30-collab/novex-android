package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.novex.domain.NovexConversationWorkspaceScope
import com.openminis.app.novex.domain.NovexConversationWorkspaceStore
import com.openminis.app.novex.domain.NovexConversationWorkspaceToolRouter
import com.openminis.app.novex.domain.NovexConversationWorkspaceTools
import com.openminis.app.novex.domain.NovexToolCapability
import com.openminis.app.novex.domain.NovexWorkspaceProvenance

/** Thin Android/provider adapter; storage paths and provider schemas stay outside the model contract. */
class NovexWorkspaceAgentTools(
    private val store: NovexConversationWorkspaceStore,
) {
    fun execute(
        name: String,
        argumentsJson: String,
        scope: NovexConversationWorkspaceScope,
        provenance: NovexWorkspaceProvenance,
    ): ToolExecutionResult {
        val router = NovexConversationWorkspaceToolRouter(
            NovexConversationWorkspaceTools(scope, store, provenance),
        )
        val result = router.execute(name, argumentsJson)
        return ToolExecutionResult(
            output = result.toJson(),
            success = result.ok,
            toolTitle = when (name) {
                NovexConversationWorkspaceToolRouter.WORKSPACE_INSPECT -> "检查工作区"
                NovexConversationWorkspaceToolRouter.WORKSPACE_READ -> "读取工作区"
                NovexConversationWorkspaceToolRouter.WORKSPACE_WRITE -> "写入工作区"
                NovexConversationWorkspaceToolRouter.WORKSPACE_EDIT -> "编辑工作区"
                NovexConversationWorkspaceToolRouter.WORKSPACE_COMPUTE -> "处理工作区"
                else -> "工作区工具"
            },
        )
    }

    companion object {
        fun providerDefinitions(): List<AgentToolDefinition> =
            NovexAgentToolCatalogAdapter.definitions(NovexToolCapability.WORKSPACE)
    }
}
