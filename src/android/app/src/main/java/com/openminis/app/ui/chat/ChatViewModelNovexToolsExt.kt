package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import novex.android.data.chat.MessageRow
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.data.attachments.NovexDocumentSnapshotExtractor
import com.openminis.app.data.attachments.containsAgentAttachmentMetadata
import com.openminis.app.data.attachments.stripAgentAttachmentMetadata
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import novex.android.data.chat.CompactMarkerRow
import novex.android.data.model.AgentContentPart
import novex.android.data.model.AgentToolDefinition
import novex.android.data.model.LLMError
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMModel
import novex.android.data.model.LLMStreamChunk
import novex.android.data.model.LLMUsage
import novex.android.data.model.ModelGroup
import novex.android.data.model.ThinkingLevel
import novex.android.data.model.hasImageInput
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.GenerateImageTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.NovexDocumentAgentTools
import com.openminis.app.tools.NovexLearningAgentTools
import com.openminis.app.tools.NovexWorkspaceAgentTools
import com.openminis.app.tools.NovexManagementTools
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import novex.content.effectiveRouting
import novex.content.effectiveTemporality
import novex.content.flattenModules
import novex.core.ConversationControlDefinition
import novex.core.ConversationControlOutcome
import novex.core.ConversationControlRegistration
import novex.core.InteractiveFictionRuntime
import novex.core.NovexConversationConfiguration
import novex.core.NovexConversationConfigurationCodec
import novex.core.NovexConversationConfigurationSnapshot
import novex.core.FileNovexDocumentSnapshotRepository
import novex.core.FileNovexLearningRepository
import novex.core.NovexBatchDocumentImporter
import novex.core.NovexBatchDocumentRequest
import novex.core.NovexDocumentBlockKind
import novex.core.NovexDocumentStatus
import novex.core.NovexDocumentToolRouter
import novex.core.NovexLearningPreflight
import novex.core.NovexLearningConfirmation
import novex.core.NovexLearningControlPolicy
import novex.core.NovexLearningCoordinator
import novex.core.NovexLearningPreflightRequest
import novex.core.NovexLearningPreflightSnapshot
import novex.core.NovexLearningReviewOutput
import novex.core.NovexLearningReviewRequest
import novex.core.NovexLearningReviewRunner
import novex.core.NovexLearningReviewer
import novex.core.NovexLearningSourceEstimate
import novex.core.NovexLearningState
import novex.core.NovexLearningSynthesisRequest
import novex.core.NovexLearningTaskStatus
import novex.core.NovexLearningTaskState
import novex.core.NovexLearningTokenBudget
import novex.core.NovexLearningToolRouter
import novex.core.NovexResourceRef
import novex.core.NovexReviewLedger
import novex.core.NovexSourceCollectionBuilder
import novex.core.PlaythroughState
import novex.core.PlaythroughStateRegistration
import novex.android.adapter.WorkspaceNovexContextLoader
import novex.core.AnswerIdentity
import novex.core.ContextSourceKind
import novex.core.ContextUsageRecord
import novex.core.NovexContextBudgetPolicy
import novex.core.NovexContextCandidate
import novex.core.NovexContextComposer
import novex.core.NovexContextComposition
import novex.core.NovexContextPromptFormatter
import novex.core.NovexCreativeDistillationPolicy
import novex.core.NovexContextUsageLedger
import novex.core.NovexContextUsageLedgerSnapshot
import novex.core.ManagedAccess
import novex.core.NovexContentAddress
import novex.core.NovexContentKind
import novex.core.NovexConversationCommand
import novex.core.reviewText
import novex.core.NovexManagementPlan
import novex.core.NovexManagementService
import novex.core.toToolJson
import novex.core.toModelToolJson
import com.openminis.app.ui.navigation.applyDraftManagedSubjects
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext
import novex.android.ContentPaths

// Novex 产品工具的执行器：管理/世界书/会话动作/面板/检查点/记忆/图像工具。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结，调用点零改动。

internal suspend fun ChatViewModel.executeNovexContentTool(name: String, argsJson: String, replyId: String, callId: String,
    requestId: String?): ToolExecutionResult = novexManagementMutex.withLock {
    prepareNovexConversationDrafts()
    val app = novexApplication()
    val executor = novex.core.NovexContentToolExecutor(app.novexWorkspace, novexManagementService(),
        novex.core.NovexCardFileOperations(
            novex.core.NovexCardSourceModules(novexDocumentRepository) { it.value in activeNovexDocumentRefs }),
        novex.core.NovexManagementTransaction { work -> app.database.withTransaction { work() } })
    // Source IO and chapter construction must finish before the settings commit locks the database.
    // The executor rechecks source visibility and the management plan against the locked configuration.
    val prepared = try { executor.prepare(name, argsJson) }
    catch (failure: Exception) {
        return@withLock ToolExecutionResult("内容准备未完成：${failure.message ?: "来源无法读取"}。没有写入卡片。",
            false, toolTitle = "内容准备未完成")
    }
    var tool: ToolExecutionResult? = null
    novexSettingsStore.update { configuration ->
        val result = executor.execute(prepared,
            novex.core.NovexContentToolExecutor.Request(configuration, currentNovexUserRequests(), replyId, callId, requestId)) { }
        tool = result.tool
        result.configuration
    }
    requireNotNull(tool)
}

internal fun ChatViewModel.novexApplication(): com.openminis.app.MinisApp {
    val application = context.applicationContext as? com.openminis.app.MinisApp
        ?: error("Novex 应用环境不可用")
    require(application.subsystemsReady()) { "Novex 数据服务尚未就绪" }
    return application
}

internal fun ChatViewModel.novexManagementService(): NovexManagementService {
    val application = novexApplication()
    return NovexManagementService(
        workspace = application.novexWorkspace,
        artifacts = application.creativeArtifactRepository,
    )
}

internal fun ChatViewModel.currentNovexMemoryScope(): novex.core.NovexMemoryScope {
    val configuration = currentNovexConfiguration()
    val characterVersionId = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId
        ?: return novex.core.NovexMemoryScope.nova()
    return novex.core.NovexMemoryScope.role(
        worldId = configuration.backgroundSettings.filter { it.subject.kind == NovexContentKind.WORLD }
            .map { it.subject.id }.sorted().joinToString("|").ifBlank { null },
        playerIdentityId = configuration.playerIdentity?.id,
        characterVersionId = characterVersionId,
    )
}

internal fun ChatViewModel.currentNovexMemoryReadContext(
    extraBranchId: String? = null,
) = novex.core.NovexMemoryReadContext(
    conversationId = realSessionId.ifEmpty { activeSessionId },
    activeBranchIds = (activeBranchPathIds + listOfNotNull(extraBranchId)).distinct(),
)

internal fun ChatViewModel.novexMemoryService() = novex.core.NovexMemoryService(
    store = novexMemoryStore,
    entryIdFactory = { java.util.UUID.randomUUID().toString() },
)

internal fun ChatViewModel.activeNovexMemoryFragment(): String? {
    if (!_memoryEnabled.value) return null
    return runCatching {
        val entries = novexMemoryService().inspect(
            scope = currentNovexMemoryScope(),
            source = currentNovexMemoryReadContext(),
            limit = 100,
        ).entries
        if (entries.isEmpty()) return null
        buildString {
            appendLine("<Novex长期记忆>")
            for (entry in entries) {
                val line = "- [${entry.ref.value}] ${entry.content}\n"
                if (length + line.length + 16 > 16_000) break
                append(line)
            }
            append("</Novex长期记忆>")
        }
    }.getOrNull()
}

internal suspend fun ChatViewModel.currentNovexUserRequests(): List<String> {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return emptyList()
    return novex.android.adapter.NovexManagementUserRequests.fromActiveMessages(
        chatRepository.loadActiveMessages(sid),
    )
}

internal suspend fun ChatViewModel.currentNovexCardTaskOutcome(blocks: List<AssistantBlock>): NovexCardCreationTask.Outcome? {
    val rows = chatRepository.loadActiveMessages(realSessionId.ifEmpty { sessionId })
    val start = rows.indexOfLast { row ->
        row.role == "user" && novex.android.adapter.NovexManagementUserRequests.fromActiveMessages(listOf(row))
            .isNotEmpty()
    }
    val persistedWrites = if (start < 0) emptyList() else rows.drop(start).flatMap { row ->
        runCatching {
            val parts = org.json.JSONArray(row.partsJson)
            (0 until parts.length()).mapNotNull { index ->
                val part = parts.getJSONObject(index)
                val value = part.optJSONObject("value")
                if (part.optString("type") != "toolResult" || value == null ||
                    value.optString("name") !in (com.openminis.app.tools.NovexCardFileTools.names + NovexManagementTools.APPLY + integratedCards.names())) null
                else AssistantBlock(value.optString("toolUseId"), "tool_use", value.optString("output"),
                    toolStatus = if (value.optBoolean("success")) ToolBlockStatus.SUCCESS else ToolBlockStatus.FAILED,
                    toolName = value.optString("name"))
            }
        }.getOrDefault(emptyList())
    }
    return NovexCardCreationTask.evaluate(persistedWrites + blocks)
}

internal suspend fun ChatViewModel.latestExplicitUserText(): String = currentNovexUserRequests().lastOrNull().orEmpty()

internal fun JSONObject.managementKindOrNull(): NovexContentKind? = when (val kind = optString("subject_kind").trim()) {
    "" -> null
    "world" -> NovexContentKind.WORLD
    "character_version" -> NovexContentKind.CHARACTER_VERSION
    "game" -> NovexContentKind.INTERACTIVE_FICTION
    "artifact" -> NovexContentKind.CREATIVE_ARTIFACT
    else -> error("未知管理对象类型：$kind")
}

internal fun JSONObject.managementSubjectOrNull(allowKindOnly: Boolean = false): NovexContentAddress? {
    val kind = managementKindOrNull()
    val id = optString("subject_id").trim()
    if (id.isBlank() && (kind == null || allowKindOnly)) return null
    require(kind != null && id.isNotBlank()) { "读取指定卡片时请同时提供卡片类型和编号；只列目录可省略编号" }
    return NovexContentAddress(kind, id)
}

internal fun ChatViewModel.executePresentChoicesTool(argsJson: String): ToolExecutionResult {
    return runCatching {
        // Providers disagree on whether `choices` is an array or a
        // JSON-encoded string.  Validate through the same parser used by
        // the renderer so a valid native array cannot fail execution and
        // leave the user without buttons.
        val (_, normalized) = parseNovexChoiceOptions(argsJson)
        require(normalized.size >= 2) { "At least two choices are required" }
        ToolExecutionResult(
            output = "已显示 ${normalized.size} 个可点击选项；用户也可以自由输入。",
            success = true,
            toolTitle = "提供行动选项",
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = "选项格式无效：${error.message ?: "请提供 JSON 字符串数组"}",
            success = false,
            toolTitle = "提供行动选项",
        )
    }
}

internal fun ChatViewModel.illustrationVisibleMessages(requestMessageId: String?): List<String> = _messages.value.filter {
    it.id in (activeBranchPathIds + listOfNotNull(requestMessageId)) && !it.isQueued && it.error == null && !it.isStreaming && it.role in setOf("user", "assistant")
}.map { if (it.role == "assistant") formalAssistantText(it.toolBlocks, it.content) else it.content }

internal suspend fun ChatViewModel.executeIllustrationTool(name: String, arguments: String, requestMessageId: String?): ToolExecutionResult = try {
    val output = NovexStoryImageCoordinator.tool(adoptedNovexConfiguration(), illustrationVisibleMessages(requestMessageId), name, arguments,
        if(integratedCardBinding()!=null)withContext(Dispatchers.IO){integratedCards.illustrations(requestMessageId)} else null)
    ToolExecutionResult(output.toString(), true, toolTitle = friendlyToolTitle(name))
} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
catch (failure: Exception) { ToolExecutionResult("图片选择未完成：${failure.message}", false, toolTitle = friendlyToolTitle(name)) }

/** Only called at normal completion. Never feeds pixels, tool output, or hidden text back into selection. */
internal suspend fun ChatViewModel.appendCompletedStoryImage(blocks: MutableList<AssistantBlock>, start: Int, messageId: String, requestMessageId: String?) {
    try {
        NovexStoryImageCoordinator.completed(adoptedNovexConfiguration(), illustrationVisibleMessages(requestMessageId), blocks, start,
            chatRepository.loadActiveMessages(realSessionId.ifEmpty { sessionId }), messageId,
            if(integratedCardBinding()!=null)withContext(Dispatchers.IO){integratedCards.illustrations(requestMessageId)} else null)?.let(blocks::add)
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { AppLogger.warning(ChatViewModel.TAG, "剧情插图未附加，正文保留") }
}

internal fun ChatViewModel.novexWorldbookActions(): novex.android.adapter.NovexWorldbookActions {
    val app = novexApplication()
    return novex.android.adapter.NovexWorldbookActions(app.novexWorkspace,
        novex.core.NovexGameWorldbooks(app.novexWorkspace) { block -> app.database.withTransaction { block() } })
}

internal suspend fun ChatViewModel.executeWorldbookAction(name: String, arguments: String): ToolExecutionResult {
    return try {
        var payload: JSONObject? = null
        novexSettingsStore.update { current ->
            val result = novexWorldbookActions().execute(current, name, JSONObject(arguments))
            payload = result.payload
            result.configuration
        }
        ToolExecutionResult(requireNotNull(payload).toString(), true, toolTitle = friendlyToolTitle(name))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) { ToolExecutionResult("世界书操作未完成：${failure.message}", false, toolTitle = friendlyToolTitle(name)) }
}

internal suspend fun ChatViewModel.executeConversationAction(name: String, arguments: String): ToolExecutionResult {
    return try {
        val app = novexApplication()
        val userStatements = currentNovexUserRequests()
        val updated = novexSettingsStore.update { current ->
            val actions = novex.android.adapter.NovexConversationActions(app.novexWorkspace, app.novexSnapshotMediaStore, userStatements)
            when (name) {
                com.openminis.app.tools.NovexConversationActionTools.SELECT_IDENTITY -> actions.selectIdentity(current, JSONObject(arguments))
                com.openminis.app.tools.NovexConversationActionTools.SET_PLAYER_IDENTITY -> actions.setPlayerIdentity(current, JSONObject(arguments))
                else -> actions.startGame(current, JSONObject(arguments))
            }
        }
        val label = when (val identity = updated.answerIdentity) {
            AnswerIdentity.Nova -> "诺瓦"
            is AnswerIdentity.PersonaPreset -> identity.label
            is AnswerIdentity.CharacterVersion -> app.novexWorkspace.characterForVersion(identity.versionId)?.character?.character?.name ?: "所选角色"
        }
        ToolExecutionResult(
            novex.core.NovexToolResult.success("conversation.configured",
                (if (name == com.openminis.app.tools.NovexConversationActionTools.SET_PLAYER_IDENTITY)
                    updated.playerIdentity?.let { "玩家身份已保存：${it.description}" } ?: "玩家身份已清空"
                else "对话设置已保存，当前回答身份：$label" + (updated.activeInteractiveFiction?.let { "；当前文游：${it.title}" } ?: "")) +
                    "。下一次回答将使用此设置，不要对用户输出内部编号。",
                data = mapOf("answer_identity" to novex.core.NovexAnswerIdentityCodec.encode(updated.answerIdentity),
                    "player_identity" to updated.playerIdentity?.let { mapOf("label" to it.label, "description" to it.description) },
                    "active_game" to updated.activeInteractiveFiction?.projectId)).toJson(),
            true, toolTitle = friendlyToolTitle(name))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) {
        ToolExecutionResult("对话设置未完成：${failure.message}", false, toolTitle = "更新对话设置")
    }
}

internal fun ChatViewModel.executePanelTool(argsJson: String): ToolExecutionResult {
    return runCatching {
        val args = JSONObject(argsJson)
        NovexPanelContent.parse(args)
        ToolExecutionResult(
            output = "内容已显示在当前会话的可折叠面板中。",
            success = true,
            toolTitle = args.optString("title", "资料面板"),
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = "面板内容无效：${error.message ?: "请检查内容"}",
            success = false,
            toolTitle = "显示资料面板",
        )
    }
}

internal suspend fun ChatViewModel.executeSaveCheckpointTool(
    argsJson: String,
    replyBranchId: String,
    sourceMessageId: String,
    toolCallId: String,
): ToolExecutionResult {
    return runCatching {
        val input = novex.core.NovexCheckpointInput.parse(argsJson)
        val name = input.name
        val summary = input.summary
        val stateJson = input.stateJson
        val scope = novex.core.NovexConversationWorkspaceScope(
            conversationId = activeSessionId,
            visibleBranchIds = activeBranchPathIds,
            writeBranchId = replyBranchId,
        )
        val configuration = currentNovexConfiguration()
        val sourceRows = chatRepository.historyFor(scope.conversationId)
        require(sourceRows.any { it.id == replyBranchId }) { "当前回复尚未保存，未创建存档，请稍后重试" }
        val sourcePath = (scope.visibleBranchIds + replyBranchId).distinct()
        val checkpoint = novex.core.NovexPlaythroughCheckpointFactory.create(
            id = novex.core.NovexFrozenContextCodec.digest("${scope.conversationId}|$replyBranchId|$toolCallId"),
            configuration = configuration,
            activePathIds = sourcePath,
            writeBranchId = replyBranchId,
            name = name,
            summary = summary,
            stateJson = stateJson,
            createdAtMillis = System.currentTimeMillis(),
            sourceEvents = novex.android.adapter.NovexCheckpointSourceCapture.capture(scope.conversationId, sourcePath, sourceRows),
        )
        val entry = novex.core.NovexPlaythroughCheckpointWriter(
            novexConversationWorkspaceStore,
        ).save(
            scope = scope,
            checkpoint = checkpoint,
            provenance = novex.core.NovexWorkspaceProvenance(
                conversationId = activeSessionId,
                branchId = replyBranchId,
                messageId = sourceMessageId,
                toolCallId = toolCallId,
            ),
        )
        val result = novex.core.NovexToolResult.success(
            code = "playthrough.checkpoint_saved",
            summary = "存档“$name”已保存，原始记录与软件状态已保留；本次保存不推进剧情。",
            data = mapOf(
                "checkpoint_id" to checkpoint.id,
                "workspace_ref" to entry.workspaceRef.value,
                "source_branch" to checkpoint.branchId,
                "model_summary_status" to "unverified_auxiliary",
                "source_read_tool" to "workspace_read",
            ),
            affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
            sideEffect = novex.core.NovexToolSideEffect.SESSION_REVERSIBLE,
        )
        ToolExecutionResult(
            output = result.toJson(),
            success = true,
            toolTitle = "存档完成",
        )
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        val result = novex.core.NovexToolResult.failure(
            code = "playthrough.checkpoint_failed",
            summary = error.message?.takeIf(String::isNotBlank) ?: "存档失败，请稍后重试",
        )
        ToolExecutionResult(
            output = result.toJson(),
            success = false,
            toolTitle = "保存对话进度",
        )
    }
}

internal suspend fun ChatViewModel.executeEndInteractiveFictionTool(argsJson: String): ToolExecutionResult = runCatching {
        var previous: NovexConversationConfigurationSnapshot? = null
        novexSettingsStore.update { current ->
            previous = current
            NovexConversationConfiguration.open(current).apply(novex.core.NovexConversationCommand.EndInteractiveFiction(
                JSONObject(argsJson).getString("playthrough_id"),
            )).snapshot
        }
        val configuration = NovexConversationConfiguration.open(requireNotNull(previous))
        val restoration = when {
            configuration.snapshot.activeInteractiveFiction == null -> "本局已经结束，没有再次切换身份。"
            configuration.snapshot.preGameAnswerIdentity != null -> "文游已结束，已恢复启动前的回答身份和玩家身份。"
            else -> "文游已结束。旧会话没有启动前的恢复点，因此保留现有身份。"
        }
        ToolExecutionResult(
            output = "$restoration 消息、状态、历史局次与存档仍然保留。",
            success = true, toolTitle = "结束文游",
        )
    }.getOrElse { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        ToolExecutionResult(output = "没有结束文游：${error.message ?: "保存失败"}", success = false, toolTitle = "结束文游")
    }

internal suspend fun ChatViewModel.executeRegisterControlsTool(
    argsJson: String,
    replyBranchId: String,
): ToolExecutionResult = runCatching {
        val args = JSONObject(argsJson)
        val controlsJson = args.jsonArrayText("controls")
        val updated = novexSettingsStore.update { current ->
            ConversationControlRegistration.registerAiControls(current, controlsJson, branchId = replyBranchId)
        }
        val count = InteractiveFictionRuntime.resolveControls(
            updated,
            activeBranchPathIds + replyBranchId,
        ).count {
            it.source == novex.core.ConversationControlSource.AI
        }
        ToolExecutionResult(
            output = "已在当前对话注册 $count 个快捷操作。",
            success = true,
            toolTitle = "更新快捷操作",
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = "快捷操作注册失败：${error.message ?: "格式无效"}",
            success = false,
            toolTitle = "更新快捷操作",
        )
    }

internal suspend fun ChatViewModel.executeUpdatePlaythroughStateTool(
    argsJson: String,
    turnMessageId: String,
): ToolExecutionResult = runCatching {
        activeStreamingTurnId = turnMessageId
        val before = _activePlaythroughState.value
        val updated = novexSettingsStore.update { configuration ->
            require(configuration.activeInteractiveFiction != null || integratedCards.binding(activeSessionId)!=null) { "请先选择互动卡片" }
            PlaythroughStateRegistration.applyUpdates(configuration = configuration, branchId = turnMessageId,
                updatesJson = JSONObject(argsJson).jsonArrayText("updates"),
                activePathIds = activeBranchPathIds)
        }
        val after = InteractiveFictionRuntime.resolveState(updated, activeBranchPathIds + turnMessageId)
        // 回合内即时可见（2026-09-15）：常规投影刷新解析的是"已持久化路径"，
        // 不含正在生成的这条消息——不在这里同步一次的话，AI 改了值、面板
        // 到回合结束前都还显示旧值，看起来就是"改不动"。
        _activePlaythroughState.value = after
        val changes = diffNovexPlaythroughState(before, after)
        if (changes.isNotEmpty()) {
            _novexDataUpdates.tryEmit(NovexDataUpdateEvent(turnMessageId, changes))
        }
        ToolExecutionResult(
            output = "已更新当前消息分支的本局状态；切换分支时会恢复对应状态。",
            success = true,
            toolTitle = "更新本局状态",
        )
    }.getOrElse { error ->
        ToolExecutionResult(
            output = "本局状态更新失败：${error.message ?: "格式无效"}",
            success = false,
            toolTitle = "更新本局状态",
        )
    }

internal fun JSONObject.jsonArrayText(key: String): String = when (val value = opt(key)) {
    is org.json.JSONArray -> value.toString()
    is String -> value
    else -> error("缺少 $key 数组")
}

/**
 * [T-android-vision-group / GH#182] Placeholder text for an image the CURRENT
 * main model can't natively see, to be carried on the outgoing image part and
 * substituted by the provider's T264 branch. Returns null when the main model
 * has native vision (pixels are attached, no placeholder needed) OR no Vision
 * Group is configured (provider falls back to its historical literal). When a
 * Vision Group IS configured, returns a hint naming [path] and steering the
 * model to call read_image — closing the loop with executeReadImageTool.
 */
internal fun ChatViewModel.visionPlaceholderFor(path: String?): String? {
    val nativeVision = currentModel?.hasImageInput == true
    if (nativeVision) return null
    if (!com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context)) return null
    return com.openminis.app.tools.VisionGroupResolver.noVisionImagePlaceholder(path)
}

/**
 * [T-android-vision-group / GH#182] read_image dispatch.
 *
 * Native-vision main models keep the original behaviour exactly: the tool
 * returns the pixels and the provider attaches them.
 *
 * A main model WITHOUT native image input only reaches here because a Vision
 * Group is configured (that's the tool-exposure gate in [agentTools]). For
 * that case we do NOT return pixels — a text-only model can't decode them and
 * the provider wire builder (T264, 已删上游 openai 包) silently drops them to a placeholder.
 * Instead we hand the bytes to the Vision Group, get a text DESCRIPTION back,
 * and return that as the tool output. `imageData` is left null so no pixels
 * are attached, but `imageFilePath` is preserved so the on-screen tool block
 * still shows the image the user's model "read". Mirrors iOS
 * AIChatViewModel+ConcurrentTools read_image branch.
 */
internal suspend fun ChatViewModel.executeReadImageTool(argsJson: String): ToolExecutionResult {
    val args = runCatching { JSONObject(argsJson) }.getOrElse {
        return ToolExecutionResult("读图参数不是有效的 JSON", false, toolTitle = "查看图片")
    }
    val artifactId = args.optString("artifact_id").trim()
    val base = if (artifactId.isEmpty()) {
        // Historical persisted calls may still contain a path. New provider schemas never do.
        ReadImageTool.executeLegacy(argsJson, activeSessionId, context)
    } else {
        val resolved = loadAccessibleImageArtifact(artifactId).getOrElse { error ->
            return ToolExecutionResult(
                error.message ?: "无法读取指定图片成果",
                false,
                toolTitle = args.optString("tool_title", "查看图片"),
            )
        }
        ReadImageTool.executeArtifact(
            argsJson = argsJson,
            artifactTitle = resolved.title,
            bytes = resolved.bytes,
            mimeType = resolved.mimeType,
            imageFilePath = resolved.file.absolutePath,
        )
    }
    return routeReadImageResult(base,argsJson)
}

internal suspend fun ChatViewModel.routeReadImageResult(base:ToolExecutionResult,argsJson:String):ToolExecutionResult {
    // [T-android-vision-group / GH#182] Optional caller instruction focusing
    // what to learn from the image.
    val customPrompt = try {
        JSONObject(argsJson).optString("prompt", "").trim().ifEmpty { null }
    } catch (_: Exception) { null }
    // Failed decode / missing file → unchanged.
    if (!base.success || base.imageData == null) return base
    val nativeVision = currentModel?.hasImageInput == true
    if (nativeVision) {
        // The model sees the pixels itself; a prompt adds no routing here, but
        // echo it as context so the tool block reflects the model's intent.
        return if (customPrompt != null) {
            base.copy(output = base.output + "\n\n[Requested focus: " + customPrompt + "]")
        } else base
    }

    val bytes = base.imageData
    val mime = base.imageMimeType ?: "image/jpeg"
    val result = com.openminis.app.tools.VisionGroupResolver.describe(
        repo = providerRepository,
        context = context,
        imageData = bytes,
        mimeType = mime,
        seed = kotlin.math.abs(argsJson.hashCode()),
        customPrompt = customPrompt,
        // [T-vision-group-attribution / GH#182] iOS rewrites the tool block's
        // live content here so the card names the model as it works. Android
        // has no equivalent channel — no tool streams partial output to its
        // card, and the progress label ("Minis is reading Image",
        // ChatToolFormatting.kt:102) is a static per-tool string. Building
        // that plumbing is a separate change, so for now the per-attempt
        // signal goes to the log, where a fallback is still traceable. The
        // RESULT-side attribution (which model answered, what was tried
        // first) is fully implemented and is what the user actually reads.
        onAttempt = { a ->
            android.util.Log.i(
                "VisionGroup",
                "[Vision] attempt ${a.index}/${a.total} via ${a.modelName}",
            )
        },
    )
    val framed = when (result) {
        is com.openminis.app.tools.VisionGroupResolver.VisionResult.Success ->
            com.openminis.app.tools.VisionGroupResolver.framedDescription(
                result,
                com.openminis.app.tools.VisionGroupResolver.groupName(providerRepository),
                question = customPrompt,
            )
        is com.openminis.app.tools.VisionGroupResolver.VisionResult.Failure ->
            com.openminis.app.tools.VisionGroupResolver.failureText(result.reason)
    }
    // Reading the file is not the same as seeing its contents. Propagate
    // vision failure so the receipt cannot report a completed image read.
    return base.copy(
        success = result is com.openminis.app.tools.VisionGroupResolver.VisionResult.Success,
        output = base.output + "\n\n" + framed,
        imageData = null,
        imageMimeType = null,
    )
}

/**
 * Mirror of iOS AIChatViewModel post-tool hook (Agent/Chat/AIChatViewModel.swift:5387 / :5408):
 * when the agent writes or edits a SKILL.md inside a `/skills/` directory
 * we ask SkillRepository to re-scan disk so the new skill is visible
 * immediately, without waiting for app restart.
 */
internal fun ChatViewModel.maybeReloadSkillsForPath(argsJson: String) {
    runCatching {
        val path = JSONObject(argsJson).optString("path", "")
        if (path.contains("/skills/") && path.endsWith("SKILL.md")) {
            skillRepository?.reloadFromDisk()
        }
    }
}

internal suspend fun ChatViewModel.executeGenerateImageTool(argsJson: String): ToolExecutionResult {
    val args = runCatching { JSONObject(argsJson) }.getOrElse {
        return ToolExecutionResult("生图参数不是有效的 JSON", false, toolTitle = "生成图片")
    }
    // [T-unified-image-reference] 参考图统一引用面（2026-09-16 用户批γ）：
    // conversation:图片编号 / card:卡片编号:资源编号 / artifact:成果编号；
    // 旧 reference_artifact_id 仍按 artifact 处理。
    val referenceRef = args.optString("reference_image").trim()
        .ifEmpty { args.optString("reference_artifact_id").trim() }
    val reference = if (referenceRef.isEmpty()) {
        null
    } else {
        when {
            referenceRef.startsWith("conversation:") -> {
                val imageId = referenceRef.removePrefix("conversation:")
                val file = runCatching { integratedConversationImages()[imageId] }.getOrNull()
                if (file == null) {
                    return ToolExecutionResult("对话图片目录里没有编号 $imageId；编号来自「对话图片目录」工具定义", false, toolTitle = "生成图片")
                }
                LLMMessage.ImagePart(file.readBytes(), "image/jpeg", null)
            }
            referenceRef.startsWith("card:") -> {
                val parts = referenceRef.removePrefix("card:").split(':', limit = 2)
                if (parts.size != 2) {
                    return ToolExecutionResult("card: 引用格式是 card:卡片编号:资源编号", false, toolTitle = "生成图片")
                }
                val bytes = runCatching { integratedCards.cardImageBytes(parts[0], parts[1]) }.getOrNull()
                    ?: return ToolExecutionResult("卡片 ${parts[0]} 上没有图片资源 ${parts[1]}；资源编号以 read_card 结构为准", false, toolTitle = "生成图片")
                LLMMessage.ImagePart(bytes.first, bytes.second, null)
            }
            else -> {
                val artifactId = referenceRef.removePrefix("artifact:")
                val resolved = loadAccessibleImageArtifact(artifactId).getOrElse { error ->
                    return ToolExecutionResult(
                        error.message ?: "无法读取指定参考图片成果",
                        false,
                        toolTitle = "生成图片",
                    )
                }
                LLMMessage.ImagePart(resolved.bytes, resolved.mimeType, null)
            }
        }
    }
    return GenerateImageTool.execute(
        argsJson = argsJson,
        sessionId = activeSessionId,
        context = context,
        repository = providerRepository,
        imageStylePrompt = _imageStylePrompt.value,
        referenceImage = reference,
    )
}

internal data class AccessibleImageArtifact(
    val title: String,
    val bytes: ByteArray,
    val mimeType: String,
    val file: java.io.File,
)

/** Resolves only current-conversation or explicitly mounted image artifacts. */
internal suspend fun ChatViewModel.loadAccessibleImageArtifact(artifactId: String): Result<AccessibleImageArtifact> =
    runCatching {
        val application = context.applicationContext as? com.openminis.app.MinisApp
            ?: error("Novex 成果库尚未就绪")
        check(application.subsystemsReady()) { "Novex 成果库尚未就绪" }
        val record = withContext(Dispatchers.IO) {
            application.creativeArtifactRepository.artifact(artifactId)
        } ?: error("找不到指定的图片成果")
        require(!record.artifact.isTrashed) { "指定图片成果已在回收站中" }
        require(
            record.artifact.kind in setOf(
                novex.core.CreativeArtifactKind.IMAGE,
                novex.core.CreativeArtifactKind.MAP,
            ),
        ) { "指定成果不是图片" }
        val conversationId = realSessionId.ifBlank { activeSessionId }
        require(
            novex.core.isCreativeArtifactAccessibleToConversation(
                originConversationId = record.artifact.origin.conversationId,
                attachments = record.attachments,
                configuration = currentNovexConfiguration(),
                conversationId = conversationId,
            ),
        ) { "当前对话没有读取这项图片成果的权限" }
        val revision = record.revisions.maxByOrNull { it.number }
            ?: error("图片成果没有可用版本")
        val bytes = withContext(Dispatchers.IO) {
            application.creativeArtifactRepository.bytes(artifactId)
        }
        val file = withContext(Dispatchers.IO) {
            application.creativeArtifactRepository.file(artifactId)
        }
        AccessibleImageArtifact(
            title = record.artifact.title,
            bytes = bytes,
            mimeType = revision.mimeType,
            file = file,
        )
    }

internal fun ChatViewModel.executeMemoryWriteTool(argsJson: String): ToolExecutionResult {
    val repo = activeMemoryRepository() ?: return ToolExecutionResult("Error: Memory not available", false)
    if (!_memoryEnabled.value) {
        val msg = "Memory writes are disabled for this session (user toggled /memory off). Reads remain available."
        return ToolExecutionResult(msg, false, toolTitle = "Memory (disabled)")
    }
    val result = MemoryTools.executeMemoryWrite(argsJson, repo)
    // Record for SessionMemorySheet
    val content = try {
        JSONObject(argsJson).optString("content", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = true,
        preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
        output = result.output,
        writtenContent = content,
    )
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

internal fun ChatViewModel.executeMemoryGetTool(argsJson: String): ToolExecutionResult {
    val repo = activeMemoryRepository() ?: return ToolExecutionResult("Error: Memory not available", false)
    val result = MemoryTools.executeMemoryGet(argsJson, repo, excludedBranchMemoryWrites)
    val keywords = try {
        JSONObject(argsJson).optString("keywords", "")
    } catch (_: Exception) { "" }
    _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
        title = result.toolTitle,
        isWrite = false,
        preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
        output = result.output,
        keywords = keywords,
    )
    return ToolExecutionResult(result.output, result.success, toolTitle = result.toolTitle)
}

// ─── UI Helpers ──────────────────────────────────────────────────────
