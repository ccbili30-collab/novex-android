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

// Novex 资料学习管线：预检、计划、运行、详情、任务投影。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结。

internal fun ChatViewModel.prepareNovexLearningPreflight(
    collectionRef: NovexResourceRef,
    requestedModelId: String?,
    action: novex.core.NovexLearningPlanAction = novex.core.NovexLearningPlanAction.START,
): NovexLearningPreflightSnapshot? {
    if (collectionRef.value !in activeNovexSourceCollectionRefs) return null
    val model = currentModel ?: return null
    val provider = currentProvider ?: return null
    if (requestedModelId != null && requestedModelId != model.id) return null
    return novexLearningPlans.prepare(activeSessionId, collectionRef, action,
        { it.value in activeNovexSourceCollectionRefs }) { state, budget, fingerprint ->
        require(currentProvider === provider && currentModel == model) { "当前模型已变化，请重新准备计划" }
        buildNovexLearningPreflight(state, model, provider.name, budget, fingerprint)
    }
}

internal fun ChatViewModel.buildNovexLearningPreflight(state: NovexLearningState, model: LLMModel, providerName: String,
    proposedBudget: NovexLearningTokenBudget? = null, sourcePlanFingerprint: String? = null): NovexLearningPreflightSnapshot =
    novex.core.NovexLearningPreflightBuilder(novexDocumentRepository).build(state,
        novex.core.NovexLearningPlanningModel(model.id, providerName,
            effectiveContextWindowTokens(), model.maxOutputTokens ?: 4096, _lastTurnContextTokens.value),
        proposedBudget, sourcePlanFingerprint)

fun ChatViewModel.dismissNovexLearningPreflight() {
    _pendingNovexLearningPreflight.value = null
    refreshNovexLearningTaskProjection()
}

fun ChatViewModel.requestNovexLearningContinuation(recheckSources: Boolean) {
    val ref = currentNovexLearningCollectionRef() ?: return
    closeNovexLearningDetails()
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val action = if (recheckSources) novex.core.NovexLearningPlanAction.RECHECK
                else novex.core.NovexLearningPlanAction.CONTINUE
            _pendingNovexLearningPreflight.value = prepareNovexLearningPreflight(ref, null, action)
            _novexLearningError.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { _novexLearningError.value = failure.message ?: "无法准备续接，原进度保留" }
    }
}

fun ChatViewModel.requestNovexLearningBudgetExtension() = requestNovexLearningContinuation(false)

fun ChatViewModel.confirmNovexLearning(preflightId: String) {
    val preflight = _pendingNovexLearningPreflight.value?.takeIf { it.id == preflightId } ?: return
    _pendingNovexLearningPreflight.value = null
    viewModelScope.launch(Dispatchers.IO) {
        try { startNovexLearningPlan(preflight.collectionRef, preflightId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { _novexLearningError.value = failure.message ?: "资料整理未启动，原进度保留" }
    }
}

/** Native confirmation and authorized model calls share this save-before-schedule path. */
internal suspend fun ChatViewModel.startNovexLearningPlan(ref: NovexResourceRef, id: String, awaitCompletion: Boolean = false): novex.core.NovexToolResult {
    val provider = requireNotNull(currentProvider) { "当前没有可用模型连接" }
    val model = requireNotNull(currentModel) { "当前没有可用模型" }
    return novexLearningSession.start(ref, id, awaitCompletion,
        commit = { novexLearningPlans.commit(activeSessionId, ref, id,
            { it.value in activeNovexSourceCollectionRefs },
            { state, budget, fingerprint -> buildNovexLearningPreflight(state, model, provider.name, budget, fingerprint) },
            { requireNovexLearningExecutionContext(it, provider) }) },
        validate = { requireNovexLearningExecutionContext(it, provider) },
        execute = { state -> _novexLearningError.value = null; runNovexLearning(state, provider) })
}

fun ChatViewModel.pauseNovexLearning() = stopNovexLearningRun(cancel = false)

fun ChatViewModel.cancelNovexLearning() = stopNovexLearningRun(cancel = true)

internal fun ChatViewModel.stopNovexLearningRun(cancel: Boolean) {
    val ref = currentNovexLearningCollectionRef() ?: return
    viewModelScope.launch(Dispatchers.IO) { novexLearningSession.stop(ref, cancel) }
}

fun ChatViewModel.resumeNovexLearning() {
    val provider = currentProvider ?: return
    val ref = currentNovexLearningCollectionRef() ?: return
    viewModelScope.launch(Dispatchers.IO) {
        try {
            novexLearningSession.resume(ref,
                validate = { requireNovexLearningExecutionContext(it, provider) },
                execute = { runNovexLearning(it, provider) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { _novexLearningError.value = failure.message ?: "资料整理未恢复，已保存进度保留" }
    }
}

fun ChatViewModel.clearNovexLearningError() {
    _novexLearningError.value = null
}

fun ChatViewModel.closeNovexLearningDetails() {
    closeNovexCheckpoints()
    novexLearningDetailsRequest++
    _novexLearningDetails.value = null
    _novexLearningReadCoverage.value = emptyList()
    _novexLearningCollections.value = null
}

fun ChatViewModel.closeNovexCheckpoints() {
    novexCheckpointRequest++
    _novexCheckpoints.value = null
}

fun ChatViewModel.closeNovexConversationExport() {
    novexExportRequest++
    novexExportJob?.cancel()
    _novexConversationExport.value = null
}

fun ChatViewModel.prepareNovexConversationExport() {
    if (com.openminis.app.BuildConfig.UPDATE_CHANNEL != "preview") return
    if(_isStreaming.value) {
        _novexConversationExport.value = com.openminis.app.share.NovexConversationExportState(error = "当前回答尚未完成，请等待保存后再导出。不会自动终止回答。")
        return
    }
    novexExportJob?.cancel()
    val request = ++novexExportRequest
    _novexConversationExport.value = com.openminis.app.share.NovexConversationExportState(busy = true)
    novexExportJob = viewModelScope.launch {
        try {
            val sid = ensureSession()
            val settings = conversationSettingsSnapshot()
            val runtime = JSONObject().put("conversationId", sid).put("capturedAt", System.currentTimeMillis())
                .put("appVersionName", com.openminis.app.BuildConfig.VERSION_NAME).put("appVersionCode", com.openminis.app.BuildConfig.VERSION_CODE)
                .put("updateChannel", com.openminis.app.BuildConfig.UPDATE_CHANNEL).put("thinkingLevel", _thinkingLevel.value.name)
                .put("modelCapabilities", currentModel?.let { JSONObject(kotlinx.serialization.json.Json.encodeToString(LLMModel.serializer(), it)) })
                .put("configurationJson", settings.novexConfigurationJson)
                .put("conversationPrompt", settings.conversationPrompt).put("imageStylePrompt", settings.imageStylePrompt)
                .put("perTurnPrompt", settings.perTurnPrompt)
                .put("textStylePrompt", settings.textStylePrompt)
                .put("diceInjectionEnabled", settings.diceInjectionEnabled)
                .put("ledgerInjectionEnabled", settings.ledgerInjectionEnabled)
                .put("activeBranchPathIds", JSONArray(activeBranchPathIds))
                .put("modelId", currentModel?.id).put("modelName", currentModel?.displayName)
                .put("contextWindow", currentModel?.contextWindowTokens).put("maxOutputTokens", currentModel?.maxOutputTokens)
                .put("providerName", _providerName.value).put("selectedGroupId", _selectedGroupId.value).put("activeEntryId", _activeEntryId.value)
                .put("toolDefinitions", JSONArray(agentTools.map { it.toOpenAIJson() }))
                .put("scope", "导出时的当前设置和工具；历史实际请求见已保存装配记录")
            val app = novexApplication()
            val result = com.openminis.app.share.NovexConversationBundleExporter(context, app.database, app.novexWorkspace).export(sid, runtime)
            if(request == novexExportRequest && activeSessionId == sid) _novexConversationExport.value = com.openminis.app.share.NovexConversationExportState(result = result)
        } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch(failure: Exception) {
            if(request == novexExportRequest) _novexConversationExport.value = com.openminis.app.share.NovexConversationExportState(error = failure.message ?: "对话包导出失败")
        }
    }
}

fun ChatViewModel.showNovexCheckpoints() {
    closeNovexLearningDetails()
    val request = ++novexCheckpointRequest
    val sid = activeSessionId
    val path = activeBranchPathIds.toList()
    viewModelScope.launch(Dispatchers.IO) {
        try {
            val records = novex.core.NovexCheckpointContinuation(novexConversationWorkspaceStore).inspect(
                novex.core.NovexConversationWorkspaceScope(sid, path,
                    novex.core.NovexConversationWorkspaceScope.ROOT_BRANCH))
            if (request == novexCheckpointRequest && activeSessionId == sid && activeBranchPathIds == path) _novexCheckpoints.value = records
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (request == novexCheckpointRequest && activeSessionId == sid) _novexLearningError.value = "存档列表暂不可读：${failure.message}"
        }
    }
}

fun ChatViewModel.showNovexLearningCollections() {
    val refs = activeNovexSourceCollectionRefs.toList()
    val request = ++novexLearningDetailsRequest
    viewModelScope.launch(Dispatchers.IO) {
        val states = runCatching { refs.mapNotNull { novexLearningRepository.find(NovexResourceRef(it)) } }
            .getOrElse { _novexLearningError.value = "资料记录暂不可读：${it.message}"; return@launch }
        if (request == novexLearningDetailsRequest && activeNovexSourceCollectionRefs.containsAll(refs)) {
            _novexLearningCollections.value = states
        }
    }
}

fun ChatViewModel.selectNovexLearningCollection(ref: NovexResourceRef) {
    if (_novexLearningCollections.value?.none { it.collection.ref == ref } != false) return
    if (ref.value !in activeNovexSourceCollectionRefs) return
    _novexLearningCollections.value = null
    loadNovexLearningDetails(ref)
}

fun ChatViewModel.showNovexLearningDetails() {
    val ref = currentNovexLearningCollectionRef() ?: return
    loadNovexLearningDetails(ref)
}

internal fun ChatViewModel.loadNovexLearningDetails(ref: NovexResourceRef) {
    val sid = activeSessionId
    val request = ++novexLearningDetailsRequest
    viewModelScope.launch(Dispatchers.IO) {
        val (state, coverage) = runCatching {
            val state = novexLearningRepository.find(ref)
            val messages = chatRepository.loadActiveMessages(sid)
            val ledger = NovexContextUsageLedger.open(NovexContextUsageLedgerSnapshot(sid, chatRepository.novexContextUsage(sid)))
            state to ledger.readCoverageForActivePath(messages.map { it.id }.toSet())
                .filter { read -> state?.collection?.uniqueDocumentRefs.orEmpty().any { it.value == read.sourceId } }
        }.getOrElse {
            _novexLearningError.value = it.message ?: "已保存任务暂不可读"; return@launch
        }
        if (request == novexLearningDetailsRequest && activeSessionId == sid && ref.value in activeNovexSourceCollectionRefs) {
            _novexLearningReadCoverage.value = coverage
            _novexLearningDetails.value = state
            _novexLearningError.value = null
        }
    }
}

fun ChatViewModel.prepareNovexLearningFiles(onReady: (String) -> Unit) {
    viewModelScope.launch {
        try {
            // Navigation may still hold the draft route. Only the persisted address
            // can own imports and match the scope used by workspace tools.
            val sid = ensureSession()
            val refs = activeNovexSourceCollectionRefs.toList()
            withContext(Dispatchers.IO) {
                val messages = chatRepository.loadActiveMessages(sid)
                refs.forEach { value ->
                    val ref = NovexResourceRef(value)
                    val state = novexLearningRepository.find(ref) ?: return@forEach
                    val binding = novexLearningWorkspaceBinding(ref, sid, messages, mediaStore.mediaBaseDir) ?: return@forEach
                    novex.core.NovexLearningWorkspaceProjection(novexConversationWorkspaceStore, novexDocumentRepository)
                        .publish(state, binding.scope, binding.originals)
                    com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(novexConversationWorkspaceStore,
                        novexApplication().creativeArtifactRepository).reconcile(binding.scope)
                }
            }
            if (activeSessionId == sid) onReady(sid)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            closeNovexLearningDetails()
            _novexLearningError.value = "对话仓库暂时无法打开，已有资料保留：${failure.message}"
        }
    }
}

fun ChatViewModel.closeNovexLearningResponsePreview() {
    novexLearningResponsePreviewRequest++
    _novexLearningResponsePreview.value = null
}

fun ChatViewModel.previewLatestNovexLearningResponse() {
    val ref = currentNovexLearningCollectionRef() ?: return
    if (ref.value !in activeNovexSourceCollectionRefs || _novexLearningResponsePreview.value != null) return
    val request = ++novexLearningResponsePreviewRequest
    _novexLearningResponsePreview.value = "正在读取已保存的模型返回…"
    viewModelScope.launch(Dispatchers.IO) {
        val preview = runCatching {
            val receipt = novexLearningRepository.responses(ref).maxByOrNull { it.receivedAtMillis }
            if (receipt == null) "尚无已保存的模型返回记录。旧任务仍可查看原有学习笔记。" else buildString {
                val output = receipt.output
                append(output.title).append("\n\n")
                append(if (output.isComplete) "模型报告本次输出正常结束；内容尚未核验，笔记提交情况以任务进度为准。"
                    else "本次返回不完整，未计入已整理范围。")
                append("\n输入 ${output.inputTokens}、输出 ${output.outputTokens} 个词元")
                append(if (output.usageIsEstimated) "（含估算）。" else "（提供商计数）。")
                append("\n\n").append(output.body.ifBlank { "模型未返回正文。" })
            }
        }.getOrElse { "读取返回记录失败：${it.message ?: "文件不可读"}。任务与原文件未改动。" }
        if (request == novexLearningResponsePreviewRequest && ref.value in activeNovexSourceCollectionRefs) {
            _novexLearningResponsePreview.value = preview
        }
    }
}

fun ChatViewModel.dismissNovexLearningTaskNotice() {
    val status = _novexLearningTask.value?.status ?: return
    if (status in setOf(
            NovexLearningTaskStatus.PAUSED_BUDGET_REACHED,
            NovexLearningTaskStatus.CANCELLED,
            NovexLearningTaskStatus.PARTIAL_FAILURE,
            NovexLearningTaskStatus.COMPLETE,
        )
    ) {
        _novexLearningTask.value = null
        _novexLearningStatus.value = null
    }
}

internal suspend fun ChatViewModel.runNovexLearning(initial: NovexLearningState, provider: LLMProvider) {
    try {
        val binding = novexLearningWorkspaceBinding(initial.collection.ref, activeSessionId,
            chatRepository.loadActiveMessages(activeSessionId), mediaStore.mediaBaseDir)
        val projection = novex.core.NovexLearningWorkspaceProjection(novexConversationWorkspaceStore, novexDocumentRepository)
        val runner = NovexLearningReviewRunner(
            documents = novexDocumentRepository,
            responseJournal = novexLearningRepository,
            reviewer = providerNovexLearningReviewer(provider, requireNotNull(initial.task).preflight),
            saveCheckpoint = { checkpoint ->
                novexLearningRepository.save(checkpoint)
                binding?.let { projection.publish(checkpoint, it.scope, it.originals) }
                if (checkpoint.collection.ref.value in activeNovexSourceCollectionRefs) {
                    _novexLearningTask.value = checkpoint.task
                    _novexLearningStatus.value = checkpoint.task?.status
                }
            },
        )
        runner.run(initial)
        binding?.let { com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(novexConversationWorkspaceStore,
            novexApplication().creativeArtifactRepository).reconcile(it.scope) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val stored = novexLearningRepository.find(initial.collection.ref)
        val runningTask = stored?.task
        if (stored != null && runningTask != null && runningTask.preflightId == initial.task?.preflightId && runningTask.status in setOf(
                NovexLearningTaskStatus.INDEXING,
                NovexLearningTaskStatus.REVIEWING,
                NovexLearningTaskStatus.SYNTHESIZING,
            )
        ) {
            val paused = stored.copy(task = runningTask.pause(), lastFailure = failure.message ?: "资料通读失败，已保留完成进度")
            novexLearningRepository.save(paused)
            if (paused.collection.ref.value in activeNovexSourceCollectionRefs) {
                _novexLearningTask.value = paused.task
                _novexLearningStatus.value = paused.task?.status
            }
        }
        if (initial.collection.ref.value in activeNovexSourceCollectionRefs &&
            runningTask?.preflightId == initial.task?.preflightId) {
            _novexLearningError.value = failure.message ?: "资料通读失败，已保留完成进度"
        }
    }
}

internal fun ChatViewModel.currentNovexLearningCollectionRef(): NovexResourceRef? =
    _novexLearningDetails.value?.collection?.ref ?: _novexLearningTask.value?.collectionRef
        ?: activeNovexSourceCollectionRefs.lastOrNull()?.let(::NovexResourceRef)

internal fun ChatViewModel.requireNovexLearningExecutionContext(preflight: NovexLearningPreflightSnapshot, provider: LLMProvider) {
    require(currentProvider === provider) { "当前模型连接已变化，学习已暂停；请恢复原配置后继续" }
    val model = requireNotNull(currentModel) { "当前没有可用模型，学习已暂停" }
    novex.core.NovexLearningGate.requireExecutionContext(preflight,
        model.id, provider.name, novex.core.NovexLearningModelLimits(
            effectiveContextWindowTokens(), model.maxOutputTokens ?: 4096))
    require(preflight.collectionRef.value in activeNovexSourceCollectionRefs) {
        "当前对话分支不再包含这份资料集，学习已暂停"
    }
}

internal fun ChatViewModel.providerNovexLearningReviewer(provider: LLMProvider, preflight: NovexLearningPreflightSnapshot): NovexLearningReviewer =
    object : NovexLearningReviewer {
        override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
            requireNovexLearningExecutionContext(preflight, provider)
            val prompt = request.prompt
            val response = provider.sendMessage(
                messages = listOf(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = prompt.user,
                    ),
                ),
                systemPrompt = prompt.system,
                maxTokens = request.maxOutputTokens,
                temperature = null,
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            )
            return NovexLearningReviewOutput.fromProvider("${request.documentTitle} · 通读笔记",
                response.text, response.usage?.inputTokens, response.usage?.outputTokens,
                request.estimatedInputTokens, request.maxOutputTokens, response.stopReason)
        }

        override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
            requireNovexLearningExecutionContext(preflight, provider)
            val prompt = request.prompt
            val response = provider.sendMessage(
                messages = listOf(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = prompt.user,
                    ),
                ),
                systemPrompt = prompt.system,
                maxTokens = request.maxOutputTokens,
                temperature = null,
                tools = emptyList(),
                thinkingLevel = ThinkingLevel.OFF,
            )
            return NovexLearningReviewOutput.fromProvider("${request.collectionTitle} · 总结",
                response.text, response.usage?.inputTokens, response.usage?.outputTokens,
                request.estimatedInputTokens, request.maxOutputTokens, response.stopReason)
        }
    }

internal fun ChatViewModel.refreshNovexLearningTaskProjection() {
    val refs = activeNovexSourceCollectionRefs.toList()
    if (refs.isEmpty()) {
        _novexLearningTask.value = null
        _novexLearningStatus.value = null
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val restored = novexLearningSession.restore()
        if (activeNovexSourceCollectionRefs.toList() == refs) {
            _novexLearningTask.value = restored?.task
            _novexLearningStatus.value = restored?.task?.status
            _novexLearningError.value = restored?.lastFailure
        }
    }
}

internal fun ChatViewModel.novexHashedRef(kind: String, material: String): NovexResourceRef {
    require(kind.matches(Regex("[a-z-]+"))) { "Novex 引用类型无效" }
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return NovexResourceRef("novex://$kind/$digest")
}

