package com.openminis.app.tools

import novex.android.data.model.AgentToolDefinition
import novex.core.NovexConversationWorkspaceScope
import novex.core.NovexConversationWorkspaceStore
import novex.core.NovexConversationWorkspaceToolRouter
import novex.core.NovexConversationWorkspaceTools
import novex.core.NovexToolCapability
import novex.core.NovexWorkspaceProvenance

/** Thin Android/provider adapter; storage paths and provider schemas stay outside the model contract. */
class NovexWorkspaceAgentTools(
    private val store: NovexConversationWorkspaceStore,
) {
    fun execute(
        name: String,
        argumentsJson: String,
        scope: NovexConversationWorkspaceScope,
        provenance: NovexWorkspaceProvenance,
        visibleImports: Set<String>? = null,
        historyScopeKey: String? = null,
    ): ToolExecutionResult {
        val router = NovexConversationWorkspaceToolRouter(
            NovexConversationWorkspaceTools(scope,
                if (visibleImports == null) store else novex.core.NovexWorkspaceVisibility(store, visibleImports), provenance,
                { entry, raw -> novex.core.NovexCheckpointReadProjection.projectArchive(entry, raw, historyScopeKey) }),
        )
        val result = router.execute(name, argumentsJson)
        return ToolExecutionResult(
            output = result.toJson(),
            success = result.ok,
            toolTitle = when (name) {
                NovexConversationWorkspaceToolRouter.WORKSPACE_INSPECT -> "检查工作区"
                NovexConversationWorkspaceToolRouter.WORKSPACE_SEARCH -> "查找仓库资料"
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
