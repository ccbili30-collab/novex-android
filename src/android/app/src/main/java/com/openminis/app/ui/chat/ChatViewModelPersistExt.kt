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

// 回合持久化、流冲刷、系统提示与部件 JSON 装配。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结。

internal fun ChatViewModel.updateAssistantMessage(
    id: String,
    content: String,
    isStreaming: Boolean,
    toolBlocks: List<AssistantBlock>,
    isAwaitingModelResponse: Boolean = false,
) {
    // T-streaming-side-channel: during a live turn, write high-frequency
    // fields into [_streamingById] instead of mutating the canonical
    // message list. This keeps the `messages` StateFlow reference stable
    // across the turn so ChatScreen's top-level reads
    // (`messages.any/.associate/.isNotEmpty/.lastOrNull`) don't trigger
    // a full recompose of the 8980-line composable on every token.
    //
    // On stream end (isStreaming=false), drain the accumulated delta
    // back into the canonical message in a single `_messages` emit, then
    // clear the side-channel entry so post-turn reads (history rebuild,
    // persist, agent loop) see the canonical truth.
    if (isStreaming) {
        val toolBlocksImmutable = toolBlocks.toList()

        // [T-android-stream-flush-dualpath] Dual-path flush at the
        // message-accumulation layer (NOT per-fragment, which never
        // throttled). Decide whether to publish this delta now:
        //   • structural change (toolBlocks count / awaiting flag) →
        //     publish immediately — these drive tool-bubble UI and must
        //     never be coalesced away or the bubble state stalls.
        //   • else time-path: enough ms since last publish for this length.
        //   • else newline fast-path: a line break in the newly-streamed
        //     chunk + ≥50 new chars, gated to short docs (iOS parity).
        // When none fire, stash the latest as a trailing publish so the
        // final chunk before a pause still lands; a fresh delta cancels
        // and replaces it.
        val st = streamFlushStates.getOrPut(id) {
            ChatViewModel.StreamFlushState().also { it.lastFlushedLen = 0 }
        }
        val prev = _streamingById.value[id]
        // [T-android-stream-flush-review] Structural change also covers an
        // in-place tool-block STATUS flip (running → success), not just a
        // count change — otherwise a spinner→checkmark could lag up to one
        // throttle tier. Compare a cheap (kind,status) fingerprint.
        val toolStatusChanged = prev != null &&
            prev.toolBlocks.size == toolBlocksImmutable.size &&
            toolBlocksImmutable.indices.any { i ->
                prev.toolBlocks[i].toolStatus != toolBlocksImmutable[i].toolStatus
            }
        val structuralChange = prev == null ||
            prev.toolBlocks.size != toolBlocksImmutable.size ||
            prev.isAwaitingModelResponse != isAwaitingModelResponse ||
            toolStatusChanged
        val now = System.currentTimeMillis()
        val elapsed = now - st.lastFlushMs
        val throttle = streamFlushThrottleMs(content.length)
        val newChunk = if (content.length > st.lastFlushedLen) {
            content.substring(st.lastFlushedLen.coerceAtMost(content.length))
        } else ""
        val unflushed = content.length - st.lastFlushedLen
        val newlineFlush = content.length < ChatViewModel.NEWLINE_FLUSH_MAX_LEN &&
            newChunk.contains('\n') &&
            unflushed >= ChatViewModel.NEWLINE_FLUSH_MIN_CHARS

        fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {
            _streamingById.value = _streamingById.value + (
                id to StreamingDelta(
                    content = text,
                    toolBlocks = blocks,
                    isAwaitingModelResponse = awaiting,
                )
            )
            st.lastFlushMs = System.currentTimeMillis()
            st.lastFlushedLen = text.length
        }

        if (structuralChange || elapsed >= throttle || newlineFlush) {
            st.trailingJob?.cancel()
            st.trailingJob = null
            st.pendingContent = null
            publish(content, toolBlocksImmutable, isAwaitingModelResponse)
        } else {
            // Throttled: always record this delta as the freshest pending
            // value, so whenever the trailing job fires it publishes the
            // latest text — not whatever was captured when it was first
            // scheduled (review #2). Schedule the job only once.
            st.pendingContent = content
            st.pendingBlocks = toolBlocksImmutable
            st.pendingAwaiting = isAwaitingModelResponse
            if (st.trailingJob == null) {
                val wait = (throttle - elapsed).coerceAtLeast(16L)
                st.trailingJob = viewModelScope.launch {
                    kotlinx.coroutines.delay(wait)
                    val pc = st.pendingContent
                    if (pc != null) {
                        publish(pc, st.pendingBlocks, st.pendingAwaiting)
                        st.pendingContent = null
                    }
                    st.trailingJob = null
                }
            }
        }
        // [T-android-timeout-while-running] If a transient banner
        // (`message.error`) is still on the canonical assistant message
        // when a fresh streaming event arrives, the banner is stale —
        // the model is producing again, by construction the prior
        // transient timeout / retry / fallback has been resolved.
        // Clear it in the same mutation. setTransientInlineError /
        // setInlineError are the only paths that write `error`; the
        // terminal path (setInlineError) sets isStreaming=false on the
        // same message in the same emit, so it cannot reach this
        // branch and the clear is safe.
        //
        // 𝙓𝙄𝙉 TG36302 (0.10): user saw a red "timeout / retry" banner
        // glued to the bottom of the conversation while the agent
        // continued running (LM Studio tool loop on 30/30, "Minis is
        // thinking" indicator). Caused by (a) the fallback-switch branch in
        // runAgentLoop not calling clearInlineError(), and (b) the
        // streaming-side-channel writing every subsequent delta into
        // _streamingById without ever touching _messages where
        // `error` lives. (a) is fixed at the fallback site; (b) is
        // fixed here defensively so any future write-path that forgets
        // to clear can't strand a stale banner across the rest of
        // the turn.
        val canonical = _messages.value
        val canonicalIdx = canonical.indexOfLast { it.id == id }
        if (canonicalIdx >= 0 && canonical[canonicalIdx].error != null) {
            val updated = canonical.toMutableList()
            updated[canonicalIdx] = canonical[canonicalIdx].copy(error = null)
            _messages.value = updated
        }
        return
    }
    // [T-android-stream-flush-dualpath] Stream end → cancel any pending
    // trailing flush and drop the throttle accumulator for this message;
    // the canonical drain below publishes the final, complete text.
    clearStreamFlushState(id)
    // Stream end → sync delta into canonical message + clear side-channel.
    val current = _messages.value
    val idx = current.indexOfLast { it.id == id }
    if (idx < 0) {
        // The message itself is gone (e.g. clearChat raced ahead) —
        // just clear any leftover stream delta and bail.
        if (_streamingById.value.containsKey(id)) {
            _streamingById.value = _streamingById.value - id
        }
        return
    }
    val updated = current.toMutableList()
    updated[idx] = current[idx].copy(
        content = content,
        isStreaming = false,
        toolBlocks = toolBlocks.toList(),
        isAwaitingModelResponse = isAwaitingModelResponse,
    )
    _messages.value = updated
    if (_streamingById.value.containsKey(id)) {
        _streamingById.value = _streamingById.value - id
    }
}

/**
 * Read a message's content + toolBlocks honoring any active streaming
 * delta. Use this from non-render code that needs the "current" view of
 * a message during a live turn (e.g. agent history builders, persistence
 * snapshots) without forcing the render layer to consult the delta map.
 */
internal fun ChatViewModel.effectiveContent(id: String): String? {
    val delta = _streamingById.value[id]
    if (delta != null) return delta.content
    return _messages.value.firstOrNull { it.id == id }?.content
}

/**
 * Force-drain any outstanding streaming delta for [id] back into the
 * canonical message and clear the side-channel slot. Called from turn
 * exit paths (cancel / error / retry / resume / clearChat) so the
 * canonical message reflects all accumulated content even if the last
 * [updateAssistantMessage] call had isStreaming=true.
 */
internal fun ChatViewModel.flushStreamingDelta(id: String) {
    val delta = _streamingById.value[id] ?: return
    val current = _messages.value
    val idx = current.indexOfLast { it.id == id }
    if (idx >= 0) {
        val updated = current.toMutableList()
        updated[idx] = current[idx].copy(
            content = delta.content,
            isStreaming = false,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
        _messages.value = updated
    }
    // [T-android-stream-flush-review] Cancel the pending trailing flush
    // BEFORE clearing the side channel — otherwise its viewModelScope
    // coroutine (not cancelled by streamJob.cancel) fires later and
    // re-adds the orphan side-channel entry, reviving a stale "thinking"
    // row after the turn was stopped/drained.
    clearStreamFlushState(id)
    _streamingById.value = _streamingById.value - id
}

/** Drain ALL outstanding streaming deltas (called on global resets). */
internal fun ChatViewModel.flushAllStreamingDeltas() {
    clearAllStreamFlushStates()
    val pending = _streamingById.value
    if (pending.isEmpty()) return
    val current = _messages.value.toMutableList()
    var changed = false
    for ((id, delta) in pending) {
        val idx = current.indexOfLast { it.id == id }
        if (idx < 0) continue
        current[idx] = current[idx].copy(
            content = delta.content,
            isStreaming = false,
            toolBlocks = delta.toolBlocks,
            isAwaitingModelResponse = delta.isAwaitingModelResponse,
        )
        changed = true
    }
    if (changed) _messages.value = current
    _streamingById.value = emptyMap()
}

/**
 * Build the ordered AgentContentPart list for this turn by walking the slice of
 * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
 * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
 * original stream order is preserved by the list slice order. Thinking and info
 * blocks are skipped (they're persisted via `reasoningContent` or not at all).
 */
internal suspend fun ChatViewModel.persistAssistantTurn(
    parts: List<AgentContentPart>,
    usage: LLMUsage?,
    reasoningContent: String? = null,
    toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    messageId: String = java.util.UUID.randomUUID().toString(),
): String? {
    if (parts.isEmpty()) return null
    val partsJson = encodeAssistantTurnParts(parts, toolBlockMeta)
    val tokenJson = usage?.let {
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
    }
    val entity = chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
        messageId = messageId,
    )
    recordActiveBranchMessage(entity.id)
    return entity.id
}

@Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
internal suspend fun ChatViewModel.persistAssistantMessage(
    text: String,
    usage: LLMUsage?,
    toolBlocks: List<AssistantBlock>? = null,
    toolCallInputs: Map<String, String> = emptyMap()
    ,
    reasoningContent: String? = null,
) {
    if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

    val partsJson = buildString {
        append("[")
        var first = true
        if (text.isNotEmpty()) {
            append("""{"type":"text","value":${escapeJson(text)}}""")
            first = false
        }
        toolBlocks?.forEach { block ->
            // Only persist real tool-use blocks. text / thinking / info blocks are
            // either represented via the `text` parameter (accumulatedText) or
            // reconstructed from thinking metadata; persisting them as `toolUse`
            // produces empty-name records that Anthropic rejects with
            // "messages.N.content.M.tool_use.name: String should have at least 1 character".
            if (block.kind != "tool_use") return@forEach
            if (block.toolName.isBlank()) return@forEach  // extra safety
            if (!first) append(",")
            first = false
            val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
            val pUrl = block.browserURL ?: ""
            val iPath = block.imageFilePath ?: ""
            append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
        }
        append("]")
    }
    val tokenJson = usage?.let {
        """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
    }
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
        reasoningContent = reasoningContent,
    )
}

/** Persist tool results as a user-role message (mirrors iOS behavior). */
internal suspend fun ChatViewModel.persistToolResultMessage(parts: List<AgentContentPart>): String? {
    val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
    if (results.isEmpty()) return null
    val partsJson = buildString {
        append("[")
        results.forEachIndexed { index, result ->
            if (index > 0) append(",")
            val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
            append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText}}}""")
        }
        append("]")
    }
    val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
    return entity.id
}

internal fun ChatViewModel.buildSystemPrompt(): String? {
    // Cache-friendly layout: keep `base` byte-stable by stripping out anything
    // that varies per request, then append a "Runtime context" suffix at the
    // very end with all the dynamic bits (date, timezone, locale, configured
    // minis-model-use count). OpenAI / DeepSeek prompt caching is prefix-
    // based, so the longer the static head, the better the hit rate.
    // Pre-T122 the prompt embedded `Current time: yyyy-MM-dd HH:mm` mid-base,
    // which guaranteed cache misses across minute boundaries — even a quick
    // follow-up could land on a different minute and pay full ingestion.
    val today = java.time.LocalDate.now()
    val dateStr = today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
    val tzId = java.util.TimeZone.getDefault().id
    val lang = context.resources.configuration.locales[0].toLanguageTag()

    // Count of agent-loop-visible models for the `minis-model-use` CLI
    // (exposed as a shell command via the native_offload handler).
    val toolsEnabled = agentTools.isNotEmpty()
    val modelUseCount = if (toolsEnabled) {
        try { providerRepository.resolvedAgentLoopEntries().size } catch (_: Exception) { 0 }
    } else 0

    // The selected identity is assembled by prepareNovexRequestContext. The editable
    // conversation instructions survive identity changes and never mutate shared cards.
    val legacyProfile = _immersiveProfile.value
    val identitySection = novex.core.NovexLegacyPromptProjection.project(
        prompt = _conversationPrompt.value ?: inheritedEditablePrompt(),
        configuration = currentNovexConfiguration(),
        legacyRoleId = legacyProfile.characterVersionId ?: legacyProfile.character?.id,
        legacyPlayerId = legacyProfile.persona?.id,
        legacyWorldId = legacyProfile.worldId,
        legacyGeneratedPrompt = com.openminis.app.data.character.CharacterPromptComposer.compose(
            legacyProfile.character?.toJson()?.toString(), legacyProfile.persona?.toJson()?.toString(), legacyProfile.world?.toJson()?.toString()),
    )
    // Keep memory prompt injection and the Novex memory tool set behind the
    // same per-conversation switch.
    val memoryOn = _memoryEnabled.value
    val preparedTeaching = if(integratedCardBinding()!=null) com.openminis.app.cards.IntegratedCardPrompt.build(
        // [T-prompt-identity-priority]（用户 2026-09-27 报告"写了对话提示词不代入"）
        // identitySection（含用户手写的对话提示词/人格指令）作为第一参数：
        // 挂卡链路下它被前置到 system 开头，不再压在十几条卡片管理条款之后。
        identitySection,memoryOn,agentTools.mapTo(linkedSetOf()){it.name}) else com.openminis.app.agent.NovexSystemPrompt.buildPrepared(
        sessionId = activeSessionId,
        context = context,
        personalitySection = identitySection,
        memoryEnabled = memoryOn,
        toolsEnabled = toolsEnabled,
        availableToolNames = agentTools.mapTo(linkedSetOf()) { it.name },
    )
    val base = preparedTeaching.prompt
    // Match iOS order exactly: skills → global memory → recent daily memory.
    // See ios/Agent/Chat/AIChatViewModel.swift:4375-4387. Each fragment is
    // appended only when non-null; absent fragments leave no separator.
    // Skills may be installed outside this view model, so refresh the adapter
    // before reading the prompt fragment. This scan performs no network I/O.
    if (toolsEnabled) skillRepository?.reloadFromDisk()
    val skillFragment = if (toolsEnabled) skillRepository?.skillPromptFragment(activeSessionId) else null
    val imageGenerationSkill = if (
        toolsEnabled && providerRepository.resolvedImageGenerationEntries().isNotEmpty()
    ) GenerateImageTool.skillPrompt() else null
    // [P3.3 裁军] MCP 提示词片段（mcpFragment，R0 沙箱退役后已恒 null）
    // 随 MCP 集成面彻底退役，不再注入。
    // [T-memory-toggle-gates-injection-and-tools-android] Skip loading
    // GLOBAL.md + recent daily logs entirely when the user has turned
    // memory off for this session. Cheaper (no disk read) and — more
    // importantly — keeps the model from seeing stale persistent state
    // it can't tell the user how to manage. Skills and SOUL.md are
    // intentionally NOT gated by this toggle: skills are part of the
    // tool surface and SOUL.md is part of identity, both orthogonal
    // to the memory feature.
    val activeMemory = activeMemoryRepository()
    val globalMemoryFragment = if (memoryOn) activeMemory?.loadGlobalMemoryFragment() else null
    val dailyMemoryFragment = if (memoryOn) {
        activeMemory?.loadRecentDailyMemoryFragment(excludedBranchMemoryWrites)
    } else null
    val novexMemoryFragment = if (memoryOn) activeNovexMemoryFragment() else null
    // [T-stage2-memory] AI 随身笔记（水位刻度后台整理产物，常驻小块）；
    // [净眼 P3] 与其他记忆源同受 /memory 开关门控。
    val sessionMemoryBlock = if (memoryOn) memoryStoreFor(activeSessionId)
        .injectionBlock(_sessionMemory.value) else null
    // [T-stage3-snapshot]（总纲 §3.6 记忆干扰根治）压缩后：常量模块重注入
    // （幂等安全）+ 当前状态锚（快照一行版，§3.8 注意力锚）+ 元说明分工。
    val compacted = _cachedLatestMarker != null && _compactSummary.value?.isNotBlank() == true
    var constantReinjectionBlock: String? = null
    var statusAnchorLine: String? = null
    if (compacted) {
        constantReinjectionBlock = buildConstantReinjection()
        statusAnchorLine = novex.core.NovexStateSnapshot.anchorLine(_worldSnapshot.value)
    }
    // [T-stage4-reading]（总纲 §3.8 第 2 层·AI 主动层）待命资料目录指针：
    // 只列模块名（几十 token 的菜单，非内容）——模型看得到"有什么可查"才
    // 会主动 read_card/read_text_block。三层读取的其余两层已落地（系统
    // 保障层=Adoption 关键词判定；注意力锚=状态锚）。sticky 挂账（需
    // Adoption 有状态化重构）。
    val standbyDirectoryBlock = buildStandbyDirectory()

    return buildString {
        append(base)
        if (skillFragment != null) {
            append("\n\n")
            append(skillFragment)
        }
        if (imageGenerationSkill != null) {
            append("\n\n")
            append(imageGenerationSkill)
        }
        if (globalMemoryFragment != null) {
            append("\n\n")
            append(globalMemoryFragment)
        }
        if (dailyMemoryFragment != null) {
            append("\n\n")
            append(dailyMemoryFragment)
        }
        if (novexMemoryFragment != null) {
            append("\n\n")
            append(novexMemoryFragment)
        }
        // [T-stage2-memory] AI 随身笔记常驻块（水位刻度后台整理产物；
        // 空笔记零痕迹）。置于 Runtime context 之前、静态区末尾。
        if (sessionMemoryBlock != null) {
            append("\n\n")
            append(sessionMemoryBlock)
        }
        if (constantReinjectionBlock != null) {
            append("\n\n")
            append(constantReinjectionBlock)
        }
        if (statusAnchorLine != null) {
            append("\n\n<当前状态锚>\n")
            append(statusAnchorLine)
            append("\n</当前状态锚>")
        }
        if (standbyDirectoryBlock != null) {
            append("\n\n")
            append(standbyDirectoryBlock)
        }
        // Runtime context goes last so the prefix above stays byte-stable
        // across requests within the same day. Keep ordering deterministic
        // (date → tz → lang → model count) — any reorder defeats the cache.
        append("\n\nRuntime context:\n")
        append("- Current date: ").append(dateStr).append(" (").append(tzId).append(")\n")
        append("- Device language: ").append(lang).append("\n")
        if (toolsEnabled) {
            append("- minis-model-use models available: ").append(modelUseCount)
        } else {
            append("- Model mode: pure chat; structured tools disabled")
        }
    }.also { assembled ->
        novexPromptAuditInput = ChatViewModel.NovexPromptAuditInput(assembled, identitySection, preparedTeaching.persistentContext, assembled.removePrefix(base))
    }
}

// ─── Legacy tool execution methods (kept for compatibility) ───────────

fun ChatViewModel.executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
    val repo = activeMemoryRepository() ?: return MemoryTools.ToolResult("Error: Memory not available", false)
    if (!_memoryEnabled.value) {
        return MemoryTools.ToolResult(
            "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
            false,
        )
    }
    val result = MemoryTools.executeMemoryWrite(argsJson, repo)
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
    return result
}

fun ChatViewModel.executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
    val repo = activeMemoryRepository() ?: return MemoryTools.ToolResult("Error: Memory not available", false)
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
    return result
}

// ─── Misc Helpers ────────────────────────────────────────────────────

/**
 * T209: resize image bytes for the LLM inference payload only — the
 * full-resolution original is preserved on disk (mediaStore + uploads
 * dir) so chat history fullscreen view, agent shell `cat`, and
 * `read_image` all see the user's original picture, matching iOS.
 *
 * Returns null when the source already fits within [maxEdge] (caller
 * should fall back to [rawBytes]) or on any decode/compress failure.
 */
internal fun ChatViewModel.buildAssistantPartsJson(parts: List<AgentContentPart>): String {
    val sb = StringBuilder("[")
    var first = true
    for (p in parts) {
        if (p !is AgentContentPart.Text) continue
        if (!first) sb.append(',') else first = false
        sb.append("""{"type":"text","value":""")
        sb.append(escapeJson(p.text))
        sb.append('}')
    }
    sb.append(']')
    return sb.toString()
}

/**
 * Resume an interrupted agent loop. Injects a `<system-reminder>` into
 * agentHistory so the model picks up where it left off, then re-enters
 * the agent loop in a fresh [streamJob]. Mirrors iOS
 * AIChatViewModel.resume().
 *
 * Safe to call only when [canResume] is true and [isStreaming] is false.
 * Clears [_canResume] on entry so repeated taps don't stack.
 */
fun ChatViewModel.resume() {
    if (_isStreaming.value || !_canResume.value) return
    // [T-run-phase] D2：清空进行中不恢复。
    if (isWipingSession) {
        appendSystemInfo(text = "正在清空对话，请稍候再继续。", iconKind = "compact")
        return
    }
    val provider = currentProvider ?: run {
        _error.value = "请先选择可用的模型"
        return
    }
    _canResume.value = resumeEligibilityAfterRecoveryAction(RecoveryAction.RESUME)
    _error.value = null
    // [T-error-persist-android] resume() follows finalizeAtTurnLimit's
    // setInlineError (which persisted an error sticker on the last assistant
    // row). Clear it now so a successful resume doesn't merge-resurrect the
    // turn-limit banner on the next reload.
    clearPersistedLastAssistantError()
    AppLogger.info(ChatViewModel.TAG, "▶️ resume: continuing partial assistant message (no new header emitted)")
    // Resume is explicit navigation intent: reveal its thinking placeholder
    // once, then let the reply grow without moving the viewport.
    _forceScrollToBottom.tryEmit(Unit)

    _isStreaming.value = true

    viewModelScope.launch {
        val baseSystemPrompt = try { buildSystemPrompt() } catch (failure: Exception) {
            _isStreaming.value = false
            _canResume.value = true
            _error.value = failure.message ?: "恢复对话前准备失败"
            return@launch
        }
        val systemPrompt =
            if ((provider as? novex.android.transport.NovexTransportProvider)?.isAnthropicOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

        AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=true (sid=$activeSessionId)")
        _isStreaming.value = true
        streamJob = launch(Dispatchers.IO) {
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob ENTER sid=$activeSessionId")
            val leasedSessionId = activeSessionId
            var slotAcquired = false
            var slotReleased = false
            try {
                SessionConcurrencyManager.acquireSlot(leasedSessionId)
                slotAcquired = true
                AppLogger.debug(ChatViewModel.TAG_STREAM, "resume streamJob slot acquired")
                SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })
                val activeFallbackStrategy = run {
                    val groupId = _selectedGroupId.value
                    groupId?.let {
                        providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy
                    } ?: novex.android.data.model.FallbackStrategy.default
                }
                val fallbackProviders = buildFallbackProviders(provider)
                try {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CALL")
                    runAgentLoop(
                        provider = provider,
                        systemPrompt = systemPrompt,
                        fallbackProviders = fallbackProviders,
                        fallbackStrategy = activeFallbackStrategy,
                        recoveryOrigin = AgentRunRecoveryOrigin.RESUME,
                    )
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop RETURN normal")
                    drainQueuedPrompts(provider, systemPrompt, fallbackProviders, activeFallbackStrategy)
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume drainQueuedPrompts RETURN")
                } catch (e: CancellationException) {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume runAgentLoop CANCELLED")
                    Log.d(ChatViewModel.TAG, "Agent loop cancelled (resume)")
                } catch (e: Exception) {
                    AppLogger.error(ChatViewModel.TAG_STREAM, "resume runAgentLoop EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                    Log.e(ChatViewModel.TAG, "Agent loop error (resume)", e)
                    if (streamJob === coroutineContext[Job]) {
                        setInlineError(e.message ?: "恢复执行未完成")
                        _canResume.value = true
                    } else {
                        AppLogger.info(ChatViewModel.TAG_STREAM, "resume stale stream ignored exception UI update")
                    }
                } finally {
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY enter")
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
                        AppLogger.info(ChatViewModel.TAG_STREAM, "resume stale stream skipped tracker finalization")
                    }
                    SessionConcurrencyManager.releaseSlot(leasedSessionId)
                    slotReleased = true
                    AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob FINALLY exit")
                }
            } catch (e: CancellationException) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob CANCELLED waiting for slot")
                Log.d(ChatViewModel.TAG, "Cancelled while waiting for concurrency slot (resume)")
            }
            if (slotAcquired && !slotReleased) SessionConcurrencyManager.releaseSlot(leasedSessionId)
            // [T-android-stale-streamjob-clears-isstreaming] guard.
            if (streamJob === coroutineContext[Job]) {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming=false (about to set)")
                _isStreaming.value = false
            } else {
                AppLogger.info(ChatViewModel.TAG_STREAM, "resume _isStreaming SKIPPED (stale job)")
            }
            AppLogger.info(ChatViewModel.TAG_STREAM, "resume streamJob EXIT")
        }
    }
}

fun ChatViewModel.cancelPendingContentPlan(planId: String, onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
        val result = runCatching {
            novexManagementMutex.withLock {
                novexApplication().novexWorkspace.apply(
                    novex.core.NovexCommand.ReleaseConversationDraftWrite(activeSessionId, planId),
                )
            }
            Unit
        }
        onResult(result)
    }
}

fun ChatViewModel.markConversationVisible() {
    conversationVisible = true
    conversationExitJob?.cancel()
    conversationExitJob = null
}

/** Only the actual navigation back to the conversation list calls this boundary. */
fun ChatViewModel.returnToConversationList(onReturned: () -> Unit) {
    conversationVisible = false
    conversationExitJob?.cancel()
    conversationExitJob = viewModelScope.launch {
        try {
            sessionLoaded.first { it }
            persistComposerDraft()
            _isStreaming.first { !it }
            novexManagementMutex.withLock {
                novexConfigurationMutex.withLock finalize@{
                    if (conversationVisible || _isStreaming.value) return@finalize
                    val sid = realSessionId.takeIf { it.isNotBlank() } ?: return@finalize
                    val app = novexApplication()
                    // A first file may still be copying: keep the empty conversation until import settles.
                    if (app.conversationRepositoryImporter.status(sid).value.running) return@finalize
                    val drafts = app.novexWorkspace.conversationDrafts(sid)
                    val protection = if (_canResume.value || _isCompacting.value || novexLearningSession.isRunning ||
                        novexOperationJournal.list(sid).any { it.status in setOf(
                            novex.core.NovexOperationStatus.WAITING,
                            novex.core.NovexOperationStatus.APPROVED) })
                        drafts?.subjects.orEmpty().toSet() else emptySet()
                    val hasFiles = withContext(Dispatchers.IO) {
                        val scope = novex.core.NovexConversationWorkspaceScope(
                            sid, activeBranchPathIds, novex.core.NovexConversationWorkspaceScope.ROOT_BRANCH,
                        )
                        com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(
                            novexConversationWorkspaceStore, app.creativeArtifactRepository,
                        ).reconcile(scope)
                        novexConversationWorkspaceStore.inspect(scope).entries.isNotEmpty()
                    }
                    val finalized = app.novexWorkspace.apply(
                        novex.core.NovexCommand.FinalizeConversationDrafts(sid, protection),
                    ) as novex.core.NovexChange.ConversationDraftsFinalized
                    if (finalized.result.snapshot.cards.isNotEmpty() || finalized.result.snapshot.pendingWrites.isNotEmpty()) return@finalize
                    if (_inputText.value.isNotEmpty() || _attachments.value.isNotEmpty() || currentNovexConfiguration().hasPersistentConfiguration) return@finalize
                    if (_conversationPrompt.value != null && _conversationPrompt.value != inheritedEditablePrompt()) return@finalize
                    if (_imageStylePrompt.value.isNotBlank() || chatRepository.messageCount(sid) > 0) return@finalize
                    if (app.creativeArtifactRepository.list(com.openminis.app.data.creative.CreativeArtifactQuery(
                            conversationId = sid, includeTrashed = true)).isNotEmpty()) return@finalize
                    if (hasFiles || conversationVisible || _isStreaming.value) return@finalize
                    chatRepository.dropSession(sid)
                    ChatViewModelStore.release(sid)
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Preserve all remaining records on failure; returning to the list must remain available.
            AppLogger.warning(ChatViewModel.TAG, "对话草稿归档未完成：${error.message}")
        }
    }
    onReturned()
}

fun ChatViewModel.clearError() {
    _error.value = null
}

internal fun ChatViewModel.escapeJson(text: String): String {
    val sb = StringBuilder("\"")
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                else sb.append(c)
            }
        }
    }
    sb.append("\"")
    return sb.toString()
}

/**
 * Convert a flat list of MessageRow into ChatMessages, merging toolResult
 * data from user-role messages back into their corresponding AssistantBlocks.
 * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
 */
/**
 * Matches a `<system-reminder>...</system-reminder>` block, including any
 * surrounding whitespace / newlines, so a part that is *only* a reminder
 * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
 * spans newlines (reminders run multi-line in the cancel/resume paths).
 *
 * Only applied at the UI-render transform — agentHistory + DB rows keep
 * the raw text so the LLM continues to see the reminder on subsequent
 * turns (matches iOS, where system-reminder text is appended to
 * agentHistory/（已退役的旧线格式） parts but never to the chat-list ChatMessage).
 */
private val systemReminderRegex =
    Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

internal fun ChatViewModel.stripSystemReminders(text: String): String =
    if (!text.contains("<system-reminder>")) text
    else systemReminderRegex.replace(text, "")

/**
 * Attachment metadata is stripped for display by the shared attachment
 * envelope helper. Persisted rows and model history keep the original receipt.
 */
