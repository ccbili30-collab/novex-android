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

// 内联错误管线与末次重试。
// 均为 ChatViewModel 扩展，签名与行为冻结。

internal fun ChatViewModel.recordModelPreparationFailure(failure: Exception) {
    com.openminis.app.diagnostics.ModelRequestAudit(
        java.io.File(context.filesDir, "novex/model-requests"), activeSessionId, null,
        JSONObject().put("entryId", _activeEntryId.value).put("displayModelId", currentModel?.id)
    ).event("preparation_failed", JSONObject().put("errorType", failure.javaClass.name)
        .put("message", safeModelDiagnostic(failure.message.orEmpty())))
}

internal fun ChatViewModel.safeModelDiagnostic(text: String): String {
    val keys = providerRepository.config.value.instances.mapNotNull { instance ->
        runCatching { providerRepository.usableApiKey(instance) }.getOrNull()
    }
    return com.openminis.app.diagnostics.ModelRequestAudit.safeText(text, keys)
}

internal fun ChatViewModel.setInlineError(errorText: String) {
    // Retain the last known estimate when preparation fails.
    _contextUsageReady.value = _lastTurnContextTokens.value > 0
    // [T-error-persist-android] Never let an empty/blank error string reach
    // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
    // would render an EMPTY error banner, and (now that errors persist) it
    // would stick across reloads. An exception with a blank `message`
    // (`e.message ?: "Unknown error"` only guards null, not "") is the
    // realistic source. Coalesce to a generic non-empty message.
    val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
    // T-streaming-side-channel: before mutating the canonical message,
    // drain any in-flight streaming delta so the error frame carries
    // the actual accumulated content (otherwise the user sees content
    // snap back to a pre-stream prefix when the error banner appears).
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    val lastUserIdx=msgs.indexOfLast {it.role=="user" && !it.isQueued}
    if(lastAssistantIdx<=lastUserIdx) {
        // Preparation failed before this request acquired an assistant row.
        // Never mutate or persist an error onto the preceding completed reply.
        _error.value=safeError
        if(lastUserIdx>=0 && _inputText.value.isBlank())setInputText(msgs[lastUserIdx].content)
        return
    }
    if (lastAssistantIdx >= 0) {
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(
            error = safeError,
            isStreaming = false,
            isAwaitingModelResponse = false,
        )
        _messages.value = msgs
        // [T-error-persist-android] Persist the terminal error onto the
        // selected path's last assistant DB row so the inline error + Retry button
        // survive a session reload. This is a targeted UPDATE (not a fresh
        // insert): the in-memory bubble id differs from the persisted row id,
        // so we address the last source row when known, otherwise resolve
        // the last assistant from the persisted active path. No-op when the
        // failing turn never persisted a row (first-turn failure).
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val sourceId = msg.sourceDbIds.lastOrNull()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (sourceId != null) {
                        chatRepository.setMessageSticker(sourceId, safeError)
                    } else {
                        chatRepository.updateLastActiveAssistantError(sid, safeError)
                    }
                }
                catch (e: Exception) { Log.w(ChatViewModel.TAG, "persist error_info failed: ${e.message}") }
            }
        }
    } else {
        // No assistant message yet — fall back to top-level error
        _error.value = safeError
    }
}

/**
 * Show a transient error on the last assistant message while keeping isStreaming=true
 * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
 * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
 */
internal fun ChatViewModel.setTransientInlineError(errorText: String) {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    msgs[lastAssistantIdx] = msg.copy(error = errorText)
    _messages.value = msgs
}

/** Clear any inline error on the last assistant message (used after successful retry). */
internal fun ChatViewModel.clearInlineError() {
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    val msg = msgs[lastAssistantIdx]
    if (msg.error == null) return
    msgs[lastAssistantIdx] = msg.copy(error = null)
    _messages.value = msgs
    // [T-error-persist-android] Clear the persisted sticker too, so a
    // recovered turn doesn't resurrect the error banner on the next reload.
    // Clear by the message's source DB rows when known (the in-memory bubble
    // maps to one or more persisted rows via sourceDbIds); fall back to the
    // last-assistant-row update otherwise.
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isNotEmpty()) {
        val dbIds = msg.sourceDbIds
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (dbIds.isNotEmpty()) {
                    dbIds.forEach { chatRepository.setMessageSticker(it, null) }
                } else {
                    chatRepository.updateLastActiveAssistantError(sid, null)
                }
            } catch (e: Exception) { Log.w(ChatViewModel.TAG, "clear error_info failed: ${e.message}") }
        }
    }
}

/**
 * [T-error-persist-android] Fire-and-forget: clear the persisted error
 * sticker on the session's last assistant row. Called from the resume / retry
 * entrypoints that drop the in-memory error but don't go through
 * [clearInlineError], so a recovered turn can't merge-resurrect the old
 * banner on the next reload. No-op when there's no session/row yet.
 */
internal fun ChatViewModel.clearPersistedLastAssistantError() {
    val sid = realSessionId.ifEmpty { sessionId }
    if (sid.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        try { chatRepository.updateLastActiveAssistantError(sid, null) }
        catch (e: Exception) { Log.w(ChatViewModel.TAG, "clear error_info (persisted) failed: ${e.message}") }
    }
}

/** Retry the last agent turn (triggered by inline error Retry button).
 *
 *  T258: ports iOS AIChatViewModel.retry() (AIChatViewModel.swift:2079).
 *  Earlier behaviour blew away the entire failed assistant ChatMessage —
 *  including its already-completed tool_use cards — and reset
 *  agentHistory back to the last "real" user message, so on Retry every
 *  succeeded tool re-executed from scratch (the bug the user reported).
 *
 *  New behaviour:
 *   - Keep the assistant ChatMessage in the UI; clear its error sticker
 *     and the streaming/awaiting flags. Drop only tool blocks still in
 *     STREAMING / PENDING / RUNNING state — those have no matching
 *     tool_result and would orphan the request body.
 *   - From agentHistory, pop ONLY a trailing assistant entry (i.e. the
 *     turn whose stream errored). If the tail is already user(tool_result),
 *     the failure happened on the NEXT LLM call before any output —
 *     history is already valid, leave it.
 *   - GC orphaned tool_result rows whose tool_use is no longer in
 *     agentHistory (defends against the API "unexpected tool_use_id" 400).
 *   - If a persisted trailing assistant is retried, retain it and create
 *     the replacement as a sibling. A tool-result tail is reused as model
 *     context without dispatching its completed tools again.
 */
fun ChatViewModel.retryLast() {
    if (_isStreaming.value) return
    // [T-run-phase] D2：清空进行中不重试。
    if (isWipingSession) {
        appendSystemInfo(text = "正在清空对话，请稍候再重试。", iconKind = "compact")
        return
    }
    _canResume.value = resumeEligibilityAfterRecoveryAction(RecoveryAction.RETRY)
    // T-streaming-side-channel: belt-and-suspenders flush in case any
    // delta survived an earlier abnormal exit; retryLast is gated on
    // !isStreaming so this is normally a no-op.
    flushAllStreamingDeltas()
    val msgs = _messages.value.toMutableList()
    val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
    if (lastAssistantIdx < 0) return
    // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
    _forceScrollToBottom.tryEmit(Unit)

    // 1. Keep the assistant message; clear error + streaming flags + drop
    //    in-flight tool blocks (STREAMING args / PENDING dispatch /
    //    RUNNING execution all have no tool_result, so they'd orphan).
    val lastMsg = msgs[lastAssistantIdx]
    val keptToolBlocks = lastMsg.toolBlocks.filter { block ->
        block.toolStatus !in ChatViewModel.IN_FLIGHT_TOOL_STATUSES
    }
    msgs[lastAssistantIdx] = lastMsg.copy(
        error = null,
        isStreaming = false,
        isAwaitingModelResponse = false,
        toolBlocks = keptToolBlocks,
    )
    _messages.value = msgs
    // 2. Pop ONLY a trailing assistant entry from agentHistory (mirrors
    //    iOS retry() :2107-2109). If the tail is already user(tool_result),
    //    the next-turn LLM call errored — leave history alone.
    // 净眼 P1-2 关账：截断+孤儿 GC 是 IO/主线程可达的盲写——整体入锁并推世代号，
    // 使取消清理的纪元复验必能看见本次重试已接管历史。
    var poppedAssistant: LLMMessage? = null
    synchronized(historyWriteLock) {
        poppedAssistant = if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT) {
            agentHistory.removeAt(agentHistory.size - 1)
        } else null

        // 3. GC orphaned tool_result parts whose tool_use is gone (mirrors
        //    iOS retry() :2114-2128). Walks backward so removeAt is safe.
        val liveToolUseIds = agentHistory.flatMap { m ->
            m.contentParts.filterIsInstance<AgentContentPart.ToolUse>().map { it.id }
        }.toSet()
        for (i in agentHistory.indices.reversed()) {
            val m = agentHistory[i]
            if (m.role != LLMMessage.Role.USER) continue
            val cleanedParts = m.contentParts.filter { p ->
                p !is AgentContentPart.ToolResult || p.id in liveToolUseIds
            }
            when {
                cleanedParts.isEmpty() && m.contentParts.isNotEmpty() ->
                    agentHistory.removeAt(i)
                cleanedParts.size < m.contentParts.size ->
                    agentHistory[i] = m.copy(contentParts = cleanedParts)
            }
        }
        historyGeneration.incrementAndGet()
    }

    val initialProvider = currentProvider ?: return
    var provider: LLMProvider = initialProvider
    _error.value = null

    // T145: claim _isStreaming synchronously — see retryFromMessage for rationale.
    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    viewModelScope.launch {
        var streamLaunched = false
        try {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

        // Preserve a persisted failed/partial reply as a sibling. If the
        // history tail is already a tool_result there is no failed
        // assistant row yet, so the new response simply appends there and
        // completed tools are never executed again.
        if (poppedAssistant != null) {
            val persistedReplyId = poppedAssistant.dbMessageId
                ?: lastMsg.sourceDbIds.lastOrNull()
            if (persistedReplyId != null) {
                val conversation = chatRepository.forkEditedMessageFrom(sid, persistedReplyId)
                installActiveConversation(conversation)
                AppLogger.info(
                    ChatViewModel.TAG_STREAM,
                    "retryLast: retained failed reply ${persistedReplyId.take(8)} as sibling",
                )
            }
        } else {
            AppLogger.info(
                ChatViewModel.TAG_STREAM,
                "retryLast: agentHistory tail was user(tool_result) — no DB cleanup needed",
            )
        }

        // Refresh OAuth token if needed
        if ((provider as? novex.android.transport.NovexTransportProvider)?.isAnthropicOAuth == true) {
            try {
                val activeEntryId = _activeEntryId.value
                val entry = activeEntryId?.let { id -> providerRepository.config.value.modelEntries.find { it.id == id } }
                val instance = entry?.let { e -> providerRepository.config.value.instances.find { it.id == e.providerInstanceId } }
                if (instance != null) {
                    val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                    val freshToken = manager?.validAccessToken()
                    if (freshToken != null) {
                        val storedKey = providerRepository.loadApiKey(instance.id)
                        if (freshToken != storedKey) {
                            providerRepository.saveApiKey(instance.id, freshToken)
                            provider = ProviderFactory.create(instance, freshToken, currentModel ?: provider.model, context)
                            currentProvider = provider
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? novex.android.transport.NovexTransportProvider)?.isAnthropicOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // _isStreaming was already set synchronously at the top.
        streamLaunched = true
        streamJob = launch(Dispatchers.IO) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob ENTER sid=$activeSessionId")
            val leasedSessionId = activeSessionId
            var slotAcquired = false
            var slotReleased = false
            try {
                SessionConcurrencyManager.acquireSlot(leasedSessionId)
                slotAcquired = true
                AppLogger.debug(ChatViewModel.TAG_STREAM, "retryLast streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: novex.android.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                        recoveryOrigin = AgentRunRecoveryOrigin.RETRY,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop RETURN normal")
                    drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast drainQueuedPrompts RETURN")
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "retryLast runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (retryLast)", e)
                    if (streamJob === coroutineContext[Job]) {
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast stale stream ignored exception UI update")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob FINALLY enter")
                    // [T-android-overlay-reply-status-34599] Surface
                    // the assistant's most recent reply text to the
                    // overlay BEFORE setInactive so the post-completion
                    // overlay state (no-running, has-outcome) carries a
                    // non-null excerpt. Reading _messages here is safe:
                    // we're in the finally block of the agent loop and
                    // the stream has already flushed its last delta.
                    if (streamJob === coroutineContext[Job]) {
                        publishOverlayReplyExcerpt(activeSessionId)
                        SessionActivityTracker.setInactive(activeSessionId)
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast stale stream skipped tracker finalization")
                    }
                    SessionConcurrencyManager.releaseSlot(leasedSessionId)
                    slotReleased = true
                    AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
            }
            if (slotAcquired && !slotReleased) SessionConcurrencyManager.releaseSlot(leasedSessionId)
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast streamJob EXIT")
        }
        } finally {
            if (!streamLaunched) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "retryLast _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
}

/**
 * Unwrap exceptions thrown inside callbackFlow.
 * callbackFlow wraps internal throws into CancellationException(cause=original).
 * This extracts the original LLMError if present.
 */
/**
 * Sanitize agentHistory before each API call to ensure tool_use/tool_result pairing.
 * Mirrors iOS AIChatViewModel pre-API validation.
 *
 * Ensures: every assistant message with tool_use is immediately followed by a user
 * message containing the matching tool_result(s). Handles:
 * - Duplicate tool IDs across messages (from provider fallback/retry)
 * - Orphaned tool_use without any tool_result
 * - Orphaned tool_result without matching tool_use
 * - Assistant text after tool_use in the same message (Anthropic rejects this)
 */
