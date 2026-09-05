package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.novex.domain.NovexLearningPreflightResolver
import com.openminis.app.novex.domain.NovexLearningToolRouter
import com.openminis.app.novex.domain.NovexLearningTools
import com.openminis.app.novex.domain.NovexToolCapability

/** Thin provider adapter for the read-only learning preflight tool. */
class NovexLearningAgentTools(
    resolver: NovexLearningPreflightResolver,
) {
    private val router = NovexLearningToolRouter(NovexLearningTools(resolver))

    fun execute(name: String, argumentsJson: String): ToolExecutionResult {
        val result = router.execute(name, argumentsJson)
        return ToolExecutionResult(
            output = result.toJson(),
            success = result.ok,
            toolTitle = if (name == NovexLearningToolRouter.LEARNING_PREPARE) {
                "准备资料学习"
            } else {
                "资料学习"
            },
        )
    }

    companion object {
        fun providerDefinitions(): List<AgentToolDefinition> =
            NovexAgentToolCatalogAdapter.definitions(NovexToolCapability.LEARNING)
    }
}
