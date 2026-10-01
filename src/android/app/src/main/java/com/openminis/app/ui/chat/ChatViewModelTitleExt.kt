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

// 会话标题生成（首发触发、回退、解析、覆盖层摘要）。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结。

/** LLM-based title + category generation, mirrors iOS generateSessionTitleIfNeeded(). */
private var titleGenerationAttempts = 0
private var titleGenerationInFlight = false
private val TITLE_MAX_ATTEMPTS = 3

// [T-android-auto-grouping-injection] Bounds on the group list folded into
// the title-generation prompt. 30 groups × ~140 chars keeps the segment
// well under a KB even in the worst case; the description cap matches
// SessionFolderRow.DESCRIPTION_MAX_CHARS, which is enforced at creation time but not
// on rows written by older builds or by the AI-Suggest path.
private val GROUP_CONTEXT_MAX = 30
private val GROUP_NAME_MAX = 40
private val GROUP_DESC_MAX = 100

internal fun ChatViewModel.generateSessionTitleIfNeeded() {
    // [T-android-titlegen-diag-logging] Unified "TitleGen" trail across
    // every path of this function — XIN 40454 reported sessions silently
    // staying "New Chat" and the failure paths were under-logged.
    // Logging only; no logic change.
    AppLogger.info(
        "TitleGen",
        "enter session=${realSessionId.ifEmpty { sessionId }} attempts=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
            "inFlight=$titleGenerationInFlight currentTitle='${_sessionTitle.value.take(200)}'",
    )
    if (titleGenerationInFlight || titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
        AppLogger.info(
            "TitleGen",
            "skip guard=${if (titleGenerationInFlight) "inFlight" else "max-attempts ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS)"}",
        )
        return
    }
    // Skip if title already set (not "New Chat")
    if (_sessionTitle.value != "New Chat" && _sessionTitle.value.isNotEmpty()) {
        AppLogger.info("TitleGen", "skip guard=title-already-set title='${_sessionTitle.value.take(200)}'")
        return
    }
    // Prefer a dedicated sub-model (cheap, non-OAuth) — mirrors iOS resolveSubEntry.
    // Falls back to the primary provider if no sub-group is configured.
    // [T-title-gen-fallback-first-message-android] If no provider can be
    // resolved at all, the session would silently stay "New Chat". Log the
    // reason and fall back to the first user message as the title.
    val subProvider = resolveTitleProvider()
    if (subProvider == null) {
        AppLogger.info("TitleGen", "resolveTitleProvider=null — falling back to currentProvider")
    }
    val provider = subProvider ?: currentProvider
    if (provider == null) {
        AppLogger.warning("TitleGen", "no provider available (sub + current both null) — fallback-to-first-message path")
        viewModelScope.launch(Dispatchers.IO) {
            applyFallbackTitleFromFirstMessage("no provider available")
        }
        return
    }

    titleGenerationInFlight = true
    titleGenerationAttempts++

    // [T-titlegen-context-first-last-pair] Build the summary from the first
    // user + first assistant message, and — when the session has more than
    // one user turn — also the last user + last assistant message, each
    // truncated to 200 chars. This lets the title adapt when the topic
    // shifts later in a long session, instead of only seeing the opener.
    val msgs = _messages.value
    val userMessages = msgs.filter { it.role == "user" }
    val firstUser = userMessages.firstOrNull()
    if (firstUser == null) {
        AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
        titleGenerationInFlight = false
        return
    }
    val userText = firstUser.content.take(200)
    // First/last assistant *text* message — skip tool-only capsules whose
    // content is blank so the summary carries real assistant prose.
    val assistantTextMessages = msgs.filter { it.role == "assistant" && it.content.isNotBlank() }
    val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
    // Only append the last pair when there is more than one user turn (i.e.
    // the first and last user messages differ) — avoids duplicating the
    // opener when the session is a single exchange.
    val hasMultipleUserTurns = userMessages.size > 1
    val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
    val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Mirror iOS callSubModelForTitle prompt shape: short cacheable system
            // prompt + user-message payload. Using the exact iOS strings keeps the
            // Anthropic prompt cache warm across title-gen calls.
            // [T-android-auto-grouping] Fold the group question into THIS
            // call — no second round-trip. With the toggle off, or with no
            // groups to offer, the prompt is byte-identical to the
            // title-only form (so the Anthropic prompt cache stays warm).
            // Port of iOS callSubModelForTitle's folderSegment.
            val autoGroupOn = com.openminis.app.ui.settings
                .autoGroupingEnabled(context)
            val groupContext: List<String> = if (!autoGroupOn) emptyList() else {
                // [T-android-auto-grouping-injection] Bound the group list.
                // Group names are free-form user input with no length or
                // content limit (only the description is capped, and only
                // at creation time), and they are interpolated into an
                // instruction — a name containing `" ].` can close the
                // bracket and inject directives. Unbounded COUNT is the
                // other half: 200 groups would add multiple KB to EVERY
                // title generation. Newest-first (listFolders is ORDER BY
                // updated_at DESC), so the cut drops the stalest groups.
                //
                // Dedup on the RENDERED name, not the raw one: sanitizing
                // and truncating to GROUP_NAME_MAX can map two distinct
                // folders onto the same string ("…Machine Learning Reading"
                // and "…Machine Learning Writing" share a 40-char prefix).
                // Offering that string twice would make the model's answer
                // unresolvable, so drop every name that is ambiguous after
                // rendering rather than filing the chat into a coin-flip
                // winner. Same reason the apply side refuses ambiguous
                // matches.
                val rendered = chatRepository.allFolders()
                    .map { f -> f to ChatViewModel.promptSafe(f.name, GROUP_NAME_MAX) }
                    .filter { (_, n) -> n.isNotEmpty() }
                val ambiguous = rendered
                    .groupingBy { (_, n) -> n.lowercase() }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                rendered
                    .filterNot { (_, n) -> n.lowercase() in ambiguous }
                    .take(GROUP_CONTEXT_MAX)
                    .map { (f, n) ->
                        // "name — one-sentence description" when the group
                        // has one; the description exists precisely to
                        // sharpen this membership judgment.
                        val d = f.description?.takeIf { it.isNotBlank() }
                            ?.let { ChatViewModel.promptSafe(it, GROUP_DESC_MAX) }
                            ?.takeIf { it.isNotEmpty() }
                        if (d != null) "\"$n\" — $d" else "\"$n\""
                    }
            }
            val prompt = buildString {
                append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                if (groupContext.isNotEmpty()) {
                    // The null path is spelled out and given its own
                    // example: a closed option list pushes sub-models
                    // toward always picking something, and a wrongly-filed
                    // session costs far more than a wrong category (which
                    // only drives a row icon).
                    append("The user organizes chats into groups. Existing groups: [")
                    append(groupContext.joinToString("; "))
                    append("]. If this conversation clearly belongs to one of these groups, ")
                    append("set \"folder\" to that exact group name. ")
                    append("If it does not clearly match any group, or you are unsure, set \"folder\" to null. ")
                    append("Never invent a new group name.\n\n")
                }
                append("You MUST respond with valid JSON only. Example:\n")
                if (groupContext.isEmpty()) {
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                } else {
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\", \"folder\": null}\n\n")
                }
                append("Conversation:\n")
                append("User: $userText\n")
                if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                append(titleLanguageDirective())
            }
            // [T-android-titlegen-systemprompt-unify] Shared with the manual
            // Regenerate path (SessionListViewModel.regenerateTitle) via the
            // single TITLE_GEN_SYSTEM_PROMPT constant so the two never drift.
            // Passed bare: for OAuth Anthropic instances,
            // AnthropicProvider.resolveSystemPrompt force-prepends the Claude
            // Code prefix block at the provider layer (and strips a
            // caller-supplied one), so no caller-side prepend is needed — the
            // previous manual prefix branch here was redundant.
            val effectiveSystemPrompt = TITLE_GEN_SYSTEM_PROMPT

            AppLogger.info(
                "TitleGen",
                "dispatch attempt=$titleGenerationAttempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
            )
            // [T-android-titlegen-reasoning] Match iOS callSubModelForTitle:
            // explicitly disable thinking (thinkingLevel = OFF). The provider
            // layer's injectThinkingParams honors OFF — e.g. DeepSeek V4 gets
            // an explicit {"thinking":{"type":"disabled"}}, o-series/gpt-5
            // omit reasoning_effort, Anthropic sends no thinking block — so a
            // reasoning sub-model doesn't burn the whole budget on hidden
            // thinking and return empty text. As a belt-and-suspenders for
            // models where OFF is still a no-op (e.g. Qwen3, which thinks by
            // default), keep the T334 budget bump so it can finish thinking
            // and still emit the JSON. Unified with regenerateTitle's ladder.
            val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
            val response = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                systemPrompt = effectiveSystemPrompt,
                maxTokens = titleMaxTokens,
                // Mirror iOS AIChatViewModel.swift:11244 — pass null so
                // gpt-5.x family doesn't reject the request (only
                // temperature=1 allowed there). buildRequestBody omits
                // the field when null.
                temperature = null,
                thinkingLevel = ThinkingLevel.OFF,
            )

            AppLogger.info(
                "TitleGen",
                "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                    "raw='${response.text.take(200).replace("\n", "\\n")}'",
            )
            val (title, category, folderName) = parseTitleResponse(response.text)
            if (title.isNotEmpty()) {
                val sid = realSessionId.ifEmpty { sessionId }
                chatRepository.renameSessionWithCategory(sid, title, category)
                withContext(Dispatchers.Main) {
                    _sessionTitle.value = title
                    _sessionCategory.value = category
                }
                AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
                // [T-android-auto-grouping] Group assignment is best-effort
                // and strictly SUBORDINATE to the title: a name that
                // resolves to nothing is silently dropped (never create a
                // group from a model's answer), and setFolderIfUnfiled
                // means a hand-filed session is never overridden by the
                // model's guess. Mirrors iOS.
                if (!folderName.isNullOrEmpty()) {
                    // findFolderByName rather than a local equals: it trims
                    // BOTH sides (models routinely echo "Work " with a
                    // trailing space, which an exact equals silently
                    // rejects — the feature then looks intermittently
                    // broken), and because listFolders is ORDER BY
                    // updated_at DESC its first hit is the most recently
                    // updated duplicate. That matters when two devices
                    // created a same-named group offline: an arbitrary pick
                    // files the chat into the group the user isn't looking
                    // at. Same resolver the AI-Suggest flow uses.
                    val match = chatRepository.findFolderByName(folderName)
                        // The prompt shows SANITIZED names, so a group whose
                        // real name contains stripped characters comes back
                        // in its sanitized form and won't match directly.
                        // Fall back to comparing sanitized-to-sanitized —
                        // but REFUSE an ambiguous hit rather than taking the
                        // first. Two folders can render to the same string
                        // (truncation at GROUP_NAME_MAX, or names differing
                        // only in stripped characters); filing into an
                        // arbitrary one is a silent wrong-group move the
                        // user gets no signal about. Leaving it ungrouped is
                        // the recoverable outcome.
                        ?: chatRepository.allFolders().filter {
                            ChatViewModel.promptSafe(it.name, GROUP_NAME_MAX)
                                .equals(folderName.trim(), ignoreCase = true)
                        }.singleOrNull()
                    if (match != null) {
                        val applied = chatRepository.setFolderIfUnfiled(match.id, sid)
                        AppLogger.info(
                            "TitleGen",
                            "auto-group '$folderName' -> ${match.id.take(8)} applied=$applied",
                        )
                    } else {
                        AppLogger.info(
                            "TitleGen",
                            "auto-group: model offered '$folderName' but no group matches — leaving ungrouped",
                        )
                    }
                }
            } else {
                // [T-title-gen-fallback-first-message-android] The request
                // succeeded but yielded no usable title — empty body or a
                // response parseTitleResponse couldn't extract a title from
                // (e.g. reasoning model that spent its whole budget thinking,
                // or non-JSON output). Previously this was silent and left
                // the session as "New Chat". Log the real cause and, on the
                // final attempt, fall back to the first user message.
                AppLogger.warning(
                    "TitleGen",
                    "outcome=no-title attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "(empty / unparseable response) stopReason=${response.stopReason} " +
                        "textLen=${response.text.length}",
                )
                if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                    AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                    applyFallbackTitleFromFirstMessage("empty/unparseable title response")
                }
            }
        } catch (e: Exception) {
            // [T-title-gen-fallback-first-message-android] Request error /
            // timeout / provider failure. Log the concrete cause (was
            // already logged, kept) and fall back to the first user message
            // on the final attempt.
            AppLogger.warning(
                "TitleGen",
                "outcome=exception attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
            if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                applyFallbackTitleFromFirstMessage("request failed: ${e.message?.take(200)}")
            }
        } finally {
            titleGenerationInFlight = false
            // [GH#210] Cancellation used to be the one exit that produced
            // NOTHING — no title, no fallback, and no terminal log line, so
            // a dispatched attempt simply vanished. That silence is how
            // this bug stayed invisible: the logs showed 6 dispatches and
            // 5 outcomes with no failure in between.
            //
            // Now a cancelled attempt still lands the first-user-message
            // fallback, so leaving the chat mid-request can no longer strand
            // a session on "New Chat".
            //
            // NonCancellable is load-bearing: we are already in a cancelled
            // scope, so without it the very first suspension point inside
            // applyFallbackTitleFromFirstMessage (the DB write) would throw
            // CancellationException again and write nothing — a fallback
            // that silently never runs is worse than none, because the log
            // line would claim it did.
            if (!isActive) {
                AppLogger.warning(
                    "TitleGen",
                    "outcome=cancelled attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "session=${(realSessionId.ifEmpty { sessionId }).take(8)} " +
                        "reason=scope-cancelled — applying first-message fallback",
                )
                withContext(NonCancellable) {
                    applyFallbackTitleFromFirstMessage("scope cancelled")
                }
            }
        }
    }
}

/**
 * [T-title-gen-fallback-first-message-android] Set the session title to a
 * cleaned-up truncation of the first user message when LLM title generation
 * fails (request error / timeout / empty / parse failure / model
 * unavailable). Strips model-only attachment metadata,
 * collapses whitespace/newlines to single spaces, and clamps to ~30 chars
 * with an ellipsis — matching the title norm (single-line, short). No-op
 * (logged) when there's no usable first-message text.
 *
 * [GH#210] Re-reads the session from the DB first and bails if it already
 * carries a real title, mirroring iOS `applyFallbackTitle` (76e4c07bc).
 * This is not defensive noise: the title request runs 22–51s against a real
 * provider, and every caller of this function is a FAILURE exit reached at
 * the end of that window. The user has had all that time to rename the
 * session by hand, and a manual rename must always win over a machine
 * fallback derived from the opening message.
 */
internal suspend fun ChatViewModel.applyFallbackTitleFromFirstMessage(reason: String) {
    val sidForCheck = realSessionId.ifEmpty { sessionId }
    val existing = chatRepository.sessionById(sidForCheck)?.title?.trim()
    if (!existing.isNullOrEmpty() && existing != "New Chat") {
        AppLogger.info(
            "TitleGen",
            "outcome=fallback-skipped session=${sidForCheck.take(8)} reason=already-titled",
        )
        return
    }
    val raw = _messages.value.firstOrNull { it.role == "user" }?.content
    val text = stripAgentAttachmentMetadata(raw ?: "")
    // Collapse all whitespace (incl. newlines) to single spaces, trim.
    val cleaned = text.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) {
        AppLogger.warning(
            "TitleGen",
            "outcome=fallback-unavailable session=${sidForCheck.take(8)} " +
                "reason=first-user-message-empty-after-cleanup ($reason)",
        )
        return
    }
    val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    chatRepository.renameSession(sidForCheck, fallbackTitle)
    withContext(Dispatchers.Main) {
        _sessionTitle.value = fallbackTitle
    }
    // Length only — never the user's prompt text.
    AppLogger.info(
        "TitleGen",
        "outcome=fallback session=${sidForCheck.take(8)} titleLen=${fallbackTitle.length} reason=$reason",
    )
}

/**
 * Resolve the provider used for title generation. Mirrors iOS resolveSubEntry:
 * prefer an explicitly configured sub-model (cheap, non-OAuth) so title
 * generation doesn't hit the expensive primary model or fail under the
 * OAuth-Anthropic Claude-Code-only gate. Falls back to null if no sub is
 * configured — caller uses the primary provider then.
 */
internal fun ChatViewModel.resolveTitleProvider(): LLMProvider? {
    // [T-disabled-provider-via-group-android] Resolve the dedicated
    // title-generation sub-model (first enabled member of defaultSubGroupId).
    // [T-android-regenerate-title-submodel] Shares
    // ProviderRepository.resolveTitleSubEntry with the manual Regenerate
    // path so both prefer the same sub-model. Silently degrades (caller
    // falls back to the primary provider) when no sub-group is configured or
    // every member sits behind a disabled provider.
    val entry = providerRepository.resolveTitleSubEntry() ?: return null
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
    var apiKey = providerRepository.loadApiKey(instance.id) ?: return null

    // [T-android-titlegen-oauth-refresh] Refresh the OAuth token before
    // building the provider — matches the manual Regenerate path
    // (SessionListViewModel.regenerateTitle) and iOS. Without this, an
    // OAuth sub-model (e.g. OAuth Anthropic Claude Code) with an expired
    // cached token would 401 during auto-title-gen and silently drop to the
    // first-message fallback title. A refresh failure is non-fatal: log it
    // and proceed with the stale token so the request's own 401 flows into
    // the existing error/fallback handling. runBlocking is safe here — this
    // is only reached from the suspend agent loop on a background thread.
    if (instance.credentialType == novex.android.data.model.ProviderCredential.oauth) {
        try {
            val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            val freshToken = kotlinx.coroutines.runBlocking { manager?.validAccessToken() }
            if (freshToken != null && freshToken != apiKey) {
                providerRepository.saveApiKey(instance.id, freshToken)
                apiKey = freshToken
            }
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "TitleGen OAuth refresh failed: ${e.message}")
        }
    }
    return ProviderFactory.create(instance, apiKey, entry.model, context)
}

/** Parse LLM response for title/category JSON. Multiple fallback strategies. */
internal fun ChatViewModel.parseTitleResponse(text: String): TitleGenResult {
    val cleaned = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    // Try JSON parse
    try {
        val json = JSONObject(cleaned)
        val title = json.optString("title", "").trim()
        val category = json.optString("category", "").trim().ifEmpty { null }
        // [T-android-auto-grouping] `folder` is absent whenever
        // auto-grouping is off, and JSON null when the model declined to
        // file the chat — optString maps both to "" → null here.
        val folder = json.optString("folder", "").trim()
            .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        if (title.isNotEmpty()) return TitleGenResult(title, category, folder)
    } catch (_: Exception) {}
    // Regex fallback: extract "title" value
    val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val folderMatch = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    if (titleMatch != null) {
        return TitleGenResult(
            titleMatch.groupValues[1].trim(),
            catMatch?.groupValues?.getOrNull(1)?.trim(),
            folderMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { !it.equals("null", ignoreCase = true) },
        )
    }
    // Plain text fallback: use first line
    val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
    return TitleGenResult(firstLine.take(50), null, null)
}

/** Parsed title-generation payload. [folder] is non-null only when
 *  auto-grouping asked for it AND the model named an existing group. */
internal data class TitleGenResult(
    val title: String,
    val category: String?,
    val folder: String?,
)

/**
 * [T-android-overlay-reply-status-34599] Pull the most recent
 * assistant text out of `_messages` and hand it to
 * [SessionActivityTracker.publishLastReply]. The tracker truncates
 * to a fixed-width excerpt and pairs it with [sessionId] so the
 * floating overlay can render a "tap to open this chat" capsule
 * after the stream completes. No-op when no assistant message has
 * content yet (e.g. fail during the very first turn).
 */
internal fun ChatViewModel.publishOverlayReplyExcerpt(sessionId: String) {
    val snapshot = _messages.value
    val text = snapshot.asReversed().firstOrNull { msg ->
        msg.role == "assistant" && msg.content.isNotBlank()
    }?.content
    SessionActivityTracker.publishLastReply(sessionId, text)
}

