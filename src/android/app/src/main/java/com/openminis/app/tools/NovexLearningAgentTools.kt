package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.novex.domain.NovexLearningPreflightResolver
import com.openminis.app.novex.domain.NovexLearningToolRouter
import com.openminis.app.novex.domain.NovexLearningTools
import com.openminis.app.novex.domain.NovexToolCapability

/** Provider adapter; paid execution is supplied by the same host lifecycle used by native controls. */
class NovexLearningAgentTools(
    resolver: NovexLearningPreflightResolver,
    private val start: suspend (String, String) -> com.openminis.app.novex.domain.NovexToolResult,
) {
    private val router = NovexLearningToolRouter(NovexLearningTools(resolver))

    suspend fun execute(name: String, argumentsJson: String): ToolExecutionResult {
        val result = if (name == NovexLearningToolRouter.LEARNING_START) {
            try {
                val args = org.json.JSONObject(argumentsJson)
                start(args.getString("collection_ref"), args.getString("preflight_id"))
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                com.openminis.app.novex.domain.NovexToolResult.failure("learning.start_failed",
                    failure.message ?: "资料整理未能启动，已保存进度保留")
            }
        } else router.execute(name, argumentsJson)
        return ToolExecutionResult(
            output = result.toJson(),
            success = result.ok,
            toolTitle = when (name) {
                NovexLearningToolRouter.LEARNING_PREPARE -> "准备资料学习"
                NovexLearningToolRouter.LEARNING_READ -> "读取整理笔记"
                NovexLearningToolRouter.LEARNING_START -> "启动资料整理"
                else -> "资料学习"
            },
        )
    }

    companion object {
        fun providerDefinitions(): List<AgentToolDefinition> =
            NovexAgentToolCatalogAdapter.definitions(NovexToolCapability.LEARNING)
    }
}
