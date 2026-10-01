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

// 回合编排：会话装载、重跑流尾、编辑分叉、排队注入、发送管线。
// 均为 ChatViewModel 扩展，签名与行为冻结。

internal suspend fun ChatViewModel.installActiveConversation(
    conversation: ChatRepository.ActiveConversation,
) {
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    data class ActiveProjection(
        val ordered: List<ChatMessage>,
        val llmHistory: List<LLMMessage>,
        val marker: novex.android.data.chat.CompactMarkerRow?,
        val activeIds: Set<String>,
        val activePathIds: List<String>,
        val excludedMemoryWrites: Map<String, Int>,
    )
    val projection = withContext(Dispatchers.IO) {
        val rows = conversation.activeMessages
        val activeIds = rows.mapTo(hashSetOf()) { it.id }
        val usageRecords = chatRepository.novexContextUsage(sid)
        val usageByRequest = NovexContextUsageLedger.open(
            NovexContextUsageLedgerSnapshot(sid, usageRecords),
        ).latestByRequestForActivePath(activeIds)
        ActiveProjection(
            ordered = rows.toChatMessages(this@installActiveConversation, conversation.graph, usageByRequest),
            llmHistory = rows.map { it.toLLMMessage(this@installActiveConversation) },
            marker = chatRepository.latestActiveCompactMarker(sid, rows),
            activeIds = activeIds,
            activePathIds = rows.map { it.id },
            excludedMemoryWrites = com.openminis.app.data.ConversationBranchMemory
                .excludedWriteCounts(conversation.allMessages, activeIds),
        )
    }
    val ordered = projection.ordered
    val llmHistory = projection.llmHistory
    val marker = projection.marker
    activeBranchMessageIds = projection.activeIds
    activeBranchPathIds = projection.activePathIds
    refreshNovexRuntimeProjection()
    excludedBranchMemoryWrites = projection.excludedMemoryWrites
    // [T-run-phase] ⑤：install 重建在 historyWriteLock 内（与纪元读/用户行
    // add/清空互为 happens-before；块内纯内存操作，D4）。
    synchronized(historyWriteLock) {
        agentHistory.clear()
        agentHistory.addAll(llmHistory)
        historyGeneration.incrementAndGet()
    }
    retainedTranscriptRows = emptyList()
    retainedTranscriptSessionId = null
    activeNovexDocumentRefs = novexDocumentRefsInHistory(llmHistory)
    activeNovexSourceCollectionRefs = novexSourceCollectionRefsInHistory(llmHistory)
    closeNovexLearningResponsePreview()
    closeNovexLearningDetails()
    _pendingNovexLearningPreflight.value = null
    refreshNovexLearningTaskProjection()
    toolLoopDetector.reset()
    _cachedLatestMarker = marker
    _compactSummary.value = marker?.summary
    _messages.value = if (marker == null) {
        ordered
    } else {
        applyCompactMarkerGraying(
            messages = ordered,
            marker = marker,
            rawMessages = conversation.activeMessages,
            historyDbIds = activeBranchMessageIds,
        )
    }
    val keptIds = ordered.mapTo(mutableSetOf()) { it.id }
    retainStreamFlushStates(keptIds)
    if (_streamingById.value.isNotEmpty()) {
        _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
    }
}

/**
 * [T-android-rerun-from-tool-block-position] Shared streaming tail used by
 * both [retryFromMessage] and [rerunFromToolBlock]: refresh the OAuth
 * token if needed, build the (OAuth-prefixed) system prompt, and launch
 * the agent-loop stream job. Callers must have already (a) claimed
 * `_isStreaming = true` synchronously, (b) truncated UI + DB to the desired
 * re-entry point, and (c) rebuilt [agentHistory]. Returns true once the
 * stream job is launched (the caller's outer `finally` resets
 * `_isStreaming` only when this returns false / throws first).
 */
internal suspend fun ChatViewModel.runRerunStreamTail(
    initialProvider: LLMProvider,
    label: String,
): Boolean {
    var provider = initialProvider
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
                        provider = com.openminis.app.provider.ProviderFactory.create(
                            instance, freshToken, currentModel ?: provider.model, context
                        )
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

    // _isStreaming was already set synchronously by the caller.
    val launchedProvider = provider
    streamJob = viewModelScope.launch(Dispatchers.IO) {
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob ENTER sid=$activeSessionId")
        val leasedSessionId = activeSessionId
        var slotAcquired = false
        var slotReleased = false
        try {
            SessionConcurrencyManager.acquireSlot(leasedSessionId)
            slotAcquired = true
            AppLogger.debug(ChatViewModel.TAG_STREAM, "$label streamJob slot acquired")
            SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
            val activeFallbackStrategy = run {
                val groupId = _selectedGroupId.value
                groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                    ?: novex.android.data.model.FallbackStrategy.default
            }
            val fallbackProviders = buildFallbackProviders(launchedProvider)
            try {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop CALL")
                runAgentLoop(
                    provider = launchedProvider,
                    systemPrompt = systemPrompt,
                    fallbackProviders = fallbackProviders,
                    fallbackStrategy = activeFallbackStrategy,
                    recoveryOrigin = AgentRunRecoveryOrigin.RETRY,
                )
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop RETURN normal")
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label runAgentLoop CANCELLED")
                Log.d(ChatViewModel.TAG, "Agent loop cancelled")
            } catch (e: Exception) {
                AppLogger.error(ChatViewModel.TAG_STREAM, "$label runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                Log.e(ChatViewModel.TAG, "Agent loop error ($label)", e)
                if (streamJob === coroutineContext[Job]) {
                    setInlineError(e.message ?: "Unknown error")
                    // T298: flag the upcoming setInactive() so the
                    // background completion notifier renders the ❌
                    // variant instead of a clean success.
                    SessionActivityTracker.markStreamError(activeSessionId)
                } else {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "$label stale stream ignored exception UI update")
                }
            } finally {
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob FINALLY enter")
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
                    AppLogger.info(ChatViewModel.TAG_STREAM, "$label stale stream skipped tracker finalization")
                }
                // The concurrency manager counts leases, so every job
                // releases its own slot even when its UI finalization is
                // stale relative to a newer job.
                SessionConcurrencyManager.releaseSlot(leasedSessionId)
                slotReleased = true
                AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob FINALLY exit")
            }
        } catch (e: CancellationException) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob CANCELLED waiting for slot")
            Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
        }
        if (slotAcquired && !slotReleased) SessionConcurrencyManager.releaseSlot(leasedSessionId)
        // [T-android-stale-streamjob-clears-isstreaming] Only the current
        // streamJob is allowed to flip _isStreaming false. An orphaned
        // earlier job (cancelled but its finally still draining downstream
        // I/O) reaching this tail AFTER a fresh send/resume/retry has
        // already taken over would otherwise hide the Stop button while
        // the new turn is still streaming. See `var streamJob` KDoc and
        // XIN 2026-06-12 log (20:22:26 / 20:23:25).
        if (streamJob === coroutineContext[Job]) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming=false (about to set)")
            _isStreaming.value = false
        } else {
            AppLogger.info(ChatViewModel.TAG_STREAM, "$label _isStreaming SKIPPED (stale job; current=${streamJob?.hashCode()} this=${coroutineContext[Job]?.hashCode()})")
        }
        AppLogger.info(ChatViewModel.TAG_STREAM, "$label streamJob EXIT")
    }
    return true
}

/**
 * T187: enter edit mode for [messageId]. Returns the cleaned text the
 * caller should drop into the composer (with any
 * model-only attachment metadata stripped), or null when the message
 * cannot be edited (streaming in progress, message missing, or not
 * a user turn). Setting `_editingMessageId` is what flips the
 * composer into edit-mode UI; the next sendMessage call sees the
 * non-null id and creates a sibling branch from that point.
 * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
 */
fun ChatViewModel.editMessage(messageId: String): String? {
    if (_isStreaming.value) return null
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
    if (msg.role != "user") return null
    val text = stripAgentAttachmentMetadata(msg.content)
    _editingMessageId.value = messageId
    AppLogger.info(ChatViewModel.TAG_STREAM, "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch")
    return text
}

/**
 * T187: leave edit mode without sending. Just clears the id flag —
 * caller (ChatScreen) is responsible for clearing inputText. iOS
 * parity: AIChatViewModel.cancelEdit (L2522).
 */
fun ChatViewModel.cancelEdit() {
    if (_editingMessageId.value != null) {
        AppLogger.info(ChatViewModel.TAG_STREAM, "✏️ cancelEdit")
    }
    _editingMessageId.value = null
}

/** Keep the original user subtree and position the next send as its sibling. */
internal suspend fun ChatViewModel.forkBeforeEdit(messageId: String): Boolean =
    forkConversationBeforeEditedMessage(messageId)

internal suspend fun ChatViewModel.forkConversationBeforeEditedMessage(messageId: String): Boolean {
    val message = _messages.value.firstOrNull { it.id == messageId } ?: return false
    val persistedId = message.branchAnchorDbId
        ?: message.sourceDbIds.firstOrNull()
        ?: message.id
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
    val conversation = chatRepository.forkEditedMessageFrom(sid, persistedId)
    installActiveConversation(conversation)
    _canResume.value = false
    return true
}

/** Long-press delete removes only the selected subtree. */
fun ChatViewModel.deleteFromMessage(messageId: String) {
    if (_isStreaming.value) return
    _editingMessageId.value = null
    _isStreaming.value = true
    viewModelScope.launch {
        try {
            val message = _messages.value.firstOrNull { it.id == messageId }
                ?: error("无法定位要删除的消息")
            val persistedId = message.branchAnchorDbId
                ?: message.sourceDbIds.firstOrNull()
                ?: message.id
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
            val deletion = chatRepository.deleteMessageBranch(sid, persistedId)
            revokeMemoryWritesInDeletedRows(
                deletedMessages = deletion.deletedMessages,
                remainingMessages = deletion.conversation.allMessages,
            )
            installActiveConversation(deletion.conversation)
            _canResume.value = false
        } catch (error: Exception) {
            AppLogger.error(
                ChatViewModel.TAG_STREAM,
                "timeline delete failed ${error::class.java.simpleName}: ${error.message}",
            )
            _error.value = "删除失败：${error.message ?: error::class.java.simpleName}"
        } finally {
            _isStreaming.value = false
        }
    }
}

/**
 * Enqueue a prompt to be injected into the currently running agent loop.
 * The message appears immediately in the chat with isQueued=true; when the
 * current agent loop finishes, drainQueuedPrompts() consumes the queue.
 * Mirrors iOS AIChatViewModel.enqueuePrompt().
 */
fun ChatViewModel.enqueuePrompt(text: String) {
    val trimmed = text.trim()
    val pendingAttachments = _attachments.value
    if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return

    val prompt = QueuedPrompt(
        id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
        text = trimmed,
        attachments = pendingAttachments,
    )
    _promptQueue.value = _promptQueue.value + prompt

    val attachmentNames = pendingAttachments.map { it.fileName }
    val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
    val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
    val chatMsg = ChatMessage(
        id = "queued_msg_${prompt.id}",
        role = "user",
        content = trimmed,
        imageUris = imageUris,
        attachmentNames = attachmentNames,
        attachmentUris = attachmentUris,
        isQueued = true,
        queuedPromptId = prompt.id,
    )
    _messages.value = _messages.value + chatMsg
    _submittedUserMessageId.tryEmit(SubmittedUserTurn(chatMsg.id, android.os.SystemClock.uptimeMillis()))
    clearAttachments()
    Log.i(ChatViewModel.TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments), queue=${_promptQueue.value.size}")
}

/** Remove a queued prompt and its chat message by prompt id. */
fun ChatViewModel.removeQueuedPrompt(promptId: String) {
    _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
    _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
}

/** Withdraw a queued message before it gets injected into the agent loop. */
fun ChatViewModel.withdrawQueuedMessage(messageId: String) {
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
    if (!msg.isQueued) return
    val pid = msg.queuedPromptId ?: return
    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
    _messages.value = _messages.value.filterNot { it.id == messageId }
    Log.i(ChatViewModel.TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
}

/**
 * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
 * interrupt: take everything in [_promptQueue] right now, finalize the
 * just-finished assistant bubble in the UI, persist a fresh user
 * message carrying the queued text + attachments, append an assistant
 * "bridge" entry into [agentHistory] (so Anthropic's
 * mergeConsecutiveSameRole doesn't fold the queued user msg into the
 * preceding tool_result), and spawn a new assistant placeholder for
 * the next iteration's response.
 *
 * Returns an [ChatViewModel.InjectedTurn] carrying the new assistantId (which the
 * caller swaps into its loop-scope `assistantId` before `continue`-ing
 * the agent loop), or `null` if every queued prompt was empty after
 * attachment processing (caller falls through to a normal next-turn
 * dispatch in that case).
 *
 * Mirrors iOS `injectQueuedPromptsAsNewTurn`
 * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
 * entry — its sole purpose is to break up the consecutive-user run for
 * the next API call; chat history reconstruction would just hide it.
 */
internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(
    finishedAssistantId: String,
    finishedAccumulatedText: String,
    finishedAllToolBlocks: List<AssistantBlock>,
): ChatViewModel.InjectedTurn? {
    if (_promptQueue.value.isEmpty()) return null
    val queued = _promptQueue.value
    _promptQueue.value = emptyList()

    // [T-android-queued-message-duplicated-on-inject] REMOVE the queued
    // placeholder bubbles (the ones enqueuePrompt added with
    // id="queued_msg_…") for the prompts we're injecting. Step (c) below
    // appends a single combined user bubble (id=userEntity.id) for the same
    // text — so flipping isQueued=false and KEEPING the placeholders (the
    // old behaviour) rendered the message TWICE: once as the un-queued
    // placeholder, once as the injected bubble. drainQueuedPrompts reuses
    // its placeholders and never re-appends, so it didn't dupe; this mid-
    // loop inject path appends a fresh bubble, so the placeholders must go.
    val queuedIds = queued.map { it.id }.toSet()
    val msgsAfterUnqueue = _messages.value.filterNot { m ->
        m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)
    }

    // Build the combined user message from all queued prompts.
    val sid = ensureSession()
    val combinedAttachments = queued.flatMap { it.attachments }
    val prepared = prepareUserAttachments(combinedAttachments, sid)

    val combinedParts = mutableListOf<AgentContentPart>()
    val combinedText = StringBuilder()
    for (prompt in queued) {
        if (prompt.text.isNotEmpty()) {
            if (combinedText.isNotEmpty()) combinedText.append("\n\n")
            combinedText.append(prompt.text)
            combinedParts.add(AgentContentPart.Text(prompt.text))
        }
    }
    prepared.imageParts.forEachIndexed { idx, part ->
        val path = prepared.imageUploadPaths.getOrNull(idx)
        if (path != null) combinedParts.add(AgentContentPart.Text(RequestAssembler.ATTACHED_IMAGE_NOTE_PREFIX + "$path]"))
        combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
    }
    prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

    // Guard: every queued prompt produced no content (no text, no
    // image). An empty user msg is a 400 from every provider. Skip —
    // the caller falls through to a normal next-turn dispatch so the
    // loop doesn't spin.
    if (combinedParts.isEmpty()) {
        AppLogger.warning(
            ChatViewModel.TAG_STREAM,
            "injectQueuedPromptsAsNewTurn: ${queued.size} queued prompt(s) produced no content, skipping",
        )
        return null
    }

    // Bridge entry into agentHistory ONLY (not persisted). The tail
    // before this call is user(tool_result); without the bridge the
    // queued user message becomes two consecutive user roles and the
    // provider merges them — exactly the regression iOS hit at #579.
    // Empty/whitespace-only bridge text would itself be merged out by
    // some sanitizers; keep a small visible string for parity with iOS.
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
            publicHistoryText = "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)",
            contentParts = listOf(
                AgentContentPart.Text("(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"),
            ),
        ),
    )

    // Persist the queued user message as its own DB row + append to
    // agentHistory so the next API call carries it.
    val userText = combinedText.toString()
    val userPartsJson = buildUserPartsJson(userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml)
    val userEntity = chatRepository.appendMessage(sid, "user", userPartsJson)
    recordActiveBranchMessage(userEntity.id)
    agentHistory.add(
        LLMMessage(
            role = LLMMessage.Role.USER,
            content = userText,
            imageParts = prepared.imageParts,
            contentParts = combinedParts,
            dbMessageId = userEntity.id,
        ),
    )

    // Finalize the just-finished assistant bubble in the UI on Main:
    // (a) un-queue the queued chat bubbles, (b) flush the side-channel
    // delta into the canonical row and clear isStreaming /
    // isAwaitingModelResponse, then (c) append the freshly-created
    // queued user ChatMessage + a NEW empty assistant placeholder so
    // the next iteration's streaming writes target the new bubble.
    val newAssistantId = "assistant_${System.currentTimeMillis()}"
    withContext(Dispatchers.Main) {
        // (a) + (b) one emit: build the post-finalize list.
        _messages.value = msgsAfterUnqueue
        updateAssistantMessage(
            finishedAssistantId,
            finishedAccumulatedText,
            false,
            finishedAllToolBlocks,
            isAwaitingModelResponse = false,
        )
        // (c) — append the queued user bubble + the new assistant
        // placeholder. Mirrors sendMessage's user-bubble append shape so
        // attachments / images / file chips render the same.
        val queuedUserMsg = ChatMessage(
            id = userEntity.id,
            role = "user",
            content = userText,
            imageUris = prepared.imageUris,
            attachmentNames = prepared.attachmentNames,
            attachmentUris = prepared.nonImageUris,
        )
        val nextAssistantMsg = ChatMessage(
            id = newAssistantId,
            role = "assistant",
            content = "",
            isStreaming = true,
            isAwaitingModelResponse = true,
            thinkingLevel = _thinkingLevel.value,
        )
        _messages.value = _messages.value + queuedUserMsg + nextAssistantMsg
        // Note: ChatScreen's `lastUserAppendMs` (the trailing-row
        // ScrollPin send-grace window) is updated reactively by
        // ChatScreen's `LaunchedEffect(messages.size)` user-send hook
        // when messages.size grows — appending the queuedUserMsg above
        // bumps the size, so the pin window opens just like a normal
        // send. No direct write needed from here (and we couldn't —
        // `lastUserAppendMs` lives in ChatScreen's composition scope).
    }

    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "injectQueuedPromptsAsNewTurn: injected ${queued.size} queued prompt(s) as new turn, " +
            "finishedId=$finishedAssistantId newId=$newAssistantId",
    )
    return ChatViewModel.InjectedTurn(newAssistantId)
}

/**
 * Drain queued prompts after an agent loop finishes. Each queued prompt is
 * appended to agentHistory, persisted, and re-runs the agent loop.
 * Mirrors iOS AIChatViewModel.drainQueuedPrompts().
 */
internal suspend fun ChatViewModel.drainQueuedPrompts(
    provider: LLMProvider,
    systemPrompt: String?,
    fallbackProviders: List<ChatViewModel.FallbackCandidate>,
    fallbackStrategy: novex.android.data.model.FallbackStrategy,
) {
    while (_promptQueue.value.isNotEmpty()) {
        val queued = _promptQueue.value
        _promptQueue.value = emptyList()
        Log.i(ChatViewModel.TAG, "📨[DRAIN] Draining ${queued.size} queued prompt(s): " +
            queued.joinToString(", ") { "${it.id}=\"${it.text.take(20)}...\"" })

        // Flip isQueued=false on corresponding chat messages so they render as sent.
        // T189: also clear queuedPromptId so a later retry of this bubble
        // doesn't try to drop a phantom queue entry (and so the field state
        // matches what retryFromMessage's truncate path now produces).
        val queuedIds = queued.map { it.id }.toSet()
        _messages.value = _messages.value.map { m ->
            if (m.queuedPromptId != null && queuedIds.contains(m.queuedPromptId)) {
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }

        // Build a combined user message (text + images from all queued prompts).
        // Persist as a single row.
        val sid = ensureSession()
        val combinedAttachments = queued.flatMap { it.attachments }
        val prepared = prepareUserAttachments(combinedAttachments, sid)

        // T132: same shape as sendMessage — caption(s) first, then for each
        // image emit "[attached image: <path>]" + ImageData, finally the
        // <user-attached-files> XML. Keeps caption adjacent to image and
        // lets the agent re-read the file via read_image.
        val combinedParts = mutableListOf<AgentContentPart>()
        val combinedText = StringBuilder()
        for (prompt in queued) {
            if (prompt.text.isNotEmpty()) {
                if (combinedText.isNotEmpty()) combinedText.append("\n\n")
                combinedText.append(prompt.text)
                combinedParts.add(AgentContentPart.Text(prompt.text))
            }
        }
        prepared.imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) combinedParts.add(AgentContentPart.Text(RequestAssembler.ATTACHED_IMAGE_NOTE_PREFIX + "$path]"))
            combinedParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        prepared.attachedFilesXml?.let { combinedParts.add(AgentContentPart.Text(it)) }

        // [T-choice-instruction-lifecycle] drain 的合并消息紧接着刚结束的
        // 助手回合：若那回合以选项卡收尾，这条排队消息就是对它的回应，
        // 与 sendMessage 同款标记（StateFlow.value 读取线程安全）。
        // 必须跳过尾部找最后一条 assistant：排队占位气泡（role=user）
        // 会一直垫在 _messages 尾部，读 lastOrNull() 恒命中占位、检测
        // 恒 false——净眼 P1-1，drain 半边曾经的死代码。
        val drainLast = _messages.value.lastOrNull { it.role == "assistant" }
        val drainReminder = if (ChoiceInstructionLifecycle.endsWithLiveChoicesCard(
                drainLast?.role, drainLast?.toolBlocks?.map { it.toolName }.orEmpty(),
            )
        ) ChoiceInstructionLifecycle.SELECTION_RESPONSE_REMINDER else null
        drainReminder?.let { combinedParts.add(AgentContentPart.Text(it)) }

        val userText = combinedText.toString()
        val userPartsJson = buildUserPartsJson(
            userText, prepared.mediaRefPartsJson, prepared.attachedFilesXml,
            extraTextParts = listOfNotNull(drainReminder),
        )
        val queuedUser = chatRepository.appendMessage(sid, "user", userPartsJson)
        recordActiveBranchMessage(queuedUser.id)

        // [T-run-phase] ⑤：drain 用户行 add 在锁内（与取消清理纪元读互为
        // happens-before，N-P2-a 的 IO 写者侧）。
        synchronized(historyWriteLock) {
            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = userText,
                imageParts = prepared.imageParts,
                contentParts = combinedParts,
                dbMessageId = queuedUser.id,
            ))
            historyGeneration.incrementAndGet()
        }

        try {
            runAgentLoop(
                provider = currentProvider ?: provider,
                systemPrompt = systemPrompt,
                fallbackProviders = fallbackProviders,
                fallbackStrategy = fallbackStrategy,
            )
        } catch (e: CancellationException) {
            Log.d(ChatViewModel.TAG, "Agent loop (queued-drain) cancelled")
            // Cancel mid-drain: cancelStream() will check _promptQueue
            // and call resumeQueueAfterCancel() if anything's still pending,
            // so just propagate.
            throw e
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Agent loop (queued-drain) error", e)
            setInlineError(e.message ?: "Unknown error")
            break
        }
    }
}

fun ChatViewModel.sendMessage(text: String) = sendMessage(text, skipContextCheck = false)

/**
 * @param skipContextCheck set by the pre-send context dialog's own actions,
 *   which have already made the compact decision. Without it the re-entrant
 *   send would re-evaluate the same (still stale until the next usage
 *   chunk) token count and pop the dialog again — iOS guards the identical
 *   re-entry with `skipCompactCheck`.
 */
internal fun ChatViewModel.sendMessage(text: String, skipContextCheck: Boolean) {
    // [T-run-phase] D2：清空进行中快速失败——半清状态上不产生发送。
    if (isWipingSession) {
        setInputText(text)
        appendSystemInfo(text = "正在清空对话，请稍候再发送。", iconKind = "compact")
        return
    }
    // [T-android-send-silent-fail] 文游入口未就绪时此发送会静默丢弃——用户
    // 看到输入清空却没有任何反应。改为可见反馈 + 把文字放回输入框，
    // 绝不让一次按键无声消失。
    if (gameEntryState.value != NovexGameEntryState.Ready) {
        setInputText(text)
        appendSystemInfo(
            text = when (val gate = gameEntryState.value) {
                is NovexGameEntryState.Preparing -> "正在进入互动文游，请稍候再发送。"
                is NovexGameEntryState.ChoosePlayer -> "请先完成玩家身份选择，再开始对话。"
                is NovexGameEntryState.Failed -> "文游入口未就绪：${gate.message}"
                else -> "当前不可发送。"
            },
            iconKind = "info",
        )
        return
    }
    val trimmed = text.trim()
    // While streaming, enqueue instead of silently dropping (iOS: send vs enqueuePrompt).
    if (_isStreaming.value) {
        enqueuePrompt(text)
        return
    }
    // T180: allow attachments-only sends (no caption). Mirrors iOS, where
    // an empty text + non-empty attachments still produces a valid user
    // message. Without this an image-only "look at this" send dropped.
    if (trimmed.isBlank() && _attachments.value.isEmpty()) return
    if (!hasModelForSend()) {
        setInputText(text)
        return
    }
    if (_isCompacting.value) {
        appendSystemInfo(
            text = "Wait for the current compact to finish before sending.",
            iconKind = "compact",
        )
        return
    }
    // A fresh send supersedes any pending resume — mirror iOS which clears
    // canResume at the top of send().
    _canResume.value = false
    // T185: clear the share-injected flag the moment the user actually
    // sends. Without this, the "Move to…" capsule (gated on
    // hasInjectedShareContent) keeps floating over the user-message row
    // after the share content has been committed — it then visually
    // collides with the user-attachment chips, which renders as the
    // "image attachment shows up as Move to" symptom in T185. Mirrors
    // iOS AIChatView.swift:2255 (`hasInjectedShareContent = false`
    // inside the send button's tap closure).
    if (_hasInjectedShareContent.value) _hasInjectedShareContent.value = false

    val initialProvider = currentProvider
    if (initialProvider == null) {
        _error.value = "No provider configured"
        return
    }
    var provider: LLMProvider = initialProvider

    _error.value = null

    // T145: claim _isStreaming synchronously so a rapid second tap can't
    // slip past the entry guard during DB/OAuth setup. See retryFromMessage.
    val navigationRequestedAtMs = android.os.SystemClock.uptimeMillis()
    AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=true (sync, sid=$activeSessionId)")
    _isStreaming.value = true

    // [T-android-thinking-indicator-linger] Invariant sweep: a fresh send
    // only reaches here when no turn is streaming (the _isStreaming guard
    // at the top routes mid-stream sends to enqueuePrompt). So any residual
    // _streamingById entry is an orphan stranded by a prior turn that
    // exited without draining it (e.g. a late delta re-added the entry
    // after finalizeAtTurnLimit / cancel cleared it). mergeStreamingOverlay
    // forces isStreaming=true on any message holding such an entry, so an
    // orphan would render a second "thinking" row alongside the new turn's.
    // Flush them into the canonical messages (isStreaming=false) before the
    // new streaming message is created — no two messages ever stream at once.
    if (_streamingById.value.isNotEmpty()) {
        AppLogger.warning(ChatViewModel.TAG_STREAM, "send: sweeping ${_streamingById.value.size} orphan streaming delta(s) before new turn")
        flushAllStreamingDeltas()
    }

    // T187: when the user is editing a previous message, retain its
    // subtree and position the new text as a sibling user turn. Snapshot
    // + clear the id here so any error in the branch path doesn't leave
    // the composer stuck in edit mode.
    val editingId = _editingMessageId.value
    if (editingId != null) _editingMessageId.value = null

    viewModelScope.launch {
        var streamLaunched = false
        try {
        // Ensure session exists in DB (creates on first message for draft sessions)
        val activeSessionId = ensureSession()

        // [T-stage1-activation] draft 挂卡首条消息前插入开局资料包：
        // 用户的话自然成为首轮（资料包必然先于它在历史里）；激活幂等。
        // 失败（资料包放不下等）明示并拒发本轮——绝不静默漏。
        runCatching { activateBoundCard() }.onFailure { failure ->
            appendSystemInfo(
                text = "开局激活未完成：${failure.message ?: failure::class.java.simpleName}",
                iconKind = "card",
            )
            setInputText(text)
            return@launch
        }

        // [T-send-stall]（用户 2026-09-15：发送有时卡十多秒）世界模板写盘与
        // 发送前上下文检查此前同步跑在 UI 线程：前者过 PRoot 沙盒文件系统，
        // 后者对整段历史做 O(对话长度) 的分词估算，长对话里两者叠加先把界
        // 面冻住再发送。两者都挪到后台调度器执行；因 T145 已在上方同步声明
        // _isStreaming，检查若改判压缩/询问/失败，必须先撤销声明再走原有的
        // parking 路径，否则会留下一个永远不会开始流的"假流式"状态。
        if (trimmed.length >= 300) {
            withContext(Dispatchers.IO) {
                runCatching {
                    // A substantial first prompt is usually a shared world
                    // template. Keep an immutable local copy before the model
                    // sees it so long sessions do not gradually dilute the
                    // world's original rules.
                    val path = "/var/minis/workspace/novex/$activeSessionId/original.md"
                    val file = ContentPaths.resolveSessionHostPath(activeSessionId, path, context)
                    if (file != null && !file.exists()) {
                        file.parentFile?.mkdirs()
                        file.writeText("# 世界原始模板\n\n$trimmed\n")
                    }
                }
            }
        }
        // Context pressure check. needsCompact HOLDS the send: either compact
        // silently (auto-compact on) or ask first — the request that tripped
        // the threshold must not be the one that goes out over-length.
        if (!skipContextCheck) {
            val decision = withContext(Dispatchers.Default) {
                runCatching { checkContextBeforeSend(text) }
            }
            when (decision.getOrNull()) {
                ChatViewModel.PreSendContextAction.PROCEED, null -> {}
                ChatViewModel.PreSendContextAction.COMPACT_THEN_SEND -> {
                    _isStreaming.value = false
                    pendingSendText = text
                    _inputText.value = ""
                    compactAndSendPending()
                    return@launch
                }
                ChatViewModel.PreSendContextAction.ASK_USER -> {
                    // Park the text on the VM (not the composer) so the
                    // dialog owns it; cancelCompactBeforeSend puts it back.
                    _isStreaming.value = false
                    pendingSendText = text
                    _inputText.value = ""
                    _showCompactBeforeSendPrompt.value = true
                    return@launch
                }
            }
            if (decision.isFailure) {
                val failure = decision.exceptionOrNull() as? Exception
                    ?: IllegalStateException("发送准备失败")
                _isStreaming.value = false
                setInputText(text)
                _error.value = "发送准备失败：${failure.message ?: "请重试"}"
                recordModelPreparationFailure(failure)
                return@launch
            }
        }
        // [T-prune-deleted-mounts] 挂载的卡片已被删除时，这里先自动清理绑定
        // 并提示，否则 candidates() 的「采用的作品不存在」会拦下整轮发送，
        // 用户还得自己去对话背景里找幽灵卡（2026-09-16 用户反馈）。
        runCatching { integratedCards.pruneDeletedMounts() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { removed ->
                appendSystemInfo(
                    text = "挂载的卡片${removed.joinToString("、") { "「$it」" }}已删除，已自动从对话背景移除；在卡片库还原后需重新挂载。",
                    iconKind = "card",
                )
            }
        val currentAttachments = _attachments.value
        clearAttachments()

        if (editingId != null) {
            if (!forkBeforeEdit(editingId)) {
                _error.value = "无法定位原消息，已取消编辑发送；请重新打开对话后重试"
                return@launch
            }
        }

        val prepared = prepareUserAttachments(currentAttachments, activeSessionId)

        // [T-choice-instruction-lifecycle] 上一条可见消息是带 present_choices
        // 卡的助手回合时，玩家这条发送就是对选项卡的回应。卡本身按终端
        // UI 工具设计不会进模型历史，点选文本若裸进场，模型分不清"选择
        // 回应"与"新元指令"（选项卡后只弹选项不写正文的另一半根因）。
        // 标记随用户行落盘：UI 走现成 system-reminder 剥离不渲染，模型
        // 后续每轮可见，内存/DB 两侧同源、影子装配天然平价。
        // 检测跳过 system 气泡（pruneDeletedMounts/compact 通知等会先于
        // 发送插入尾部，净眼 P2-3）；占位气泡 role=user 归入"已有人回应"
        // 的安全方向，不挂标记。
        val lastVisible = _messages.value.lastOrNull { it.role == "assistant" || it.role == "user" }
        val selectionReminder = if (ChoiceInstructionLifecycle.endsWithLiveChoicesCard(
                lastVisible?.role, lastVisible?.toolBlocks?.map { it.toolName }.orEmpty(),
            )
        ) ChoiceInstructionLifecycle.SELECTION_RESPONSE_REMINDER else null

        // Save user message — text + persisted mediaRef parts so images survive
        // a session reload (T128). Non-image attachments still only contribute
        // their name (rendered as a file tile) and are not persisted.
        val userPartsJson = buildUserPartsJson(
            trimmed, prepared.mediaRefPartsJson, prepared.attachedFilesXml,
            extraTextParts = listOfNotNull(selectionReminder),
        )
        val persistedUser = chatRepository.appendMessage(activeSessionId, "user", userPartsJson)
        recordActiveBranchMessage(persistedUser.id)

        val userMsg = ChatMessage(
            id = persistedUser.id,
            role = "user",
            content = trimmed,
            imageUris = prepared.imageUris,
            attachmentNames = prepared.attachmentNames,
            attachmentUris = prepared.nonImageUris,
        )
        _messages.value = _messages.value + userMsg
        _submittedUserMessageId.tryEmit(SubmittedUserTurn(userMsg.id, navigationRequestedAtMs))
        val imageParts = prepared.imageParts

        // T132: build the user contentParts in iOS order — caption first
        // (only if non-empty), then per image emit
        //   text("[attached image: /var/minis/attachments/uploads/<f>]")
        //   ImageData(<bytes>, <mime>)
        // so the caption sits adjacent to the image in the wire payload,
        // and the agent's read_image tool can resolve the same path back
        // to bytes. Trailing <user-attached-files> XML block lets the
        // model see filenames/sizes without needing tool calls.
        val userContentParts = mutableListOf<AgentContentPart>()
        if (trimmed.isNotEmpty()) userContentParts.add(AgentContentPart.Text(trimmed))
        imageParts.forEachIndexed { idx, part ->
            val path = prepared.imageUploadPaths.getOrNull(idx)
            if (path != null) userContentParts.add(AgentContentPart.Text(RequestAssembler.ATTACHED_IMAGE_NOTE_PREFIX + "$path]"))
            userContentParts.add(AgentContentPart.ImageData(part.data, part.mimeType, linuxPath = path, noVisionPlaceholder = visionPlaceholderFor(path)))
        }
        prepared.attachedFilesXml?.let { userContentParts.add(AgentContentPart.Text(it)) }
        // [T-choice-instruction-lifecycle] 与落盘 parts 同源同序：内存侧
        // agentHistory 与 DB 行携带同一标记，影子装配两侧一致。
        selectionReminder?.let { userContentParts.add(AgentContentPart.Text(it)) }

        // [T-run-phase] ⑤：sendMessage 用户行 add 在锁内（同上，N-P2-a 写者侧）。
        synchronized(historyWriteLock) {
            agentHistory.add(LLMMessage(
                role = LLMMessage.Role.USER,
                content = trimmed,
                imageParts = imageParts,
                contentParts = userContentParts,
                dbMessageId = persistedUser.id,
            ))
            historyGeneration.incrementAndGet()
        }

        // Refresh OAuth token if needed before sending (mirrors iOS validAccessToken)
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
                            // Recreate provider with fresh token
                            provider = com.openminis.app.provider.ProviderFactory.create(
                                instance, freshToken, currentModel ?: provider.model, context
                            )
                            currentProvider = provider
                            android.util.Log.i(ChatViewModel.TAG, "OAuth token refreshed before send")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(ChatViewModel.TAG, "OAuth token refresh failed: ${e.message}")
            }
        }

        // Build system prompt
        // Anthropic OAuth requires the Claude Code prefix in the system prompt
        val baseSystemPrompt = buildSystemPrompt()
        val systemPrompt = if ((provider as? novex.android.transport.NovexTransportProvider)?.isAnthropicOAuth == true) {
            val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
            if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
            else "$prefix\n\n${baseSystemPrompt ?: ""}"
        } else baseSystemPrompt

        // Start agent loop with fallback. _isStreaming was set synchronously at top.
        streamLaunched = true
        streamJob = launch(Dispatchers.IO) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob ENTER sid=$activeSessionId")
            val leasedSessionId = activeSessionId
            var slotAcquired = false
            var slotReleased = false
            try {
                // Acquire concurrency slot (suspends if at max)
                SessionConcurrencyManager.acquireSlot(leasedSessionId)
                slotAcquired = true
                AppLogger.debug(ChatViewModel.TAG_STREAM, "send streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                // Resolve the active group's fallback strategy
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                        ?: novex.android.data.model.FallbackStrategy.default
                }

                // Build full fallback provider list upfront (mirrors iOS triedEntries approach)
                val fallbackProviders = buildFallbackProviders(provider)

                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop RETURN normal")
                    // Drain any prompts the user queued while this loop was running.
                    // Skipped on cancel: cancelled job won't reach here.
                    drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send drainQueuedPrompts RETURN")
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "send runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (all fallbacks exhausted)", e)
                    if (streamJob === coroutineContext[Job]) {
                        setInlineError(e.message ?: "Unknown error")
                        // T298: completion notifier should show the ❌ variant.
                        SessionActivityTracker.markStreamError(activeSessionId)
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send stale stream ignored exception UI update")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob FINALLY enter")
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
                        AppLogger.info(ChatViewModel.TAG_STREAM, "send stale stream skipped tracker finalization")
                    }
                    SessionConcurrencyManager.releaseSlot(leasedSessionId)
                    slotReleased = true
                    AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot")
            }
            if (slotAcquired && !slotReleased) SessionConcurrencyManager.releaseSlot(leasedSessionId)
            // [T-android-stale-streamjob-clears-isstreaming] guard — see
            // `var streamJob` KDoc; identical pattern as runRerunStreamTail.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "send streamJob EXIT")
        }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            setInputText(text)
            _error.value = "发送准备失败：${failure.message ?: "请重试"}"
            recordModelPreparationFailure(failure)
        } finally {
            if (!streamLaunched) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "send _isStreaming=false (setup aborted)")
                _isStreaming.value = false
            }
        }
    }
}

/** Set error inline on the last assistant message (iOS: message.error).
 *
 *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
 *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
 *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
 *  on screen even though streaming is over. The flag is per-message and
 *  is not implicitly cleared by isStreaming=false. */
