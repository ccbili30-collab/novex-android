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

// 上下文计量/卸载：token 估算、动态上限、历史净化、卸载。
// 均为 ChatViewModel 扩展，签名与行为冻结。

internal fun ChatViewModel.sanitizeAgentHistory() {
    // Walk through history sequentially, checking each assistant message.
    // For each assistant message with tool_use blocks, verify the NEXT message
    // is a user message with matching tool_result blocks. If not, inject them.
    var i = 0
    while (i < agentHistory.size) {
        val msg = agentHistory[i]
        if (msg.role != LLMMessage.Role.ASSISTANT) { i++; continue }

        val toolUses = msg.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
        if (toolUses.isEmpty()) { i++; continue }

        val toolUseIds = toolUses.map { it.id }.toSet()

        // Check next message for matching tool_results
        val next = agentHistory.getOrNull(i + 1)
        val nextResultIds = next?.contentParts
            ?.filterIsInstance<AgentContentPart.ToolResult>()
            ?.map { it.id }?.toSet() ?: emptySet()

        val missingIds = toolUseIds - nextResultIds
        if (missingIds.isEmpty()) { i++; continue }

        // Some tool_uses have no matching tool_result in the next message.
        // If next message is a user message, add the missing results to it.
        // Otherwise, inject a new user message with placeholder results.
        val placeholders = toolUses.filter { it.id in missingIds }.map { use ->
            AgentContentPart.ToolResult(
                id = use.id, name = use.name,
                content = "Tool execution was interrupted by an unexpected error.",
                isError = true,
            )
        }
        Log.w(ChatViewModel.TAG, "sanitize: injecting ${placeholders.size} placeholder tool_result(s) after history[$i]")

        if (next != null && next.role == LLMMessage.Role.USER &&
            next.contentParts.any { it is AgentContentPart.ToolResult }) {
            // Append missing results to the existing user message
            agentHistory[i + 1] = next.copy(
                contentParts = next.contentParts + placeholders
            )
        } else {
            // Insert a new user message with just the placeholder results
            agentHistory.add(i + 1, LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = placeholders,
            ))
        }
        i++
    }

    // Remove orphaned tool_results (result IDs not found in any tool_use)
    val allToolUseIds = agentHistory.flatMap { it.contentParts }
        .filterIsInstance<AgentContentPart.ToolUse>().map { it.id }.toSet()
    val iter = agentHistory.listIterator()
    while (iter.hasNext()) {
        val msg = iter.next()
        if (msg.role != LLMMessage.Role.USER) continue
        val cleaned = msg.contentParts.filter { part ->
            part !is AgentContentPart.ToolResult || part.id in allToolUseIds
        }
        if (cleaned.isEmpty() && msg.content.isBlank()) {
            iter.remove()
        } else if (cleaned.size < msg.contentParts.size) {
            iter.set(msg.copy(contentParts = cleaned))
        }
    }
}

/**
 * Compute max output tokens that fits within the remaining context window.
 * Logic mirrors iOS's dynamicMaxTokens():
 *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, ChatViewModel.MIN_MAX_TOKENS))
 *
 * @param provider The current LLM provider (carries defaultMaxTokens).
 * @param lastContextTokens API-reported input token count from the last call (0 = first call).
 */
internal fun ChatViewModel.dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
    val model = currentModel ?: return minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
    // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
    // The global cap means we never send more than 128K regardless of
    // what the model claims it can output.
    val maxOutputCeiling = minOf(ChatViewModel.GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
    // Context window: model.contextWindow if known, else the shared
    // model-id heuristic. [T-anthropic-context-window] Route through
    // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
    // values apply here too, instead of the stale local "everything 200K"
    // copy that under-reported modern Claude/Gemini windows.
    val contextWindow = effectiveContextWindowTokens() ?: model.contextWindowTokens
    if (contextWindow <= 0) return maxOutputCeiling
    val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
    val remaining = contextWindow - inputTokens
    val clamped = maxOf(remaining, ChatViewModel.MIN_MAX_TOKENS)
    val result = minOf(maxOutputCeiling, clamped)
    if (result < maxOutputCeiling) {
        android.util.Log.i(ChatViewModel.TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
    }
    return result
}

// ─── Context Window Offload ──────────────────────────────────────────────
//
// Mirrors iOS `AIChatViewModel.swift`:
//   - estimateContextTokens()        (line 7451)
//   - offloadContextIfNeeded()       (line 7481)
// Per-tool writers live in [com.openminis.app.data.ContextOffload].
//
// The agent loop calls [offloadContextIfNeeded] once per turn just before
// the next API call. When token usage crosses the policy threshold, large
// tool outputs in older messages are written to disk under
// `filesDir/minis-sessions/<sid>/offloads/tools/` and replaced in
// [agentHistory] by `[CONTEXT OFFLOADED] … <linux path>` stubs. The model
// can later `file_read` the path to retrieve the original content.
//
// Why this matters: without offloading, a session that runs many large
// shell tools fills the context window and either trips compact (lossy)
// or hits the model's context-exhausted error. Offload is lossless —
// the data still exists, just on disk instead of in-prompt.

/**
 * Char-based fallback estimate when the API hasn't reported a token
 * baseline yet (first call in a turn). Mirrors iOS line 7451.
 *
 * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
 * image-aware count for image bytes. Underestimates JSON-heavy tool
 * inputs slightly but is adequate as a "should we offload" gate —
 * offload itself uses precise [BPETokenizer.countTokens] per-part
 * for the candidate ranking.
 */
internal fun ChatViewModel.estimateContextTokens(): Int {
    var totalChars = 0
    var imageTokens = 0
    for (msg in agentHistory) {
        for (part in msg.contentParts) {
            when (part) {
                is AgentContentPart.Text -> totalChars += part.text.length
                is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                is AgentContentPart.ToolResult -> {
                    totalChars += part.content.length
                    part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                }
                is AgentContentPart.ImageData -> {
                    imageTokens += BPETokenizer.countImageTokens(part.data)
                }
            }
        }
    }
    return (totalChars / 3.5).toInt() + imageTokens
}

/**
 * Approximate token count for a single agent content part. Used to rank
 * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
 * — text uses BPE, images use the grid-cell heuristic.
 */
internal fun ChatViewModel.countHistoryMessage(message: LLMMessage): Int = NovexRequestEstimate.message(message)

internal fun ChatViewModel.countPartTokens(part: AgentContentPart): Int = NovexRequestEstimate.part(part)

/**
 * Offload candidate descriptor. `msgIdx` and `partIdx` index back into
 * [agentHistory] so we can mutate the part in place after writing the
 * stub to disk.
 */
private data class OffloadCandidate(
    val msgIdx: Int,
    val partIdx: Int,
    val tokens: Int,
    val bytes: Int,
    val toolId: String,
    val toolName: String,
)

/**
 * Walk [agentHistory], identify large tool outputs in the older
 * (non-protected) message range, and offload the highest-token ones to
 * disk until we're back under [ContextPolicy.offloadTarget]. Mirrors iOS
 * `offloadContextIfNeeded(model:lastContextTokens:force:)` (line 7481).
 *
 * Protection rules (parity with iOS line 7535):
 *   - Last 4 messages are never offloaded — the model needs them
 *     verbatim to plan the current turn coherently.
 *   - Already-offloaded parts (prefix [ContextOffload.OFFLOADED_PREFIX])
 *     are skipped — second pass would rewrite the stub uselessly.
 *
 * Eligibility (parity with iOS lines 7556-7596):
 *   - `ToolResult` with content > 500 chars OR image data > 1 KB
 *   - `ToolUse` for `file_write` / `file_edit` whose `content` arg > 500 chars
 *   - bare `ImageData` part > 1 KB
 *
 * Candidates are sorted by token count descending and offloaded greedily
 * until current usage drops below [policy.offloadTarget] (or all
 * candidates are exhausted). When [force] is true, all eligible
 * candidates are offloaded regardless of remaining headroom — used by
 * post-compact code paths to slim down the kept-tail aggressively.
 */
internal fun ChatViewModel.offloadContextIfNeeded(
    contextWindow: Int,
    lastContextTokens: Int,
    force: Boolean = false,
) {
    // New-card conversations use a non-mutating history projection with
    // their real recall tool. Do not replace originals with file_read stubs
    // when that legacy tool is not exposed to this conversation.
    if (integratedCards.binding(activeSessionId) != null) return
    val sid = activeSessionId
    val policy = ContextPolicy.forContextWindow(contextWindow)

    if (!force && policy.offloadThreshold == 0) {
        // Small-window tier: offload disabled — UI surfaces "exhausted"
        // when the user crosses the threshold. Nothing to do here.
        return
    }

    val effectiveTokens =
        if (lastContextTokens > 0) lastContextTokens else estimateContextTokens()

    if (!force && effectiveTokens < policy.offloadThreshold) {
        // Below threshold — no work needed. Caller logs at debug level
        // via dynamicMaxTokens; we stay silent to keep logs readable.
        return
    }

    val targetTokens = if (force) 0 else policy.offloadTarget
    val beforeTokens = effectiveTokens
    var currentTokens = effectiveTokens
    val pct = (effectiveTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
    val remaining = contextWindow - beforeTokens

    AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Triggered ━━━")
    AppLogger.info(ChatViewModel.TAG, "  Window: $contextWindow tokens")
    AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens tokens ($pct% of window, ~$remaining remaining)")
    if (force) {
        AppLogger.info(ChatViewModel.TAG, "  Mode: FORCE — offloading all eligible candidates")
    } else {
        AppLogger.info(ChatViewModel.TAG, "  Threshold: ${policy.offloadThreshold} → Target: $targetTokens")
        AppLogger.info(ChatViewModel.TAG, "  Need to free: ~${beforeTokens - targetTokens} tokens")
    }
    AppLogger.info(ChatViewModel.TAG, "  Agent history: ${agentHistory.size} messages")

    val protectedCount = minOf(4, agentHistory.size)
    val candidateUpper = agentHistory.size - protectedCount
    AppLogger.info(ChatViewModel.TAG, "  Scanning messages 0..<$candidateUpper (last $protectedCount protected)")

    val candidates = mutableListOf<OffloadCandidate>()
    var skippedAlreadyOffloaded = 0
    var skippedUserImages = 0
    var skippedTooSmall = 0

    for (msgIdx in 0 until candidateUpper) {
        val msg = agentHistory[msgIdx]
        for ((partIdx, part) in msg.contentParts.withIndex()) {
            when (part) {
                is AgentContentPart.ToolResult -> {
                    if (part.content.startsWith(ContextOffload.OFFLOADED_PREFIX)) {
                        skippedAlreadyOffloaded++
                        continue
                    }
                    val hasLargeContent = part.content.length > 500
                    val hasLargeImage = (part.imageData?.size ?: 0) > 1024
                    if (!hasLargeContent && !hasLargeImage) {
                        skippedTooSmall++
                        continue
                    }
                    val tokens = countPartTokens(part)
                    val bytes = part.content.toByteArray(Charsets.UTF_8).size +
                        (part.imageData?.size ?: 0)
                    candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                is AgentContentPart.ToolUse -> {
                    if (part.name != "file_write" && part.name != "file_edit") continue
                    val content = part.input.optString("content", "")
                    if (content.length <= 500) continue
                    val tokens = countPartTokens(part)
                    val bytes = content.toByteArray(Charsets.UTF_8).size
                    candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, bytes, part.id, part.name))
                }
                is AgentContentPart.ImageData -> {
                    // [T-user-image-never-offload] 2026-09-16 用户实锤（wire
                    // capture 证据链）：长会话触发上下文卸载后，用户附件图被
                    // 换成"文件已转存"路径文字——模型只剩文件名可看。带
                    // linuxPath 的 ImageData 是用户亲手上传的附件：永不卸载，
                    // 上下文压力让位给文本/工具结果。仅工具产出/无路径的图
                    // 仍可卸载。
                    if (part.linuxPath != null) {
                        skippedUserImages++
                        continue
                    }
                    if (part.data.size <= 1024) {
                        skippedTooSmall++
                        continue
                    }
                    val tokens = countPartTokens(part)
                    // Synthesize a tool id since bare images don't carry one.
                    val synthId = "img${msgIdx}_$partIdx"
                    candidates.add(OffloadCandidate(msgIdx, partIdx, tokens, part.data.size, synthId, "image"))
                }
                is AgentContentPart.Text -> Unit
            }
        }
    }

    candidates.sortByDescending { it.tokens }
    val totalCandidateTokens = candidates.sumOf { it.tokens }
    AppLogger.info(ChatViewModel.TAG, "  Candidates: ${candidates.size} parts (~$totalCandidateTokens tokens total)")
    AppLogger.info(ChatViewModel.TAG, "  Skipped: $skippedAlreadyOffloaded already offloaded, $skippedTooSmall too small, $skippedUserImages user images protected")

    var offloadedCount = 0
    var freedTokens = 0

    for (candidate in candidates) {
        if (currentTokens <= targetTokens) break

        val msg = agentHistory[candidate.msgIdx]
        val parts = msg.contentParts.toMutableList()
        val part = parts[candidate.partIdx]
        var linuxPath = ""

        val newPart: AgentContentPart? = when (part) {
            is AgentContentPart.ToolResult -> {
                if (part.content.length > 500) {
                    linuxPath = ContextOffload.offloadContent(
                        context, sid, part.content,
                        toolId = part.id, toolName = part.name,
                    )
                }
                val imgPath = part.imageData?.let { data ->
                    if (data.size > 1024) {
                        ContextOffload.offloadImage(
                            context, sid, data,
                            toolId = part.id,
                            mimeType = part.imageMimeType ?: "image/png",
                        )
                    } else ""
                } ?: ""
                if (linuxPath.isEmpty()) linuxPath = imgPath
                val stub = ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath)
                part.copy(content = stub, imageData = null, imageMimeType = null)
            }
            is AgentContentPart.ToolUse -> {
                val content = part.input.optString("content", "")
                linuxPath = ContextOffload.offloadContent(
                    context, sid, content,
                    toolId = part.id, toolName = part.name,
                )
                val newInput = org.json.JSONObject(part.input.toString())
                newInput.put(
                    "content",
                    ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath),
                )
                part.copy(input = newInput)
            }
            is AgentContentPart.ImageData -> {
                linuxPath = ContextOffload.offloadImage(
                    context, sid, part.data,
                    toolId = candidate.toolId,
                    mimeType = part.mimeType,
                )
                // Bare ImageData has no toolUseId pairing — replace with a
                // text part carrying the stub. Mirrors iOS line 7653.
                AgentContentPart.Text(
                    ContextOffload.stub(candidate.tokens, candidate.bytes, linuxPath),
                )
            }
            is AgentContentPart.Text -> null
        }

        if (newPart == null) continue
        parts[candidate.partIdx] = newPart
        agentHistory[candidate.msgIdx] = msg.copy(contentParts = parts)

        currentTokens -= candidate.tokens
        freedTokens += candidate.tokens
        offloadedCount++
        val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        AppLogger.info(
            ChatViewModel.TAG,
            "  ✂ Offloaded #$offloadedCount: [${candidate.toolName}] id:${candidate.toolId.take(8)} ~${candidate.tokens} tokens (${candidate.bytes} bytes) → $linuxPath [now $currentTokens ($afterPct%)]",
        )
    }

    if (offloadedCount > 0) {
        val afterPct = (currentTokens.toLong() * 100 / contextWindow.coerceAtLeast(1)).toInt()
        AppLogger.info(ChatViewModel.TAG, "━━━ Context Offload Complete ━━━")
        AppLogger.info(ChatViewModel.TAG, "  Parts offloaded: $offloadedCount")
        AppLogger.info(ChatViewModel.TAG, "  Tokens freed: ~$freedTokens")
        AppLogger.info(ChatViewModel.TAG, "  Before: $beforeTokens/$contextWindow ($pct%)")
        AppLogger.info(ChatViewModel.TAG, "  After:  $currentTokens/$contextWindow ($afterPct%)")
        AppLogger.info(ChatViewModel.TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }
}



