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

// Agent 主循环与工具调度：runAgentLoop/回合计数/上下文卸载/工具执行链。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结。

internal suspend fun ChatViewModel.runAgentLoop(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    fallbackStrategy: novex.android.data.model.FallbackStrategy = novex.android.data.model.FallbackStrategy.default,
    recoveryOrigin: AgentRunRecoveryOrigin = AgentRunRecoveryOrigin.FRESH,
) {
    val audit = com.openminis.app.diagnostics.ModelRequestAudit(
        java.io.File(context.filesDir, "novex/model-requests"), activeSessionId,
        latestNovexUserRequest(agentHistory)?.dbMessageId,
        JSONObject().put("entryId", _activeEntryId.value).put("groupId", _selectedGroupId.value)
            .put("displayModelId", currentModel?.id).put("providerModelId", provider.model.id)
            .put("displayName", _modelName.value).put("displayProvider", _providerName.value)
            .put("providerClass", provider.javaClass.simpleName))
    // 净眼 P1：在 runAgentLoop 层捕获本流身份（五个调用点都在 streamJob 协程内
    // 同层直调，此处捕获恰为 streamJob 本身）——旧流尸体的 end-finally 迟到时
    // 不再污染审计。注意不能在 runAgentLoopBody 内捕获：双层 withContext 的
    // 子 Job 永不等于 streamJob，stale 恒真=审计全盲。
    val jobAtLoopEntry = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
    try {
        withContext(audit) {
            require(currentProvider === provider && currentModel == provider.model) {
                "模型显示与当前连接不一致，请重新选择模型"
            }
            require(provider.model.isTextOutput && !novex.android.data.model.ChatModelSelection.imageOutput(provider.model)) {
                "所选模型用于图片输出，请选择聊天模型"
            }
            integratedCards.editingSession {
                runAgentLoopBody(provider,systemPrompt,fallbackProviders,fallbackStrategy,recoveryOrigin)
            }
            audit.event("run_returned")
        }
    } catch (failure: Exception) {
        audit.event(if (failure is kotlinx.coroutines.CancellationException) "cancelled" else "failed",
            JSONObject().put("errorType", failure.javaClass.name).put("message", safeModelDiagnostic(failure.message.orEmpty()))
                .put("causeType", failure.cause?.javaClass?.name)
                .put("cause", safeModelDiagnostic(failure.cause?.message.orEmpty())))
        throw failure
    } finally {
        // [T-run-phase] D1：审计记录终点相位，不执法。PR3：stale-job 身份比对 +
        // RunPhasePolicy 降级（清空已置 IDLE / 迟到尸体不得改写审计轨迹）。
        val staleJob = streamJob != null && streamJob !== jobAtLoopEntry
        val suppressed = staleJob || RunPhase.shouldSuppressEndPhase(runPhase, isWipingSession)
        if (suppressed) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "[RunPhase] end-phase suppressed (stale=$staleJob wiping=$isWipingSession phase=$runPhase)")
        } else {
            runPhaseTransitionTo(if (_canResume.value) RunPhase.AWAITING_RESUME else RunPhase.IDLE, "runAgentLoop-end")
        }
    }
}

internal suspend fun ChatViewModel.runAgentLoopBody(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate> = emptyList(),
    fallbackStrategy: novex.android.data.model.FallbackStrategy = novex.android.data.model.FallbackStrategy.default,
    recoveryOrigin: AgentRunRecoveryOrigin = AgentRunRecoveryOrigin.FRESH,
) {
    AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop ENTER provider=${provider.javaClass.simpleName} historySize=${agentHistory.size}")
    runPhaseTransitionTo(RunPhase.STREAMING, "runAgentLoop")
    val novexRequestMessage = latestNovexUserRequest(agentHistory)
    val failedToolProgress = com.openminis.app.agent.FailedToolProgress()
    if (recoveryOrigin == AgentRunRecoveryOrigin.RESUME) {
        if (recoverPendingToolTurn()) return
        if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            val reminder = "<system-reminder>用户要求继续此前未完成的回复。已经完成的操作以真实回执为准，不重复执行。</system-reminder>"
            val partsJson = JSONArray().put(JSONObject().put("type", "text").put("value", reminder)).toString()
            chatRepository.appendMessage(activeSessionId, "user", partsJson)
            withContext(Dispatchers.Main) { installActiveConversation(chatRepository.loadActiveConversation(activeSessionId)) }
        }
    }
    var preparedNovexContext = novexRequestMessage?.dbMessageId?.let { requestId ->
        try {
            prepareNovexRequestContext(systemPrompt, requestId, novexRequestMessage.content)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLogger.error(
                ChatViewModel.TAG_STREAM,
                "Novex context preparation failed ${error::class.java.simpleName}: ${error.message}",
            )
            throw IllegalStateException("本轮资料准备未完成，尚未开始回答：${error.message ?: "请重新检查采用的卡片"}", error)
        }
    }
    var requestSystemPrompt = preparedNovexContext?.systemPrompt ?: systemPrompt
    var requestContextRevision = novexContextRevision
    var preparedRequestId = novexRequestMessage?.dbMessageId
    // [T-android-mem-probe-trust] Send-path context shape. The existing
    // `messages-shape` probe only runs on session LOAD, so the 2026-08-15
    // log described the session as it was opened, never as it was sent —
    // and the send is where the memory goes. `historySize` alone says
    // nothing about payload: 17 messages carrying a 100 KB tool_result each
    // is a very different request from 1500 short ones. Logged once per
    // agent loop (not per turn) to stay cheap; the walk is O(parts) over
    // already-resident strings.
    runCatching {
        var chars = 0L
        var maxOne = 0
        var toolResults = 0
        var images = 0
        var imageBytes = 0L
        var audioChars = 0L
        var biggestRole = ""
        for (m in agentHistory) {
            var perMsg = m.content.length
            for (p in m.contentParts) {
                when (p) {
                    is AgentContentPart.ToolResult -> {
                        perMsg += p.content.length
                        toolResults++
                        // Inline image bytes never reach the char count, so
                        // track them separately — an image-heavy request is
                        // a different failure shape from a text-heavy one.
                        p.imageData?.let { images++; imageBytes += it.size }
                    }
                    is AgentContentPart.Text -> perMsg += p.text.length
                    is AgentContentPart.ImageData -> { images++; imageBytes += p.data.size }
                    else -> {}
                }
            }
            for (a in m.audioParts) audioChars += a.base64Data.length
            images += m.imageParts.size
            chars += perMsg
            if (perMsg > maxOne) { maxOne = perMsg; biggestRole = m.role.name }
        }
        AppLogger.info(
            ChatViewModel.TAG_STREAM,
            "[CtxShape] historySize=${agentHistory.size} totalChars=$chars " +
                "maxMsgChars=$maxOne maxMsgRole=$biggestRole toolResultParts=$toolResults " +
                "imageParts=$images imageBytes=$imageBytes audioB64Chars=$audioChars " +
                "approxTokens=${chars / 4} " +
                "${com.openminis.app.diagnostics.MemorySnapshot.capture().toLogString()}",
        )
    }
    // [T-android-queued-message-interrupt-on-toolclose] `assistantId` is
    // normally a single message id for the whole agent loop (iOS-parity:
    // multiple tool/text turns folded into one bubble). It is reassigned
    // ONLY when a queued mid-loop prompt is injected as a new turn: the
    // just-finished bubble is sealed and a fresh assistantId starts so the
    // queued user message renders BETWEEN them. `allToolBlocks` and
    // `accumulatedText` are also reset at that point so the new bubble
    // starts empty and `buildTurnParts(allToolBlocks, turnStartBlockIndex,
    // toolInputMap)` continues to slice only the current turn's blocks
    // (turnStartBlockIndex is captured at iteration start to 0 after reset).
    var assistantId = "assistant_${System.currentTimeMillis()}"
    val allToolBlocks = mutableListOf<AssistantBlock>()
    var accumulatedText = ""
    var lastContextTokens = 0

    // Fallback state — mirrors iOS streamWithGroupFallback
    var currentProvider = provider
    val streamRecovery = novex.core.NovexModelStreamRecovery(
        ChatViewModel.FallbackCandidate(provider, _activeEntryId.value.orEmpty()), fallbackProviders, fallbackStrategy,
        label = { it.provider.model.displayName }, rejected = { failure ->
            if (failure is LLMError.ProviderError) novex.android.data.model.ProviderFailure.rejectedInputTokens(failure.detail)?.let { used ->
                _contextEstimated.value = false
                _lastTurnContextTokens.value = used
                _contextUsageReady.value = true
            }
        })

    // Accumulate tool inputs across all turns (so persist includes all, not just current turn)
    val allToolInputs = mutableMapOf<String, String>()

    // Add placeholder assistant message (once). Mark as awaiting so the
    // "Minis is thinking" indicator shows during the initial request gap
    // before the first stream chunk arrives. Mirrors iOS isAwaitingModelResponse.
    // T300: snapshot the user's current thinking level at message
    // creation so the renderer can hide Deep Thinking blocks for
    // turns the user explicitly asked not to surface, even when a
    // forced-reasoning model still streams reasoning_content.
    val turnThinkingLevel = _thinkingLevel.value
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value + ChatMessage(
            id = assistantId, role = "assistant", content = "", isStreaming = true,
            isAwaitingModelResponse = true,
            thinkingLevel = turnThinkingLevel,
        )
    }

    // Tracks whether the loop was exited via a `break` (any reason — no
    // tool calls, msgIdx safety, etc.) or fell off the end of the range.
    // Set false by every break path that *isn't* "the model wanted to
    // keep going past ChatViewModel.MAX_AGENT_TURNS". Without this flag the post-loop
    // tail can't tell the runaway path apart from a normal turn ending,
    // which previously slapped a fake "200 turns hit" error on every
    // ordinary completion.
    var loopExitedNormally = false
    // [T-android-auto-compact-inloop] How many times the in-loop guard has
    // compacted during THIS runAgentLoop. Bounds compact-thrash: once the
    // cap is hit, a still-over-threshold history stops the turn rather than
    // compacting forever. Mirrors iOS maxInLoopCompactions.
    var inLoopCompactions = 0
    // Explicit `stop` + no content is a distinct upstream failure. Keep
    // independent one-shot budgets for the initial response and the
    // response after a tool result; neither path can loop indefinitely.
    val emptyResponseRetryState = EmptyResponseRetryState()
    // Some OpenAI-compatible relays intermittently flatten an explicitly
    // requested present_choices call into prose. Allow exactly one repair
    // request, restricted to that single tool, then stop visibly.
    var choiceRepairAttempted = false
    var forcedChoiceToolOnly = false
    // [T-choice-instruction-lifecycle] 恢复指令的寿命＝被强制的那一个逻辑
    // 请求。历史上这里直接把"Do not write prose"提醒永久写进 agentHistory
    // 的用户消息（2026-09-17 会话 297e156a 只出选项死循环的根因）；改为
    // 每轮迭代头部取走即清零，只在请求副本上追加（见
    // ChoiceInstructionLifecycle），内存与 DB 零残留。
    var pendingForcedChoiceHint: String? = null
    // [T-request-assembler] P2-1：队列注入产生的新逻辑轮的 turn 号——该轮
    // 与 turn==0 一样重读 DB 定 I1 基线（attempt lambda 读取后复位）。
    var pendingI1BaselineTurn = -1
    // Keep the request context explicit: re-inferring it from contentParts
    // would incorrectly turn the second post-tool attempt back into an
    // INITIAL request and grant it another retry budget.
    var emptyResponseContext = EmptyResponseContext.INITIAL
    for (turn in 0 until ChatViewModel.MAX_AGENT_TURNS) {
        val novexRequestMessage = latestNovexUserRequest(agentHistory)
        // Pre-allocate the persisted assistant-row identity so branch-local
        // state tools can bind to this exact turn before the row is written.
        val turnMessageId = java.util.UUID.randomUUID().toString()
        // Sanitize history before each API call (mirrors iOS pre-API validation)
        sanitizeAgentHistory()

        // Context window management: offload large tool outputs in older
        // messages to disk when the policy threshold for this model's
        // context window is crossed. Stubs in agentHistory still tell the
        // model where to file_read the original content. Mirrors iOS
        // AIChatViewModel.swift:4549.
        // [T-anthropic-context-window] Use contextWindowTokens (heuristic-
        // backed) instead of the raw nullable field, so offload triggers at
        // the correct fraction for heuristic-only Claude/Gemini models (1M)
        // rather than never firing when contextWindow is unset.
        // [T-context-window-live-read] Live read per loop turn — a stale
        // snapshot inside a long-running agent turn is exactly the iOS
        // fcc22b66 item-3 bug.
        effectiveContextWindowTokens()?.takeIf { it > 0 }?.let { window ->
            offloadContextIfNeeded(
                contextWindow = window,
                lastContextTokens = lastContextTokens,
            )
        }

        // [T-android-auto-compact-inloop] In-loop context guard (iOS
        // f70ac173). checkContextBeforeSend only runs at the SEND entry
        // point, so a single turn that fans out into many tool iterations
        // could blow past the thresholds mid-loop. Offload alone can't
        // recover when the bulk is the model's own text, and the turn would
        // slam into the provider's context ceiling.
        //
        // Runs AFTER offload so it judges the post-offload size.
        // New card excerpts can shrink; compact the retained conversation first,
        // then allocate the remainder to cards. Do not repeatedly compact a
        // static injected card because the preceding response reported it.
        // [T-request-assembler] PR2b ③：回合级输入缓存——估算与 attempt 共用
        // 同一 assemblyInputs 快照（旧序卡片场景双份只读 IO）。本迭代内此处
        // 与 attempt 之间无历史写点；COMPACTED continue 时快照自然废弃。
        val turnInputs = assemblyInputs()
        val retainedContext=if(integratedCards.binding(activeSessionId)!=null)
            estimatePreparedRequest(RequestAssembler.assemble(turnInputs).assembled,systemPrompt,agentTools)
            else null
        when (inLoopContextCheck(inLoopCompactions,retainedContext)) {
            ChatViewModel.InLoopContextAction.PROCEED -> {}
            ChatViewModel.InLoopContextAction.COMPACTED -> {
                // The next API call reads the freshly-compacted
                // effectiveAgentHistory automatically — compaction already
                // re-appends the recent turns, so no resume handoff is
                // needed. A compaction iteration is space management, not
                // task progress — but `turn` is a for-range val, so the
                // continue DOES consume the iteration index (the
                // pendingI1BaselineTurn bump below compensates for it). The
                // ChatViewModel.MAX_AGENT_TURNS ceiling is never reset, and
                // maxInLoopCompactions bounds compact-thrash within a turn,
                // so a loop that keeps compacting cannot defeat the runaway
                // backstop.
                inLoopCompactions++
                // [T-request-assembler] PR2a B1：压缩迭代顺延 I1 基线标志——注入
                // 设 N+1 后若本 continue 在 turn N+1 先于 attempt 命中，标志过期、
                // 该逻辑轮基线丢失（净眼 P2-2b）。净眼二审定案：循环不变量为
                // 标志 ≤ turn，故用 == 而非 >（> 恒假是死代码；>= 会复活过期
                // 标志到中轮造成 I1 误报）。
                if (pendingI1BaselineTurn == turn) pendingI1BaselineTurn = turn + 1
                continue
            }
            ChatViewModel.InLoopContextAction.STOP -> {
                // The loop cannot present a modal mid-flight, so stop
                // safely: user-visible notice + resumable, without the
                // turn-limit error overwrite.
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[AutoCompact] stopping turn: context exhausted and compaction cannot recover",
                )
                // [T-android-inloop-stop-thinking-orphan] Finalize the
                // assistant message before leaving the loop.
                //
                // The placeholder was created with isStreaming = true /
                // isAwaitingModelResponse = true. Only updateAssistantMessage
                // (isStreaming = false) or finalizeAtTurnLimit ever clears
                // those, and this branch reaches NEITHER: appendSystemInfo
                // appends a SEPARATE system row and never touches the
                // placeholder, while `loopExitedNormally = true` below
                // deliberately skips finalizeAtTurnLimit at the loop tail.
                //
                // Without this the bubble stays on "Minis is thinking"
                // forever — the streamJob's finally only clears the GLOBAL
                // _isStreaming, not the per-message flags. Reachable with no
                // failure at all: ContextPolicy gives every model with a
                // context window under 64K `exhaustedOnly = true`, so
                // crossing the exhaust line lands here directly.
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(
                        assistantId, accumulatedText, false, allToolBlocks,
                        isAwaitingModelResponse = false,
                    )
                    // Same orphan guard finalizeAtTurnLimit carries: the loop
                    // ran on IO while this hops to Main, so a late delta can
                    // re-add the side-channel entry after the drain, and
                    // mergeStreamingOverlay would then force isStreaming=true
                    // again with no further writer left to clear it.
                    clearStreamFlushState(assistantId)
                    if (_streamingById.value.containsKey(assistantId)) {
                        _streamingById.value = _streamingById.value - assistantId
                    }
                }
                // No persistAssistantTurn here: this guard runs BEFORE the
                // turn body, so nothing new has been produced yet and the
                // per-turn accumulators it would need
                // (turnStartBlockIndex / lastUsage / turnReasoningContent)
                // are not in scope. Everything from previous turns was
                // already persisted by those turns.
                appendSystemInfo(
                    text = "Context is full and could not be reduced further. " +
                        "Tap Continue to resume, or start a new chat.",
                    iconKind = "compact",
                )
                _canResume.value = shouldOfferResumeAfterFailure(recoveryOrigin)
                // Android's equivalent of iOS's `hitTurnLimit = false`: this
                // is a deliberate stop, NOT the runaway-ceiling path, so the
                // post-loop tail must not slap a fake "hit 200 turns" error
                // on it. finalizeAtTurnLimit is skipped; the notice above is
                // the user-visible explanation.
                loopExitedNormally = true
                break
            }
        }

        val contextChanged = preparedRequestId != novexRequestMessage?.dbMessageId || requestContextRevision != novexContextRevision || (turn>0 && integratedCards.binding(activeSessionId)!=null)
        if (contextChanged) {
            val updatedBasePrompt = buildSystemPrompt()
            preparedNovexContext = novexRequestMessage?.dbMessageId?.let { requestId ->
                prepareNovexRequestContext(updatedBasePrompt, requestId, novexRequestMessage.content)
            }
            requestSystemPrompt = preparedNovexContext?.systemPrompt ?: updatedBasePrompt
            requestContextRevision = novexContextRevision
            preparedRequestId = novexRequestMessage?.dbMessageId
        }
        val contextForTurn = preparedNovexContext
        if (novexRequestMessage?.dbMessageId != null) {
            val persistedRecord = (contextForTurn?.record ?: ContextUsageRecord(
                id = "request-context:$turnMessageId", requestMessageId = novexRequestMessage.dbMessageId,
                branchId = turnMessageId, answerIdentity = currentNovexConfiguration().answerIdentity,
                includedSources = emptyList(), usedTokens = 0,
                effectiveWindowTokens = effectiveContextWindowTokens() ?: 128_000,
            )).copy(
                id = "request-context:$turnMessageId",
                historyScopeKey = historyScopeKey(),
                responseMessageId = turnMessageId,
                branchId = turnMessageId,
            )
            try {
                chatRepository.recordNovexContextUsage(
                    realSessionId.ifEmpty { sessionId },
                    persistedRecord,
                )
                withContext(Dispatchers.Main) {
                    _messages.value = _messages.value.map { message ->
                        if (message.id == persistedRecord.requestMessageId) {
                            message.copy(novexContextUsage = persistedRecord)
                        } else {
                            message
                        }
                    }
                }
            } catch (error: Throwable) {
                AppLogger.error(
                    ChatViewModel.TAG_STREAM,
                    "Novex context usage persistence failed ${error::class.java.simpleName}: ${error.message}",
                )
                if (error is kotlinx.coroutines.CancellationException) throw error
                throw IllegalStateException("本轮访问范围未保存，尚未发送模型请求。请重试。", error)
            }
        }

        // Mark where this turn's blocks start in allToolBlocks so we can persist
        // only the NEW parts from this turn (not the full accumulated history).
        // Matches iOS's per-turn RawMessage persistence.
        val turnStartBlockIndex = allToolBlocks.size
        val streamedTurn = AssistantStreamTurn(turn, ::friendlyToolTitle)
        suspend fun publishStream(snapshot: AssistantStreamTurn.Snapshot) {
            while (allToolBlocks.size > turnStartBlockIndex) allToolBlocks.removeAt(allToolBlocks.lastIndex)
            allToolBlocks.addAll(snapshot.blocks)
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText + snapshot.text, true, allToolBlocks)
            }
        }
        val maxTokens = dynamicMaxTokens(provider, lastContextTokens)
        val thisTurnIsForcedChoiceRepair = forcedChoiceToolOnly
        forcedChoiceToolOnly = false
        // [T-choice-instruction-lifecycle] 取走即清零：本迭代内 attempt 的
        // 每次重发（连接重试/降级）都携带同一份强制指令——它们是同一个
        // 逻辑请求；下一轮迭代自然为空，指令到期。
        val forcedChoiceHintForTurn = pendingForcedChoiceHint
        pendingForcedChoiceHint = null

        var failedAttemptVisibleText = ""
        streamRecovery.collect(
            attempt = { endpoint ->
                currentProvider = endpoint.provider
                kotlinx.coroutines.currentCoroutineContext()[com.openminis.app.diagnostics.ModelRequestAudit]?.event(
                    "model_attempt", JSONObject().put("entryId", endpoint.entryId)
                        .put("modelId", endpoint.provider.model.id).put("modelName", endpoint.provider.model.displayName)
                        .put("providerClass", endpoint.provider.javaClass.simpleName))
                // [T-android-enhanced-cache] Stamp the per-turn Enhanced
                // Cache flag onto the active provider here — the single
                // choke point every turn passes through, regardless of how
                // currentProvider was (re)assigned by the fallback loop.
                // Non-Anthropic providers ignore it (cast fails silently).
                (currentProvider as? novex.android.transport.NovexTransportProvider)
                    ?.enhancedCache = _enhancedCacheEnabled.value
                // [T-request-assembler] Route through the assembler's input
                // collector (assemblyInputs → effectiveAgentHistory wrapper
                // elsewhere) so a populated [_compactSummary] is prepended as
                // a `<context-summary>` user message. Falls through to the raw
                // agentHistory when no compact has happened.
                val conversationTools = if (thisTurnIsForcedChoiceRepair) {
                    agentTools.filter { it.name == "present_choices" }
                } else {
                    agentTools
                }
                val requestToolsEnabled = conversationTools.isNotEmpty()
                // [T-request-assembler] P1-3 关账：九段全部过装配线，顺序定义只剩
                // assemble() 一处（A1）。骰子先掷（注入是纯函数、给定 diceRolls），
                // 尾三段闭包在此接上，前六段输入由 assemblyInputs() 统一收集。
                val diceRolls = if (_diceInjectionEnabled.value) {
                    List(com.openminis.app.data.RUNTIME_DICE_COUNT) { runtimeSecureRandom.nextInt(100) + 1 }
                } else {
                    emptyList()
                }
                val assembly = RequestAssembler.assemble(
                    turnInputs.copy(
                        pureChat = if (requestToolsEnabled) { history -> history } else ::pureChatHistory,
                        imageBudget = ::applyRequestImageBudget,
                        injections = { history ->
                            com.openminis.app.data.appendRuntimeInjections(
                                history,
                                _perTurnPrompt.value,
                                _diceInjectionEnabled.value,
                                _ledgerInjectionEnabled.value,
                                diceRolls,
                                _textStylePrompt.value,
                            )
                        },
                    ),
                )
                val assembledHistory = assembly.assembled
                // pureChat 只在工具禁用时删"纯结构化部件"消息；I1 断言用 pureChat
                // 之前的 assembled 基线，与 DB 侧同构（见 PreSendContract 头注）。
                val requestHistory = assembly.request
                // Per-turn injection: applied to the request copy only (never persisted,
                // never rendered) and BEFORE the estimate so it counts toward the
                // context budget. Rebuilt from the blank-checking helper on every
                // attempt, so tool-loop iterations each see exactly one copy at the
                // latest user turn — no accumulation across the loop.
                // Wenyou runtime adds the same-placement external state (turn count +
                // latest <账本> echo) and client-rolled dice values when enabled.
                val runtimeAudit = kotlinx.coroutines.currentCoroutineContext()[com.openminis.app.diagnostics.ModelRequestAudit]
                val perTurnInjection = com.openminis.app.data.perTurnInjectionContent(_perTurnPrompt.value)
                if (perTurnInjection != null) {
                    runtimeAudit?.event(
                        "per_turn_injection", JSONObject().put("chars", perTurnInjection.length),
                    )
                }
                if (_diceInjectionEnabled.value) {
                    runtimeAudit?.event("dice_injection", JSONObject().put("count", com.openminis.app.data.RUNTIME_DICE_COUNT))
                }
                if (_ledgerInjectionEnabled.value) {
                    runtimeAudit?.event(
                        "ledger_injection",
                        JSONObject().put(
                            "chars",
                            com.openminis.app.data.latestLedgerFromHistory(requestHistory)?.length ?: 0,
                        ),
                    )
                }
                val styleInjection = com.openminis.app.data.textStyleInjectionContent(_textStylePrompt.value)
                if (styleInjection != null) {
                    runtimeAudit?.event("text_style_injection", JSONObject().put("chars", styleInjection.length))
                }
                // Per-turn injection 已在装配线 injections 段完成：只改请求副本、
                // 不落库不渲染、在 estimate 之前计入预算（每 attempt 重建）。
                // [T-choice-instruction-lifecycle] 强制选项恢复指令同位追加：
                // 也只改请求副本（最后一个 user 消息），不进 agentHistory/DB，
                // 随本逻辑请求（含其连接重试）结束自动消失。
                val boundedHistory = ChoiceInstructionLifecycle.appendForcedChoiceHint(
                    assembly.injected, forcedChoiceHintForTurn,
                )
                // [T-presend-contract] PR 0 地震仪：出口三断言（I1 历史守恒 /
                // I2 图片守恒——沿用 [T-user-image-never-offload] 护栏语义—— /
                // I3 工具配对）。违反即拒发：宁可报错也不发明知残缺的请求
                // （conv9 队列注入空请求的教训）；证据三路落盘：audit 事件 +
                // wire-capture note 行 + 异常文案。
                // I1 仅在每轮发送的首请求（turn==0）重读 DB 定基线；工具循环
                // 中轮 DB 落后于内存，跳过（I2/I3 仍全量检查）。重读失败不
                // 拦截——地震仪不制造新故障。
                // [T-request-assembler] P2-1：队列注入产生的新逻辑轮与 turn==0
                // 一样重读 DB 定 I1 基线；P2-3：DB 侧基线过同款投影
                // （scope→compact→blank→orphan），不再裸数行数；A3：DB 侧
                // 影子装配与内存侧指纹对比，只观察不拦截——双真相源的分歧
                // 在此显形，PR 2 切换后应归零。
                val runI1Baseline = turn == 0 || turn == pendingI1BaselineTurn
                if (runI1Baseline) pendingI1BaselineTurn = -1
                var shadowDbAssembled: List<LLMMessage>? = null
                val dbExpectedMessages = if (runI1Baseline) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val conversation = chatRepository.loadActiveConversation(activeSessionId)
                            val rows = conversation.activeMessages
                            val scopeKey = historyScopeKey()
                            val dbScoped = novex.android.adapter.NovexScopedConversationHistory.project(
                                rows.map { it.toLLMMessage(this@runAgentLoopBody) },
                                rows,
                                chatRepository.novexContextUsage(activeSessionId),
                                scopeKey,
                            ).messages
                            val allowSummary = novex.core.NovexHistoryAccessScope.canReplay(
                                _cachedLatestMarker?.historyScopeKey, scopeKey,
                            )
                            val dbAssembled = RequestAssembler.assemble(
                                RequestAssembler.Inputs(
                                    scopedHistory = dbScoped,
                                    compactRebuild = { effectiveAgentHistoryUncounted(it, allowSummary) },
                                    orphanRepair = ::dropOrphanedToolParts,
                                    // 净眼 P1-1a：DB 侧同过 retention，与内存侧阶段对齐。
                                    retentionProject = ::budgetedRequestHistory,
                                ),
                            ).assembled
                            val sideOf = runCatching { chatRepository.sessionById(activeSessionId)?.sideOfSession }.getOrNull()
                            // 影子只比主线：侧边有快照前拼的合法差异（A3）。
                            if (sideOf == null) shadowDbAssembled = dbAssembled
                            dbAssembled.size
                        }
                    }.onFailure { error ->
                        // 净眼 P1-1：runCatching 会连 CancellationException 一起吞，
                        // 用户点停止时不能把取消当"重读失败"继续跑。照抄 6616/7828 的 rethrow 范式。
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        AppLogger.error(ChatViewModel.TAG_STREAM, "presend-contract DB recount failed: ${error::class.java.simpleName}: ${error.message}")
                    }.getOrNull()
                } else {
                    null
                }
                shadowDbAssembled?.let { dbSide ->
                    // 净眼 P1-1b/c：两级信号分流——structural（角色/DB id/工具对/
                    // 部件类计数，剔除内存专属 Text）是强信号=双真相源真分歧；
                    // structural 相同而全量指纹不同=已知投影噪音（卸载改写等），
                    // 只计数。内存专属部件（提示语/图路径注释）已在指纹内剔除。
                    val structMemory = RequestAssembler.structural(assembledHistory)
                    val structDb = RequestAssembler.structural(dbSide)
                    if (structMemory != structDb) {
                        val zipped = structMemory.zip(structDb)
                        val firstDivergence = if (zipped.none { (memory, db) -> memory != db }) {
                            "tail(len ${structMemory.size} vs ${structDb.size})"
                        } else {
                            zipped.indexOfFirst { (memory, db) -> memory != db }.toString()
                        }
                        AppLogger.warning(
                            ChatViewModel.TAG_STREAM,
                            "[ShadowAssembly] 结构分歧(强信号): memory=${structMemory.size} db=${structDb.size} " +
                                "firstDivergence=$firstDivergence（双真相源分歧，PR 2 切换后应归零；仅记录不拦截）",
                        )
                        runtimeAudit?.event(
                            "shadow_assembly_diff",
                            JSONObject()
                                .put("memory", structMemory.size)
                                .put("db", structDb.size)
                                .put("firstDivergence", firstDivergence),
                        )
                    } else {
                        val contentMismatches = RequestAssembler.fingerprint(assembledHistory)
                            .zip(RequestAssembler.fingerprint(dbSide))
                            .count { (memory, db) -> memory != db }
                        if (contentMismatches > 0) {
                            AppLogger.info(
                                ChatViewModel.TAG_STREAM,
                                "[ShadowAssembly] 内容差异(弱信号/已知噪音类): $contentMismatches 条 " +
                                    "（卸载改写/提示语版本等投影差异，结构与工具对一致）",
                            )
                            runtimeAudit?.event(
                                "shadow_assembly_content_noise",
                                JSONObject().put("count", contentMismatches),
                            )
                        }
                    }
                }
                val contractViolation = PreSendContract.firstViolation(
                    assembled = assembledHistory,
                    requestHistory = requestHistory,
                    boundedHistory = boundedHistory,
                    expectedFromDb = dbExpectedMessages,
                    compactInProgress = _cachedLatestMarker != null && !_compactSummary.value.isNullOrBlank(),
                )
                if (contractViolation != null) {
                    runtimeAudit?.event(
                        "presend_contract_violation",
                        JSONObject()
                            .put("invariant", contractViolation.invariant)
                            .put("detail", contractViolation.detail)
                            .put("expected", dbExpectedMessages ?: -1)
                            .put("assembled", assembledHistory.size),
                    )
                    com.openminis.app.provider.ProviderWireCapture.record(
                        currentProvider.name, "", "",
                        com.openminis.app.provider.ProviderWireCapture.RequestStats.of(boundedHistory),
                        note = "refused:${contractViolation.invariant}",
                    )
                    throw IllegalStateException(
                        "发送前检查未通过（${contractViolation.invariant}）：${contractViolation.detail} " +
                            "未发出任何请求；请导出对话包反馈，历史与图片文件均保留。",
                    )
                }
                val estimate = estimatePreparedRequest(boundedHistory, requestSystemPrompt, conversationTools)
                _contextEstimated.value = true
                _lastTurnContextTokens.value = estimate
                _contextUsageReady.value = true
                val limit = effectiveContextWindowTokens() ?: currentProvider.model.contextWindowTokens
                val output = minOf(dynamicMaxTokens(currentProvider, estimate), (limit - estimate - limit / 20).coerceAtLeast(1))
                require(estimate.toLong() + output + limit / 20 <= limit) {
                    "本轮上下文预计超出启用容量，尚未发送。请减少携带资料或压缩历史，原文保留。"
                }
                // [T-stream-stall-watchdog] 等待计时窗口：本 attempt 的
                // 请求即将发出→置位；首个任意类型 chunk 到达→清零。重试/
                // 降级每次 attempt 都会重走这里，计时自然归零重来。
                _streamAwaitingSince.value = System.currentTimeMillis()
                try {
                    currentProvider.streamMessage(
                        boundedHistory,
                        requestSystemPrompt, output,
                        tools = if (requestToolsEnabled) conversationTools else emptyList(),
                        thinkingLevel = if (currentModelSupportsReasoning) _thinkingLevel.value else ThinkingLevel.OFF,
                    ).collect { chunk ->
                        _streamAwaitingSince.value = null
                        streamedTurn.accept(chunk, currentProvider.streamTextIsMonolithic, ::publishStream)
                        if (chunk is LLMStreamChunk.Usage && streamedTurn.contextTokens > 0) {
                            lastContextTokens = streamedTurn.contextTokens
                            _contextEstimated.value = false
                            _lastTurnContextTokens.value = lastContextTokens
                            _contextUsageReady.value = true
                        }
                    }
                } finally {
                    // Covers normal completion AND every failure path
                    // (watchdog timeout, HTTP error, cancellation): the
                    // waiting hint must never outlive its attempt.
                    _streamAwaitingSince.value = null
                }
                streamedTurn.finish(::publishStream)
            },
            rollback = {
                val failed = streamedTurn.reset()
                failedAttemptVisibleText = failed.text
                publishStream(AssistantStreamTurn.Snapshot(failed.text, emptyList()))
            },
            retrying = { failure, attempt, limit ->
                kotlinx.coroutines.currentCoroutineContext()[com.openminis.app.diagnostics.ModelRequestAudit]?.event(
                    "retry", JSONObject().put("attempt", attempt).put("limit", limit)
                        .put("errorType", failure.javaClass.name).put("message", safeModelDiagnostic(failure.message.orEmpty())))
                withContext(Dispatchers.Main) {
                    _autoRetryAttempt.value = attempt
                    setTransientInlineError("模型连接暂时中断，正在重试（$attempt/$limit）")
                }
                AppLogger.warning(ChatViewModel.TAG_STREAM, "Stream retry $attempt/$limit: ${failure.message}")
            },
            countdown = { _autoRetryCountdown.value = it },
            settled = {
                _autoRetryAttempt.value = 0
                _autoRetryCountdown.value = 0
                withContext(Dispatchers.Main) { clearInlineError() }
            },
            switched = { previous, next, reasons ->
                currentProvider = next.provider
                withContext(Dispatchers.Main) { installStreamFallback(previous, next) }
                streamedTurn.addConnectionNotice(AssistantBlock(
                    id = "fallback_info_$turn", kind = "info",
                    content = reasons.joinToString("\n") + "\n已切换到 ${next.provider.model.displayName}",
                    toolTitle = "模型连接恢复", toolStatus = ToolBlockStatus.SUCCESS))
                publishStream(streamedTurn.snapshot().copy(text = failedAttemptVisibleText))
            },
            unavailable = ::unavailableGroupMembers,
        )

        // [T-stage2-memory] 回合收尾后后台检查水位刻度（不阻塞流式收尾）
        viewModelScope.launch { maybeConsolidateMemoryAfterTurn() }
        val completedStream = streamedTurn.snapshot()
        kotlinx.coroutines.currentCoroutineContext()[com.openminis.app.diagnostics.ModelRequestAudit]?.event(
            "model_result", JSONObject().put("modelId", currentProvider.model.id)
                .put("finishReason", streamedTurn.finishReason).put("textLength", completedStream.text.length)
                .put("toolCalls", streamedTurn.toolCalls.size))
        val turnText = completedStream.text
        val toolCalls = streamedTurn.toolCalls
        val toolCallSignatures = streamedTurn.toolSignatures
        val turnFinishReason = streamedTurn.finishReason
        val lastUsage = streamedTurn.usage
        val latestVisibleUserRequest = agentHistory.asReversed()
            .firstOrNull { message ->
                message.role == LLMMessage.Role.USER &&
                    (message.content.isNotBlank() || message.contentParts.any {
                        it is AgentContentPart.Text && it.text.isNotBlank() &&
                            !it.text.startsWith("<system-reminder>")
                    })
            }
            ?.let { message ->
                message.content.takeIf(String::isNotBlank)
                    ?: message.contentParts.filterIsInstance<AgentContentPart.Text>()
                        .firstOrNull { !it.text.startsWith("<system-reminder>") }
                        ?.text
            }
            .orEmpty()
        val choiceRecoveryAction = MissingChoiceToolRecoveryPolicy.decide(
            userRequest = latestVisibleUserRequest,
            assistantText = turnText,
            hasAnyToolCall = toolCalls.isNotEmpty(),
            hasPresentChoicesCall = toolCalls.any { (_, name, _) -> name == "present_choices" },
            finishReason = turnFinishReason,
            presentChoicesAvailable = agentTools.any { it.name == "present_choices" },
            forcedAttempt = thisTurnIsForcedChoiceRepair,
        )
        if (choiceRecoveryAction == MissingChoiceToolRecoveryAction.RETRY_PRESENT_CHOICES) {
            while (allToolBlocks.size > turnStartBlockIndex) {
                allToolBlocks.removeAt(allToolBlocks.lastIndex)
            }
            // [T-choice-instruction-lifecycle] 只置瞬态提示，不再改写
            // agentHistory 的用户消息。旧实现把"Do not write prose"永久
            // 留在内存历史里，之后每一轮请求模型都看得见，导致只出选项
            // 不出正文的死循环（2026-09-17 会话 297e156a）。现在提示由
            // 下一轮迭代头部取走、只附加到该次请求的副本上。
            pendingForcedChoiceHint = ChoiceInstructionLifecycle.FORCED_CHOICE_RECOVERY_HINT
            choiceRepairAttempted = true
            forcedChoiceToolOnly = true
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "missing present_choices retry 1/1: model=${currentProvider.model.id} " +
                    "finishReason=$turnFinishReason textLen=${turnText.length}",
            )
            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, true, allToolBlocks,
                    isAwaitingModelResponse = true,
                )
            }
            continue
        }
        // Accumulate text across turns
        accumulatedText += turnText

        // Build assistant contentParts for history
        val assistantParts = mutableListOf<AgentContentPart>()
        if (turnText.isNotEmpty()) {
            assistantParts.add(AgentContentPart.Text(turnText))
        }
        for ((id, name, args) in toolCalls) {
            // [T-android-gemini3-thoughtsig / #179] Attach the captured Gemini
            // 3.x signature so it round-trips through persistence and replay.
            assistantParts.add(AgentContentPart.ToolUse(id, name, args, thoughtSignature = toolCallSignatures[id]))
        }

        // Map toolUseId -> input JSON string for persistence (accumulated across turns)
        toolCalls.forEach { (id, _, args) -> allToolInputs[id] = args.toString() }
        val toolInputMap = allToolInputs
        val turnReasoningContent = streamedTurn.reasoningContent

        if (choiceRecoveryAction == MissingChoiceToolRecoveryAction.FAIL_AFTER_RETRY) {
            AppLogger.error(
                ChatViewModel.TAG_STREAM,
                "missing present_choices after retry: model=${currentProvider.model.id} " +
                    "finishReason=$turnFinishReason textLen=${turnText.length} attempted=$choiceRepairAttempted",
            )
            if (assistantParts.isNotEmpty()) {
                agentHistory.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = turnText,
                        contentParts = assistantParts,
                        reasoningContent = turnReasoningContent,
                        // The scope projection rebuilds unrowed messages
                        // from their persisted rows; without this id the
                        // live-session history loses the turn's text
                        // (see the sibling adds below).
                        dbMessageId = turnMessageId,
                    ),
                )
                val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
                val blockMeta = allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }
                persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta, turnMessageId)
            }
            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, false, allToolBlocks,
                    isAwaitingModelResponse = false,
                )
                setInlineError(context.getString(R.string.chat_error_present_choices_missing))
            }
            if (turn == 0) generateSessionTitleIfNeeded()
            loopExitedNormally = true
            break
        }

        val emptyContext = emptyResponseContext
        val completionAction = emptyResponseRetryState.decide(
            hasVisibleContent = turnText.isNotBlank(),
            hasToolCalls = toolCalls.isNotEmpty(),
            finishReason = turnFinishReason,
            context = emptyContext,
        )

        if (completionAction == AgentTurnCompletionAction.INTERRUPTED) {
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "stream interrupted: model=${currentProvider.model.id} turn=$turn " +
                    "finishReason=null textLen=${turnText.length} toolCalls=${toolCalls.size}",
            )
            // Never execute or replay a possibly truncated tool call. Keep
            // received text, and mark live cards failed for this UI session.
            for (index in turnStartBlockIndex until allToolBlocks.size) {
                val block = allToolBlocks[index]
                if (block.kind == "tool_use") {
                    allToolBlocks[index] = block.copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = "连接中断，工具未执行",
                    )
                }
            }
            val safeParts = assistantParts.filterIsInstance<AgentContentPart.Text>()
            if (safeParts.isNotEmpty()) {
                val dbId = persistAssistantTurn(
                    safeParts,
                    lastUsage,
                    turnReasoningContent,
                    toolBlockMeta = allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id },
                    messageId = turnMessageId,
                )
                agentHistory.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = turnText,
                        contentParts = safeParts,
                        dbMessageId = dbId,
                        reasoningContent = turnReasoningContent,
                    ),
                )
            }
            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, false, allToolBlocks,
                    isAwaitingModelResponse = false,
                )
                setInlineError(context.getString(R.string.chat_error_stream_dropped_partial))
            }
            _canResume.value = shouldOfferResumeAfterFailure(recoveryOrigin)
            if (turn == 0) generateSessionTitleIfNeeded()
            loopExitedNormally = true
            break
        }

        if (completionAction == AgentTurnCompletionAction.RETRY_EMPTY) {
            val reminder = when (emptyContext) {
                EmptyResponseContext.INITIAL ->
                    "<system-reminder>The previous model response was empty despite a normal stop. Retry this user request once. Return visible text or a structured tool call; do not return another empty response.</system-reminder>"
                EmptyResponseContext.AFTER_TOOL_RESULT ->
                    "<system-reminder>The previous model response after a tool result was empty despite a normal stop. Continue once with the next tool call(s) or a final visible answer; do not return another empty response.</system-reminder>"
            }
            val historyIndex = agentHistory.lastIndex
            if (historyIndex >= 0) {
                val message = agentHistory[historyIndex]
                val updatedParts = message.contentParts.toMutableList()
                if (emptyContext == EmptyResponseContext.AFTER_TOOL_RESULT) {
                    val resultIndex = updatedParts.indexOfLast { it is AgentContentPart.ToolResult }
                    if (resultIndex >= 0) {
                        val result = updatedParts[resultIndex] as AgentContentPart.ToolResult
                        updatedParts[resultIndex] = result.copy(
                            content = result.content + "\n\n$reminder",
                        )
                    } else {
                        updatedParts.add(AgentContentPart.Text(reminder))
                    }
                } else {
                    updatedParts.add(AgentContentPart.Text(reminder))
                }
                agentHistory[historyIndex] = message.copy(contentParts = updatedParts)
            }
            AppLogger.warning(
                ChatViewModel.TAG_STREAM,
                "empty response retry 1/1: model=${currentProvider.model.id} " +
                    "context=$emptyContext finishReason=$turnFinishReason",
            )
            continue
        }

        if (completionAction == AgentTurnCompletionAction.FAIL_EMPTY) {
            AppLogger.error(
                ChatViewModel.TAG_STREAM,
                "empty response failed after retry: model=${currentProvider.model.id} " +
                    "context=$emptyContext finishReason=$turnFinishReason textLen=${turnText.length}",
            )
            val window = effectiveContextWindowTokens()
            val usedCtx = lastUsage?.latestContextTokens ?: 0
            val contextNearFull = window != null && window > 0 && usedCtx > 0 &&
                usedCtx.toDouble() / window.toDouble() > 0.70
            val hint = when {
                emptyContext == EmptyResponseContext.AFTER_TOOL_RESULT ->
                    context.getString(R.string.error_empty_response_after_tool)
                contextNearFull -> context.getString(R.string.error_empty_response_context_large)
                else -> context.getString(R.string.error_empty_response_generic)
            }
            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, false, allToolBlocks,
                    isAwaitingModelResponse = false,
                )
                setInlineError(hint)
            }
            _canResume.value = shouldOfferResumeAfterFailure(recoveryOrigin)
            loopExitedNormally = true
            break
        }

        agentHistory.add(
            LLMMessage(
                role = LLMMessage.Role.ASSISTANT,
                content = turnText,
                contentParts = assistantParts,
                reasoningContent = turnReasoningContent,
                // Without the row id the scope projection cannot resolve
                // this turn from storage and blanks it, so a live session
                // silently sends its history without any prior assistant
                // replies — the model then re-answers every old question.
                dbMessageId = turnMessageId,
            ),
        )

        if (completionAction == AgentTurnCompletionAction.COMPLETE) {
            for (index in turnStartBlockIndex until allToolBlocks.size) {
                val block = allToolBlocks[index]
                if (block.isText) allToolBlocks[index] = block.copy(executionText = false)
            }
            val cardOutcome = currentNovexCardTaskOutcome(allToolBlocks)
            if (cardOutcome != null) {
                allToolBlocks.add(cardOutcome.block("card-task:$turnMessageId"))

            }

            appendCompletedStoryImage(allToolBlocks, turnStartBlockIndex, turnMessageId, novexRequestMessage?.dbMessageId)
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "runAgentLoop complete: model=${currentProvider.model.id} turn=$turn " +
                    "finishReason=$turnFinishReason textLen=${turnText.length}",
            )
            withContext(Dispatchers.Main) {
                updateAssistantMessage(assistantId, accumulatedText, false, allToolBlocks)
            }
            val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }
            persistAssistantTurn(turnParts, lastUsage, turnReasoningContent, blockMeta, turnMessageId)
            if (turn == 0) generateSessionTitleIfNeeded()
            loopExitedNormally = true
            break
        }
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop turn=$turn dispatching ${toolCalls.size} tool call(s), continuing")

        // [T-android-session-last-message-live-tool-call] Push a live
        // preview to the session list NOW, before the (possibly long-
        // running) tools execute. The authoritative assistant row isn't
        // written until turn end (persistAssistantTurn below), so without
        // this the home list shows a stale preview — or "No messages yet"
        // for a turn that opened with a tool call and no prior text —
        // for the entire tool duration. extractTextPreview prefers the
        // assistant's partial text and falls back to the tool summary, so
        // the list reflects exactly what the model just emitted. Mirrors
        // iOS overlaying the live VM's last message over the DB value.
        run {
            val livePreviewParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val liveMeta = allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }
            if (livePreviewParts.isNotEmpty()) {
                chatRepository.updateSessionPreview(
                    realSessionId.ifEmpty { sessionId },
                    encodeAssistantTurnParts(livePreviewParts, liveMeta),
                )
            }
        }

        // Persist the call before approval or execution can suspend. Later checkpoints update this same row.
        persistAssistantTurn(buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap), lastUsage,
            turnReasoningContent, allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }, turnMessageId)

        // Execute all tool calls
        val resultParts = mutableListOf<AgentContentPart>()
        val terminalUiToolIds = linkedSetOf<String>()
        var toolStopReason: String? = null
        for ((id, name, rawArgs) in toolCalls) {
            val stopped = toolStopReason
            if (stopped != null) {
                val skipped = "本轮已停止，该操作未执行：$stopped"
                resultParts.add(AgentContentPart.ToolResult(id, name, skipped, isError = true))
                val index = allToolBlocks.indexOfFirst { it.id == id }
                if (index >= 0) allToolBlocks[index] = allToolBlocks[index].copy(
                    toolStatus = ToolBlockStatus.FAILED, content = skipped)
                continue
            }
            val args = JSONObject(rawArgs.toString())
            // [T-android-overlay-tool-title] Pull tool_title uniformly
            // from args for ALL tools — without this browser_use's
            // tool_title never reached the overlay (only shell_execute
            // had a per-tool status override that surfaced it). Reading
            // it here also means new tools added later automatically
            // get title-in-overlay behavior without per-call plumbing.
            val dispatchToolTitle = try {
                args.optString("tool_title", "").takeIf { it.isNotBlank() }
            } catch (_: Exception) { null }
            SessionActivityTracker.updateToolStatus(
                status = "Running: $name",
                toolName = name,
                isRunning = true,
                toolTitle = dispatchToolTitle,
            )
            // JSON repair (T-tool-json-repair b2c4f8a6): salvage truncated /
            // type-mismatched / typo'd args BEFORE preflight rejects them.
            // Repairs a copy; raw model history and persisted input remain unchanged.
            // Downstream argsStr and preflight see the effective payload. Mirrors iOS repairToolArgs in
            // AIChatViewModel.swift.
            val repairs = com.openminis.app.provider.ToolJsonRepair.repair(
                name, args, streamedTurn.inputTail(id), agentTools,
            )
            if (repairs.isNotEmpty()) {
                AppLogger.warning(
                    "ToolPreflight",
                    "[ToolRepair] REPAIRED tool=$name id=$id strategies=[${repairs.joinToString(", ")}] " +
                        "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                        "rawTail=<<<${streamedTurn.inputTail(id)?.take(500) ?: ""}>>>"
                )
            }

            // [T-truncated-args-visibility #119] Non-null when THIS call's
            // arguments arrived truncated and were auto-closed. Only the
            // truncation strategy means the VALUE was cut short; coercion
            // and fuzzy-name repairs fix the shape of a complete argument.
            // Mirrors iOS truncationRepairTag.
            val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith("truncation+") }

            // [T-truncated-args-visibility #119] Refuse truncated WRITES.
            // Auto-closing an unterminated JSON string is indistinguishable
            // from the model ending `content` there, so a half file lands on
            // disk while UI and tool result both report success. For writes a
            // partial artifact is silent corruption of user data and is worse
            // than no write at all; read-only and shell tools keep the
            // repair-and-run behaviour. Mirrors iOS ConcurrentTools.
            if (truncationRepairTag != null && (name == "file_write" || name == "file_edit" ||
                name in com.openminis.app.tools.NovexCardFileTools.names || name in setOf(NovexManagementTools.PROPOSE, NovexManagementTools.APPLY))) {
                val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
                AppLogger.warning(
                    "ToolPreflight",
                    "[ToolRepair] REFUSED truncated write tool=$name id=$id strategy=$truncationRepairTag path=$path"
                )
                val modelMessage = buildString {
                    append("Error: This call was NOT executed. Its argument stream was truncated ")
                    append("in transit (repair strategy: $truncationRepairTag), so the `content` ")
                    append("your client sent was cut short and would have written an incomplete file")
                    if (path.isNotBlank()) append(" to $path")
                    append(". Nothing was written to disk — the target file is unchanged.\n\n")
                    append("The most likely cause is the response hitting its output-token limit ")
                    append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                    append("part, then append the rest with follow-up calls, rather than repeating ")
                    append("the same oversized call.")
                }
                val refusedIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (refusedIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - allToolBlocks[refusedIdx].startTimeMs
                    allToolBlocks[refusedIdx] = allToolBlocks[refusedIdx].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = "Blocked: arguments were truncated in transit",
                        durationMs = elapsed,
                    )
                    withContext(Dispatchers.Main) {
                        updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                    }
                }
                toolLoopDetector.record(name, parseToolParams(args.toString()),
                    result = null, errorMessage = modelMessage, toolCallId = id)
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = modelMessage,
                    isError = true,
                ))
                streamedTurn.takeInputHistory(id)
                continue
            }
            val argsStr = args.toString()
            val paramsMap = parseToolParams(argsStr)
            // Flip PENDING → RUNNING right before the execute dispatch so the UI
            // (tool pill spinner) shows the exact moment execution begins.
            val preIdx = allToolBlocks.indexOfFirst { it.id == id }
            if (preIdx >= 0 && allToolBlocks[preIdx].toolStatus == ToolBlockStatus.PENDING) {
                allToolBlocks[preIdx] = allToolBlocks[preIdx].copy(toolStatus = ToolBlockStatus.RUNNING)
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                }
            }

            // Loop-detector check BEFORE execution. CRITICAL outcomes short-circuit
            // the call: synthesize an error result so the tool_use/tool_result pair
            // stays balanced and the LLM sees the block reason.
            val precheck = toolLoopDetector.check(name, paramsMap)
            if (precheck.level == Level.CRITICAL) {
                val blockedMsg = precheck.message ?: "工具重复执行且没有进展"
                toolStopReason = "重复操作没有进展，已停止本轮自动执行。已完成的结果保留。"
                android.util.Log.w("ToolChain[VM]",
                    "[turn=$turn] tool BLOCKED by loop detector name=$name msg=$blockedMsg")
                AppLogger.warning("ChatViewModel",
                    "tool blocked by loop detector name=$name reason=$blockedMsg")
                val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdx >= 0) {
                    val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                    allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = blockedMsg,
                        durationMs = elapsed,
                    )
                }
                // Record the blocked attempt so consecutive blocks still
                // count toward the unknown-tool / circuit-breaker windows.
                toolLoopDetector.record(name, paramsMap,
                    result = null, errorMessage = blockedMsg, toolCallId = id)
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = blockedMsg,
                    isError = true,
                ))
                continue
            }

            // Preflight: reject empty / missing-required-field tool calls
            // BEFORE the UI flips to RUNNING and BEFORE executeTool() does
            // any actual work. Mirrors iOS preflightValidateToolCall in
            // AIChatViewModel.swift. Synthesizes a tool_result error so the
            // model can self-correct on the next turn without us spawning
            // shells or touching the filesystem on `{}` args.
            val preflightError = preflightValidateToolCall(name, args, agentTools)
            if (preflightError != null) {
                val chunkRing: List<String> = streamedTurn.takeInputHistory(id)
                AppLogger.warning(
                    "ToolPreflight",
                    "BLOCKED tool=$name id=$id reason=\"$preflightError\" " +
                        "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                        "chunkCount=${chunkRing.size} " +
                        "lastChunk=<<<${chunkRing.lastOrNull()?.take(500) ?: ""}>>>"
                )
                chunkRing.forEachIndexed { i, snap ->
                    AppLogger.warning(
                        "ToolPreflight",
                        "  chunk[$i] bytes=${snap.toByteArray(Charsets.UTF_8).size} raw=<<<${snap.take(500)}>>>"
                    )
                }
                // English literal — string resource lookup intentionally
                // avoided to keep this commit independent of any in-flight
                // strings.xml refactor in other sessions. Promote to a
                // localized R.string entry in a follow-up if needed.
                val uiMessage = "Blocked invalid tool call"
                val modelMessage = "Error: Tool call rejected before execution. $preflightError The arguments your client sent were empty or missing required fields — re-issue the call with all required parameters filled in. Do not retry with the same empty arguments."
                val blockIdxPre = allToolBlocks.indexOfFirst { it.id == id }
                if (blockIdxPre >= 0) {
                    val elapsedPre = System.currentTimeMillis() - allToolBlocks[blockIdxPre].startTimeMs
                    allToolBlocks[blockIdxPre] = allToolBlocks[blockIdxPre].copy(
                        toolStatus = ToolBlockStatus.FAILED,
                        content = uiMessage,
                        durationMs = elapsedPre,
                    )
                }
                toolLoopDetector.record(
                    toolName = name, params = paramsMap,
                    result = null, errorMessage = modelMessage, toolCallId = id
                )
                resultParts.add(AgentContentPart.ToolResult(
                    id = id, name = name,
                    content = modelMessage,
                    isError = true,
                ))
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(assistantId, accumulatedText, true, allToolBlocks)
                }
                continue
            }

            val preparedIndex = allToolBlocks.indexOfFirst { it.id == id }
            if (preparedIndex >= 0) {
                allToolBlocks[preparedIndex] = allToolBlocks[preparedIndex].copy(executionArgs = args.toString())
            }
            // Approval and replay must use exactly the same effective arguments. Keep the
            // original provider input beside this host metadata, never rewrite the transcript.
            persistAssistantTurn(buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap), lastUsage,
                turnReasoningContent, allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }, turnMessageId)


            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool START name=$name args=${argsStr.take(200)}")
            val result = executeTool(
                name,
                argsStr,
                id,
                allToolBlocks,
                assistantId,
                accumulatedText,
                turnMessageId,
                novexRequestMessage?.dbMessageId,
            )
            val capturedArtifact = captureCreativeArtifact(
                toolName = name,
                argsJson = argsStr,
                toolCallId = id,
                branchMessageId = turnMessageId,
                result = result,
            )
            val modelToolOutput = capturedArtifact?.let { (artifactId, title) ->
                novex.core.CreativeArtifactCapturePolicy.appendModelReceipt(
                    toolOutput = result.output,
                    artifactId = artifactId,
                    title = title,
                )
            } ?: result.output
            android.util.Log.d("ToolChain[VM]", "[turn=$turn] executeTool END name=$name success=${result.success} title=${result.toolTitle} outputLen=${result.output.length} output=${result.output.take(200)}")

            // Record post-execution. WARNING text is appended to the tool
            // result so the model sees it on its next turn. No block here —
            // CRITICAL only fires from check() and we already returned above.
            toolStopReason = result.stopAgentReason ?: failedToolProgress.record(name, paramsMap, result.success, result.output)
            val errMsgForDetector = if (!result.success) result.output else null
            val postRecord = toolLoopDetector.record(
                toolName = name,
                params = paramsMap,
                result = if (result.success) modelToolOutput else null,
                errorMessage = errMsgForDetector,
                toolCallId = id,
            )
            val outputForLLM = if (postRecord.level == Level.WARNING && postRecord.message != null) {
                AppLogger.debug("ChatViewModel",
                    "appending loop-warning to tool result name=$name key=${postRecord.warningKey}")
                "${modelToolOutput}\n\n${postRecord.message}"
            } else {
                modelToolOutput
            }

            val blockIdx = allToolBlocks.indexOfFirst { it.id == id }
            if (blockIdx >= 0) {
                val elapsed = System.currentTimeMillis() - allToolBlocks[blockIdx].startTimeMs
                // Keep live-streamed content if it has more data than the truncated result.
                // T263: takeLast(80) was applied uniformly, but it was sized for
                // shell_execute (long stdout streams where the tail is what
                // matters). For tools whose first line carries metadata —
                // file_read's `[path | N bytes | M lines | showing A-B of M]`
                // banner, file_write/file_edit confirmations, memory_* /
                // browser_use structured headers — clipping the head dropped
                // the banner entirely. iOS routes file_read through a
                // dedicated branch (AIChatViewModel.swift:5229) and avoids
                // this; mirror that intent by gating the trim to shell_execute.
                val existingContent = allToolBlocks[blockIdx].content
                val resultContent = if (name == "shell_execute") {
                    result.output.lines().takeLast(80).joinToString("\n")
                } else {
                    result.output
                }
                val finalContent = if (existingContent.length > resultContent.length) existingContent else resultContent
                // [T-truncated-args-visibility #119] A call built from
                // truncated args must not render as a clean success — that
                // silence is the reported bug. Show it with the same weight
                // as the blocked path. Mirrors iOS ConcurrentTools.
                val finalStatus = when {
                    result.success && truncationRepairTag != null -> ToolBlockStatus.FAILED
                    result.success -> ToolBlockStatus.SUCCESS
                    result.timedOut -> ToolBlockStatus.TIMEOUT
                    else -> ToolBlockStatus.FAILED
                }
                // T-bg-overlay phase 1: tool finished — drop the
                // notification's indeterminate progress bar so the
                // user can tell streaming has paused (LLM step) vs
                // a tool is in flight.
                // [T-overlay-glyph-typed-outcome] Pass the typed
                // outcome so the bg overlay glyph reflects the real
                // SUCCESS / TIMEOUT / FAILED result instead of
                // text-sniffing the stale "Running: foo" status.
                val toolOutcome = when (finalStatus) {
                    ToolBlockStatus.SUCCESS -> com.openminis.app.service.ToolOutcome.Success
                    ToolBlockStatus.TIMEOUT -> com.openminis.app.service.ToolOutcome.Timeout
                    ToolBlockStatus.FAILED -> com.openminis.app.service.ToolOutcome.Error
                    else -> com.openminis.app.service.ToolOutcome.Unknown
                }
                SessionActivityTracker.clearToolRunning(toolOutcome)
                android.util.Log.d("ToolChain[VM]", "[turn=$turn] block[$blockIdx] status→$finalStatus title=${result.toolTitle} contentLen=${finalContent.length}")
                allToolBlocks[blockIdx] = allToolBlocks[blockIdx].copy(
                    toolStatus = finalStatus,
                    content = finalContent,
                    toolTitle = result.toolTitle.ifEmpty { allToolBlocks[blockIdx].toolTitle },
                    durationMs = elapsed,
                    browserURL = result.pageURL ?: allToolBlocks[blockIdx].browserURL,
                    imageFilePath = result.imageFilePath ?: allToolBlocks[blockIdx].imageFilePath,
                )
            }

            // [T-truncated-args-visibility #119] Tell the MODEL its own
            // arguments were altered. Writes never reach here (refused
            // above); this covers the tools we still run repaired, where the
            // model would otherwise assume the args it emitted were the args
            // that ran. Mirrors iOS ConcurrentTools.
            val outputForLLMWithNote = if (truncationRepairTag != null) {
                outputForLLM + "\n\n<system-reminder>The argument stream for this call was " +
                    "truncated in transit and auto-closed by the client (repair strategy: " +
                    "$truncationRepairTag) before execution. The arguments actually used may be " +
                    "incomplete — verify the result and re-issue the call with complete " +
                    "arguments if anything is missing.</system-reminder>"
            } else {
                outputForLLM
            }

            if (isSuccessfulTerminalUiTool(name, result.success)) {
                // UI-only success: the visible card is the entire result.
                // Do not create a provider-facing ToolResult at all.
                terminalUiToolIds += id
            } else {
                resultParts.add(AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = outputForLLMWithNote,
                    isError = !result.success,
                    imageData = result.imageData,
                    imageMimeType = result.imageMimeType,
                    imageLinuxPath = result.imageLinuxPath,
                ))
            }
        }

        // `present_choices` is a terminal UI tool: once its arguments are
        // validated and the buttons are rendered, the user — not the model
        // — owns the next move. Do not manufacture a tool result or make an
        // unnecessary follow-up request. Persist the visible tool card as a
        // uiToolUse below, while removing it from provider-facing history.
        currentNovexCardTaskOutcome(allToolBlocks)?.let {
            allToolBlocks.add(it.block("card-task:$turnMessageId"))
        }
        if (terminalUiToolIds.isNotEmpty()) {
            if (isCompletedPresentationTurn(allToolBlocks.drop(turnStartBlockIndex))) {
                for (index in turnStartBlockIndex until allToolBlocks.size) {
                    val block = allToolBlocks[index]
                    if (block.isText) allToolBlocks[index] = block.copy(executionText = false)
                }
            }
            if (isCompletedPresentationTurn(allToolBlocks.drop(turnStartBlockIndex))) {
                appendCompletedStoryImage(allToolBlocks, turnStartBlockIndex, turnMessageId, novexRequestMessage?.dbMessageId)
            }
            val lastHistoryIndex = agentHistory.lastIndex
            if (lastHistoryIndex >= 0) {
                val lastMessage = agentHistory[lastHistoryIndex]
                val providerParts = withoutTerminalUiToolUses(
                    lastMessage.contentParts,
                    terminalUiToolIds,
                )
                if (lastMessage.content.isEmpty() && providerParts.isEmpty()) {
                    agentHistory.removeAt(lastHistoryIndex)
                } else {
                    agentHistory[lastHistoryIndex] = lastMessage.copy(contentParts = providerParts)
                }
            }

            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, false, allToolBlocks,
                    isAwaitingModelResponse = false,
                )
            }

            val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
            val blockMeta = allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }
            val assistantDbId = persistAssistantTurn(
                turnParts,
                lastUsage,
                turnReasoningContent,
                blockMeta,
                turnMessageId,
            )
            if (assistantDbId != null) {
                val historyIndex = agentHistory.indexOfLast {
                    it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null
                }
                if (historyIndex >= 0) {
                    agentHistory[historyIndex] = agentHistory[historyIndex].copy(dbMessageId = assistantDbId)
                }
            }

            // A provider may return another tool call beside the terminal
            // UI tool. Preserve those real tool results as balanced history,
            // but never include the present_choices result itself.
            val providerResultParts = resultParts
            if (providerResultParts.isNotEmpty()) {
                val toolResultDbId = persistToolResultMessage(providerResultParts)
                agentHistory.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = providerResultParts + listOf(AgentContentPart.Text(ChatViewModel.TOOL_RESULT_HINT)),
                        dbMessageId = toolResultDbId,
                    ),
                )
            }

            if (toolStopReason != null) {
                // A user stop or permission stop is a normal terminal
                // state, not an exceptional provider failure. The tool
                // result and assistant row are already persisted above;
                // expose the reason and leave the turn resumable without
                // sending it through the crash/error path.
                withContext(Dispatchers.Main) {
                    updateAssistantMessage(
                        assistantId, accumulatedText, false, allToolBlocks,
                        isAwaitingModelResponse = false,
                    )
                    setInlineError(toolStopReason!!)
                }
                _canResume.value = true
                loopExitedNormally = true
                break
            }
            if (turn == 0) generateSessionTitleIfNeeded()
            loopExitedNormally = true
            break
        }

        // Update UI with tool statuses. Mark as awaiting the next model
        // response so "Minis is thinking" shows during the network gap
        // between tool results being sent and the next turn's first chunk.
        // Mirrors iOS isAwaitingModelResponse.
        withContext(Dispatchers.Main) {
            updateAssistantMessage(
                assistantId, accumulatedText, true, allToolBlocks,
                isAwaitingModelResponse = true,
            )
        }

        // Persist the assistant+tools turn (with full input JSON and thinking).
        // Capture the persisted DB id so we can back-fill agentHistory's last
        // assistant entry — compact-marker boundary resolution depends on it.
        val turnParts = buildTurnParts(allToolBlocks, turnStartBlockIndex, toolInputMap)
        val blockMeta = allToolBlocks.drop(turnStartBlockIndex).associateBy { it.id }
        val assistantDbId = persistAssistantTurn(
            turnParts,
            lastUsage,
            turnReasoningContent,
            blockMeta,
            turnMessageId,
        )
        if (assistantDbId != null) {
            val lastIdx = agentHistory.indexOfLast { it.role == LLMMessage.Role.ASSISTANT && it.dbMessageId == null }
            if (lastIdx >= 0) {
                agentHistory[lastIdx] = agentHistory[lastIdx].copy(dbMessageId = assistantDbId)
            }
        }

        // Persist tool results as user-role message (mirrors iOS)
        val toolResultDbId = persistToolResultMessage(resultParts)

        // Add tool results to history
        // [T-tool-turn-pressure] 工具结果轮附隐形提示（防中转翻译层把工具轮
        // 变成"空用户消息"诱发重新开场）；软顶后追加收尾指令。
        agentHistory.add(LLMMessage(
            role = LLMMessage.Role.USER,
            content = "",
            contentParts = resultParts + buildList {
                add(AgentContentPart.Text(ChatViewModel.TOOL_RESULT_HINT))
                if (turn + 1 >= ChatViewModel.SOFT_TOOL_TURN_LIMIT) add(AgentContentPart.Text(ChatViewModel.TOOL_TURN_BUDGET_NOTE))
            },
            dbMessageId = toolResultDbId,
        ))
        emptyResponseContext = EmptyResponseContext.AFTER_TOOL_RESULT
        if (toolStopReason != null) {
            // A stop or permission result is a normal resumable boundary,
            // not a provider exception. The tool result is already in
            // history, so finish this turn without entering the failure
            // path that can reset the screen or duplicate the prompt.
            withContext(Dispatchers.Main) {
                updateAssistantMessage(
                    assistantId, accumulatedText, false, allToolBlocks,
                    isAwaitingModelResponse = false,
                )
                setInlineError(toolStopReason!!)
            }
            _canResume.value = true
            loopExitedNormally = true
            break
        }

        // Auto-title after first exchange (mirrors iOS generateSessionTitleIfNeeded)
        if (turn == 0) {
            generateSessionTitleIfNeeded()
        }

        // [T-android-queued-message-interrupt-on-toolclose] iOS d14174d3
        // parity. User report: "怎么样了" queued bubble (dashed border,
        // red X) stayed pending behind a long sync→export→read→gh-issue
        // tool chain — drainQueuedPrompts() only fires when the WHOLE
        // tool loop converges, so the queued prompt waited for the
        // entire plan to finish even though the user wanted to
        // interrupt the moment a tool closed.
        //
        // Fix: at the post-tool-result boundary (we just appended the
        // tool_result to agentHistory above), if there's anything in
        // the queue, abandon the rest of the running plan and inject
        // the queued prompt as a fresh user turn — the next iteration
        // makes a brand-new API call whose response targets the
        // queued prompt directly.
        //
        // Why not just append-and-continue: the agentHistory tail is
        // user(tool_result). Anthropic's mergeConsecutiveSameRole would
        // fold a directly-appended user(queued_text) into that
        // tool_result, so the model would read the queued prompt as
        // in-loop context for the previous turn (#579 / iOS regression).
        // Inject a minimal assistant bridge first so the sequence is
        //   …user(tool_result) → assistant(bridge) → user(queued) →
        //   …assistant(responds-to-queued).
        // The bridge lives in agentHistory only (NOT persisted) —
        // it's purely a wire-format spacer for the API call.
        if (_promptQueue.value.isNotEmpty()) {
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "📨[QueueInterrupt] turn=$turn ${_promptQueue.value.size} queued prompt(s) — interrupting after current tool call to start a standalone turn",
            )
            val handled = try {
                injectQueuedPromptsAsNewTurn(
                    finishedAssistantId = assistantId,
                    finishedAccumulatedText = accumulatedText,
                    finishedAllToolBlocks = allToolBlocks,
                )
            } catch (e: Exception) {
                Log.e(ChatViewModel.TAG, "injectQueuedPromptsAsNewTurn failed", e)
                null
            }
            if (handled != null) {
                // Switch loop-scope state to the new bubble. Subsequent
                // iterations populate `handled.newAssistantId` and slice
                // `allToolBlocks` from the freshly-zeroed start index
                // (turnStartBlockIndex captures allToolBlocks.size at
                // iteration top, so clearing means new turn's blocks
                // span [0..size).
                assistantId = handled.newAssistantId
                failedToolProgress.reset()
                toolLoopDetector.reset()
                accumulatedText = ""
                allToolBlocks.clear()
                allToolInputs.clear()
                _canResume.value = false
                // [T-request-assembler] P2-1：注入的新逻辑轮恢复 I1 基线检查。
                pendingI1BaselineTurn = turn + 1
                continue
            }
            // null return = empty-after-build / drain rejected; fall
            // through to normal next-turn dispatch so the queue doesn't
            // pin the loop indefinitely.
        }
    }
    // Two ways to leave the for-loop above:
    //   (a) `break` from the "no tool calls" happy-path → loopExitedNormally=true,
    //       updateAssistantMessage(...false...) already cleared streaming state.
    //   (b) `for (turn in 0 until ChatViewModel.MAX_AGENT_TURNS)` exhausted → flag stays false,
    //       which means the model kept asking for tool calls past the ceiling.
    //
    // (b) is the only case that needs the inline-error/Resume hand-holding;
    // (a) must NOT be touched or every normal completion gets a fake "hit
    // 200 turns" sticker (the bug user hit at v1.4.0-dev tip).
    if (!loopExitedNormally) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "runAgentLoop EXIT — hit ChatViewModel.MAX_AGENT_TURNS=$ChatViewModel.MAX_AGENT_TURNS, finalizing as resumable",
        )
        withContext(Dispatchers.Main) {
            finalizeAtTurnLimit(
                assistantId,
                accumulatedText,
                allToolBlocks,
                recoveryOrigin,
            )
        }
    } else {
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop EXIT (loop body ended naturally)")
    }
    // A cancelled job can finish downstream I/O after a new send has
    // already taken over. Do not let that stale projection replace the
    // newer conversation in the UI.
    if (streamJob !== coroutineContext[Job]) {
        AppLogger.info(ChatViewModel.TAG_STREAM, "runAgentLoop stale job skipped final conversation install")
        return
    }
    // Replace volatile streaming bubbles with the canonical active-path
    // projection. This attaches sibling counts to newly persisted reply
    // and edit branches and keeps tool-result rows in model history.
    val finishedConversation = chatRepository.loadActiveConversation(
        realSessionId.ifEmpty { sessionId },
    )
    withContext(Dispatchers.Main) {
        installActiveConversation(finishedConversation)
    }
}

/**
 * Finalize the current assistant message when [runAgentLoop] hits the
 * ChatViewModel.MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
 * "thinking" indicator clears, writes an inline error explaining *why*
 * we stopped, and arms canResume so the user can continue from here.
 * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
 */
internal fun ChatViewModel.finalizeAtTurnLimit(
    assistantId: String,
    text: String,
    blocks: List<AssistantBlock>,
    recoveryOrigin: AgentRunRecoveryOrigin,
) {
    updateAssistantMessage(
        assistantId, text, false, blocks,
        isAwaitingModelResponse = false,
    )
    // [T-android-thinking-indicator-linger] updateAssistantMessage drains
    // _streamingById[assistantId] above, but the agent loop ran on
    // Dispatchers.IO while this finalize hops to Main — a late streaming
    // delta can re-add the side-channel entry AFTER the drain, and since
    // the loop has now exited no further isStreaming=false write will ever
    // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
    // on any message with a side-channel entry, so that orphan keeps the
    // "thinking" row alive forever. Defensively drop the entry here as the
    // last Main-thread write of this turn.
    // [T-android-stream-flush-review] Cancel the trailing flush too, so it
    // can't re-add this orphan entry after we drop it on the error path.
    clearStreamFlushState(assistantId)
    if (_streamingById.value.containsKey(assistantId)) {
        _streamingById.value = _streamingById.value - assistantId
    }
    setInlineError(
        "Stopped after $ChatViewModel.MAX_AGENT_TURNS agent turns to prevent runaway " +
        "tool use. The model kept calling tools without finishing — tap " +
        "Resume to continue from here, or send a new message to start over.",
    )
    _canResume.value = shouldOfferResumeAfterFailure(recoveryOrigin)
}

/**
 * Instance entry point used by the tool-dispatch path. The real logic lives
 * in the companion so tests can reach it without a ChatViewModel.
 */
internal fun ChatViewModel.preflightValidateToolCall(
    name: String,
    args: JSONObject,
    tools: List<AgentToolDefinition>,
): String? = ChatViewModel.preflightValidateToolCallImpl(name, args, tools)

internal suspend fun ChatViewModel.captureCreativeArtifact(
    toolName: String,
    argsJson: String,
    toolCallId: String,
    branchMessageId: String,
    result: ToolExecutionResult,
): Pair<String, String>? {
    if (result.success && toolName in setOf("workspace_write", "workspace_edit", "workspace_compute")) {
        return runCatching {
            withContext(Dispatchers.IO) {
                val app = novexApplication()
                val scope = novex.core.NovexConversationWorkspaceScope(activeSessionId, activeBranchPathIds, branchMessageId)
                val record = com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(
                    novexConversationWorkspaceStore, app.creativeArtifactRepository,
                ).capture(toolName, result.output, scope) ?: return@withContext null
                runCatching { app.creativeArtifactDeviceDirectory.autoCopy(record, app.creativeArtifactRepository.bytes(record.artifact.id)) }
                record.artifact.id to record.artifact.title
            }
        }.onFailure { AppLogger.warning("CreativeArtifact", "工作空间成果待补登记：${it.message}") }.getOrNull()
    }
    val capture = novex.core.CreativeArtifactCapturePolicy.fromToolResult(
        toolName = toolName,
        argsJson = argsJson,
        success = result.success,
        imageBytes = result.imageData,
        imageMimeType = result.imageMimeType,
        imageHostPath = result.imageFilePath,
    ) ?: return null
    val application = context.applicationContext as? com.openminis.app.MinisApp ?: return null
    if (!application.subsystemsReady()) return null
    val conversationId = realSessionId.ifBlank { activeSessionId }
    return runCatching {
        withContext(Dispatchers.IO) {
            val bytes = capture.imageBytes ?: capture.sourcePath?.let { path ->
                ContentPaths.resolveSessionHostPath(
                    conversationId,
                    path,
                    context,
                )?.takeIf { file -> file.isFile }?.readBytes()
            } ?: return@withContext null
            val mimeType = capture.mimeType ?: mimeTypeForArtifactPath(capture.sourcePath)
            val record = application.creativeArtifactRepository.capture(
                title = capture.title,
                kind = capture.kind,
                bytes = bytes,
                mimeType = mimeType,
                origin = novex.core.CreativeArtifactOrigin(
                    conversationId = conversationId,
                    branchId = branchMessageId,
                    messageId = branchMessageId,
                    toolCallId = toolCallId,
                ),
                sourcePath = capture.sourcePath,
            )
            runCatching {
                application.creativeArtifactDeviceDirectory.autoCopy(record, bytes)
            }.onFailure { mirrorError ->
                AppLogger.warning(
                    "CreativeArtifact",
                    "device mirror failed artifact=${record.artifact.id} " +
                        "type=${mirrorError::class.java.simpleName} message=${mirrorError.message}",
                )
            }
            record.artifact.id to record.artifact.title
        }
    }.onFailure { error ->
        AppLogger.warning(
            "CreativeArtifact",
            "capture failed tool=$toolName type=${error::class.java.simpleName} message=${error.message}",
        )
    }.getOrNull()
}

internal fun ChatViewModel.mimeTypeForArtifactPath(path: String?): String = when (
    path?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
) {
    "md", "markdown" -> "text/markdown"
    "txt" -> "text/plain"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "novexworld", "novexcharacter", "novexgame", "zip" -> "application/zip"
    else -> "application/octet-stream"
}

internal suspend fun ChatViewModel.recoverPendingToolTurn(): Boolean {
    val conversation = chatRepository.loadActiveConversation(activeSessionId)
    val pending = novex.core.NovexPendingToolTurn.find(conversation.activeMessages) ?: return false
    val reply = conversation.activeMessages.filter { it.id == pending.replyId }.toChatMessages(this).singleOrNull()
    val blocks = reply?.toolBlocks.orEmpty().toMutableList()
    var recoveryStop: String? = null
    val terminal = pending.recover(invoke = { call ->
        val operation = novex.core.NovexToolOperation(activeSessionId, pending.replyId,
            call.id, call.name, call.arguments, friendlyToolTitle(call.name))
        val receipt = novexToolExecution.recordedResult(operation)
        val validation = preflightValidateToolCall(call.name, JSONObject(call.arguments), agentTools)
        val result = if (recoveryStop != null) ToolExecutionResult("本轮已停止，该操作未执行", false, stopAgentReason = recoveryStop)
        else receipt ?: if (validation != null) novexToolExecution.retireUnexecutable(operation, validation)
        else executeTool(call.name, call.arguments, call.id, blocks, pending.replyId,
            reply?.content.orEmpty(), pending.replyId, pending.requestId)
        recoveryStop = result.stopAgentReason ?: recoveryStop
        captureCreativeArtifact(call.name, call.arguments, call.id, pending.replyId, result)
        val index = blocks.indexOfFirst { it.id == call.id }
        if (index >= 0) blocks[index] = blocks[index].copy(
            toolStatus = if (result.success) ToolBlockStatus.SUCCESS else ToolBlockStatus.FAILED,
            content = result.output, toolTitle = result.toolTitle,
            imageFilePath = result.imageFilePath ?: blocks[index].imageFilePath)
        result
    }, persist = { call, result ->
        persistToolResultMessage(listOf(AgentContentPart.ToolResult(
            id = call.id, name = call.name, content = result.output, isError = !result.success,
            imageData = result.imageData, imageMimeType = result.imageMimeType, imageLinuxPath = result.imageLinuxPath,
        )))
    })
    if (terminal && isCompletedPresentationTurn(blocks)) chatRepository.markAssistantTextFormal(pending.replyId)
    withContext(Dispatchers.Main) { installActiveConversation(chatRepository.loadActiveConversation(activeSessionId)) }
    if (recoveryStop != null) {
        _canResume.value = true
        withContext(Dispatchers.Main) { setInlineError(recoveryStop!!) }
    }
    return terminal
}

internal suspend fun ChatViewModel.executeTool(
    name: String,
    argsJson: String,
    toolId: String,
    toolBlocks: MutableList<AssistantBlock>,
    assistantId: String,
    currentText: String,
    turnMessageId: String,
    requestMessageId: String?,
): ToolExecutionResult {
    val baseOperation = novex.core.NovexToolOperation(activeSessionId, turnMessageId,
        toolId, name, argsJson, friendlyToolTitle(name))
    if (name in ConversationRecall.names && novexToolExecution.recordedResult(baseOperation) != null) {
        return ToolExecutionResult("历史读取记录已结束；如仍需回查，请发起新的读取调用以核对当前范围。", false, toolTitle = "读取对话历史")
    }
    if (name !in ConversationRecall.names && currentNovexConfiguration().executionMode != novex.core.NovexExecutionMode.READ_ONLY) {
        novexToolExecution.recordedResult(baseOperation)?.let { return it }
    }
    val details = runCatching {
        when (name) {
            in integratedCards.names() -> integratedCards.review(name,argsJson)
            com.openminis.app.tools.NovexConversationActionTools.SET_PLAYER_IDENTITY -> {
                val args = JSONObject(argsJson)
                val previous = currentNovexConfiguration().playerIdentity?.description
                if (args.optBoolean("clear")) "清空本对话的玩家身份。当前身份：${previous.orEmpty()}"
                else "保存本对话的玩家身份：${args.getString("description")}" +
                    (previous?.let { "\n原身份：$it" } ?: "")
            }
            com.openminis.app.tools.NovexConversationActionTools.SELECT_IDENTITY -> {
                val args = JSONObject(argsJson)
                val label = when (args.optString("kind")) {
                    "nova" -> "诺瓦"
                    "custom" -> args.getString("name")
                    "character" -> requireNotNull(novexApplication().novexWorkspace.characterForVersion(args.getString("version_id"))) { "所选角色已不存在" }.character.character.name
                    else -> error("请选择有效的回答身份")
                }
                "将本对话的回答身份改为：$label。" + if (args.optBoolean("replace_player_identity")) "同时替换玩家身份。" else "已有玩家身份保持不变。"
            }
            com.openminis.app.tools.NovexConversationActionTools.START_GAME -> {
                val args = JSONObject(argsJson)
                val game = requireNotNull(novexApplication().novexWorkspace.interactiveFiction(args.getString("project_id"))) { "所选文游已不存在" }
                buildString {
                    append("在本对话启动《${game.project.name}》。")
                    if (args.optBoolean("replace_active_game")) append("将切换当前文游，原局次保留。")
                    args.optString("player_description").takeIf(String::isNotBlank)?.let { description ->
                        currentNovexConfiguration().playerIdentity?.let { append("\n原玩家身份：${it.description}") }
                        append("\n本局玩家身份：$description")
                    }
                }
            }
            in com.openminis.app.tools.NovexIllustrationTools.names -> "查看或选择本对话已采用的剧情插图，完成回答后展示。"
            in com.openminis.app.tools.NovexWorldbookTools.names -> novexWorldbookActions().review(currentNovexConfiguration(), name, JSONObject(argsJson))
            com.openminis.app.tools.NovexMemoryAgentTools.APPLY -> novexMemoryExecutor.review(
                currentNovexMemoryScope(), currentNovexMemoryReadContext(), JSONObject(argsJson).getString("proposal_id"))
            NovexManagementTools.APPLY -> {
                val plan = novexManagementService().planForExecution(currentNovexConfiguration(),
                    JSONObject(argsJson).getString("proposal_id"))
                requireNotNull(plan) { "变更计划不存在，请重新准备" }.reviewText()
            }
            NovexLearningToolRouter.LEARNING_START -> {
                val args = JSONObject(argsJson)
                novexLearningPlans.review(activeSessionId, NovexResourceRef(args.getString("collection_ref")),
                    args.getString("preflight_id")) { it.value in activeNovexSourceCollectionRefs }
            }
            else -> ""
        }
    }.getOrElse {
        return novexToolExecution.retireUnexecutable(baseOperation, it.message ?: "无法读取本次变更，请重新准备")
    }
    val operation = novex.core.NovexToolOperation(
        activeSessionId, turnMessageId, toolId, name, argsJson, friendlyToolTitle(name), details)
    return novexToolExecution.execute(operation, { currentNovexConfiguration().executionMode }) {
        executeAuthorizedTool(name, argsJson, toolId, toolBlocks, assistantId, currentText, turnMessageId, requestMessageId)
    }
}

internal suspend fun ChatViewModel.executeAuthorizedTool(
    name: String, argsJson: String, toolId: String, toolBlocks: MutableList<AssistantBlock>,
    assistantId: String, currentText: String, turnMessageId: String, requestMessageId: String?,
): ToolExecutionResult {
    // T330: tri-state permission gating moved into the offload IPC
    // handler (OffloadGate). The CLIs land there whether the LLM
    // emitted a named tool call or a raw shell command, so the gate
    // is consistent across both paths. The pre-check that lived here
    // (`permissionTools = {calendar, location, …}`) was effectively
    // dead since these tools have no native ChatViewModel executor
    // — they always fall through to shell_execute or the offload
    // bridge, which is now where checkPermission runs.
    val toolTitle = try { JSONObject(argsJson).optString("tool_title", name) } catch (_: Exception) { name }

    return when (name) {
        in ConversationRecall.names -> withContext(Dispatchers.IO) {
            val originals = chatRepository.loadActiveMessages(activeSessionId).map { it.toLLMMessage(this@executeAuthorizedTool) }
            val history = scopedHistory(originals).messages
            val budget = ((effectiveContextWindowTokens() ?: 128_000) / 32).coerceIn(512, 6000)
            runCatching { ConversationRecall.execute(name, JSONObject(argsJson), history, budget) }
                .fold({ ToolExecutionResult(it, true, toolTitle = if (name.startsWith("search")) "搜索对话历史" else "读取历史原文") },
                    { ToolExecutionResult(it.message ?: "历史读取失败", false, toolTitle = "读取对话历史") })
        }
        FileReadTool.NAME -> {
            val result = FileReadTool.execute(argsJson, activeSessionId, context)
            // Record skill usage when SKILL.md under /var/minis/skills/<id>/ is read.
            if (result.success) {
                runCatching {
                    val readPath = JSONObject(argsJson).optString("path", "")
                    if (readPath.isNotEmpty()) {
                        skillRepository?.skillIdFromPath(readPath)?.let { sid ->
                            skillRepository.recordSkillUse(sid)
                        }
                    }
                }
            }
            result
        }
        FileWriteTool.NAME -> FileWriteTool.execute(argsJson, activeSessionId, context).also {
            if (it.success) maybeReloadSkillsForPath(argsJson)
        }
        FileEditTool.NAME -> FileEditTool.execute(argsJson, activeSessionId, context).also {
            if (it.success) maybeReloadSkillsForPath(argsJson)
        }
        // T178: pass sessionId + context so read_image routes through
        // resolveSessionHostPath like file_read/write/edit do — without
        // these, the tool consults the global last-writer-wins
        // bindMounts map and would surface another session's
        // /var/minis/{workspace,attachments,offloads,browser} files.
        ReadImageTool.NAME -> executeReadImageTool(argsJson)
        GenerateImageTool.NAME -> executeGenerateImageTool(argsJson)
        "shell_execute" -> com.openminis.app.tools.ToolExecutionResult(
            // R0 沙箱退役灰度：工具目录已不含 shell_execute；此处只兜历史回放或
            // 模型幻觉调用，明确拒绝而不是执行（P2.5 计划表）。
            output = "shell_execute is no longer available on this device. Tell the user this operation is unsupported and suggest attaching the material as a document instead.",
            success = false,
        )
        "browser_use" -> com.openminis.app.tools.ToolExecutionResult(
            // [P3.3 裁军] 内置浏览器全家退役：此处只兜历史回放或旧客户端
            // 请求，让模型明确告知用户该操作不受支持（可自行在系统浏览
            // 器打开相关链接）。
            output = "browser_use is no longer available on this device. Tell the user this operation is unsupported.",
            success = false,
        )
        "memory_write" -> executeMemoryWriteTool(argsJson)
        "memory_get" -> executeMemoryGetTool(argsJson)
        com.openminis.app.tools.NovexMemoryAgentTools.INSPECT -> novexMemoryExecutor.inspect(
            currentNovexMemoryScope(), currentNovexMemoryReadContext(), argsJson)
        com.openminis.app.tools.NovexMemoryAgentTools.PROPOSE -> novexMemoryExecutor.propose(
            currentNovexMemoryScope(), currentNovexMemoryReadContext(turnMessageId), turnMessageId,
            requestMessageId, argsJson)
        com.openminis.app.tools.NovexMemoryAgentTools.APPLY -> novexMemoryExecutor.apply(
            currentNovexMemoryScope(), currentNovexMemoryReadContext(), argsJson).also { if (it.success) novexContextRevision++ }
        com.openminis.app.tools.NovexConversationActionTools.SELECT_IDENTITY,
        com.openminis.app.tools.NovexConversationActionTools.SET_PLAYER_IDENTITY,
        com.openminis.app.tools.NovexConversationActionTools.START_GAME -> executeConversationAction(name, argsJson)
        in com.openminis.app.tools.NovexWorldbookTools.names -> executeWorldbookAction(name, argsJson)
        in com.openminis.app.tools.NovexIllustrationTools.names -> executeIllustrationTool(name, argsJson, requestMessageId)
        "present_choices" -> executePresentChoicesTool(argsJson)
        "render_panel", "panel" -> executePanelTool(argsJson)
        // Compatibility for tool calls already stored by earlier Novex builds.
        "present_system_panel" -> executePanelTool(argsJson)
        "save_checkpoint" -> executeSaveCheckpointTool(
            argsJson = argsJson,
            replyBranchId = turnMessageId,
            sourceMessageId = turnMessageId,
            toolCallId = toolId,
        )
        "register_controls" -> executeRegisterControlsTool(argsJson, turnMessageId)
        "update_playthrough_state" -> executeUpdatePlaythroughStateTool(argsJson, turnMessageId)
        "end_interactive_fiction" -> executeEndInteractiveFictionTool(argsJson)
        in integratedCards.names() -> {
            val result=withContext(Dispatchers.IO) { integratedCards.execute(activeSessionId,turnMessageId,toolId,name,argsJson,integratedConversationImages()) }
            if(result.success && name !in setOf("read_card","read_text_block","read_card_image"))novexContextRevision++
            if(name=="read_card_image")routeReadImageResult(result,argsJson) else result
        }
        NovexManagementTools.READ_CONTEXT -> executeNovexReadContextTool(argsJson, requestMessageId, turnMessageId)
        NovexManagementTools.INSPECT -> executeNovexInspectTool(argsJson, requestMessageId, turnMessageId)
        NovexManagementTools.PROPOSE -> executeNovexContentTool(name, argsJson, turnMessageId, toolId, requestMessageId)
        NovexManagementTools.APPLY -> executeNovexContentTool(name, argsJson, turnMessageId, toolId, requestMessageId)
        in com.openminis.app.tools.NovexCardFileTools.names -> executeNovexContentTool(name, argsJson, turnMessageId, toolId, requestMessageId)
        NovexDocumentToolRouter.DOCUMENT_INSPECT,
        NovexDocumentToolRouter.DOCUMENT_READ,
        -> recordNovexFileRead(novexDocumentAgentTools.execute(name, argsJson), requestMessageId, turnMessageId)
        NovexLearningToolRouter.LEARNING_PREPARE,
        NovexLearningToolRouter.LEARNING_START -> novexLearningAgentTools.execute(name, argsJson)
        NovexLearningToolRouter.LEARNING_READ -> recordNovexFileRead(novexLearningAgentTools.execute(name, argsJson), requestMessageId, turnMessageId)
        in novex.core.NovexConversationWorkspaceToolRouter.TOOL_NAMES -> {
            val scope = novex.core.NovexConversationWorkspaceScope(
                conversationId = activeSessionId,
                visibleBranchIds = activeBranchPathIds,
                writeBranchId = turnMessageId,
            )
            recordNovexFileRead(novexWorkspaceAgentTools.execute(
                historyScopeKey = historyScopeKey(),
                visibleImports = novexApplication().creativeArtifactRepository.visibleSourcePaths(activeSessionId),
                name = name,
                argumentsJson = argsJson,
                scope = scope,
                provenance = novex.core.NovexWorkspaceProvenance(
                    conversationId = scope.conversationId,
                    branchId = scope.writeBranchId,
                    messageId = turnMessageId,
                    toolCallId = toolId,
                ),
            ), requestMessageId, turnMessageId)
        }
        else -> ToolExecutionResult("Unknown tool: $name", false)
    }
}

internal suspend fun ChatViewModel.recordNovexFileRead(result: ToolExecutionResult, requestMessageId: String?, responseMessageId: String): ToolExecutionResult {
    if (!result.success) return result
    val payload = runCatching { JSONObject(result.output) }.getOrNull() ?: return result
    if (payload.optJSONObject("data")?.optJSONArray("read_observations") == null) return result
    return try {
        val receipt = novexContextUsageMutex.withLock {
            novex.android.adapter.NovexContextReadJournal(chatRepository).record(
                conversationId = activeSessionId, requestMessageId = requireNotNull(requestMessageId) { "读取没有对应的用户请求" },
                responseMessageId = responseMessageId, activeMessageIds = activeBranchPathIds.toSet(),
                answerIdentity = currentNovexConfiguration().answerIdentity, effectiveWindowTokens = effectiveContextWindowTokens() ?: 128_000,
                operation = "source_tool", result = payload)
        }
        receipt.usage?.let { usage -> withContext(Dispatchers.Main) {
            _messages.value = _messages.value.map { if (it.id == usage.requestMessageId) it.copy(novexContextUsage = usage) else it }
        } }
        result.copy(output = receipt.result.toString(2))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: Exception) {
        ToolExecutionResult("阅读记录未能保存，本次没有返回正文：${failure.message ?: "请重试读取"}", false,
            toolTitle = result.toolTitle,
            stopAgentReason = "文档读取未完成：阅读记录保存失败。已停止重复请求，原文件和已完成操作保留，请重试本轮。")
    }
}

internal suspend fun ChatViewModel.executeNovexReadContextTool(argsJson: String, requestMessageId: String?, responseMessageId: String): ToolExecutionResult = try {
    val args = JSONObject(argsJson.ifBlank { "{}" })
    val workspace = requireNotNull((context.applicationContext as? com.openminis.app.MinisApp)?.novexWorkspace) { "内容工作空间尚未就绪" }
    val profile = _immersiveProfile.value
    val reader = novex.android.adapter.NovexContextReadService(workspace,
        novex.android.adapter.NovexLegacyContext(profile.characterVersionId, profile.character, profile.world),
        visibleMessages = _messages.value.filter { it.id in (activeBranchPathIds + listOfNotNull(requestMessageId)) && !it.isQueued && it.error == null && it.role in setOf("user", "assistant") }.map { it.content })
    val configuration = adoptedNovexConfiguration()
    val offset = args.optInt("offset", 0)
    val operation = args.optString("operation", "inspect")
    val result = when (operation) {
        "inspect" -> reader.inspect(configuration, offset, args.optInt("limit", 80))
        "search" -> reader.search(configuration, args.getString("query"), offset, args.optInt("limit", 20))
        "read" -> reader.read(configuration, args.getString("source_id"), offset, args.optInt("limit", 12_000),
            args.optString("revision").ifBlank { null })
        else -> error("操作无效，请选择查看目录、读取或搜索")
    }
    val receipt = novexContextUsageMutex.withLock {
        novex.android.adapter.NovexContextReadJournal(chatRepository).record(
            conversationId = activeSessionId,
            requestMessageId = requireNotNull(requestMessageId) { "当前阅读没有可保存的用户请求编号" },
            responseMessageId = responseMessageId,
            activeMessageIds = activeBranchPathIds.toSet(),
            answerIdentity = configuration.answerIdentity,
            effectiveWindowTokens = effectiveContextWindowTokens() ?: 128_000,
            operation = operation, result = result,
        )
    }
    receipt.usage?.let { usage -> withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map { if (it.id == usage.requestMessageId) it.copy(novexContextUsage = usage) else it }
    } }
    ToolExecutionResult(receipt.result.toString(2), true, toolTitle = "读取当前采用资料")
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    ToolExecutionResult("无法读取当前资料：${failure.message ?: "请求无效"}", false, toolTitle = "读取当前采用资料")
}

internal suspend fun ChatViewModel.executeNovexInspectTool(argsJson: String, requestMessageId: String?, responseMessageId: String): ToolExecutionResult = runCatching {
    val args = JSONObject(argsJson.ifBlank { "{}" })
    if (args.optString("profile_section") == "exchange_source") {
        require(args.optString("module_id").isBlank()) { "读取交换原件时请指定角色版本，不同时指定模块" }
        val configuration = currentNovexConfiguration()
        val result = novexManagementService().readExchangeSource(configuration, requireNotNull(args.managementSubjectOrNull()) { "请指定管理区中的角色版本" },
            args.optInt("offset", 0), args.optInt("limit", 8000), args.optString("revision").ifBlank { null })
        val receipt = novexContextUsageMutex.withLock {
            novex.android.adapter.NovexContextReadJournal(chatRepository).record(
                conversationId = activeSessionId, requestMessageId = requireNotNull(requestMessageId) { "读取没有对应的用户请求" },
                responseMessageId = responseMessageId, activeMessageIds = activeBranchPathIds.toSet(),
                answerIdentity = configuration.answerIdentity, effectiveWindowTokens = effectiveContextWindowTokens() ?: 128_000,
                operation = "read", result = result)
        }
        receipt.usage?.let { usage -> withContext(Dispatchers.Main) {
            _messages.value = _messages.value.map { if (it.id == usage.requestMessageId) it.copy(novexContextUsage = usage) else it }
        } }
        return@runCatching ToolExecutionResult(receipt.result.toString(2), true, toolTitle = "读取酒馆原始资料")
    }
    val inspection = novexManagementService().inspect(
        configuration = currentNovexConfiguration(),
        subject = args.managementSubjectOrNull(allowKindOnly = true),
        moduleId = args.optString("module_id").trim().ifBlank { null },
        profileSection = args.optString("profile_section").trim().ifBlank { "public" },
        kindFilter = args.managementKindOrNull(),
    )
    ToolExecutionResult(
        output = inspection.toModelToolJson(args.optBoolean("include_advanced")).apply {
            if (args.optBoolean("include_advanced")) put("advanced_change_guide", NovexManagementTools.advancedGuide())
        }.toString(2),
        success = true,
        toolTitle = "查看挂载内容",
    )
}.getOrElse { error ->
    ToolExecutionResult(
        output = "无法查看内容：${error.message ?: "请求无效"}",
        success = false,
        toolTitle = "查看挂载内容",
    )
}

