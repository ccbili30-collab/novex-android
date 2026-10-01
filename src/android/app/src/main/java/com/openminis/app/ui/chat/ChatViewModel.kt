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

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

/** Bounds recursive summary splitting so an unavailable provider cannot fan out forever. */
private class CompactionCallBudget(private val maximum: Int = 8) {
    private var used = 0
    fun take() {
        used += 1
        check(used <= maximum) { "压缩请求次数已达到上限，请保留原对话后稍后重试" }
    }
}

class ChatViewModel(
    internal val sessionId: String,
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    // [P3.3 裁军] mcpRepository 参数随 MCP 集成面退役（MCPRepository 已删）。
) : ViewModel() {

    companion object {
        internal const val TAG = "ChatViewModel"

        /**
         * [T-android-auto-grouping-injection] Strip the characters that would let
         * user-authored text escape its slot in the prompt's group list, then
         * bound the length.
         *
         * The list is rendered as `"name" — desc; "name2" — desc2`, so a quote,
         * bracket or semicolon inside a value can terminate the list early and the
         * remainder reads as instruction. Newlines do the same at the line level.
         * Collapses whitespace so a name padded with tabs/newlines can't blow the
         * budget either.
         *
         * Deliberately NOT escaping instead of stripping: the sanitized name has to
         * survive a round trip (the model echoes it back and we match it against
         * the real folder name), and an escape sequence would come back escaped.
         * Stripping keeps the value matchable — findFolderByName's trim +
         * case-fold absorbs the difference for every realistic group name.
         */
        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                // Unicode quote lookalikes: a model reads curly and CJK
                // brackets as quoting just as readily as ASCII, so leaving
                // them in re-opens the break-out the ASCII strip closes.
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                // Format/bidi controls (RLO, LRO, ZWJ...) — invisible in code
                // review, and they can reorder how the rendered line reads.
                .replace(Regex("\\p{Cf}"), "")
                // WHITESPACE: Kotlin Regex is java.util.regex WITHOUT
                // UNICODE_CHARACTER_CLASS, so plain \\s is only
                // [ \\t\\n\\x0B\\f\\r] — U+2028 LINE SEPARATOR, U+2029
                // PARAGRAPH SEPARATOR and U+0085 NEL slip through as REAL line
                // breaks, which is exactly the multi-line break-out this
                // sanitizer exists to stop. \\p{Z} additionally covers NBSP
                // (U+00A0) and the ideographic space, neither of which
                // Kotlin's trim() removes either.
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                // Trim AGAIN after the cut: take() can leave a trailing space,
                // and the round-trip matcher compares trimmed values.
                .trim()

        // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
        // `required` list (so the schema keeps nudging the model to emit them —
        // tool_title drives the live pill header) but which must NOT block the
        // call when absent: they carry no execution semantics, so rejecting the
        // whole call over a missing one is pure downside. Preflight skips these
        // when checking for missing required fields. Mirrors iOS
        // AIChatViewModel.preflightNonBlockingFields.
        private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
         * missing-field check entirely: these fields must still be PRESENT in
         * args — they are just allowed to hold "" as their content.
         *
         * The canonical case is `file_edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into shell_execute + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
        )

        /** True when "" is a legal value for this exact (tool, field) pair. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a ChatViewModel and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            // Unknown tool names go through to the existing `else` branch in
            // executeTool() which returns "Unknown tool: …". Preflight stays
            // silent so we don't double-fail.
            val toolDef = tools.firstOrNull { it.name == name } ?: return null
            // Required fields that actually gate execution (everything except the
            // non-blocking ones like tool_title — see PREFLIGHT_NON_BLOCKING_FIELDS).
            val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
            // Empty args on a tool that requires anything → block. Gate on
            // `enforced` so a tool whose only required field is non-blocking isn't
            // rejected for empty args, and the message lists only real blockers.
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                // Absent — or present as an explicit JSON null. org.json reports
                // has() == true for `{"x": null}` and opt() hands back
                // JSONObject.NULL, which is not a String, so a null previously
                // slipped through BOTH checks and reached the tool as a non-String
                // value. Both spellings are genuinely missing.
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                // Only the truly-empty literal "" is rejected — NOT whitespace.
                // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
                // most notably file_edit with `new_string: "\n"` (replace a block
                // with a newline) or `old_string: "  "` (match consecutive spaces).
                // Both are valid edits, neither is stream corruption.
                //
                // And even "" is legal for whitelisted (tool, field) pairs:
                // file_edit.new_string == "" is the documented "delete old_string"
                // form, not a missing value. [T-preflight-empty-string-allowed]
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            return null
        }
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        internal const val NEWLINE_FLUSH_MIN_CHARS = 50
        internal const val NEWLINE_FLUSH_MAX_LEN = 5_000
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        internal const val TAG_STREAM = "ChatVMStream"
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        internal const val MAX_AGENT_TURNS = 200

        // [T-tool-turn-pressure] 回合终止压力（2026-09-16 用户批④）：细粒度工具
        // 协议下模型会把工具轮当成新提问无限续写（对话包实测一问 40 答）。
        // 软顶 40 轮起注入收尾指令；200 硬顶仍是最后保险。
        internal const val SOFT_TOOL_TURN_LIMIT = 40
        internal const val TOOL_RESULT_HINT =
            "(以下是本轮工具的执行结果，供完成当前任务使用；这不是用户发送的新消息。完成当前任务后请直接给出结果或总结，不要重新开场，也不要重复已完成的工作。)"
        internal const val TOOL_TURN_BUDGET_NOTE =
            "(系统提示：本轮工具调用轮数已达到上限。请利用已获得的结果直接向用户总结当前进展并结束本轮回复，不要再发起新的工具调用。)"
        internal const val MIN_MAX_TOKENS = 1024
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        internal const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        internal const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        private const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /** A compaction run must finish or report a recoverable failure. */
        // One initial summary plus at most two children at each of the two
        // split levels: 1 + 2 + 4 = 7. Keep one spare call for a provider
        // retry path without allowing an unbounded compression cascade.
        private const val COMPACT_MAX_LLM_CALLS = 8
        private const val COMPACT_MAX_SPLIT_DEPTH = 2
        private const val COMPACT_TIMEOUT_MS = 120_000L
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        /** Auto-retry backoff schedule (seconds). Mirrors iOS retryDelays, scaled to task spec: 1s → 2s → 4s. */

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = sessionId,
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                ) as T
            }
        }
    }

    internal val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    internal val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    internal val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            // [T-android-uimessages-sublist-cme] `.toList()` is defensive
            // hardening, NOT a proven fix for the reported crash. Read the
            // measured facts before changing it back.
            //
            // `subList` returns a live VIEW sharing the parent's modCount, and
            // emitting it puts that view in Compose state (ChatScreen collects
            // `uiMessages`). That is a latent hazard worth closing on its own.
            //
            // MEASURED, so nobody re-derives it: a SubList only throws
            // ConcurrentModificationException when its PARENT is structurally
            // mutated IN PLACE (add/removeAt/clear). Every write here is
            // `_messages.value = <new list>` via `+` / filterNot / map, and all
            // of those ALLOCATE A FRESH ArrayList rather than mutating — so the
            // old view's parent is never touched and no CME results. Verified on
            // a JVM probe (`base + x`, `filterNot`, `map` all return a new
            // java.util.ArrayList; comparing a stale window after such a write
            // returned OK, not CME).
            //
            // Also verified end-to-end on device (Pixel 4a, build with this
            // `.toList()` deliberately REVERTED): create a multi-turn session,
            // long-press a middle user message → 编辑 → send. The former
            // destructive edit path provably ran (8 messages → 4), storing a live SubList as
            // `_messages.value`, and a further message was sent — NO crash. The
            // next `+` copies the SubList back into a plain ArrayList, so the
            // view stops being the state before anything can invalidate it.
            //
            // The user's crash (ArrayList$SubList.equals, main thread, realme
            // RMX5010 / Android 16, 2026-08-10/11/12) therefore still has an
            // UNIDENTIFIED trigger: something must mutate a subList's parent in
            // place. That site was not found in ChatViewModel; look next at
            // ChatFlatItems / ChatScreen and at any long-lived mutableListOf
            // whose contents reach Compose.
            //
            // Keep the copy regardless: the window is a snapshot by definition,
            // so copying is also the correct semantics. Only long sessions past
            // the cap allocate; the common path above still returns `raw`
            // unchanged and stays identity-equal.
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    val hasOlderMessages: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { full, cap ->
            full.size > LONG_SESSION_THRESHOLD && full.size > cap
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /**
     * Bump the visible cap by [VISIBLE_MESSAGE_CAP_STEP], saturating at
     * the total message count. Safe to call when there are no older
     * messages — it's a no-op (cap clamps to size). Called by the
     * LazyColumn's "load older" header when the user reaches the top of
     * the windowed slice.
     */
    fun historyNavigationMessages(): List<ChatMessage> = _messages.value.filter { it.role != "system" }

    fun revealHistoryMessage(id: String) {
        val index = _messages.value.indexOfFirst { it.id == id || id in it.sourceDbIds }
        if (index >= 0) _visibleMessageCap.value = maxOf(_visibleMessageCap.value, _messages.value.size - index + 10)
    }

    fun loadOlderMessages() {
        val totalNow = _messages.value.size
        if (totalNow <= LONG_SESSION_THRESHOLD) return
        val next = (_visibleMessageCap.value + VISIBLE_MESSAGE_CAP_STEP).coerceAtMost(totalNow)
        if (next != _visibleMessageCap.value) {
            _visibleMessageCap.value = next
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    internal val _streamingById = MutableStateFlow<Map<String, StreamingDelta>>(emptyMap())
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    /**
     * [T-android-stream-flush-dualpath] Per-message streaming-flush state for
     * the dual-path throttle in [updateAssistantMessage]. Keyed by messageId so
     * the throttle accumulator survives the high-frequency token calls (the
     * earlier per-fragment produceState version reset every fragment rebuild and
     * so never actually throttled — diagnostics showed every tick flushing).
     * Mirrors iOS AIChatViewModel+SSEStream's lastTextDeltaFlush/…Length.
     */
    internal class StreamFlushState {
        var lastFlushMs: Long = 0L
        var lastFlushedLen: Int = 0
        var trailingJob: Job? = null
        // [T-android-stream-flush-review] Freshest suppressed delta. Updated on
        // EVERY throttled tick so the trailing job publishes the latest content
        // (not the stale value captured when the job was first scheduled) — a
        // burst of sub-throttle deltas followed by a pause would otherwise leave
        // the side channel several deltas behind.
        var pendingContent: String? = null
        var pendingBlocks: List<AssistantBlock> = emptyList()
        var pendingAwaiting: Boolean = false
    }
    internal val streamFlushStates = HashMap<String, StreamFlushState>()

    /**
     * [T-android-stream-flush-review] Cancel a message's pending trailing flush
     * and drop its throttle accumulator. Call from EVERY stream-termination
     * path (natural end, cancel, turn-limit, retry-truncate, clearChat) so a
     * trailing coroutine — which runs on viewModelScope, NOT streamJob, and is
     * therefore NOT cancelled by streamJob.cancel() — can't fire after the
     * side channel was drained and re-revive a stale "thinking" overlay row.
     */
    internal fun clearStreamFlushState(id: String) {
        streamFlushStates.remove(id)?.trailingJob?.cancel()
    }
    internal fun clearAllStreamFlushStates() {
        streamFlushStates.values.forEach { it.trailingJob?.cancel() }
        streamFlushStates.clear()
    }
    /** Cancel + drop flush states for any message id NOT in [keptIds] (retry/truncate). */
    internal fun retainStreamFlushStates(keptIds: Set<String>) {
        val drop = streamFlushStates.keys.filter { it !in keptIds }
        for (id in drop) streamFlushStates.remove(id)?.trailingJob?.cancel()
    }

    // Dual-path flush thresholds — ported from iOS. Time tiers scale with total
    // length; the newline fast-path flushes immediately on a line break once
    // enough new chars have accumulated, gated to short docs so dense
    // box-drawing streams don't pin the flush rate to the per-token cadence.
    internal fun streamFlushThrottleMs(len: Int): Long = when {
        len < 500 -> 200L
        len < 2_000 -> 300L
        len < 32_000 -> 500L
        len < 64_000 -> 1_000L
        len < 128_000 -> 1_500L
        else -> 2_000L
    }

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    internal val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()
    internal var inputEditedBeforeLoad = false
    private val composerDraftMutex = kotlinx.coroutines.sync.Mutex()
    private val _modelSetupRequired = MutableStateFlow(false)
    val modelSetupRequired: StateFlow<Boolean> = _modelSetupRequired.asStateFlow()

    fun dismissModelSetup() { _modelSetupRequired.value = false }

    /** Gate sending before the screen clears the composer or its attachments. */
    fun hasModelForSend(): Boolean {
        // The screen also gates queued prompts. Do not replace a running connection.
        if (_isStreaming.value) return true
        try {
            val id = requireNotNull(_activeEntryId.value) { "请先选择模型" }
            val (entry, instance) = novex.android.data.model.ChatModelSelection.resolve(providerRepository.config.value, id)
            val key = requireNotNull(providerRepository.usableApiKey(instance)) { "模型连接缺少凭据" }
            bindChatEntry(entry, instance, key)
            return true
        } catch (failure: Exception) {
            currentProvider = null
            _error.value = failure.message ?: "所选模型无法连接"
            recordModelPreparationFailure(failure)
        }
        _modelSetupRequired.value = true
        return false
    }

    internal suspend fun persistComposerDraft() = composerDraftMutex.withLock {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return@withLock
        // Read inside the lock so a queued older save cannot overwrite a newer flush.
        chatRepository.saveComposerDraft(sid, _inputText.value)
    }

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)
    /** Cached only for the session that produced it; never leak rows across reloads. */
    internal var retainedTranscriptRows: List<FlatChatItem> = emptyList()
    internal var retainedTranscriptSessionId: String? = null

    // A chronological LazyColumn starts at its oldest row. Each session-scoped
    // ViewModel consumes exactly one initial navigation to the newest row; the
    // flag then survives forward navigation together with listState so returning
    // to the conversation preserves the reader's actual position.
    private var needsInitialTranscriptPositioning = true

    val isTranscriptViewportPositioned: Boolean
        get() = !needsInitialTranscriptPositioning

    fun consumeInitialTranscriptPositioning(): Boolean {
        if (!needsInitialTranscriptPositioning) return false
        needsInitialTranscriptPositioning = false
        return true
    }

    fun setInputText(value: String) {
        inputEditedBeforeLoad = true
        _inputText.value = value
    }

    private val _novexControls = MutableStateFlow<List<ConversationControlDefinition>>(emptyList())
    val novexControls: StateFlow<List<ConversationControlDefinition>> = _novexControls.asStateFlow()
    internal val _activePlaythroughState = MutableStateFlow<PlaythroughState?>(null)
    val activePlaythroughState: StateFlow<PlaythroughState?> = _activePlaythroughState.asStateFlow()
    internal val _novexDataUpdates = MutableSharedFlow<NovexDataUpdateEvent>(extraBufferCapacity = 8)
    /** Software-generated state changes; model prose never writes this stream. */
    internal val novexDataUpdates: SharedFlow<NovexDataUpdateEvent> = _novexDataUpdates.asSharedFlow()
    private val _novexControlView = MutableStateFlow<ConversationControlOutcome.View?>(null)
    val novexControlView: StateFlow<ConversationControlOutcome.View?> = _novexControlView.asStateFlow()

    fun dismissNovexControlView() {
        _novexControlView.value = null
    }

    fun runNovexControl(control: ConversationControlDefinition) {
        when (
            val outcome = InteractiveFictionRuntime.invoke(
                control,
                _activePlaythroughState.value ?: PlaythroughState(activeBranchPathIds.lastOrNull() ?: "unstarted"),
            )
        ) {
            is ConversationControlOutcome.View -> _novexControlView.value = outcome
            is ConversationControlOutcome.Action -> sendMessage(outcome.userTurn)
        }
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing> <snippet> "`
     *
     * Whitespace between [existing] and [snippet] is normalized to a
     * single space so we never produce `"foo  bar "` when the user's
     * draft happens to end in a trailing space already.
     */
    fun appendToInputText(snippet: String) {
        val cleaned = snippet.trim()
        if (cleaned.isEmpty()) return
        val current = _inputText.value
        val joined = if (current.isBlank()) {
            "$cleaned "
        } else {
            current.trimEnd() + " " + cleaned + " "
        }
        _inputText.value = joined
    }

    internal val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    internal val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call retains the old subtree and persists the edited content as a
     * sibling user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    internal val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    internal val sessionLoaded = MutableStateFlow(false)
    val conversationSettingsReady: StateFlow<Boolean> = sessionLoaded.asStateFlow()

    internal val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    internal val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    internal val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    private val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * Fire-and-forget edge events for explicit resume/retry actions. ChatScreen
     * responds with one navigation to the chronological tail. No stream token,
     * image decode or later row remeasure can repeat that movement.
     */
    internal val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    /**
     * Identity of the user row that was just accepted into the visible
     * transcript. The screen waits for this exact row to be flattened before
     * revealing it; a timer must never guess that the old final row is the new
     * turn.
     */
    internal val _submittedUserMessageId = MutableSharedFlow<SubmittedUserTurn>(extraBufferCapacity = 8)
    val submittedUserMessageId: SharedFlow<SubmittedUserTurn> = _submittedUserMessageId.asSharedFlow()

    internal val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    internal val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    internal val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    internal val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    internal val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    internal val _activeEntryId = MutableStateFlow<String?>(null)
    val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    internal val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    internal val _contextUsageReady = MutableStateFlow(false)
    val contextUsageReady = _contextUsageReady.asStateFlow()
    internal val _contextEstimated = MutableStateFlow(true)
    val contextEstimated = _contextEstimated.asStateFlow()
    internal val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] prepends it as a `<context-summary>` user message
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    private var compactionJob: kotlinx.coroutines.Job? = null
    fun cancelCompaction() { compactionJob?.cancel() }

    internal val _compactSummary = MutableStateFlow<String?>(null)
    val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    internal val _isCompacting = MutableStateFlow(false)
    val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    internal val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    internal val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    /**
     * [T-stream-stall-watchdog] Epoch millis of the in-flight request whose
     * FIRST stream chunk has not arrived yet (null = not waiting). Set when an
     * attempt dispatches, cleared on the first chunk of any type; drives the
     * "已等待 X 秒" hint beside the typing dots (LocalStreamAwaitingSince) so
     * a silent relay (conversation-f899bf05: 51-minute hole) is visible as a
     * running counter instead of a frozen "thinking…" spinner. The stall
     * watchdog itself lives at the provider layer — this is presentation only.
     */
    internal val _streamAwaitingSince = MutableStateFlow<Long?>(null)
    val streamAwaitingSince: StateFlow<Long?> = _streamAwaitingSince.asStateFlow()

    // ── [T-stage2-memory] AI 随身笔记本（总纲 §3.7）──
    /** 内存缓存：注入读它（buildSystemPrompt 在 Main，不碰 IO）；后台整理落盘后刷新。 */
    internal val _sessionMemory = MutableStateFlow(novex.core.NovexNotebookStore.SessionMemory())
    private val memoryConsolidating = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun memoryStoreFor(sessionId: String) =
        novex.core.NovexNotebookStore.forSession(context, sessionId)
    // ── [T-stage3-snapshot] 世界快照缓存（总纲 §3.6；压缩时后台刷新）──
    internal val _worldSnapshot = MutableStateFlow<novex.core.NovexStateSnapshot.Snapshot?>(null)

    // [T-android-stale-streamjob-clears-isstreaming] @Volatile so cross-coroutine
    // reads (the orphaned previous streamJob's tail block running on a different
    // dispatcher) see the latest assignment. Without it, an old job's
    // `if (streamJob === thisJob)` guard could read a cached reference and
    // wrongly reset _isStreaming on the new live job — the exact race XIN hit
    // 2026-06-12 20:22:26 / 20:23:25 (cancel → resume → cancel → retry, where
    // the cancelled resume's finally fired ~2s after the new retry was already
    // streaming, hiding the Stop button while the new turn was live).
    @Volatile
    internal var streamJob: Job? = null
    @Volatile internal var currentProvider: LLMProvider? = null
    @Volatile internal var currentModel: LLMModel? = null

    /** Structured agent history for the agent loop (contentParts-based).
     *
     * [T-single-writer-contract] PR 2a 写点契约（任务书
     * docs/tasks/2026-09-16-conversation-core-pr2-single-writer.md §②）。
     * **非穷尽清单**——净眼二审查明的已知例外全部点名，它们是 PR 2b 的收敛对象：
     * - 权威重建：installActiveConversation；已知第二重建者 loadSession
     *   （compact revert / branch change / recovery 均重载）；
     * - 对偶增量（先 DB 后内存）：runAgentLoop 循环内追加（助手轮/工具对/工具
     *   结果行——真用户行在循环外，见 sendMessage）、injectQueuedPromptsAsNewTurn
     *   用户行、sendMessage 用户行、drainQueuedPrompts、runCrossSync；
     * - 内存专属/就地投影（保留语义但**不保证条数**）：注入桥接段（已登记）、
     *   卸载就地改写（压缩不写本表——audit trail 保留原列表）、sanitizeAgentHistory
     *   （可插行/删行）、choice-repair 用户行改写、终端 UI 工具剥离、dbMessageId 回填；
     * - **PR 2b 已收敛**：clearChat（DB 删除先行、内存投影随后清）、
     *   handleUserCancelledCleanup Case 2（DB 先行拿 dbId；纪元变化走 install 对账）；
     * - **遗留待收敛（PR 2c）**：retryLast（内存回滚靠事后 fork+install 对账，
     *   有 dropOrphaned+sanitize 双兜底；消息树分支化是产品级重写，单独评估）；
     * - 禁止新增：任何绕过 DB 的整表替换/清空（conv9 病根）。
     * 运行时稽查：出口 I1 历史守恒 + 影子装配强信号（shadow_assembly_diff）。
     */
    internal val agentHistory = mutableListOf<LLMMessage>()

    // ── [T-run-phase] PR2c/PR3 状态机种子与写者门 ──
    // 判定逻辑在 RunPhasePolicy（纯函数，测试墙 E1 覆盖）；这里只记录不执法。
    @Volatile
    internal var runPhase = RunPhase.IDLE

    internal fun runPhaseTransitionTo(next: RunPhase, where: String) {
        val previous = runPhase
        runPhase = next
        if (previous.isLegalTransitionTo(next)) {
            AppLogger.info(TAG_STREAM, "[RunPhase] $previous -> $next ($where)")
        } else {
            AppLogger.warning(TAG_STREAM, "[RunPhase] ILLEGAL $previous -> $next ($where) — 仅审计记录，PR3 依据数据执法化")
        }
    }

    /** D2：clearChat 协程期间挡住发送类入口（finally 复位）。 */
    @Volatile
    internal var isWipingSession = false

    /** D3：消息表关键写串行——wipe 的 DELETE 与取消清理的 append 互斥，消灭行复活（P2-2 关账）。 */
    private val dbWriteSerial = kotlinx.coroutines.sync.Mutex()

    /** D4：内存历史竞态对的 happens-before 锁（纪元读/用户行 add/install 重建/清空）。 */
    internal val historyWriteLock = Any()

    /**
     * [T-run-phase] PR3 五件套收敛：世代号取代 size 纪元（E2——语义超集：
     * 同长度换内容/重建也算纪元推进，净眼 P2-1 调度依赖面随之收窄）。
     * 在 historyWriteLock 的写块内自增，读取侧同锁快照。
     */
    internal val historyGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    internal val novexDocumentRepository by lazy {
        FileNovexDocumentSnapshotRepository(
            java.io.File(context.filesDir, "novex/derived/document-snapshots"),
        )
    }
    internal val novexDocumentSnapshotExtractor by lazy {
        NovexDocumentSnapshotExtractor(novexDocumentRepository)
    }
    internal val novexDocumentAgentTools by lazy {
        NovexDocumentAgentTools(novexDocumentRepository) { requested ->
            requested.value in activeNovexDocumentRefs
        }
    }
    @Volatile
    internal var activeNovexDocumentRefs: Set<String> = emptySet()
    internal val novexLearningRepository by lazy {
        FileNovexLearningRepository(
            java.io.File(context.filesDir, "novex/learning"),
        )
    }
    internal val novexLearningPlans by lazy {
        novex.core.NovexLearningExecutionPlans(novexLearningRepository, novexDocumentRepository,
            java.io.File(context.filesDir, "novex/learning-plans"))
    }
    internal val novexLearningSession by lazy {
        novex.core.NovexLearningSession(viewModelScope, novexLearningRepository,
            visibleCollections = { activeNovexSourceCollectionRefs.map(::NovexResourceRef) },
            onState = { state ->
                _novexLearningTask.value = state.task
                _novexLearningStatus.value = state.task?.status
            })
    }
    @Volatile
    internal var activeNovexSourceCollectionRefs: Set<String> = emptySet()
    internal val _pendingNovexLearningPreflight = MutableStateFlow<NovexLearningPreflightSnapshot?>(null)
    val pendingNovexLearningPreflight: StateFlow<NovexLearningPreflightSnapshot?> =
        _pendingNovexLearningPreflight.asStateFlow()
    internal val novexLearningAgentTools by lazy {
        NovexLearningAgentTools(object : novex.core.NovexLearningPreflightResolver {
            override fun prepare(collectionRef: NovexResourceRef, modelId: String?) =
                prepareNovexLearningPreflight(collectionRef, modelId)
            override fun prepare(collectionRef: NovexResourceRef, modelId: String?, action: novex.core.NovexLearningPlanAction) =
                prepareNovexLearningPreflight(collectionRef, modelId, action)
            override fun readState(collectionRef: NovexResourceRef): NovexLearningState? {
                if (collectionRef.value !in activeNovexSourceCollectionRefs) return null
                return novexLearningRepository.find(collectionRef)?.takeIf { collectionRef.value in activeNovexSourceCollectionRefs }
            }
            override fun readSource(collectionRef: NovexResourceRef, documentRef: NovexResourceRef, revision: String): novex.core.NovexDocumentSnapshot? {
                if (collectionRef.value !in activeNovexSourceCollectionRefs || documentRef.value !in activeNovexDocumentRefs) return null
                return novexDocumentRepository.findRevision(documentRef, revision)?.takeIf {
                    collectionRef.value in activeNovexSourceCollectionRefs && documentRef.value in activeNovexDocumentRefs
                }
            }
        }, start = { ref, id -> startNovexLearningPlan(NovexResourceRef(ref), id, awaitCompletion = true) })
    }
    internal val novexConversationWorkspaceStore by lazy {
        novexApplication().conversationWorkspaceStore
    }
    internal val novexWorkspaceAgentTools by lazy {
        NovexWorkspaceAgentTools(novexConversationWorkspaceStore)
    }
    internal val novexMemoryStore by lazy {
        novex.core.FileNovexMemoryStore(
            java.io.File(context.filesDir, "novex/memory"),
        )
    }
    internal val _novexLearningStatus = MutableStateFlow<NovexLearningTaskStatus?>(null)
    val novexLearningStatus: StateFlow<NovexLearningTaskStatus?> = _novexLearningStatus.asStateFlow()
    internal val _novexLearningTask = MutableStateFlow<NovexLearningTaskState?>(null)
    val novexLearningTask: StateFlow<NovexLearningTaskState?> = _novexLearningTask.asStateFlow()
    internal val _novexLearningError = MutableStateFlow<String?>(null)
    val novexLearningError: StateFlow<String?> = _novexLearningError.asStateFlow()
    internal val _novexLearningResponsePreview = MutableStateFlow<String?>(null)
    val novexLearningResponsePreview: StateFlow<String?> = _novexLearningResponsePreview.asStateFlow()
    internal var novexLearningResponsePreviewRequest = 0
    internal val _novexLearningDetails = MutableStateFlow<NovexLearningState?>(null)
    internal val _novexConversationExport = MutableStateFlow<com.openminis.app.share.NovexConversationExportState?>(null)
    val novexConversationExport = _novexConversationExport.asStateFlow()
    internal var novexExportJob: Job? = null
    internal var novexExportRequest = 0

    internal val _novexCheckpoints = MutableStateFlow<List<novex.core.NovexCheckpointRecord>?>(null)
    val novexCheckpoints: StateFlow<List<novex.core.NovexCheckpointRecord>?> = _novexCheckpoints.asStateFlow()
    internal var novexCheckpointRequest = 0
    val novexLearningDetails: StateFlow<NovexLearningState?> = _novexLearningDetails.asStateFlow()
    internal val _novexLearningReadCoverage = MutableStateFlow<List<novex.core.NovexSourceReadCoverage>>(emptyList())
    val novexLearningReadCoverage = _novexLearningReadCoverage.asStateFlow()
    internal val _novexLearningCollections = MutableStateFlow<List<NovexLearningState>?>(null)
    val novexLearningCollections: StateFlow<List<NovexLearningState>?> = _novexLearningCollections.asStateFlow()
    internal var novexLearningDetailsRequest = 0
    internal var conversationVisible = true
    internal var conversationExitJob: Job? = null
    internal val novexManagementMutex = kotlinx.coroutines.sync.Mutex()
    internal val novexContextUsageMutex = kotlinx.coroutines.sync.Mutex()
    internal val novexConfigurationMutex = kotlinx.coroutines.sync.Mutex()
    internal val novexOperationJournal by lazy {
        novex.core.NovexOperationJournal(java.io.File(context.filesDir, "novex-operations"))
    }
    internal val novexToolExecution by lazy { novex.core.NovexToolExecution(novexOperationJournal) }
    val pendingToolApprovals get() = novexToolExecution.pending
    fun restoreToolApprovals() {
        viewModelScope.launch(Dispatchers.IO) {
            try { novexToolExecution.restore(activeSessionId) }
            catch (failure: Exception) { _error.value = failure.message ?: "执行记录未能恢复" }
        }
    }
    fun decideToolOperation(operation: novex.core.NovexToolOperation, approve: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionLoaded.first { it }
                val pending = novex.core.NovexPendingToolTurn.find(
                    chatRepository.loadActiveConversation(activeSessionId).activeMessages)
                val belongsToPendingTurn = pending?.replyId == operation.replyId && pending.calls.any { it.id == operation.callId }
                require(!approve || belongsToPendingTurn) {
                    "这次操作不在当前待续的对话中，请先切回对应分支"
                }
                novexToolExecution.decide(operation, approve)
                withContext(Dispatchers.Main) {
                    // [T-run-phase] P2-7 关账：decideToolOperation 不再绕过清空门
                    // 自置 canResume 触发恢复。
                    if (belongsToPendingTurn && !_isStreaming.value && !isWipingSession) { _canResume.value = true; resume() }
                }
            }
            catch (failure: Exception) { _error.value = failure.message ?: "操作批准未保存" }
        }
    }

    private val sessionCreationMutex = kotlinx.coroutines.sync.Mutex()
    internal val novexMemoryExecutor by lazy {
        novex.core.NovexMemoryToolExecutor(novexMemoryService(),
            java.io.File(context.filesDir, "novex/memory-plans"))
    }

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    internal val integratedCards by lazy { com.openminis.app.cards.IntegratedCards(context,
        readBinding={currentNovexConfiguration().cardBindingJson},
        updateBinding={change->novexSettingsStore.update { configuration->
            configuration.copy(cardBindingJson=change(com.openminis.app.cards.CardBinding.decode(configuration.cardBindingJson)?:com.openminis.app.cards.CardBinding()).encode())
        };Unit}) }
    fun integratedCardBinding() = com.openminis.app.cards.CardBinding.decode(currentNovexConfiguration().cardBindingJson)
    suspend fun saveIntegratedCardBinding(value:com.openminis.app.cards.CardBinding,expected:com.openminis.app.cards.CardBinding?) {
        sessionLoaded.first {it}
        require(!_isStreaming.value){"请先停止当前回答，再调整卡片关联"}
        novexSettingsStore.update { configuration->
            require(com.openminis.app.cards.CardBinding.decode(configuration.cardBindingJson)==expected){"卡片关联已更新，请返回后重新打开，避免覆盖新内容"}
            configuration.copy(cardBindingJson=value.encode())
        }
        // [T-stage1-activation] 已有会话挂卡=立即激活并自动开局（用户零输入）。
        activateBoundCard()?.let { bootstrap -> viewModelScope.launch { sendMessage(bootstrap) } }
    }

    /**
     * [T-stage3-save] 手动存档（/save 名称）：三元组=世界快照+AI 记忆。
     */
    private fun saveGameSlot(name: String) {
        viewModelScope.launch {
            val sid = ensureSession()
            val memory = memoryStoreFor(sid).load().let { loaded -> _sessionMemory.value = loaded; loaded }
            val entry = novex.core.NovexSaveStore.SaveEntry(
                id = novex.core.NovexSaveStore.manualSaveId(name.ifBlank { "存档" }),
                name = name.ifBlank { "存档" },
                createdAt = System.currentTimeMillis(),
                anchorMessageId = synchronized(historyWriteLock) { agentHistory.lastOrNull()?.dbMessageId },
                snapshotJson = (_worldSnapshot.value ?: novex.core.NovexStateSnapshot
                    .loadLatest(context, sid))?.rawJson,
                memoryJson = novex.core.NovexNotebookStore
                    .encode(memory).takeIf { memory.entries.isNotEmpty() },
                ledgerJson = null,
            )
            novex.core.NovexSaveStore.save(context, sid, entry)
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = "已存档「${entry.name}」。/saves 查看列表，/load 序号 回档。",
                    iconKind = "card",
                )
            }
        }
    }

    /**
     * [T-stage3-save] 回档 v1（/load 序号）：恢复三元组，历史保留+系统声明
     * （硬回档 fork 挂账 PR-C）。
     */
    private fun loadGameSlot(index: Int?) {
        if (index == null || index < 1) {
            appendSystemInfo(text = "用法：/load 序号（先 /saves 查看列表）", iconKind = "card")
            return
        }
        viewModelScope.launch {
            val sid = ensureSession()
            val saves = novex.core.NovexSaveStore.list(context, sid)
            val entry = saves.getOrNull(index - 1)
            if (entry == null) {
                withContext(Dispatchers.Main) {
                    appendSystemInfo(text = "存档序号超出范围（共 ${saves.size} 个），/saves 查看。", iconKind = "card")
                }
                return@launch
            }
            entry.snapshotJson?.let { raw ->
                novex.core.NovexStateSnapshot.parse(raw, System.currentTimeMillis())?.let { snap ->
                    novex.core.NovexStateSnapshot.saveLatest(context, sid, snap)
                    _worldSnapshot.value = snap
                }
            }
            entry.memoryJson?.let { raw ->
                runCatching { novex.core.NovexNotebookStore.decode(raw) }.getOrNull()?.let { memory ->
                    // [净眼 N-2] 回档水位归零——新周期重新数档
                    val reset = memory.copy(highWaterTickPercent = 0)
                    memoryStoreFor(sid).save(reset)
                    _sessionMemory.value = reset
                }
            }
            withContext(Dispatchers.Main) {
                appendSystemInfo(
                    text = "已回档到「${entry.name}」（${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(entry.createdAt))}）。世界状态与记忆已恢复；历史保留，本局自此存档点继续。",
                    iconKind = "memory",
                )
            }
        }
    }

    /**
     * [T-stage4-reading] 待命资料目录指针（总纲 §3.8 AI 主动层）：挂卡会话的
     * standby 路由模块名一览 + 查阅指引。只列菜单不放内容（防上下文膨胀）；
     * 无 standby 模块返回 null 零痕迹。
     */
    // [净眼 P2-1] revision 键缓存：卡不变不重读盘（每卡版本一次 IO，Main
    // 上可接受；完整异步化挂账）。key=各卡 revision 拼串。
    @Volatile private var standbyDirectoryCache: Pair<String, String?>? = null
    @Volatile private var constantReinjectionCache: Pair<String, String?>? = null

    /** [净眼 N-1] 各卡 revision 拼串（背景卡编辑也失效缓存，注释与实现归一）。 */
    private fun revisionCacheKey(binding: com.openminis.app.cards.CardBinding): String =
        (listOfNotNull(binding.primary) + binding.backgrounds).joinToString("|") { sel ->
            "${sel.rootId}@${sel.targetId}@" +
                runCatching { integratedCards.store.open(sel.rootId)?.revision }.getOrDefault("")
        }

    internal fun buildStandbyDirectory(): String? {
        val binding = integratedCardBinding() ?: return null
        val revKey = revisionCacheKey(binding)
        standbyDirectoryCache?.let { (key, block) -> if (key == revKey) return block }
        val documents = buildList {
            binding.primary?.let { sel ->
                runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.let { root ->
                    novex.content.ContentTargets.find(root.content, sel.targetId)?.let(::add)
                }
            }
            binding.backgrounds.forEach { sel ->
                runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.let { root ->
                    novex.content.ContentTargets.find(root.content, sel.targetId)?.let(::add)
                }
            }
        }
        val standby = documents.flatMap { it.modules.flattenModules() }
            .filter { it.effectiveRouting() == novex.content.ModuleRouting.STANDBY }
        val block = if (standby.isEmpty()) null else buildString {
            appendLine("<待查资料目录>")
            appendLine("以下资料不在当前上下文内，需要时先 read_card 读结构，再用 read_text_block 按编号读正文：")
            appendLine(standby.take(60).joinToString("、") { it.name })
            append("</待查资料目录>")
        }
        standbyDirectoryCache = revKey to block
        return block
    }

    /**
     * [T-stage3-snapshot]（总纲 §3.6）压缩后的常量模块重注入块：temporality=
     * CONSTANT 且 routing=DEFAULT 的模块全文（幂等安全——游玩不变的事实可
     * 重复出现不产生矛盾）+ 元说明把信息源分工教给模型（常设规则 vs 剧情现
     * 状以摘要/账本为准），根治"压缩后设定/现状互相打架"。快照型（SNAPSHOT）
     * 模块绝不在此出现——它们只随开局资料包一次。
     */
    internal fun buildConstantReinjection(): String? {
        val binding = integratedCardBinding() ?: return null
        val revKey = revisionCacheKey(binding) + "@compacted"
        constantReinjectionCache?.let { (key, block) -> if (key == revKey) return block }
        val documents = buildList {
            binding.primary?.let { sel ->
                runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.let { root ->
                    novex.content.ContentTargets.find(root.content, sel.targetId)?.let(::add)
                }
            }
            binding.backgrounds.forEach { sel ->
                runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.let { root ->
                    novex.content.ContentTargets.find(root.content, sel.targetId)?.let(::add)
                }
            }
        }
        val constants = documents.flatMap { it.modules.flattenModules() }.filter {
            it.effectiveRouting() == novex.content.ModuleRouting.DEFAULT &&
                it.effectiveTemporality() == novex.content.ModuleTemporality.CONSTANT
        }
        if (constants.isEmpty()) {
            constantReinjectionCache = revKey to null
            return null
        }
        val block = buildString {
            appendLine("<世界常设规则（压缩后重注入）>")
            appendLine("以下为开局即定、游玩中不变的事实与规则。剧情的当前状态以对话中的摘要、当前状态锚与账本为准；两者不一致时，规则不变、状态以后者为准。")
            constants.forEach { module ->
                appendLine()
                appendLine("## ${module.name}")
                module.blocks.forEach { block ->
                    val text = (block as? novex.content.ContentBlock.Text)?.let {
                        runCatching { novex.storage.TextPages(integratedCards.store.contents).read(it.content, 0, Int.MAX_VALUE).text }.getOrNull()
                    }
                    if (!text.isNullOrBlank()) appendLine(text)
                }
            }
            append("</世界常设规则>")
        }
        constantReinjectionCache = revKey to block
        return block
    }

    /**
     * [T-stage2-memory] 水位刻度触发的后台记忆整理（总纲 §3.5/3.7）。
     * 回合结束后由 [maybeConsolidateMemoryAfterTurn] 调用：跨过整十刻度才跑
     * （高水位制，回落不重复）；主模型旁路调用整理；护栏——单并发、失败
     * 静默跳过本档（记忆是潜在收益，绝不阻塞或打断用户）。
     */
    internal suspend fun maybeConsolidateMemoryAfterTurn() {
        // [净眼 P3] /memory 关闭时不整理（与注入门控一致）
        if (!_memoryEnabled.value) return
        if (!memoryConsolidating.compareAndSet(false, true)) return
        try {
            val sid = activeSessionId
            val store = memoryStoreFor(sid)
            val memory = store.load().let { loaded ->
                _sessionMemory.value = loaded; loaded
            }
            val window = effectiveContextWindowTokens() ?: currentProvider?.model?.contextWindowTokens ?: return
            val systemEstimate = BPETokenizer.countTokens(buildSystemPrompt().orEmpty())
            val historyEstimate = synchronized(historyWriteLock) {
                BPETokenizer.countTokens(agentHistory.joinToString("") { it.content })
            }
            val reading = novex.core.MemoryWindowBudget.Reading(
                windowTokens = window, systemTokens = systemEstimate, historyTokens = historyEstimate)
            val tick = novex.core.MemoryWindowBudget
                .crossedMemoryTick(memory.highWaterTickPercent, reading) ?: return
            val provider = currentProvider ?: return
            val recentTurns = synchronized(historyWriteLock) { agentHistory.takeLast(6) }
                .joinToString("\n") { it.content.take(2000) }
            val cardName = integratedCardBinding()?.primary?.let { sel ->
                runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.content?.name
            }
            val answer = withContext(Dispatchers.IO) {
                provider.sendMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER,
                        novex.core.NovexNotebookStore.consolidationPrompt(memory, recentTurns, cardName))),
                    null, 4096)
            }
            val now = System.currentTimeMillis()
            val parsed = withContext(Dispatchers.IO) {
                novex.core.NovexNotebookStore.parseConsolidation(answer.text, now)
            }
            // [净眼 P1-2/P2-3] 会话守卫 + 失败也推进高水位（条目保留旧值——
            // "跳过本档"语义，防模型持续吐非 JSON 时每回合重复旁路付费）。
            val next = (parsed ?: memory).copy(highWaterTickPercent = tick, updatedAt = now)
            withContext(Dispatchers.IO) { store.save(next) }
            if (activeSessionId == sid) _sessionMemory.value = next
        } catch (_: Exception) {
            // 静默（任务书 §3.7 护栏）
        } finally {
            memoryConsolidating.set(false)
        }
    }

    /**
     * [T-stage1-activation] 开局激活协议（总纲 §3.2）：绑定主卡后构建开局
     * 资料包（default 路由模块全文）→ 持久化 system 消息（居中渲染、模型
     * 投影为 user 上下文块）→ per_turn/style 模块文本拼接进每轮注入/文风
     * 槽位（空则填、有则追加）→ 幂等（binding 记 activationKey）。返回
     * 自动启动回合指令；null=已激活过（幂等跳过）或无可激活主卡。
     * 放不下绝不静默漏：资料包超预算抛错并附携带清单。
     */
    suspend fun activateBoundCard(): String? {
        sessionLoaded.first { it }
        val binding = integratedCardBinding() ?: return null
        val primary = binding.primary ?: return null
        // 幂等键（净眼 P1-2 修正）：只取 primary+backgrounds——managed/
        // createdReceipts/overrides 的波动（AI 建卡自动入 managed、对话级
        // 携带开关）不构成"换卡"，不得触发重激活（对齐 NovexHistoryAccessScope
        // 的剥法）；换主卡/换背景=重新激活。
        val activationKey = com.openminis.app.cards.CardBinding(
            primary = binding.primary, backgrounds = binding.backgrounds).encode()
        if (binding.activatedKey == activationKey) return null
        val root = integratedCards.store.open(primary.rootId) ?: return null
        val target = novex.content.ContentTargets.find(root.content, primary.targetId)
        val documents = mutableListOf(target)
        binding.backgrounds.forEach { sel ->
            runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.let { bg ->
                novex.content.ContentTargets.find(bg.content, sel.targetId)?.let { documents += it }
            }
        }
        val material = com.openminis.app.cards.NovexCardActivation.build(
            documents,
            readText = { ref -> novex.storage.TextPages(integratedCards.store.contents).read(ref, 0, Int.MAX_VALUE).text },
        )
        // 预算护栏（fail-loud，绝不静默截断）
        // [净眼 P2-4/P3-6] 窗口未知时保守兜底 200K（不再静默跳过激活）；
        // 预算口径 3/5：资料包之外还要容纳 system/工具定义/输出预留/槽位注入/
        // 用户文本——精确分区核算是阶段 2 的活，这里先取保守常数。
        val limit = effectiveContextWindowTokens()
            ?: currentProvider?.model?.contextWindowTokens ?: 200_000
        com.openminis.app.cards.NovexCardActivation.requireFits(
            material, (limit * 3 / 5).coerceAtLeast(1024), com.openminis.app.data.BPETokenizer::countTokens)
        // 落库 system 消息（对模型=对话流位置的 user 资料块；对 UI=居中资料包行）。
        // [净眼 P1-1] 落库后必须同步双补内存——否则 I1 历史守恒在出口把本 VM
        // 的所有发送拒死（DB 有行而 agentHistory/_messages 缺行），且首轮请求
        // 与 UI 都看不到资料包。
        val entity = chatRepository.appendMessage(
            activeSessionId, "system",
            com.openminis.app.cards.NovexCardActivation.partsJson(material),
        )
        withContext(Dispatchers.Main) {
            // [净眼 N-3] 与 sendMessage 用户行同锁写入（锁纪律一致）。
            synchronized(historyWriteLock) {
                agentHistory.add(LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = material.packageText,
                    // [净眼 N-2] 与 DB 投影行齐平（text part 两侧同在），
                    // 影子装配 structural 不产 [] vs [T] 强信号噪音。
                    contentParts = listOf(novex.android.data.model.AgentContentPart.Text(material.packageText)),
                    dbMessageId = entity.id,
                ))
            }
            _messages.value = _messages.value + ChatMessage(
                id = entity.id,
                role = "system",
                content = "",
                toolBlocks = listOf(com.openminis.app.ui.chat.AssistantBlock(
                    id = "activation:${entity.id}",
                    kind = "info",
                    content = "已载入开局资料",
                    toolName = com.openminis.app.cards.NovexCardActivation.SYSTEM_ICON_KIND,
                    toolArgs = material.packageText,
                    toolStatus = ToolBlockStatus.SUCCESS,
                )),
            )
        }
        // 槽位拼接：空则填、有则追加（用户已写内容保留在前）
        if (material.perTurnText.isNotEmpty() || material.styleText.isNotEmpty()) {
            val settings = conversationSettingsSnapshot()
            // 重激活（换卡）去重：同一文本已在槽内则不重复追加（净眼 P1-2）。
            val perTurn = if (settings.perTurnPrompt.contains(material.perTurnText)) settings.perTurnPrompt
                else listOf(settings.perTurnPrompt, material.perTurnText).filter { it.isNotBlank() }.joinToString("\n\n")
            val style = if (settings.textStylePrompt.contains(material.styleText)) settings.textStylePrompt
                else listOf(settings.textStylePrompt, material.styleText).filter { it.isNotBlank() }.joinToString("\n\n")
            if (perTurn != settings.perTurnPrompt || style != settings.textStylePrompt) {
                // [净眼 P3-1→修正] 直写 store（suspend 可等待）。不可在 update 前
                // 手动置位内存态——current() 会读到已置位的新值使 committed==before
                // 短路跳过落盘（内存有 DB 无，重启即丢）；update 在落盘差异时经
                // install 回调统一装内存态。
                val updated = settings.copy(perTurnPrompt = perTurn, textStylePrompt = style)
                novexSettingsStore.update(settings = com.openminis.app.data.normalizeConversationSettings(updated))
            }
        }
        // 幂等标记 + 包内模块快照（净眼 N-1：材料流关闸以此为据）
        novexSettingsStore.update { configuration ->
            val current = com.openminis.app.cards.CardBinding.decode(configuration.cardBindingJson) ?: return@update configuration
            configuration.copy(cardBindingJson = current.copy(
                activatedKey = activationKey,
                activatedModules = material.defaultModuleIds.toList(),
            ).encode())
        }
        return if (target.kind == novex.content.CardKind.CHARACTER) {
            com.openminis.app.cards.NovexCardActivation.BOOTSTRAP_DIRECTIVE_CHARACTER
        } else {
            com.openminis.app.cards.NovexCardActivation.BOOTSTRAP_DIRECTIVE_WORLD
        }
    }
    internal suspend fun integratedConversationImages():Map<String,java.io.File> {
        val base=mediaStore.mediaBaseDir.canonicalFile
        val attached=chatRepository.loadActiveMessages(activeSessionId).flatMap {message->
            val parts=org.json.JSONArray(message.partsJson)
            (0 until parts.length()).mapNotNull {index->
                val part=parts.getJSONObject(index)
                val ref=part.takeIf {it.optString("type")=="mediaRef"}?.optJSONObject("value")?:return@mapNotNull null
                if(!ref.optString("mimeType").startsWith("image/"))return@mapNotNull null
                val id=ref.optString("id");val path=ref.optString("relativePath")
                if(id.isBlank() || path.isBlank())return@mapNotNull null
                val file=java.io.File(base,path).canonicalFile
                if(!file.path.startsWith(base.path+java.io.File.separator) || !file.isFile)return@mapNotNull null
                id to file
            }
        }.toMap()
        val repository=novexApplication().creativeArtifactRepository
        val generated=repository.list(com.openminis.app.data.creative.CreativeArtifactQuery(conversationId=activeSessionId,
            kinds=setOf(novex.core.CreativeArtifactKind.IMAGE,novex.core.CreativeArtifactKind.MAP)))
            .filter {it.artifact.origin.conversationId==activeSessionId && it.artifact.origin.branchId in activeBranchPathIds}
            .associate {it.artifact.id to repository.file(it.artifact.id)}
        return attached+generated
    }
    private val allAgentTools: List<AgentToolDefinition>
        get() = if (currentModel?.supportsTools == false) {
            emptyList()
        } else AgentTools.makeAgentTools(
            // Keep artifact inspection available when either the main model or
            // the configured vision group can interpret the image bytes.
            supportsImageInput = currentModel?.hasImageInput == true,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = _memoryEnabled.value,
            imageGenerationConfigured = providerRepository.resolvedImageGenerationEntries().isNotEmpty(),
            interactiveFictionActive = currentNovexConfiguration().activeInteractiveFiction != null || integratedCards.binding(activeSessionId)!=null,
            documentsAvailable = activeNovexDocumentRefs.isNotEmpty(),
            sourceCollectionsAvailable = activeNovexSourceCollectionRefs.isNotEmpty(),
            workspaceAvailable = true,
        )

    /** Changing answer persona cannot bypass or remove the actual model/configuration tool gates. */
    internal val agentTools: List<AgentToolDefinition>
        get() {
            if (!currentNovexConfiguration().executionMode.exposesTools) return emptyList()
            if (currentModel?.supportsTools == false) return emptyList()
            val legacy=com.openminis.app.tools.NovexCardFileTools.names + NovexManagementTools.definitions().map {it.name} + com.openminis.app.tools.NovexWorldbookTools.names + setOf(com.openminis.app.tools.NovexConversationActionTools.START_GAME,com.openminis.app.tools.NovexConversationActionTools.SELECT_IDENTITY,"end_interactive_fiction")
            val common=allAgentTools.filter {it.name !in legacy && it.name !in integratedCards.names()}
            return common + ConversationRecall.definitions() + if(integratedCards.binding(activeSessionId)!=null)integratedCards.definitions(activeSessionId) else emptyList()
        }

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    internal val toolLoopDetector = ToolLoopDetector()
    private val conversationBranchMutex = kotlinx.coroutines.sync.Mutex()
    internal var activeBranchMessageIds: Set<String> = emptySet()
    internal var activeBranchPathIds: List<String> = emptyList()

    /**
     * 正在生成的这条 AI 消息 id（2026-09-15）。回合内的状态工具写入都挂在这个
     * 分支上，但它要到回合结束持久化时才进 [activeBranchPathIds]——状态面板若
     * 只按持久化路径解析，回合内永远显示旧值（用户报告"AI 改不动状态"的病因
     * 之一）。工具首次写入时登记；新用户回合开启时轮换（旧 id 已持久化进路径，
     * 重复出现在解析列表里无害）。
     */
    internal var activeStreamingTurnId: String? = null
    internal var novexContextRevision = 0L
    internal var excludedBranchMemoryWrites: Map<String, Int> = emptyMap()

    // [P3.3 裁军] BrowserTabPool（browser_use 工具伴奏池）与 showBrowserSheet
    // 状态流随内置浏览器全家（browser/ + ui/browser/）退役删除；
    // toggleBrowserSheet / dismissBrowserSheet / openBrowserSheetForUrl
    // 同批移除（原在 ChatViewModelUiStateExt.kt）。

    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    internal val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    /**
     * Revoke a previously recorded memory_write by removing its entry from
     * today's or yesterday's daily log on disk, and dropping the row from
     * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
     *
     * Returns the repository result so the UI can show a success / not-found
     * / I/O error dialog. The original ChatMessage tool block stays in the
     * conversation history untouched — only the on-disk entry and the
     * op-log row are mutated.
     */
    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = activeMemoryRepository()
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value - record
        }
        return result
    }

    /** Remove writes owned only by physically deleted branch rows. */
    internal fun revokeMemoryWritesInDeletedRows(
        deletedMessages: List<novex.android.data.chat.MessageRow>,
        remainingMessages: List<novex.android.data.chat.MessageRow>,
    ) {
        val repository = activeMemoryRepository() ?: return
        val deletedContents = com.openminis.app.data.ConversationBranchMemory
            .writesOwnedOnlyByDeletedMessages(deletedMessages, remainingMessages)
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedRows: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            val result = repository.revokeEntry(content)
            val record = _memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            }
            if (record != null) _memoryToolRecords.value = _memoryToolRecords.value - record
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

    /**
     * Replace the body of a previously recorded memory_write with
     * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
     * On success, also updates the in-memory [MemoryToolRecord] so a
     * subsequent revoke or revisit sees the new body.
     */
    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = activeMemoryRepository()
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.IOError("Memory not available")
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value.map {
                if (it === record) it.copy(
                    writtenContent = newContent,
                    preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                ) else it
            }
        }
        return result
    }

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * transport provider (P3.1c 起为 NovexTransportProvider 的 anthropic 线) is
     * stamped with `enhancedCache = true` just before the request (see the
     * streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == novex.android.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    fun setFastModeEnabled(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun setAutoCompactEnabled(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through) OR it's the Codex OAuth route (OpenAI type +
     * oauth credential + no custom base URL). Chat-completions providers stay
     * excluded. Recomputes on entry/config changes like the Enhanced Cache
     * gate above.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == novex.android.data.model.ProviderType.openAI &&
                instance.credentialType == novex.android.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            entry != null && instance != null &&
                entry.model.id.contains("gpt", ignoreCase = true) &&
                (instance.useResponsesAPI || isCodexOAuth)
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans /var/minis/{workspace,attachments,shared,
     * skills,memory}/<sessionId>/ on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    /** Live model ceiling, then the explicit conversation limit or inherited group limit. */
    internal fun effectiveContextWindowTokens(): Int? {
        val config = providerRepository.config.value
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
        return novex.core.NovexConversationContextLimit.effective(
            detectedModelContextWindow, currentNovexConfiguration().contextLimitTokens, groupLimit)
    }

    val contextCapacity: StateFlow<Pair<Int?, Int?>> by lazy {
        combine(providerRepository.config, _activeEntryId, _selectedGroupId, _novexConfigurationJson) { _, _, _, _ ->
            detectedModelContextWindow to effectiveContextWindowTokens()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            detectedModelContextWindow to effectiveContextWindowTokens())
    }

    val detectedModelContextWindow: Int?
        get() = (_activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }?.model
        } ?: currentModel)?.contextWindowTokens?.takeIf { it > 0 }

    internal fun estimatePreparedRequest(history: List<LLMMessage>, prompt: String?, tools: List<AgentToolDefinition>): Int =
        NovexRequestEstimate.total(history, prompt, tools)

    val canEditModelCapacity: Boolean
        get() = true

    fun saveModelContextWindow(tokens: Int?) {
        check(canEditModelCapacity) { "测试模型配置不可修改" }
        check(!_isStreaming.value) { "本轮结束后可校正模型容量" }
        require(tokens == null || tokens >= 1024) { "请输入至少 1024 个词元，或留空恢复自动识别" }
        val id = requireNotNull(_activeEntryId.value) { "请先选择模型" }
        val entry = requireNotNull(providerRepository.config.value.modelEntries.find { it.id == id })
        providerRepository.updateEntry(entry.copy(overrides = entry.overrides.copy(contextWindow = tokens)))
        _contextEstimated.value = true
        novexContextRevision++
    }

    val modelContextIsEstimated: Boolean
        get() = (_activeEntryId.value?.let { id -> providerRepository.config.value.modelEntries.find {it.id==id}?.model } ?: currentModel)?.contextWindow?.takeIf {it>0}==null

    suspend fun saveConversationContextLimit(tokens: Int) {
        sessionLoaded.first { it }
        require(!_isStreaming.value) { "请在本轮回答结束后调整上下文容量" }
        val maximum = requireNotNull(detectedModelContextWindow) { "尚未读取到模型的上下文容量" }
        val selected = novex.core.NovexConversationContextLimit.selection(tokens, maximum)
        novexSettingsStore.update { it.copy(contextLimitTokens = selected) }
    }

    val currentModelMaxOutputTokens: Int?
        get() = currentModel?.maxOutputTokens

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
    )

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                output += obj.optLong("outputTokens", 0L)
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops)
    }

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to ChatViewModelUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = novex.android.ui.NovexIcons.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = novex.android.ui.NovexIcons.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = novex.android.ui.NovexIcons.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = novex.android.ui.NovexIcons.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
        // [T-cross-sync] 双向沟通：压缩自己的记忆发给另一边（2026-09-16 用户批δ）。
        SlashCommand(
            id = "sync",
            icon = novex.android.ui.NovexIcons.Share,
            title = "Sync",
            subtitle = "",
        ),
        // [T-stage3-save] 存档命令组（总纲 §3.9）：/save 名称｜/saves 列表｜/load 序号
        SlashCommand(
            id = "save",
            icon = novex.android.ui.NovexIcons.Compress,
            title = "Save",
            subtitle = "",
        ),
        SlashCommand(
            id = "saves",
            icon = novex.android.ui.NovexIcons.Compress,
            title = "Saves",
            subtitle = "",
        ),
        SlashCommand(
            id = "load",
            icon = novex.android.ui.NovexIcons.CloseFullscreen,
            title = "Load",
            subtitle = "",
        ),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.

    /**
     * Execute a slash command. Returns the text the composer should hold
     * afterward (caret via [pendingCaret] when relevant).
     *
     * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
     * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
     * original text): a skill row prepends "/<skill> " to the original; an
     * action command (clear/compact/…) runs as a side effect and restores the
     * original (stripping the injected "/ "). Typed-"/" (no saved original):
     * a skill fills "/<skill> ", an action clears the input. The original body
     * is always preserved — never discarded (no regression of e48fe7a0).
     *
     * [currentInput] is retained for call-site compatibility; the body text is
     * sourced from [savedInputBeforeSlash], not the live string.
     */
    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
        // they're a typing aid. Fill the composer with the literal slash
        // command; the user then taps Send and the model handles the skill via
        // the existing SKILL.md fragment injection in runAgentLoop.
        if (cmd.isSkill) {
            AppLogger.info(TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
            // (saved != null) → PREPEND "/<skill> " to the original, so the
            // composer reads "/<skill> <original>" with the original as args,
            // caret right after the prefix (before the original). Typed-"/"
            // (saved == null) → just "/<skill> " (the input WAS the partial
            // command). Trailing space lets the user type "/<skill> <args>".
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "compact" -> compactAll()
            "memory" -> toggleMemoryEnabled()
            "thinking" -> toggleThinking()
            "clear" -> _clearChatConfirmRequested.value = true
            "sync" -> startCrossSync(currentInput.trim().removePrefix("/").removePrefix("／").substringAfter(' ', "").trim())
            "save" -> saveGameSlot(currentInput.trim().removePrefix("/").removePrefix("／").substringAfter(' ', "").trim())
            "saves" -> appendSystemInfo(
                text = "存档",
                iconKind = "card",
                payload = novex.core.NovexSaveStore
                    .listDescription(novex.core.NovexSaveStore.list(context, activeSessionId)),
            )
            "load" -> loadGameSlot(currentInput.trim().removePrefix("/").removePrefix("／").substringAfter(' ', "").trim().toIntOrNull())
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        // [T-android-slash-menu-align-ios-prepend] Action command: restore the
        // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
        // survives — never the live "/ <original>". Typed-"/" path → clear.
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

    /** Toggle memory writes on/off, persist to DB, and append a system-info message. */
    private fun toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            // Toggling before the first message means the row doesn't exist
            // yet — materialize the session row so the preference lands on
            // the persisted id instead of silently updating zero rows under
            // the draft key.
            val sid = ensureSession()
            chatRepository.dao.setMemoryFlag(sid, if (newValue) 1 else 0)
        }
        appendSystemInfo(
            text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
            iconKind = "memory",
        )
    }

    /** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
    private fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = "The current model does not support deep thinking.",
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = "Thinking set to ${newLevel.displayName.lowercase()}.",
            iconKind = "thinking",
        )
    }

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    /**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Uses [ensureSession] so toggling on a draft (no DB row yet) first
     * materialises the row, mirroring how toggleMemoryEnabled lands its
     * preference on the persisted id rather than the `__new__…` draft key.
     */
    private fun persistThinkingOverride(level: ThinkingLevel) {
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.setThinkingChoice(sid, level.name)
        }
    }

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: availableSlashCommands.firstOrNull { it.title.lowercase() == name.substringBefore(' ') }
            ?: return false
        executeSlashCommand(cmd, trimmed.drop(1))
        return true
    }

    /**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream. Future work: wire real conversation compaction through the LLM.
     */
    internal fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        _messages.value = _messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        )
    }

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerRow` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    fun runCompactNow() {
        compactAll()
    }

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    fun compactBefore(dbMessageId: String, includesBoundary: Boolean = false) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }

    /**
     * [T-android-auto-compact-inloop] Compact the session.
     *
     * [allowDuringProcessing] lets the in-loop guard in [runAgentLoop] compact
     * BETWEEN agent iterations, where `_isStreaming` is legitimately true. All
     * user-initiated paths keep the default (false) so the "can't compact while
     * a turn is running" guard is unchanged for them. Re-entrancy is still
     * covered by [_isCompacting]. Mirrors iOS f70ac173.
     *
     * [onFinished] fires on the IO coroutine once the compaction attempt has
     * settled (success or failure), so the loop can await it before issuing the
     * next API call — the function itself is fire-and-forget.
     */
    /**
     * Public entry: guarantees [onFinished] is invoked exactly once even when a
     * precondition rejects the request before any work is launched. The inner
     * implementation has many early returns; wrapping it here is safer than
     * threading a callback through each one, and it means an in-loop caller can
     * never hang waiting for a callback that was skipped.
     */
    private fun compactAll(
        anchorIdxOverride: Int? = null,
        allowDuringProcessing: Boolean = false,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        var started = false
        compactAllImpl(anchorIdxOverride, allowDuringProcessing, onFinished) { started = true }
        if (!started) onFinished?.invoke(false)
    }

    private inline fun compactAllImpl(
        anchorIdxOverride: Int?,
        allowDuringProcessing: Boolean,
        noinline onFinished: ((Boolean) -> Unit)?,
        markStarted: () -> Unit,
    ) {
        AppLogger.info(TAG, "[Compact] compactAll() invoked streaming=${_isStreaming.value} compacting=${_isCompacting.value} historySize=${agentHistory.size} anchorOverride=$anchorIdxOverride inLoop=$allowDuringProcessing")
        if (_isStreaming.value && !allowDuringProcessing) {
            AppLogger.info(TAG, "[Compact] aborted: stream in progress")
            appendSystemInfo(
                text = "Cannot compact while a turn is in progress. Stop the current response first.",
                iconKind = "compact",
            )
            return
        }
        if (_isCompacting.value) {
            AppLogger.info(TAG, "[Compact] aborted: another compact already in flight")
            appendSystemInfo(
                text = "A compact is already in progress. Please wait for it to finish.",
                iconKind = "compact",
            )
            return
        }
        val provider = currentProvider ?: run {
            appendSystemInfo("No provider configured. Cannot compact.", "compact")
            return
        }
        val history = agentHistory.toList()
        if (history.isEmpty()) {
            appendSystemInfo("Nothing to compact — the session is empty.", "compact")
            return
        }
        val compactionScopeKey = historyScopeKey()
        val compactionSessionId = activeSessionId
        val compactionWindow = effectiveContextWindowTokens() ?: 128_000
        val compactionProvider = provider
        val compactionModelId = currentModel?.id
        val prev = _cachedLatestMarker?.takeIf {
            novex.core.NovexHistoryAccessScope.canReplay(it.historyScopeKey, compactionScopeKey)
        }
        val effectiveStartIdx = compactionStartIndex(history, prev)
        val cut = ConversationRetention.cut(history, effectiveStartIdx,
            ConversationRetention.recentBudget(compactionWindow), anchorIdxOverride, ::countHistoryMessage)
        if (cut == null) {
            appendSystemInfo("没有可压缩的较早完整消息，近期内容已保留。", "compact")
            return
        }
        val anchorIdx = cut.endExclusive - 1
        val toCompact = history.subList(cut.start, cut.endExclusive)
        val firstKeptId = requireNotNull(history[cut.endExclusive].dbMessageId)
        // Past every precondition — from here the launch below owns the
        // onFinished callback.
        markStarted()
        _isCompacting.value = true
        compactionJob = viewModelScope.launch(Dispatchers.IO) {
            // [T-android-compact-queued-drain] Only a SUCCESSFUL compact kicks
            // the queued-prompt drain below; failure/cancel/empty-summary paths
            // keep today's behavior (queued bubbles stay pending + cancellable).
            var compactSucceeded = false
            try {
                val existing = _compactSummary.value.takeIf { prev != null }
                val projectedHistory = scopedHistory(history, compactionScopeKey).messages
                // Mirrors iOS `generateCompactSummaryWithSplitting` — when the
                // joined transcript exceeds the model's context window, halve
                // the message list and summarize each half independently, then
                // merge. The depth and call budgets below prevent pathological recursion.
                val summary = try {
                    withTimeout(COMPACT_TIMEOUT_MS) {
                        generateCompactSummaryWithSplitting(
                            messages = projectedHistory.subList(effectiveStartIdx, anchorIdx + 1),
                            previousSummary = existing,
                            depth = 0,
                            window = compactionWindow,
                            provider = compactionProvider,
                            budget = CompactionCallBudget(COMPACT_MAX_LLM_CALLS),
                        ).trim()
                    }
                } catch (e: TimeoutCancellationException) {
                    throw IllegalStateException("压缩请求超时，原对话仍保留；请稍后重试", e)
                }
                if (summary.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        appendSystemInfo("Compaction produced no output — try again later.", "compact")
                    }
                    return@launch
                }

                val sid = compactionSessionId
                require(activeSessionId == sid && currentModel?.id == compactionModelId && effectiveContextWindowTokens() == compactionWindow && historyScopeKey() == compactionScopeKey && agentHistory.toList() == history) {
                    "对话或消息分支已变化，本次摘要未采用。"
                }
                val rawDbRows = chatRepository.loadActiveMessages(sid)
                val rawDbIds = rawDbRows.mapTo(hashSetOf()) { it.id }
                require(toCompact.all { it.dbMessageId in rawDbIds } && firstKeptId in rawDbIds) {
                    "消息尚未完整保存，本次摘要未采用。"
                }
                val lastCompactedDbId = requireNotNull(history[anchorIdx].dbMessageId)
                val distillationSourceRefs = toCompact.mapNotNull { message ->
                    message.dbMessageId?.takeIf(String::isNotBlank)?.let { messageId ->
                        novex.core.NovexResourceRef(
                            "novex://conversations/$sid/messages/$messageId",
                        )
                    }
                }.distinct()
                val distillationMemoryRefs = runCatching {
                    novexMemoryService().inspect(
                        scope = currentNovexMemoryScope(),
                        source = currentNovexMemoryReadContext(),
                        limit = 500,
                    ).entries.map { it.ref.asResourceRef() }
                }.getOrDefault(emptyList())
                val distillationWorkspaceRefs = runCatching {
                    val readScope = novex.core.NovexConversationWorkspaceScope(
                        conversationId = sid,
                        visibleBranchIds = activeBranchPathIds,
                        writeBranchId = lastCompactedDbId,
                    )
                    novexConversationWorkspaceStore.inspect(readScope).entries
                        .filter { entry ->
                            entry.workspaceRef.area == novex.core.NovexWorkspaceArea.OUTPUTS ||
                                entry.workspaceRef.area == novex.core.NovexWorkspaceArea.SAVES
                        }
                        .map { it.workspaceRef.asResourceRef() }
                }.getOrDefault(emptyList())
                val distillationId = java.util.UUID.randomUUID().toString()
                val distillationScope = novex.core.NovexConversationWorkspaceScope(
                    conversationId = sid,
                    visibleBranchIds = activeBranchPathIds,
                    writeBranchId = lastCompactedDbId,
                )
                val distillationEntry = novex.core.NovexDistillationRecordWriter(
                    novexConversationWorkspaceStore,
                ).save(
                    scope = distillationScope,
                    record = novex.core.NovexDistillationRecord(
                        id = distillationId,
                        conversationId = sid,
                        branchId = lastCompactedDbId,
                        summary = summary,
                        sourceMessageRefs = distillationSourceRefs,
                        durableFactRefs = (distillationMemoryRefs + distillationWorkspaceRefs).distinct(),
                        createdAtMillis = System.currentTimeMillis(),
                        historyScopeKey = compactionScopeKey,
                    ),
                    provenance = novex.core.NovexWorkspaceProvenance(
                        conversationId = sid,
                        branchId = lastCompactedDbId,
                        messageId = lastCompactedDbId,
                    ),
                )
                val marker = CompactMarkerRow(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = sid,
                    summary = summary,
                    firstKeptSortOrder = Int.MAX_VALUE,   // legacy field; v2 ignores
                    compactedCount = toCompact.size,
                    createdAt = System.currentTimeMillis(),
                    uiBoundarySortOrder = null,
                    boundaryMessageId = null,
                    firstKeptMessageId = firstKeptId,
                    lastCompactedMessageId = lastCompactedDbId,
                    version = 3,
                    historyScopeKey = compactionScopeKey,
                )
                require(activeSessionId == sid && currentModel?.id == compactionModelId && effectiveContextWindowTokens() == compactionWindow && historyScopeKey() == compactionScopeKey && agentHistory.toList() == history) {
                    "对话已变化，本次摘要未采用。"
                }
                chatRepository.dao.addMarker(marker)
                AppLogger.info(
                    TAG,
                    "[Compact] persisted Novex distillation ${distillationEntry.workspaceRef.value}",
                )
                withContext(Dispatchers.Main) {
                    require(activeSessionId == sid && historyScopeKey() == compactionScopeKey && agentHistory.toList() == history) {
                        "对话已变化；摘要仅保留在原分支，本页未切换上下文。"
                    }
                    _compactSummary.value = summary
                    _cachedLatestMarker = marker
                    // [净眼 P2-2] 压缩后水位归零重数（总纲 §3.5：压缩后水位
                    // 归零→新周期刻度重新数；条目保留）
                    runCatching {
                        val memoryStore = memoryStoreFor(activeSessionId)
                        val memory = memoryStore.load()
                        memoryStore.save(memory.copy(highWaterTickPercent = 0))
                    }
                    val originals = _messages.value.filterNot {
                        it.role == "system" && it.toolBlocks.firstOrNull()?.toolName == "compact"
                    }.map { it.copy(isCompactedHistory = false) }
                    _messages.value = applyCompactMarkerGraying(originals, marker, rawDbRows, rawDbIds)
                }
                // [T-stage3-snapshot] 压缩成功→后台增量更新世界快照 + 压缩自动档
                // （失败静默：快照是压缩产物增强，不阻塞压缩本身）
                viewModelScope.launch {
                    try {
                        val provider = currentProvider ?: return@launch
                        val snapshotSid = sid
                        val previous = withContext(Dispatchers.IO) {
                            novex.core.NovexStateSnapshot.loadLatest(context, snapshotSid)
                        }
                        val recent = history.takeLast(6).joinToString("\n") { it.content.take(1500) }
                        val cardName = integratedCardBinding()?.primary?.let { sel ->
                            runCatching { integratedCards.store.open(sel.rootId) }.getOrNull()?.content?.name
                        }
                        val snapshot = withContext(Dispatchers.IO) {
                            novex.core.NovexStateSnapshot.generate(provider, previous, summary, recent, cardName)
                        } ?: return@launch
                        withContext(Dispatchers.IO) {
                            novex.core.NovexStateSnapshot.saveLatest(context, snapshotSid, snapshot)
                        }
                        // [净眼 P2-4] 会话守卫：生成期间用户切换会话时不污染新会话状态锚
                        if (activeSessionId == snapshotSid) _worldSnapshot.value = snapshot
                        // [T-stage3-save] 压缩自动档（总纲 §3.9）
                        novex.core.NovexSaveStore.save(context, sid,
                            novex.core.NovexSaveStore.SaveEntry(
                                id = "auto-${snapshot.createdAt}",
                                name = "压缩自动档",
                                createdAt = snapshot.createdAt,
                                anchorMessageId = null,
                                snapshotJson = snapshot.rawJson,
                                memoryJson = novex.core.NovexNotebookStore
                                    .encode(_sessionMemory.value).takeIf { _sessionMemory.value.entries.isNotEmpty() },
                                ledgerJson = null,
                            ))
                    } catch (_: Exception) {
                        // 静默（快照增强不阻塞压缩）
                    }
                }
                compactSucceeded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Compact failed", e)
                withContext(Dispatchers.Main) {
                    appendSystemInfo(
                        text = "Compaction failed: ${e.message ?: e.javaClass.simpleName}",
                        iconKind = "compact",
                    )
                }
            } finally {
                _isCompacting.value = false
                // [T-android-auto-compact-inloop] Signal the awaiting in-loop
                // caller. In `finally` so a thrown/cancelled compaction can
                // never strand the agent loop waiting on a callback.
                onFinished?.invoke(compactSucceeded)
            }
            // [T-android-compact-queued-drain] A successful compact must let
            // any queued prompts proceed — previously nothing re-triggered the
            // drain after compact (loop-end / cancel / tool-boundary are the
            // only drain triggers), so a prompt sitting in the queue when a
            // compact ran stayed in the dashed "queued" state forever. Reuse
            // resumeQueueAfterCancel: it re-checks queue-non-empty + not-
            // streaming + not-compacting after its grace delay (so an ✕ tap at
            // the compact-finish instant is a clean no-op), refreshes OAuth,
            // and drains through the normal stream-slot machinery — no new
            // reentrancy path. Runs after `finally` so isCompacting is already
            // false. Mirrors the iOS fix for the same report.
            if (compactSucceeded && _promptQueue.value.isNotEmpty()) {
                AppLogger.info(TAG, "[Compact] success with ${_promptQueue.value.size} queued prompt(s) — kicking drain")
                resumeQueueAfterCancel()
            }
        }
    }

    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    fun revertCompact() {
        if (_isStreaming.value) {
            appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
            return
        }
        if (_isCompacting.value) {
            appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
            return
        }
        val current = _cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val removed = runCatching { chatRepository.dao.dropMarker(current.id) }.getOrNull() ?: 0
            if (removed <= 0) {
                Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Revert failed: marker not found in DB.", "compact")
                }
                return@launch
            }

            // Refresh cache to next-most-recent marker (or null).
            val activeRows = chatRepository.loadActiveMessages(sid)
            val next = chatRepository.latestActiveCompactMarker(sid, activeRows)
            _cachedLatestMarker = next
            _compactSummary.value = next?.summary

            // Rebuild UI from DB so the previous marker's divider re-emerges
            // (or all dividers vanish if there are no remaining markers).
            // Drop any stale compact-divider system rows first; the reload
            // path will re-insert one only if the new latest marker calls
            // for it.
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" &&
                        msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
            }

            // Reload session messages — the existing path runs Phase 2.5
            // graying via applyCompactMarkerGraying() with the new cached
            // marker, so divider position falls back to the previous one
            // (or disappears entirely). loadSession() launches its own
            // viewModelScope job, so call from the Main thread.
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

    /**
     * Re-load the current session's UI message list from disk so any
     * cached-marker change (revert) gets re-applied through Phase-2.5-
     * style restore. Defers to the existing [loadSession] entry; that
     * function reads `_cachedLatestMarker` we just refreshed and routes
     * through [applyCompactMarkerGraying] to (re)position the divider.
     */
    private fun reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

    /**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */
    /**
     * Apply the request-level image-byte budget to a fully-resolved
     * message list before handing it to a provider. Images that don't
     * fit under [ImageBudget.MAX_REQUEST_BYTES] (oldest first) are
     * replaced in-place with a text placeholder that, when the original
     * bytes were offloaded to disk, points the model back to the linux
     * path so it can re-fetch via `read_image` if needed. Images that
     * never had a linuxPath are spilled to
     * `attachments/spillover/<sha1>.<ext>` lazily so the placeholder
     * still carries an addressable reference.
     *
     * Returns the budgeted message list. When nothing was elided this
     * is the same instance as [messages].
     *
     * Emits a one-shot [requestBudgetEvent] for the UI Snackbar so the
     * user knows older images were compacted into placeholders.
     */
    internal fun applyRequestImageBudget(messages: List<LLMMessage>): List<LLMMessage> {
        // Collect every image in chronological order so the planner can
        // walk in reverse and protect the most recent images.
        data class ImageRef(val msgIdx: Int, val partIdx: Int, val image: ImageBudget.BudgetImage)
        val images = mutableListOf<ImageRef>()
        // [T-user-image-never-offload] 最新一条用户消息里的图无条件保留——
        // 那是本轮正在讨论的内容，裁掉等于让模型对着文件名猜图。
        val lastUserIdx = messages.indexOfLast { it.role == LLMMessage.Role.USER }
        messages.forEachIndexed { mi, msg ->
            msg.contentParts.forEachIndexed { pi, part ->
                when (part) {
                    is AgentContentPart.ImageData -> {
                        if (mi == lastUserIdx) return@forEachIndexed
                        images.add(
                            ImageRef(
                                mi, pi,
                                ImageBudget.BudgetImage(part.data, part.linuxPath, part.mimeType),
                            ),
                        )
                    }
                    is AgentContentPart.ToolResult -> {
                        val img = part.imageData
                        if (img != null) {
                            images.add(
                                ImageRef(
                                    mi, pi,
                                    ImageBudget.BudgetImage(
                                        img,
                                        part.imageLinuxPath,
                                        part.imageMimeType ?: "image/jpeg",
                                    ),
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        if (images.isEmpty()) return messages

        val plan = ImageBudget.planRequestBudget(images.map { it.image })
        if (!plan.mutated) return messages

        // For dropped images without a linuxPath, lazily spill to disk so
        // the placeholder still gives the model an addressable reference.
        val attachmentsRoot = activeSessionId?.let { sid ->
            java.io.File(context.filesDir, "minis-sessions/$sid/attachments")
        }
        val resolvedPaths = HashMap<ImageBudget.ImagePartId, String?>()
        for (ref in images) {
            val id = ImageBudget.ImagePartId.of(ref.image.data)
            if (id !in plan.droppedIds) continue
            val existing = ref.image.linuxPath
            if (existing != null) {
                resolvedPaths[id] = existing
            } else if (attachmentsRoot != null) {
                resolvedPaths[id] = ImageBudget.ensureSpillover(
                    attachmentsRoot, ref.image.data, ref.image.mimeType,
                )
            } else {
                resolvedPaths[id] = null
            }
        }

        // Build a new message list with dropped image parts replaced by
        // text placeholders. Same-message multiple drops collapse cleanly
        // because we never touch parts whose ids weren't in droppedIds.
        val byMsg = images.groupBy { it.msgIdx }
        val mutated = messages.toMutableList()
        for ((mi, refs) in byMsg) {
            val msg = mutated[mi]
            val newParts = msg.contentParts.toMutableList()
            for (ref in refs) {
                val id = ImageBudget.ImagePartId.of(ref.image.data)
                if (id !in plan.droppedIds) continue
                val path = resolvedPaths[id]
                val placeholder = AgentContentPart.Text(ImageBudget.elidedImagePlaceholder(path))
                val originalPart = newParts[ref.partIdx]
                newParts[ref.partIdx] = when (originalPart) {
                    is AgentContentPart.ImageData -> placeholder
                    is AgentContentPart.ToolResult -> originalPart.copy(
                        // Strip the bytes but keep the structural ToolResult
                        // role; append the elision marker into content so
                        // the model sees it next to the rest of the tool
                        // output. linux path remains in the part for any
                        // subsequent diagnostic round-trip.
                        imageData = null,
                        imageMimeType = null,
                        content = originalPart.content +
                            (if (originalPart.content.isEmpty()) "" else "\n") +
                            ImageBudget.elidedImagePlaceholder(path),
                    )
                    else -> originalPart
                }
            }
            mutated[mi] = msg.copy(contentParts = newParts)
        }

        _requestBudgetEvent.tryEmit(plan)
        AppLogger.info(
            TAG,
            "applyRequestImageBudget: dropped=${plan.droppedCount}/${plan.totalCount} keptBytes=${plan.keptBytes}B elidedBytes=${plan.elidedBytes}B",
        )
        return mutated
    }

    /**
     * [T-android-compact-orphan-toolcall] The outgoing history, with tool
     * call/result pairing repaired. Every request goes through here — see
     * [dropOrphanedToolParts] for why the sweep exists and what it can and
     * cannot fix.
     */
    internal fun historyScopeKey(): String = novex.core.NovexHistoryAccessScope.key(currentNovexConfiguration())

    internal suspend fun scopedHistory(history: List<LLMMessage>, scopeKey: String = historyScopeKey()) =
        novex.android.adapter.NovexScopedConversationHistory.project(history,
            chatRepository.loadActiveMessages(activeSessionId), chatRepository.novexContextUsage(activeSessionId), scopeKey)

    /** 装配线前六段的统一输入收集（scope 投影/快照解析是挂起 IO，在装配线外完成）。 */
    internal suspend fun assemblyInputs(): RequestAssembler.Inputs {
        val scopeKey = historyScopeKey()
        val projection = scopedHistory(agentHistory.toList(), scopeKey)
        val allowSummary = novex.core.NovexHistoryAccessScope.canReplay(_cachedLatestMarker?.historyScopeKey, scopeKey)
        return RequestAssembler.Inputs(
            scopedHistory = projection.messages,
            sideSnapshotMainline = resolveSideSnapshotMainline(),
            compactRebuild = { effectiveAgentHistoryUncounted(it, allowSummary) },
            orphanRepair = ::dropOrphanedToolParts,
            snapshotOrphanRepair = { dropOrphanedToolParts(it, exemptTrailing = false) },
            retentionProject = ::budgetedRequestHistory,
        )
    }

    private suspend fun effectiveAgentHistory(): List<LLMMessage> =
        // [T-request-assembler] PR 1：内存侧组装走装配线。A2 行为等价——与旧内联
        // 路径同函数同参同序，顺序定义只剩 assemble() 一处（发送出口另接尾三段）。
        RequestAssembler.assemble(assemblyInputs()).assembled

    /**
     * [T-side-snapshot] 解析侧边分裂点快照的原始主线消息（只读拉取，含已被切换
     * 分支弃用的死分支消息——认 ID 不认现状）。界面仍只显示侧边消息；孤儿修复
     * 与前拼由 [RequestAssembler.assemble] 的快照步骤统一负责（PR0 P2-2 上收）。
     * 返回 null = 非侧边 / 无快照 / 快照缺失（旧侧边、直连对话）。
     */
    private suspend fun resolveSideSnapshotMainline(): List<LLMMessage>? {
        if (agentHistory.isEmpty()) return null
        val sideOf = runCatching { chatRepository.sessionById(activeSessionId)?.sideOfSession }.getOrNull()
            ?: return null
        val snapshot = SideSnapshotStore.read(context, activeSessionId) ?: return null
        if (snapshot.parentSessionId != sideOf || snapshot.messageIds.isEmpty()) return null
        val mainline = snapshot.messageIds.mapNotNull { id ->
            runCatching { chatRepository.findMessageById(id) }.getOrNull()
                ?.takeIf { it.sessionId == sideOf }
                ?.let { entity ->
                    runCatching { entity.toLLMMessage(this@ChatViewModel) }.getOrNull()
                }
        }.filter { it.content.isNotBlank() || it.contentParts.isNotEmpty() || it.imageParts.isNotEmpty() }
        if (mainline.isEmpty()) return null
        AppLogger.info(TAG, "[SideSnapshot] resolved ${mainline.size} mainline messages (split-point freeze) for side $activeSessionId")
        return mainline
    }

    internal fun effectiveAgentHistoryUncounted(history: List<LLMMessage>, allowSummary: Boolean): List<LLMMessage> {
        val summary = _compactSummary.value.takeIf { allowSummary }
        val marker = _cachedLatestMarker
        // No compact in play → return full history untouched.
        if (summary.isNullOrBlank() || marker == null) return history.toList()

        val summaryWrappedText = "<context-summary>\n" +
            "The following is a summary of the earlier conversation that was compacted to save context space.\n" +
            "Treat it as background context only. The user's most recent message (below or in the next turn) takes precedence — if it changes the task, the goal, or any numbers/scope, follow the new instruction and do not resume the old plan from this summary. Do not re-run discovery (reading memory, scanning skills, re-reading files) unless the new instruction requires it.\n\n" +
            summary +
            "\n</context-summary>"

        if (marker.version >= 3) {
            return marker.firstKeptMessageId?.let { ConversationRetention.rebuild(history, it, summary) }
                ?: history.toList()
        }

        // ─── v2 markers (id-only anchor model) ─────────────────────────
        //
        // anchor = lastCompactedMessageId. What we send to the model:
        //   1. last [COMPACT_KEEP_RECENT_USER_TURNS] user-text turns BEFORE
        //      anchor (inclusive of anchor) — recent verbatim warm-up
        //   2. the summary, INLINED as a `<context-summary>` text part
        //      prepended to the first user message AFTER anchor (preserves
        //      strict role alternation — no synthetic standalone user turn)
        //   3. all messages strictly after anchor (the kept-tail "active"
        //      region — typically empty right after compact, populated as
        //      the user sends new prompts)
        //
        // If anchor unresolvable, degrade to full history (over-inform
        // beats summary-only; the M-Team session bug taught us that a lone
        // summary message paired with hot tools makes the model loop).
        if (marker.version >= 2) {
            val anchorId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val anchorIdx = anchorId?.let { id ->
                history.indexOfLast { it.dbMessageId == id }
            } ?: -1
            if (anchorIdx < 0) {
                Log.w(TAG, "[Compact] effectiveAgentHistory v2: anchorId=${anchorId?.take(8) ?: "nil"} not in history(size=${history.size}) — degrading to full history (no summary)")
                return history.toList()
            }

            // Step 1: walk back from anchor collecting user-text turns. Stop
            // when EITHER we've collected N user-text turns OR including the
            // next turn would push preAnchor over 100 messages. Decisions
            // happen only at user-message boundaries so we never split a
            // user/assistant/tool round in half (which would orphan a
            // tool_use with no matching tool_result).
            //
            // [T-compact-preanchor-prune, port iOS 8b76cd74]
            val keepN = COMPACT_KEEP_RECENT_USER_TURNS
            val preAnchorCap = 100
            val walkBack = walkBackUserTurnsBounded(
                anchorIdx = anchorIdx,
                maxUserTextTurns = keepN,
                maxMessages = preAnchorCap,
            )
            val priorIdxResolved: Int? = walkBack.priorIdx
            val priorIdx = walkBack.priorIdx ?: (anchorIdx + 1) // empty preAnchor sentinel
            if (walkBack.stopReason != "userTextTargetMet") {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 walkBack stopped: reason=${walkBack.stopReason} priorIdx=$priorIdx userTextTurnsFound=${walkBack.userTextTurnsFound} preAnchorMsgs=${walkBack.messageCount}")
            }

            // PRE-ANCHOR PRUNE (tool-heavy session fix):
            // The walk-back-N-user-text strategy pulls in everything between
            // the Nth-last and last user-text turn — in a heavy tool-call
            // session that can be many messages of tool_result / tool_use,
            // tens of thousands of tokens that the summary already covers.
            // Drop any tool_result > 1000 chars in the preAnchor slice and
            // strip the matching tool_use part (same id) from the assistant
            // message so the model never sees a dangling tool_use/result.
            val preAnchorRaw: List<LLMMessage> =
                if (priorIdx <= anchorIdx) history.subList(priorIdx, anchorIdx + 1).toList()
                else emptyList()

            val droppedToolIds = mutableSetOf<String>()
            var droppedToolResultCount = 0
            for (msg in preAnchorRaw) {
                for (part in msg.contentParts) {
                    if (part is AgentContentPart.ToolResult && part.content.length > 1000) {
                        droppedToolIds.add(part.id)
                        droppedToolResultCount += 1
                    }
                }
            }

            val preAnchorPruned: MutableList<LLMMessage> = ArrayList(preAnchorRaw.size)
            for (msg in preAnchorRaw) {
                if (msg.contentParts.isEmpty()) {
                    // Plain text-only message — nothing to prune.
                    preAnchorPruned.add(msg)
                    continue
                }
                val kept = msg.contentParts.filter { part ->
                    when (part) {
                        is AgentContentPart.ToolUse -> !droppedToolIds.contains(part.id)
                        is AgentContentPart.ToolResult -> !droppedToolIds.contains(part.id)
                        else -> true
                    }
                }
                if (kept.isEmpty()) continue // skip empty shells
                preAnchorPruned.add(msg.copy(contentParts = kept))
            }

            if (droppedToolResultCount > 0) {
                AppLogger.info(TAG, "[CompactDiag] eAH v2 preAnchor prune: dropped $droppedToolResultCount toolResult(>1kc) + paired toolUse, ${preAnchorRaw.size - preAnchorPruned.size} messages emptied; pruned slice=${preAnchorPruned.size}")
            }

            // ROLE ALIGNMENT: the API requires the first message to be `user`.
            // After clamp (cap may land on assistant) and after prune (the
            // head user may have been emptied), peel any leading non-user
            // messages so preAnchor starts on a user turn.
            while (preAnchorPruned.isNotEmpty() && preAnchorPruned.first().role != LLMMessage.Role.USER) {
                preAnchorPruned.removeAt(0)
            }

            // Step 2 & 3: copy the lookback window (post-prune), then splice
            // in the summary as parts[0] of the first post-anchor user msg.
            val result = mutableListOf<LLMMessage>()
            result.addAll(preAnchorPruned)

            val postAnchor = if (anchorIdx + 1 < history.size) {
                history.subList(anchorIdx + 1, history.size)
            } else {
                emptyList()
            }

            // DIAG: explain how the slice was sized using post-prune /
            // post-alignment counts so the log reflects what actually
            // reaches the model.
            val preAnchorRawCount = maxOf(0, anchorIdx - priorIdx + 1)
            val priorIdxSource =
                if (priorIdxResolved == null) "fallback=empty(<$keepN user-text turns before anchor or cap hit)"
                else "userTextWalkBack(N=$keepN)"
            AppLogger.info(TAG, "[CompactDiag] eAH v2 slice: priorIdx=$priorIdx anchorIdx=$anchorIdx history.size=${history.size} → preAnchorRaw=$preAnchorRawCount preAnchorSent=${preAnchorPruned.size} postAnchor=${postAnchor.size} summaryChars=${summary.length} priorIdxSource=$priorIdxSource markerId=${marker.id.take(8)}")

            val firstUserOffset = postAnchor.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (firstUserOffset >= 0) {
                if (firstUserOffset > 0) {
                    result.addAll(postAnchor.subList(0, firstUserOffset))
                }
                val target = postAnchor[firstUserOffset]
                // Prepend `<context-summary>...` to the user content. We
                // edit `content` directly because Android LLMMessage uses
                // `content: String` as the canonical text payload; any
                // contentParts the message also carries get preserved.
                val injected = target.copy(
                    content = summaryWrappedText + "\n\n" + target.content,
                )
                result.add(injected)
                if (firstUserOffset + 1 < postAnchor.size) {
                    result.addAll(postAnchor.subList(firstUserOffset + 1, postAnchor.size))
                }
            } else {
                // Rare: no user message after anchor. Append everything
                // post-anchor (typically empty) then a standalone summary
                // user turn. Safe — no later user follows it to break
                // alternation.
                result.addAll(postAnchor)
                result.add(LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText))
            }
            return result
        }

        // ─── v1 (legacy) markers ──────────────────────────────────────
        //
        // Original behavior preserved unchanged so old markers keep
        // rendering / sending data the same way they always did.
        val summaryHead = LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
        val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
            ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })

        if (firstKeptId != null) {
            val keepStart = history.indexOfFirst { it.dbMessageId == firstKeptId }
            if (keepStart >= 0) {
                return buildList(history.size - keepStart + 1) {
                    add(summaryHead)
                    addAll(history.subList(keepStart, history.size))
                }
            }
            // Fall through to safety net.
        } else {
            val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }
            val lcmIdx = lcmId?.let { id ->
                history.indexOfLast { it.dbMessageId == id }
            } ?: -1
            val postCompactStart = lcmIdx + 1
            return buildList(history.size - postCompactStart + 1) {
                add(summaryHead)
                if (postCompactStart < history.size) {
                    addAll(history.subList(postCompactStart, history.size))
                }
            }
        }

        Log.w(TAG, "[Compact] effectiveAgentHistory: marker ${marker.id.take(8)} unresolvable in history (size=${history.size}); returning full history")
        return history.toList()
    }

    /**
     * [T-android-compact-orphan-toolcall] Last line of defence before a history
     * slice becomes a provider request: every ToolResult (function_call_output)
     * must have a matching ToolUse (function_call) in the same slice, and vice
     * versa.
     *
     * An unmatched pair is a hard 400 on OpenAI-compatible APIs —
     *     No tool call found for function call output with call_id …
     * — and because the slice is recomputed deterministically, it repeats on
     * every retry AND every fallback model, wedging the conversation until the
     * user clears the session. Port of iOS `dropOrphanedToolParts`
     * (AIChatViewModel+Persistence.swift, c7f6a299e).
     *
     * Any orphan reaching here is an upstream bug (the walk-back boundary is
     * supposed to preserve pairing), so this logs loudly rather than silently
     * papering over it:
     *   - orphaned result → drop it; its call is gone from the slice and
     *     nothing can reconstruct it.
     *   - orphaned call → synthesise an error result rather than deleting the
     *     call, because deleting would silently discard the assistant's own
     *     reasoning. The placeholder keeps the turn intact and tells the model
     *     that round failed.
     * Messages emptied by the drop are removed — a parts-less message is itself
     * invalid on several providers.
     */
    internal fun dropOrphanedToolParts(history: List<LLMMessage>, exemptTrailing: Boolean = true): List<LLMMessage> {
        val toolUseIds = HashSet<String>()
        val toolResultIds = HashSet<String>()
        for (msg in history) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.ToolUse -> toolUseIds.add(part.id)
                    is AgentContentPart.ToolResult -> toolResultIds.add(part.id)
                    else -> {}
                }
            }
        }
        val orphanedResults = toolResultIds - toolUseIds
        val orphanedUses = HashSet(toolUseIds - toolResultIds)

        // [T-android-compact-orphan-toolcall] IN-FLIGHT EXEMPTION (iOS
        // 5d346dc2e). The tool_uses in the FINAL assistant message are not
        // orphans while the loop sits between "model asked for tools" and
        // "results appended" — agentHistory legitimately looks unpaired for
        // that whole window (the assistant turn is appended at ~7500 and its
        // tool results only at ~7517). Any snapshot taken inside that gap would
        // otherwise carry fabricated "interrupted" results for tools that were
        // about to run normally, telling the model its tools had failed.
        // Trailing unanswered calls need no repair anyway: a request ending on
        // an assistant tool_use is exactly what the API expects mid-round.
        // [T-request-assembler] 净眼 P1-2：exemptTrailing=false 供冻结态快照段
        // 使用——快照不存在"在飞"轮次，段尾未答 use 必须合成错误结果。
        val last = history.lastOrNull()
        if (exemptTrailing && last != null && last.role == LLMMessage.Role.ASSISTANT) {
            for (part in last.contentParts) {
                if (part is AgentContentPart.ToolUse) orphanedUses.remove(part.id)
            }
        }

        if (orphanedResults.isEmpty() && orphanedUses.isEmpty()) return history

        AppLogger.warning(
            TAG,
            "[CompactDiag] orphan tool parts in OUTGOING history — repairing. " +
                "orphanedOutputs=${orphanedResults.size} [${orphanedResults.sorted().take(3).joinToString(",")}] " +
                "orphanedCalls=${orphanedUses.size} [${orphanedUses.sorted().take(3).joinToString(",")}] " +
                "historyCount=${history.size}",
        )

        val cleaned = ArrayList<LLMMessage>(history.size)
        for (msg in history) {
            val kept = msg.contentParts.filter { part ->
                if (part is AgentContentPart.ToolResult) !orphanedResults.contains(part.id) else true
            }
            // Only drop the message when it HAD parts and lost them all. A
            // plain text message legitimately carries no contentParts and must
            // survive untouched.
            if (kept.isEmpty() && msg.contentParts.isNotEmpty()) continue
            cleaned.add(if (kept.size == msg.contentParts.size) msg else msg.copy(contentParts = kept))

            // Follow an assistant turn holding orphaned calls with the
            // placeholder results it never got, so the pair is complete.
            if (msg.role != LLMMessage.Role.ASSISTANT) continue
            val unanswered = kept.filterIsInstance<AgentContentPart.ToolUse>()
                .filter { orphanedUses.contains(it.id) }
            if (unanswered.isNotEmpty()) {
                cleaned.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = unanswered.map {
                            AgentContentPart.ToolResult(
                                id = it.id,
                                name = it.name,
                                content = "Tool execution was interrupted by an unexpected error.",
                                isError = true,
                            )
                        },
                    ),
                )
            }
        }
        return cleaned
    }

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    internal var _cachedLatestMarker: novex.android.data.chat.CompactMarkerRow? = null

    /**
     * Result of a bounded walk-back. `priorIdx` is the agentHistory index
     * the caller should use as the start of preAnchor; `null` means even
     * the first user turn including anchor would exceed `maxMessages`, so
     * preAnchor should be empty.
     *
     * Mirrors iOS `WalkBackResult` in AIChatViewModel.swift (8b76cd74).
     */
    private data class WalkBackResult(
        val priorIdx: Int?,
        val userTextTurnsFound: Int,
        val messageCount: Int,
        /** "userTextTargetMet" | "messageCapWouldExceed" | "reachedStart" | "invalidAnchor" */
        val stopReason: String,
    )

    /**
     * Walk back from `anchorIdx` toward 0, deciding ONLY at user-message
     * boundaries whether to include the next round. Stops when:
     * - we've collected `maxUserTextTurns` user-text turns (success), OR
     * - including the next user round would push total messages over
     *   `maxMessages` (cap reason — don't split a user/assistant/tool round
     *   in the middle, otherwise a tool_use would be orphaned without its
     *   tool_result), OR
     * - we hit index 0 (start of history).
     *
     * Port of iOS `walkBackUserTurnsBounded` (AIChatViewModel.swift, 8b76cd74).
     */
    private fun walkBackUserTurnsBounded(
        anchorIdx: Int,
        maxUserTextTurns: Int,
        maxMessages: Int,
    ): WalkBackResult {
        if (anchorIdx < 0 || anchorIdx >= agentHistory.size) {
            return WalkBackResult(null, 0, 0, "invalidAnchor")
        }
        var acceptedPriorIdx: Int? = null
        var acceptedUserTextTurns = 0
        var acceptedMessageCount = 0

        var i = anchorIdx
        while (i >= 0) {
            val msg = agentHistory[i]
            if (msg.role != LLMMessage.Role.USER) {
                i -= 1
                continue
            }
            // [T-android-compact-orphan-toolcall] A user message CARRYING a
            // tool result is the second half of a round, not the start of one.
            // This walk-back's whole premise is that `role == USER` marks a
            // round boundary — but tool results are themselves persisted as
            // USER messages (see the agentHistory.add at the end of the tool
            // dispatch loop), so stopping on one cuts between an assistant's
            // tool_use and its own tool_result. The call is then discarded with
            // pre-history while the result survives in preAnchor and goes out
            // alone, which every OpenAI-compatible provider answers with
            //     400 No tool call found for function call output with call_id …
            // and, since the slice is recomputed identically on every retry and
            // fallback, the session wedges permanently. Port of iOS c7f6a299e.
            if (msg.contentParts.any { it is AgentContentPart.ToolResult }) {
                i -= 1
                continue
            }
            val candidateMessageCount = anchorIdx - i + 1
            if (candidateMessageCount > maxMessages) {
                return WalkBackResult(
                    priorIdx = acceptedPriorIdx,
                    userTextTurnsFound = acceptedUserTextTurns,
                    messageCount = acceptedMessageCount,
                    stopReason = "messageCapWouldExceed",
                )
            }
            // Accept this user as the new tentative priorIdx.
            acceptedPriorIdx = i
            acceptedMessageCount = candidateMessageCount
            val hasText = msg.content.isNotBlank() ||
                msg.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }
            if (hasText) {
                acceptedUserTextTurns += 1
                if (acceptedUserTextTurns >= maxUserTextTurns) {
                    return WalkBackResult(
                        priorIdx = acceptedPriorIdx,
                        userTextTurnsFound = acceptedUserTextTurns,
                        messageCount = acceptedMessageCount,
                        stopReason = "userTextTargetMet",
                    )
                }
            }
            i -= 1
        }
        return WalkBackResult(
            priorIdx = acceptedPriorIdx,
            userTextTurnsFound = acceptedUserTextTurns,
            messageCount = acceptedMessageCount,
            stopReason = "reachedStart",
        )
    }

    private fun buildConversationTextForSummary(history: List<LLMMessage>): String =
        ConversationCompactionPolicy.transcript(history)

    /**
     * Summarize [messages], recursively halving and merging when the input
     * exceeds the model's context window. Mirrors iOS
     * `generateCompactSummaryWithSplitting` (AIChatViewModel+Compaction.swift:820).
     *
     * Depth cap = 2 and a shared call budget so a pathologically large conversation
     * still terminates instead of fanning out indefinitely. At each split we
     * choose a safe turn boundary, summarize each half independently, then ask
     * the LLM to merge the two partial summaries into one — prioritizing Part 2
     * (more recent) when space is tight.
     */
    private suspend fun generateCompactSummaryWithSplitting(
        messages: List<LLMMessage>,
        previousSummary: String? = null,
        depth: Int = 0,
        window: Int = effectiveContextWindowTokens() ?: 128_000,
        provider: com.openminis.app.provider.LLMProvider = requireNotNull(currentProvider),
        budget: CompactionCallBudget = CompactionCallBudget(),
    ): String {
        val transcript = buildConversationTextForSummary(messages)
        val conversationText = if (previousSummary.isNullOrBlank()) {
            transcript
        } else {
            "Previous context summary:\n$previousSummary\n\n" +
                "New conversation to merge:\n$transcript"
        }
        return try {
            generateCompactSummary(conversationText, window, provider, budget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isSegmentRetryableError(e)) throw e
            val split = ConversationCompactionPolicy.splitBetweenTurns(messages)
            if (split == null || depth >= COMPACT_MAX_SPLIT_DEPTH) {
                if (BPETokenizer.countTokens(conversationText) < window / 2) throw e
                var running = previousSummary.orEmpty()
                for (page in ConversationCompactionPolicy.pages(transcript, window / 3, BPETokenizer::countTokens)) {
                    running = generateCompactSummary(
                        "Earlier summary:\n$running\n\nNext fragment of the historical transcript (may continue a message; do not invent missing context):\n$page",
                        window, provider, budget)
                }
                return running
            }
            val (firstHalf, secondHalf) = split
            AppLogger.info(
                TAG,
                "[Compact] Splitting ${messages.size} messages into ${firstHalf.size} + ${secondHalf.size} (depth=$depth)",
            )
            val summary1 = generateCompactSummaryWithSplitting(firstHalf, null, depth + 1, window, provider, budget)
            val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1, window, provider, budget)
            val mergeInput = buildString {
                append("Merge these partial summaries into one continuity checkpoint. ")
                append("Preserve the latest user corrections, current relationships, time, scene and world state, ")
                append("unresolved requests, tool effects, and exact technical details when relevant. ")
                append("Completed events stay completed; unresolved matters stay unresolved; cancelled or ")
                append("superseded matters must not be revived. Do not continue the conversation.\n\n")
                append("PRIORITIZE Part 2 (more recent) over Part 1 (older) when space is tight.\n\n")
                if (!previousSummary.isNullOrBlank()) {
                    append("Existing earlier continuity summary (preserve relevant current state):\n")
                    append(previousSummary).append("\n\n")
                }
                append("Part 1:\n").append(summary1).append("\n\n")
                append("Part 2:\n").append(summary2)
            }
            generateCompactSummary(mergeInput, window, provider, budget)
        }
    }

    /**
     * Single-shot LLM call that turns [conversationText] into a structured
     * summary. Throws on provider error so the splitter above can detect
     * context-too-large failures and retry with halved input.
     */
    private suspend fun generateCompactSummary(
        conversationText: String,
        contextWindow: Int,
        provider: com.openminis.app.provider.LLMProvider,
        budget: CompactionCallBudget = CompactionCallBudget(),
    ): String {
        // Wrap the transcript in explicit BEGIN/END framing so the model
        // treats it as material to summarize rather than as a chat turn to
        // continue. Mirrors iOS AIChatViewModel+Compaction.swift
        // `compactUserMessage` construction. Without this wrapper, fast models
        // (e.g. deepseek-v4-flash) tend to "answer" whatever the last user
        // turn in the transcript said — producing a single-line continuation
        // instead of a structured summary.
        val userMessage = buildString {
            append("Compact this conversation into a context summary:\n\n")
            append(conversationText)
            append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
            append("Now generate the continuity checkpoint required by the system prompt. ")
            append("Do not answer or continue the conversation. Keep current state in present tense, ")
            append("completed events in past tense, and unresolved matters explicitly unresolved.")
        }
        val estimatedInput = BPETokenizer.countTokens(userMessage) + BPETokenizer.countTokens(compactSummarySystemPrompt) + 256
        require(estimatedInput + 1024 < contextWindow) { "压缩输入超过当前对话容量" }
        val maxOut = minOf(8192, contextWindow - estimatedInput)
        budget.take()
        val response = provider.sendMessage(
            messages = listOf(
                LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
            ),
            systemPrompt = compactSummarySystemPrompt,
            maxTokens = maxOut,
            // Mirror iOS AIChatViewModel.swift:12926 — null lets the
            // provider/model use its default. gpt-5.x family rejects any
            // temperature != 1 with HTTP 400, and Android
            // 上游 openai 包（已删）的 buildRequestBody omits the field entirely when
            // temperature is null.
            temperature = null,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        )
        require(response.stopReason?.lowercase() !in setOf("length", "max_tokens", "max_output_tokens")) {
            "摘要输出被容量限制截断，未替换原上下文"
        }
        return response.text
    }

    /**
     * Should a failed summary attempt be retried by splitting the input in half?
     *
     * Ported from iOS `isSegmentRetryableError`
     * (AIChatViewModel+Compaction.swift:1010, T-compact-segment-retry-any-error).
     *
     * Everything EXCEPT the two cases where a smaller request cannot help:
     *   - cancellation — the user (or a session switch) stopped the work, so a
     *     retry would fight that and immediately throw again;
     *   - network/offline — the request never reached a model, so payload size
     *     is irrelevant and splitting just doubles the failed round-trips.
     *
     * This deliberately REPLACES [isContextTooLargeError] on the split path.
     * That substring allow-list tried to enumerate how every provider words an
     * over-length refusal and was provably incomplete — OpenMinis#133's
     * `[context_length_exceeded] Your input exceeds the context window of this
     * model` slipped past several variants — and every miss silently disabled
     * splitting, so compaction failed outright instead of retrying smaller.
     *
     * Splitting on an unclassified error is safe: the worst case is two smaller
     * calls reaching the same failure, bounded by depth < 3 (≤8 leaf calls). A
     * summary built from halves is never worse than no summary at all, so the
     * burden of proof is inverted — retry unless retrying is provably pointless.
     */
    private fun isSegmentRetryableError(error: Throwable): Boolean {
        if (error is CancellationException) return false
        if (error is LLMError) {
            return when (error) {
                is LLMError.Cancelled, is LLMError.NetworkError, is LLMError.InvalidApiKey, is LLMError.RateLimited -> false
                else -> true
            }
        }
        // Raw OkHttp/socket failures are the Android equivalent of iOS's
        // NSURLErrorDomain bail-out: offline / DNS / TLS / timeout, all
        // payload-size independent.
        if (error is java.io.IOException) return false
        return true
    }

    /**
     * Match provider error text against the substring set iOS
     * `isContextTooLargeError` used before T-compact-segment-retry-any-error.
     *
     * NO LONGER gates segment retry — [isSegmentRetryableError] does, for the
     * reasons documented there. Retained only for user-facing wording, where
     * guessing wrong costs a less specific message rather than a failed
     * compaction.
     */
    @Suppress("unused")
    private fun isContextTooLargeError(error: Throwable): Boolean {
        val desc = (error.message ?: error.toString()).lowercase()
        return desc.contains("too many tokens") ||
            desc.contains("context length") ||
            desc.contains("max_tokens") ||
            desc.contains("content is too long") ||
            desc.contains("exceeds the model") ||
            desc.contains("request too large") ||
            desc.contains("prompt is too long") ||
            desc.contains("token limit") ||
            desc.contains("context window")
    }

    /**
     * Consult [ContextPolicy] before sending. Returns true to proceed. The
     * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
     * so we only warn via [appendSystemInfo] at the `needsCompact` /
     * `exhausted` boundaries and still allow the send. That gives the user
     * a signal to invoke `/compact` explicitly without blocking their turn.
     */
    private fun compactionStartIndex(history: List<LLMMessage>, marker: CompactMarkerRow?): Int {
        if (marker == null) return 0
        if (marker.version >= 3) return history.indexOfFirst { it.dbMessageId == marker.firstKeptMessageId }.coerceAtLeast(0)
        val anchor = marker.lastCompactedMessageId?.let { id -> history.indexOfLast { it.dbMessageId == id } }
        return if (anchor != null && anchor >= 0) anchor + 1 else 0
    }

    private fun shouldCompactRetainedHistory(tokens: Int, window: Int): Boolean {
        val history = agentHistory.toList()
        val marker = _cachedLatestMarker?.takeIf {
            novex.core.NovexHistoryAccessScope.canReplay(it.historyScopeKey, historyScopeKey())
        }
        return ConversationRetention.shouldCompact(history, compactionStartIndex(history, marker), window, tokens, ::countHistoryMessage)
    }

    internal fun budgetedRequestHistory(history: List<LLMMessage>): List<LLMMessage> {
        if (integratedCards.binding(activeSessionId) == null) return history
        val window = effectiveContextWindowTokens() ?: return history
        return ConversationToolRetention.project(history, window,
            estimatePreparedRequest(history, novexPromptAuditInput?.prompt, agentTools),
            agentTools.any { it.name == "read_conversation_history" }, ::countPartTokens)
    }

    private fun retainedContextEstimate(): Int {
        val allowed = novex.core.NovexHistoryAccessScope.canReplay(_cachedLatestMarker?.historyScopeKey, historyScopeKey())
        // The send-entry check precedes prompt assembly; do not reread files on
        // the UI path just to estimate. The prepared/continuation guards use the
        // freshly assembled prompt and the same schema/message estimator.
        val knownPrompt = novexPromptAuditInput?.prompt
        return estimatePreparedRequest(budgetedRequestHistory(effectiveAgentHistoryUncounted(agentHistory.toList(), allowed)), knownPrompt, agentTools) +
            if (knownPrompt == null) 4096 else 0
    }

    internal fun checkContextBeforeSend(pendingText: String): PreSendContextAction {
        val tokens = retainedContextEstimate() + BPETokenizer.countTokens(pendingText)
        if (tokens <= 0) return PreSendContextAction.PROCEED
        // [T-context-window-live-read] Live window (entry re-resolved + group
        // contextLimitTokens folded in) — not the currentModel snapshot.
        val window = effectiveContextWindowTokens() ?: return PreSendContextAction.PROCEED
        val check = if (shouldCompactRetainedHistory(tokens, window)) ContextPolicy.CheckResult.NEEDS_COMPACT else ContextPolicy.CheckResult.OK
        return when (check) {
            ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

            // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
            // appended a notice here and sent anyway, which meant the very
            // request that tripped the threshold still went out over-length —
            // the warning arrived alongside the failure it was meant to avoid.
            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (com.openminis.app.data.AutoCompactPrefs.isEnabled()) {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                    )
                    PreSendContextAction.COMPACT_THEN_SEND
                } else {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                    )
                    PreSendContextAction.ASK_USER
                }
            }

            // Exhausted tiers have compactThreshold = 0 by policy: the window is
            // too small for a summary to pay for itself, so compacting is not
            // on offer. Keep the existing advisory-and-proceed behaviour rather
            // than blocking the user out of their own chat.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                appendSystemInfo(
                    text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                    iconKind = "compact",
                )
                PreSendContextAction.PROCEED
            }
        }
    }

    /** What the pre-send context check decided. Mirrors iOS's send() branch. */
    internal enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    internal var pendingSendText: String? = null

    internal val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * Dialog action: compact the history, then send what the user was holding.
     * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
     * now AND remembers the choice for every future conversation.
     */
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        val pendingAttachments = _attachments.value.toList()
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok || _attachments.value != pendingAttachments) {
                restorePendingCompactText(text)
                return@launch
            }
            val newDraft = _inputText.value
            sendMessage(text, skipContextCheck = true)
            if (newDraft.isNotEmpty()) _inputText.value = newDraft
        }
    }

    private fun restorePendingCompactText(text: String) {
        val draft = _inputText.value
        _inputText.value = if (draft.isBlank() || draft == text) text else text + "\n\n" + draft
    }

    /** Dialog action: send without compacting. */
    fun sendPendingWithoutCompacting() {
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        sendMessage(text, skipContextCheck = true)
    }

    /** Dialog dismissed — restore the text to the composer so it isn't lost. */
    fun cancelCompactBeforeSend() {
        _showCompactBeforeSendPrompt.value = false
        pendingSendText?.let(::restorePendingCompactText)
        pendingSendText = null
    }

    /**
     * [T-android-auto-compact-inloop] What the in-loop context guard decided.
     */
    internal enum class InLoopContextAction {
        /** Under threshold — issue the next API call as normal. */
        PROCEED,

        /** History was compacted in place; re-run the iteration. */
        COMPACTED,

        /** Cannot recover — stop the turn safely and let the user resume. */
        STOP,
    }

    /**
     * [T-android-auto-compact-inloop] Max in-loop compactions per runAgentLoop.
     * Bounds compact-thrash within a single turn; the MAX_AGENT_TURNS ceiling is
     * never reset by compaction, so this is a second, tighter backstop.
     */
    private val maxInLoopCompactions = 3

    /**
     * [T-android-auto-compact-inloop] Re-evaluate [ContextPolicy] between agent
     * iterations and act on it (iOS f70ac173).
     *
     * Why this exists: [checkContextBeforeSend] only runs at the SEND entry
     * point. A single turn that fans out into many tool iterations can cross the
     * compact/exhausted thresholds mid-loop, and offload alone cannot recover
     * when the bulk is the model's own text — the turn then slams into the
     * provider's context ceiling.
     *
     * Blocks until the compaction attempt settles, because the next API call
     * must read the freshly-compacted history.
     */
    internal suspend fun inLoopContextCheck(compactionsSoFar: Int, tokensOverride:Int?=null): InLoopContextAction {
        val tokens = tokensOverride ?: retainedContextEstimate()
        if (tokens <= 0) return InLoopContextAction.PROCEED
        val window = effectiveContextWindowTokens() ?: return InLoopContextAction.PROCEED
        val check = if (shouldCompactRetainedHistory(tokens, window)) ContextPolicy.CheckResult.NEEDS_COMPACT else ContextPolicy.CheckResult.OK
        return when (check) {
            ContextPolicy.CheckResult.OK -> InLoopContextAction.PROCEED

            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (compactionsSoFar >= maxInLoopCompactions) {
                    AppLogger.warning(
                        TAG,
                        "[AutoCompact] still over threshold after $compactionsSoFar compaction(s) — stopping",
                    )
                    return InLoopContextAction.STOP
                }
                // NOTE: deliberately NOT gated on AutoCompactPrefs. That flag
                // governs the SEND-time decision (compact silently vs. ask
                // first) — mid-loop there is nobody to ask, and the alternative
                // to compacting is aborting the user's turn outright. iOS makes
                // the same call: its in-loop branch
                // (AIChatViewModel.swift:4739) never consults
                // autoCompactEnabled either.
                AppLogger.info(
                    TAG,
                    "[AutoCompact] mid-loop compact #${compactionsSoFar + 1}: $tokens / $window tokens " +
                        "(autoCompactPref=${com.openminis.app.data.AutoCompactPrefs.isEnabled()}, not a gate here)",
                )
                appendSystemInfo(
                    text = "Context is filling up ($tokens / $window tokens) — compacting to continue.",
                    iconKind = "compact",
                )
                val ok = awaitCompaction()
                if (!ok) return InLoopContextAction.STOP
                // [T-android-auto-compact-inloop] Invalidate the stale reading.
                // `_lastTurnContextTokens` is only refreshed by a usage chunk,
                // which needs a COMPLETED API call — but this path compacts and
                // `continue`s without one. Leaving the pre-compaction value in
                // place made the very next iteration read the same number and
                // compact again immediately, burning the whole budget in
                // seconds (observed on device: two compactions 3s apart, both
                // logging an identical 66358). Zeroing it makes the guard
                // PROCEED once, so the next real response measures the
                // post-compaction size and the decision is made on fresh data.
                _contextEstimated.value = true
                _lastTurnContextTokens.value = retainedContextEstimate()
                _contextUsageReady.value = true
                InLoopContextAction.COMPACTED
            }

            // EXHAUSTED is only ever returned by `exhaustedOnly` tiers — windows
            // under 64K, where ContextPolicy sets compactThreshold = 0 precisely
            // BECAUSE the window is too small for auto-compact to pay for itself
            // (the summary plus re-appended recent turns would eat the headroom
            // it just freed). Attempting a "rescue" compaction here would
            // contradict the policy, so stop and let the user decide.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                AppLogger.warning(
                    TAG,
                    "[AutoCompact] exhausted on a no-auto-compact tier ($tokens / $window) — stopping",
                )
                InLoopContextAction.STOP
            }
        }
    }

    /**
     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
     * suspend until it settles. Returns whether it actually compacted.
     *
     * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
     * loop cannot simply call it and continue — the next API call would read the
     * pre-compaction history and the guard would fire again immediately.
     */
    private suspend fun awaitCompaction(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { compactionJob?.cancel() }
            var resumed = false
            compactAll(allowDuringProcessing = true) { ok ->
                // compactAll guarantees exactly one callback, but guard anyway:
                // resuming a continuation twice throws.
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ok) { _, _, _ -> }
                }
            }
        }

    /** Shared compaction policy plus Novex long-form creative continuity rules. */
    private val compactSummarySystemPrompt: String = buildString {
        append(ConversationCompactionPolicy.systemPrompt)
        append("\n\n")
        append(NovexCreativeDistillationPolicy.systemPrompt)
    }

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    internal val isDraft: Boolean = sessionId.startsWith("__new__")

    private fun draftMarker(name: String): String? =
        sessionId.substringAfter("__${name}__", "").substringBefore("__").takeIf { it.isNotEmpty() }

    /** Model group ID from long-press FAB, encoded in the draft session ID.
     *  substringBefore strips the folder marker in case both are present. */
    internal val initialGroupId: String? =
        draftMarker("grp")

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    private val initialFolderId: String? =
        draftMarker("fld")

    internal val initialCharacterId: String? = draftMarker("char")
    internal val initialCharacterVersionId: String? = draftMarker("version")
    internal val initialPersonaId: String? = draftMarker("persona")
    internal val initialWorldId: String? = draftMarker("world")
    internal val initialInteractiveFictionId: String? = draftMarker("game")
    internal val initialCardEntry by lazy { draftMarker("card")?.let {com.openminis.app.cards.IntegratedCardEntry.read(context,it)} }

    internal val gameEntry = NovexGameEntryController(
        requested = initialInteractiveFictionId != null,
        prepare = {
            val app = novexApplication()
            val current = currentNovexConfiguration()
            novex.android.adapter.NovexGameSnapshotAssembler(app.novexWorkspace, app.novexSnapshotMediaStore)
                .create(requireNotNull(initialInteractiveFictionId), current.backgroundSettings, current.adoptedContexts)
        },
        currentPlayer = { currentNovexConfiguration().playerIdentity },
        activate = { game ->
            novexSettingsStore.update(
                settings = conversationSettingsSnapshot().copy(conversationPrompt = inheritedEditablePrompt(
                    game.answerIdentity ?: novex.core.NovexPersonaPresets.gameHost)),
                captureSources = true,
            ) { current ->
                NovexConversationConfiguration.open(current).apply(
                    novex.core.NovexConversationCommand.ActivateInteractiveFiction(game, replacePlayerIdentity = true),
                ).snapshot
            }
            prepareNovexConversationForEntry()
        },
    )
    val gameEntryState = gameEntry.state
    fun retryGameEntry() { viewModelScope.launch { gameEntry.start() } }
    fun selectGameEntryPlayer(id: String) { viewModelScope.launch { gameEntry.select(id) } }

    internal val _immersiveProfile = MutableStateFlow(com.openminis.app.data.character.ImmersiveChatProfile())
    val immersiveProfile: StateFlow<com.openminis.app.data.character.ImmersiveChatProfile> by lazy {
        combine(_immersiveProfile, _novexConfigurationJson) { profile, configuration ->
            novex.core.NovexSnapshotMediaProjection.profile(
                NovexConversationConfigurationCodec.decode(configuration, activeSessionId), profile)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, _immersiveProfile.value)
    }
    internal val _conversationPrompt = MutableStateFlow<String?>(null)
    val conversationPrompt: StateFlow<String?> = _conversationPrompt.asStateFlow()
    internal val _imageStylePrompt = MutableStateFlow("")
    val imageStylePrompt: StateFlow<String> = _imageStylePrompt.asStateFlow()
    /** Standing instruction appended to every request's latest user turn; blank = off. */
    internal val _perTurnPrompt = MutableStateFlow("")
    val perTurnPrompt: StateFlow<String> = _perTurnPrompt.asStateFlow()
    internal val _textStylePrompt = MutableStateFlow("")
    val textStylePrompt: StateFlow<String> = _textStylePrompt.asStateFlow()
    internal val _diceInjectionEnabled = MutableStateFlow(false)
    val diceInjectionEnabled: StateFlow<Boolean> = _diceInjectionEnabled.asStateFlow()
    internal val _ledgerInjectionEnabled = MutableStateFlow(false)
    val ledgerInjectionEnabled: StateFlow<Boolean> = _ledgerInjectionEnabled.asStateFlow()
    /** Client-side randomness for wenyou dice injection; never seeded from model output. */
    internal val runtimeSecureRandom = java.security.SecureRandom()
    /** Explicit conversation override. null means inherit character/world background. */
    internal val _conversationBackgroundPathOverride = MutableStateFlow<String?>(null)

    fun sourceConversationBackgroundPath(): String? =
        _immersiveProfile.value.character?.defaultBackgroundPath
            ?: _immersiveProfile.value.world?.backgroundPath

    internal val _novexConfigurationJson = MutableStateFlow(
        novex.core.NovexConversationConfigurationCodec.encode(
            novex.core.NovexConversationConfiguration.empty(sessionId).snapshot.copy(cardBindingJson=if(isDraft)com.openminis.app.cards.CardBinding().encode() else null),
        ),
    )
    val novexConfigurationJson: StateFlow<String> = _novexConfigurationJson.asStateFlow()

    val conversationStatus: StateFlow<NovexConversationStatus> by lazy {
        combine(_novexConfigurationJson, sessionLoaded) { raw, loaded ->
            if (loaded) NovexConversationStatus.read(NovexConversationConfigurationCodec.decode(raw, activeSessionId))
            else NovexConversationStatus(answer = "正在读取对话", editable = false)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            NovexConversationStatus(answer = "正在读取对话", editable = false))
    }

    suspend fun saveConversationExecutionMode(mode: novex.core.NovexExecutionMode) {
        sessionLoaded.first { it }
        require(!_isStreaming.value) { "请在本轮回答结束后调整工具权限" }
        novexSettingsStore.update { current ->
            NovexConversationConfiguration.open(current).apply(
                novex.core.NovexConversationCommand.SetExecutionMode(mode)).snapshot
        }
    }

    @Volatile private var decodedNovexConfigurationCache: Pair<String, NovexConversationConfigurationSnapshot>? = null

    internal fun currentNovexConfiguration(): NovexConversationConfigurationSnapshot {
        val raw = _novexConfigurationJson.value
        val id = activeSessionId
        decodedNovexConfigurationCache?.let { cached ->
            if (cached.first === raw && cached.second.conversationId == id) return cached.second
        }
        return NovexConversationConfigurationCodec.decode(raw, id).also {
            decodedNovexConfigurationCache = raw to it
        }
    }

    internal data class NovexPromptAuditInput(val prompt: String, val style: String, val persistentContext: String, val extraContext: String)
    internal var novexPromptAuditInput: NovexPromptAuditInput? = null
    private val novexTeachingTraceStore by lazy {
        novex.core.FileNovexTeachingTraceStore(java.io.File(context.filesDir, "novex/teaching-traces"))
    }

    internal data class PreparedNovexRequestContext(
        val composition: NovexContextComposition,
        val record: ContextUsageRecord,
        val systemPrompt: String,
    )

    /**
     * Builds one immutable context selection for an entire user request. Tool iterations reuse it;
     * they do not re-query cards, so a branch switch or tool result cannot repeat external effects.
     */
    internal suspend fun prepareNovexRequestContext(
        baseSystemPrompt: String?,
        requestMessageId: String,
        query: String,
    ): PreparedNovexRequestContext? {
        _contextUsageReady.value = false
        val application = context.applicationContext as? com.openminis.app.MinisApp ?: return null
        if (!application.subsystemsReady()) return null
        val configuration = adoptedNovexConfiguration()
        val profile = _immersiveProfile.value
        val usingNewCards=integratedCards.binding(activeSessionId)!=null
        val currentOccupied=if(usingNewCards) estimatePreparedRequest(effectiveAgentHistory(),baseSystemPrompt,agentTools)
            else maxOf(_lastTurnContextTokens.value,estimateContextTokens())
        val candidates = (if(usingNewCards)emptyList() else WorkspaceNovexContextLoader(application.novexWorkspace,
            novex.android.adapter.NovexLegacyContext(profile.characterVersionId, profile.character, profile.world))
            .load(configuration)).toMutableList()
        if(usingNewCards && configuration.executionMode.exposesTools) {
            val images=integratedConversationImages()
            if(images.isNotEmpty())candidates+=NovexContextCandidate("new-card-conversation-images","对话图片目录",
                "这些是当前对话可保存到卡片的图片编号，目录不代表已看图："+JSONArray(images.keys.toList()),ContextSourceKind.TOOL_DEFINITION,alwaysInclude=true)
        }
        candidates += novex.core.NovexReadOnlyAttachmentContext(novexDocumentRepository).candidates(
            configuration.executionMode,
            novexDocumentRefsInHistory(agentHistory.filter { it.role == LLMMessage.Role.USER })
                .filter { it in activeNovexDocumentRefs }.map(::NovexResourceRef),
        )
        if(usingNewCards || configuration.activeInteractiveFiction!=null) {
            val state = InteractiveFictionRuntime.resolveState(configuration, activeBranchPathIds)
            if (state.values.isNotEmpty()) {
                candidates += NovexContextCandidate(
                    sourceId = "playthrough:${state.branchId}",
                    label = "对话状态",
                    content = state.values.entries.joinToString("\n") { (key, value) ->
                        "$key：${when (value) {
                            is novex.core.PlaythroughValue.Text -> value.value
                            is novex.core.PlaythroughValue.Number -> value.value
                            is novex.core.PlaythroughValue.Flag -> value.value
                        }}"
                    },
                    kind = ContextSourceKind.PLAYTHROUGH_STATE,
                    alwaysInclude = true,
                    position = Int.MIN_VALUE + 1,
                )
            }
        }
        novex.core.NovexCheckpointContinuation(novexConversationWorkspaceStore).prepare(configuration,
            novex.core.NovexConversationWorkspaceScope(configuration.conversationId, activeBranchPathIds, requestMessageId))
            ?.let { candidates += it }
        if (candidates.isEmpty() && !usingNewCards) return null

        val window = effectiveContextWindowTokens() ?: 128_000
        val occupied = currentOccupied
        val outputReserve = (currentModel?.maxOutputTokens ?: 8_192).coerceIn(1_024, 32_000) + window / 20
        val budget = NovexContextBudgetPolicy.moduleBudget(window, occupied, outputReserve)
        if(usingNewCards) {
            val otherCost=candidates.sumOf {BPETokenizer.countTokens(it.content)}
            candidates+=integratedCards.candidates(activeSessionId,requestMessageId,query,agentHistory,
                (budget-otherCost).coerceAtLeast(0),BPETokenizer::countTokens,
                configuration.executionMode.exposesTools && currentModel?.supportsTools!=false) { selection->
                requireNotNull(currentProvider){"请先选择模型"}.sendMessage(
                    listOf(LLMMessage(LLMMessage.Role.USER,selection)),"你是只读资料选择器，只选择模块编号，不执行任何操作。",1024).text
            }
        }
        val worldbook = if(usingNewCards)null else novex.core.NovexTavernWorldbook.adopted(configuration)
        val worldbookReserve = if(worldbook == null && candidates.none { it.worldbookConditions.isNotEmpty() }) 0 else minOf(2048, budget / 4)
        val baseComposition = NovexContextComposer.compose(
            query = query,
            tokenBudget = budget - worldbookReserve,
            candidates = candidates.filter { it.worldbookConditions.isEmpty() },
            estimateTokens = BPETokenizer::countTokens,
        )
        if(usingNewCards) {
            val selectedCards = candidates.filter {
                it.sourceId.startsWith("new-card") &&
                    it.sourceId != "new-card-reading" &&
                    !it.partial &&
                    it.content.isNotBlank()
            }
            val complete = selectedCards.all { candidate ->
                baseComposition.fragments.any { it.sourceId == candidate.sourceId && !it.partial && it.text == candidate.content.trim() }
            }
            require(complete) {
                "采用的卡片内容超过当前模型可用上下文，无法发送本轮请求；请降低本对话容量、减少模块或换用更大上下文的模型"
            }
        }
        val worldbookResult = run {
            val visibleById = _messages.value.filter { !it.isQueued && it.error == null && it.role in setOf("user", "assistant") }.associateBy { it.id }
            val visible = (activeBranchPathIds + requestMessageId).distinct().mapNotNull { visibleById[it]?.content }
                .let { if(requestMessageId !in visibleById) it + query else it }
            novex.core.NovexWorldbookRuntime.evaluate(candidates, worldbook, visible,
                (budget - baseComposition.usedTokens).coerceAtLeast(0), BPETokenizer::countTokens)
        }
        val worldbookFragments = worldbookResult.fragments
        val closedWorldbooks = (if(usingNewCards)emptyList() else novex.core.NovexWorldbookUse.references(configuration)).filterNot { it.enabled }.map { reference ->
            novex.core.ContextSourceOmission(novex.core.ContextSourceKind.BACKGROUND_MODULE,
                "reference:${reference.id}", reference.targetLabel.ifBlank { "世界书引用" }, "此引用已关闭；其他启用来源分别判断")
        }
        val composition = baseComposition.copy(fragments = baseComposition.fragments + worldbookFragments,
            omissions = baseComposition.omissions + worldbookResult.omissions + closedWorldbooks,
            usedTokens = baseComposition.usedTokens + worldbookFragments.sumOf { it.tokenCount })
        var record = composition.toUsageRecord(
            id = java.util.UUID.randomUUID().toString(),
            requestMessageId = requestMessageId,
            branchId = requestMessageId,
            answerIdentity = configuration.answerIdentity,
            effectiveWindowTokens = window,
            createdAt = System.currentTimeMillis(),
        )
        val formalPrompt = NovexContextPromptFormatter.appendTo(baseSystemPrompt, composition.fragments) +
            composition.omissions.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "\n<本轮未完整提供的资料>\n", postfix = "\n不能把上述资料视为已通读。\n</本轮未完整提供的资料>",
                separator = "\n",
            ) { "${it.label}：${it.reason}" }.orEmpty()
        if(com.openminis.app.BuildConfig.UPDATE_CHANNEL == "preview") {
            try {
                val definitions = agentTools
                val audit = novexPromptAuditInput?.takeIf { it.prompt == baseSystemPrompt }
                val candidate = audit?.let {
                    val source = context.assets.open("novex/teaching/v6-candidate.md").bufferedReader().use { reader -> reader.readText() }
                    novex.core.NovexTeachingCandidate.build(source, configuration.answerIdentity,
                        definitions.mapTo(linkedSetOf()) { definition -> definition.name }, it.style, _imageStylePrompt.value,
                        it.persistentContext + it.extraContext + "\n" + NovexContextPromptFormatter.appendTo("", composition.fragments))
                }
                val payload = JSONObject().put("version", 1).put("conversationId", configuration.conversationId)
                    .put("recordId", record.id).put("requestMessageId", requestMessageId).put("createdAt", record.createdAt)
                    .put("stage", "assembled_before_provider").put("candidateSent", false)
                    .put("formalPrompt", formalPrompt).put("formalRevision", novex.core.NovexFrozenContextCodec.digest(formalPrompt))
                    .put("toolDefinitions", JSONArray(definitions.map { definition -> definition.toOpenAIJson() }))
                    .put("configuration", JSONObject(NovexConversationConfigurationCodec.encode(configuration)))
                    .put("candidate", candidate?.toJson())
                    .put("candidateUnavailable", if(candidate == null) "当前基础提示词不来自已捕获的诺文装配，未猜测其六部分" else JSONObject.NULL)
                val reference = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    novexTeachingTraceStore.save(configuration.conversationId, record.id, payload)
                }
                record = record.copy(teachingTraceRef = reference)
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(failure: Exception) { record = record.copy(teachingTraceError = failure.message ?: "本轮装配记录未保存") }
        }
        _contextEstimated.value = true
        _lastTurnContextTokens.value = estimatePreparedRequest(effectiveAgentHistory(), formalPrompt, agentTools)
        _contextUsageReady.value = true
        return PreparedNovexRequestContext(composition, record, formalPrompt)
    }

    /** Rebuilds branch-sensitive UI state without executing a control or tool. */
    internal fun refreshNovexRuntimeProjection() {
        val configuration = currentNovexConfiguration()
        // 状态解析并入在途回合分支：否则回合内的任何配置写回（含状态工具自身
        // 的落库）触发本刷新时，面板会被打回"持久化路径"的旧值。
        val statePath = activeBranchPathIds + listOfNotNull(activeStreamingTurnId)
        _novexControls.value = InteractiveFictionRuntime.resolveControls(configuration, activeBranchPathIds)
        _activePlaythroughState.value = if(configuration.activeInteractiveFiction!=null || configuration.cardBindingJson!=null) InteractiveFictionRuntime.resolveState(configuration, statePath) else null
        _novexControlView.value = null
    }

    internal fun installNovexConfiguration(configuration: NovexConversationConfigurationSnapshot) {
        _novexConfigurationJson.value = NovexConversationConfigurationCodec.encode(
            configuration.copy(conversationId = activeSessionId),
        )
        refreshNovexRuntimeProjection()
    }

    internal fun recordActiveBranchMessage(messageId: String) {
        if (messageId.isBlank() || messageId in activeBranchMessageIds) return
        // 用户消息入路径 = 新回合开启：上一条在途回合已终结，撤销其面板旁路。
        if (activeStreamingTurnId != null && activeStreamingTurnId != messageId) activeStreamingTurnId = null
        activeBranchPathIds = activeBranchPathIds + messageId
        activeBranchMessageIds = activeBranchMessageIds + messageId
        refreshNovexRuntimeProjection()
    }

    internal val novexSettingsStore by lazy {
        novex.android.adapter.NovexConversationSettingsStore(
            mutex = novexConfigurationMutex,
            sessionId = { ensureSession() },
            current = { conversationSettingsSnapshot() },
            transaction = novex.core.NovexManagementTransaction { work ->
                novexApplication().database.withTransaction { work() }
            },
            adopt = { configuration ->
                val app = novexApplication()
                val profile = _immersiveProfile.value
                novex.android.adapter.NovexConversationContextAdoption(app.novexWorkspace,
                    novex.android.adapter.NovexLegacyContext(profile.characterVersionId, profile.character, profile.world),
                    app.novexSnapshotMediaStore).adopt(configuration)
            },
            write = { sid, saved -> chatRepository.writeConversationSettings(sid, saved) },
            install = { saved -> withContext(Dispatchers.Main) { installSavedNovexSettings(saved) } },
        )
    }

    private fun installSavedNovexSettings(saved: com.openminis.app.data.ConversationSettingsSnapshot) {
        _conversationPrompt.value = saved.conversationPrompt
        _imageStylePrompt.value = saved.imageStylePrompt
        _perTurnPrompt.value = saved.perTurnPrompt
        _textStylePrompt.value = saved.textStylePrompt
        _diceInjectionEnabled.value = saved.diceInjectionEnabled
        _ledgerInjectionEnabled.value = saved.ledgerInjectionEnabled
        _novexConfigurationJson.value = saved.novexConfigurationJson
        refreshNovexRuntimeProjection()
        _immersiveProfile.value = _immersiveProfile.value.copy(
            rolePresentationEnabled = saved.rolePresentationEnabled,
            assistantDisplayName = saved.assistantDisplayName.ifBlank { null },
            assistantAvatarPath = saved.assistantAvatarPath,
            playerDisplayName = saved.playerDisplayName.ifBlank { null },
            playerAvatarPath = saved.playerAvatarPath,
        )
        novexContextRevision++
    }

    /** Save the captured sources before any request or tool can rely on them. */
    internal suspend fun adoptedNovexConfiguration(): NovexConversationConfigurationSnapshot =
        novexSettingsStore.update(captureSources = true)

    internal fun legacyNovexConfiguration(
        conversationId: String,
        worldId: String?,
        characterVersionId: String?,
    ): novex.core.NovexConversationConfigurationSnapshot {
        val effectiveCharacterVersionId = characterVersionId ?: _immersiveProfile.value.character?.id
        val backgrounds = buildList {
            worldId?.takeIf(String::isNotBlank)?.let {
                add(
                    novex.core.BackgroundSetting(
                        novex.core.NovexContentAddress.world(it),
                    ),
                )
            }
            effectiveCharacterVersionId?.takeIf(String::isNotBlank)?.let {
                add(
                    novex.core.BackgroundSetting(
                        novex.core.NovexContentAddress.characterVersion(it),
                    ),
                )
            }
        }
        return novex.core.NovexConversationConfiguration.open(
            novex.core.NovexConversationConfigurationSnapshot(
                conversationId = conversationId,
                answerIdentity = effectiveCharacterVersionId?.takeIf(String::isNotBlank)?.let {
                    novex.core.AnswerIdentity.CharacterVersion(it)
                } ?: novex.core.AnswerIdentity.Nova,
                backgroundSettings = backgrounds,
                playerIdentity = _immersiveProfile.value.persona?.let { player ->
                    novex.core.ConversationPlayerIdentity(
                        id = player.id,
                        label = player.name,
                        description = com.openminis.app.data.character.CharacterPromptComposer.compose(
                            characterSnapshot = null, personaSnapshot = player.toJson().toString(),
                        ).orEmpty(),
                    )
                },
            ),
        ).snapshot
    }

    internal fun inheritedEditablePrompt(identity: AnswerIdentity = currentNovexConfiguration().answerIdentity): String {
        if (identity is AnswerIdentity.PersonaPreset) return identity.instructions
        val profile = _immersiveProfile.value
        val selectedRole = (identity as? AnswerIdentity.CharacterVersion)?.versionId
        if (selectedRole != null && selectedRole != profile.characterVersionId && selectedRole != profile.character?.id) return ""
        return profile.character?.takeIf { selectedRole != null }?.let {
            com.openminis.app.data.character.CharacterPromptComposer.compose(
                characterSnapshot = it.toJson().toString(),
                personaSnapshot = null,
                worldSnapshot = null,
            )
        } ?: com.openminis.app.agent.SoulStore.load(context)?.let { soul ->
            buildString {
                append(soul.body.trim())
                soul.metadata.style.trim().takeIf { it.isNotEmpty() }?.let { style ->
                    if (isNotEmpty()) append("\n\n")
                    append("<回复风格>\n").append(style).append("\n</回复风格>")
                }
            }
        }.orEmpty()
    }

    /** The current source prompt before conversation-level edits are applied. */
    fun sourceConversationPrompt(identity: AnswerIdentity = currentNovexConfiguration().answerIdentity): String = inheritedEditablePrompt(identity)

    fun conversationSettingsSnapshot(): com.openminis.app.data.ConversationSettingsSnapshot {
        val profile = _immersiveProfile.value
        return com.openminis.app.data.ConversationSettingsSnapshot(
            conversationPrompt = _conversationPrompt.value ?: inheritedEditablePrompt(),
            imageStylePrompt = _imageStylePrompt.value,
            perTurnPrompt = _perTurnPrompt.value,
            textStylePrompt = _textStylePrompt.value,
            diceInjectionEnabled = _diceInjectionEnabled.value,
            ledgerInjectionEnabled = _ledgerInjectionEnabled.value,
            backgroundPath = _conversationBackgroundPathOverride.value,
            rolePresentationEnabled = profile.rolePresentationEnabled || profile.character != null,
            assistantDisplayName = profile.assistantDisplayName.orEmpty(),
            assistantAvatarPath = profile.assistantAvatarPath,
            playerDisplayName = profile.playerDisplayName.orEmpty(),
            playerAvatarPath = profile.playerAvatarPath,
            novexConfigurationJson = _novexConfigurationJson.value,
        )
    }

    fun saveConversationSettings(
        settings: com.openminis.app.data.ConversationSettingsSnapshot,
        expectedConfigurationJson: String? = null,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        val normalized = com.openminis.app.data.normalizeConversationSettings(settings)
        val value = normalized.copy(
            novexConfigurationJson = normalized.novexConfigurationJson.ifBlank {
                novex.core.NovexConversationConfigurationCodec.encode(
                    legacyNovexConfiguration(
                        conversationId = activeSessionId,
                        worldId = _immersiveProfile.value.worldId,
                        characterVersionId = _immersiveProfile.value.characterVersionId,
                    ),
                )
            },
        )
        _conversationPrompt.value = value.conversationPrompt
        _imageStylePrompt.value = value.imageStylePrompt
        _perTurnPrompt.value = value.perTurnPrompt
        _textStylePrompt.value = value.textStylePrompt
        _diceInjectionEnabled.value = value.diceInjectionEnabled
        _ledgerInjectionEnabled.value = value.ledgerInjectionEnabled
        _conversationBackgroundPathOverride.value = value.backgroundPath
        _immersiveProfile.value = _immersiveProfile.value.copy(
            backgroundPath = value.backgroundPath ?: sourceConversationBackgroundPath(),
            rolePresentationEnabled = value.rolePresentationEnabled,
            assistantDisplayName = value.assistantDisplayName.ifBlank { null },
            assistantAvatarPath = value.assistantAvatarPath,
            playerDisplayName = value.playerDisplayName.ifBlank { null },
            playerAvatarPath = value.playerAvatarPath,
        )
        viewModelScope.launch {
            val result = runCatching {
                novexSettingsStore.update(settings = value, expectedConfigurationJson = expectedConfigurationJson, captureSources = true)
                Unit
            }
            withContext(Dispatchers.Main) { onComplete(result) }
        }
    }

    private var scopedMemoryKey: String? = null
    private var scopedMemoryRepository: MemoryRepository? = null

    /** Role memory is isolated by world + player identity + role card. */
    internal fun activeMemoryRepository(): MemoryRepository? {
        val configuration = currentNovexConfiguration()
        val characterId = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId
            ?: return memoryRepository
        val worldId = configuration.backgroundSettings.filter { it.subject.kind == NovexContentKind.WORLD }
            .map { it.subject.id }.sorted().joinToString("|").ifBlank { null }
        val personaId = configuration.playerIdentity?.id
        val key = listOf(worldId, personaId, characterId).joinToString("|")
        if (scopedMemoryKey != key || scopedMemoryRepository == null) {
            scopedMemoryKey = key
            scopedMemoryRepository = com.openminis.app.data.character.CharacterCardStore
                .characterMemoryRepository(context, characterId, worldId, personaId)
        }
        return scopedMemoryRepository
    }

    /** null = inherit the role card background; empty = explicitly hide it. */
    fun setImmersiveBackground(path: String?) {
        _conversationBackgroundPathOverride.value = path
        val effective = path
            ?: _immersiveProfile.value.character?.defaultBackgroundPath
            ?: _immersiveProfile.value.world?.backgroundPath
        _immersiveProfile.value = _immersiveProfile.value.copy(backgroundPath = effective)
        val sid = realSessionId
        if (sid.isNotEmpty()) {
            viewModelScope.launch { chatRepository.setChatWallpaper(sid, path) }
        }
    }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    init {
        ChatViewModelStore.registerRuntime(sessionId, requireNotNull(viewModelScope.coroutineContext[Job]))
        loadSession()
        viewModelScope.launch {
            sessionLoaded.first { it }
            _inputText.collect {
                try { persistComposerDraft() }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { _error.value = "草稿尚未保存：${failure.message}。请保留当前页面后重试。" }
            }
        }
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                _availableGroups.value = config.modelGroups
                // [T-android-disabled-provider-still-selectable-via-group #34]
                // Runtime re-resolution when a GROUP-bound session's currently
                // active member has its provider DISABLED mid-session. The
                // selection paths (resolveProviderFromGroup → enabledMemberEntries)
                // already skip disabled members, but they only run while
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // below short-circuits — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        _activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        // Whole group is now unavailable (all members disabled /
                        // credential-less) — fall through to the default group /
                        // new-chat fallback chain by clearing the cached provider
                        // so the guard below re-runs the standard resolution.
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the default group, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.sessionById(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
                    var resolved = false
                    if (effectiveGroupId != null) {
                        resolved = resolveProviderFromGroup(effectiveGroupId)
                        if (resolved) {
                            _selectedGroupId.value = effectiveGroupId
                        }
                    }
                    if (!resolved) {
                        // [T-newchat-default-model-fallback-android] Same
                        // new-chat fallback chain as the draft branch in
                        // loadSession: last-used → newest-provider/newest-text.
                        // Was allVisibleEntries().firstOrNull().
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts and browser artifacts
     * all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [SessionRow] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): novex.android.data.chat.SessionRow? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.sessionById(sid) }.getOrNull()
    }

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.renameSessionWithCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { "New Chat" }
            _sessionCategory.value = category
        }
    }

    /** Ensure the session exists in the database. Called before first message. */
    internal suspend fun ensureSession(): String = sessionCreationMutex.withLock {
        if (realSessionId.isNotEmpty()) return@withLock realSessionId
        val modelId = currentModel?.id ?: providerRepository.allVisibleEntries().firstOrNull()?.model?.id ?: "unknown"
        // [T-memory-global-toggle-settings-ui-android] Snapshot the
        // current in-memory `_memoryEnabled` into the new row. For a
        // draft VM this matches the global default we seeded at
        // construction; if the user flipped /memory on the draft
        // before first send, that choice wins.
        val session = chatRepository.createSession(
            title = initialCardEntry?.first,
            modelId = modelId,
            memoryEnabled = _memoryEnabled.value,
            characterId = _immersiveProfile.value.character?.id,
            characterSnapshotJson = _immersiveProfile.value.character?.toJson()?.toString(),
            worldSnapshotJson = _immersiveProfile.value.world?.toJson()?.toString(),
            personaId = _immersiveProfile.value.persona?.id,
            personaSnapshotJson = _immersiveProfile.value.persona?.toJson()?.toString(),
            worldId = _immersiveProfile.value.worldId,
            characterVersionId = _immersiveProfile.value.characterVersionId,
            chatBackgroundPath = _conversationBackgroundPathOverride.value,
            conversationPrompt = _conversationPrompt.value ?: inheritedEditablePrompt(),
            imageStylePrompt = _imageStylePrompt.value.ifBlank { null },
            rolePresentationEnabled = _immersiveProfile.value.rolePresentationEnabled ||
                _immersiveProfile.value.character != null,
            assistantDisplayName = _immersiveProfile.value.assistantDisplayName,
            assistantAvatarPath = _immersiveProfile.value.assistantAvatarPath,
            playerDisplayName = _immersiveProfile.value.playerDisplayName,
            playerAvatarPath = _immersiveProfile.value.playerAvatarPath,
            novexConfigurationJson = _novexConfigurationJson.value,
        )
        realSessionId = session.id
        // "New Chat in Group": file the just-promoted draft into its folder.
        // Unconditional (vs iOS setFolderIfUnfiled) — the session is seconds
        // old and nothing else can have filed it yet.
        initialFolderId?.let { chatRepository.setFolderForSessions(it, listOf(session.id)) }
        // Move our cached VM from the draft key ("__new__...") to the real
        // sessionId so re-entering the session reuses the same instance.
        if (isDraft) {
            ChatViewModelStore.rename(sessionId, session.id)
            // Bring every disk/shell resource that was opened with the draft
            // id over to the real id *before* agent tools start running against
            // the persisted session — otherwise the first tool call (e.g.
            // yt-dlp writing into /var/minis/attachments) would land in
            // minis-sessions/__new__*/… and be orphaned when the user
            // re-enters the session and everything is resolved via the real
            // id. See debug report 2026-04-21 (TikTok Chinese filename).
            com.openminis.app.diagnostics.ModelRequestAudit.promoteDraft(
                java.io.File(context.filesDir, "novex/model-requests"), sessionId, session.id)
            migrateDraftResources(fromDraft = sessionId, toReal = session.id)
            // [T-android-session-skill-override-init-timing] Re-point any
            // session_skill_overrides / mcp_session_overrides rows written
            // pre-first-message (against `__new__<uuid>`) onto the real
            // session id, mirroring the disk-resource hop above. Without
            // this, a skill or MCP server the user toggled on the draft
            // session sheet vanishes the next time the same chat is opened
            // (the prop carries the real id by then, but the override row
            // is still stranded under the draft key). Aligns with iOS
            // ed861471 (T-ios-session-skill-override-init-timing). Cheap
            // no-op when no rows match.
            skillRepository?.renameSessionOverrides(fromDraft = sessionId, toReal = session.id)
            // [P3.3 裁军] mcp_session_overrides 的草稿改名随 MCP 集成面退役
            // （MCPRepository 已删）；BrowserTabPool 的重指合同上。
        }
        // Persist the current model binding so it survives re-entry
        val groupId = _selectedGroupId.value
        val entryId = _activeEntryId.value
        val binding = when {
            groupId != null && entryId != null -> """{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}"""
            groupId != null -> """{"type":"group","groupId":"$groupId"}"""
            entryId != null -> """{"type":"entry","entryId":"$entryId"}"""
            else -> null
        }
        if (binding != null) {
            chatRepository.rebindSessionModel(realSessionId, binding, modelId)
        }
        realSessionId
    }

    internal suspend fun prepareNovexConversationForEntry() {
        try {
            prepareNovexConversationDrafts()
            adoptedNovexConfiguration()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _error.value = "对话草稿或成果目录尚未准备完成：${error.message}。已有内容仍保留，可稍后重试。"
        }
    }

    internal suspend fun prepareNovexConversationDrafts() {
        val sid = ensureSession()
        if(integratedCards.binding(sid)==null)novexApplication().novexWorkspace.apply(novex.core.NovexCommand.EnsureConversationDrafts(sid))
        withContext(Dispatchers.IO) {
            val persistedReplies = chatRepository.loadActiveConversation(sid).activeMessages
                .filter { it.role == "assistant" }.mapTo(hashSetOf()) { it.id }
            novexConversationWorkspaceStore.recoverLegacyReplyFiles(sid, persistedReplies)
            com.openminis.app.data.creative.WorkspaceCreativeArtifactBridge(
                novexConversationWorkspaceStore, novexApplication().creativeArtifactRepository,
            ).reconcile(novex.core.NovexConversationWorkspaceScope(
                sid, activeBranchPathIds, novex.core.NovexConversationWorkspaceScope.ROOT_BRANCH,
            ))
        }
    }

    /**
     * Move every per-session disk resource from the draft directory to the
     * real one.
     *
     * The draft key leaks into browser artifacts and the `BrowserTabPool`'s
     * cookie/state store. Before this migration ran, a tool invocation that
     * happened before the user's first message would write into the draft's
     * `minis-sessions/__new__{uuid}` directory and become invisible the
     * moment the VM was recreated under the real id.
     */
    private fun migrateDraftResources(fromDraft: String, toReal: String) {
        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }
        // [P3.3 裁军] browser_tabs/<sid>.json 的改名迁移随 BrowserTabPool
        // 退役删除；"browser" 子目录仍保留在迁移清单里，仅为搬运旧安装
        // 的既有浏览器工件，不再有新写入方。
    }

    private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)

    internal data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

    fun clearChat() {
        // 净眼 P2-2：连点两次的复位窗口门——第二个 wipe 未清内存前 isWiping 不得提前失效。
        if (isWipingSession) return
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        // [T-single-writer] PR2b ①：真相源（agentHistory/_messages/压缩标记）改为
        // DB 先行——下方协程先删库再清内存，崩溃窗口内重启不再复活已清对话。
        // 删除是单条 SQL（毫秒级），UI 清屏延迟不可感知；其余非真相源状态保持
        // 同步清以保即时反馈。
        activeNovexDocumentRefs = emptySet()
        activeNovexSourceCollectionRefs = emptySet()
        closeNovexLearningResponsePreview()
        closeNovexLearningDetails()
        _novexLearningError.value = null
        _pendingNovexLearningPreflight.value = null
        _novexLearningTask.value = null
        _novexLearningStatus.value = null
        _error.value = null
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        _selectedToolDetailId.value = null
        // [P3.3 裁军] 会话浏览器标签清理（BrowserTabPool.releaseAllTabs +
        // browser_tabs/<sid>.json 删除）随内置浏览器退役删除。
        // Persist: drop messages + compact markers. Files (workspace,
        // attachments, offloads) intentionally retained.
        // [T-run-phase] D2/D3：isWiping 门 + dbWriteSerial 串行（P2-1/P2-2 关账）。
        isWipingSession = true
        viewModelScope.launch {
            try {
                dbWriteSerial.withLock {
                    chatRepository.dao.dropAllMessages(sid)
                    chatRepository.dao.dropMarkersFor(sid)
                }
                withContext(Dispatchers.Main) {
                    synchronized(historyWriteLock) {
                        agentHistory.clear()
                        historyGeneration.incrementAndGet()
                    }
                    _messages.value = emptyList()
                    _cachedLatestMarker = null
                }
                Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
            } finally {
                isWipingSession = false
                runPhaseTransitionTo(RunPhase.IDLE, "clearChat")
            }
        }
    }

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    internal val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) InputAttachment.Kind.IMAGE
                   else InputAttachment.Kind.DOCUMENT
        // T185 fix: ChatScreen wipes the share-extension directory right
        // after this call returns (`SharedShareStore.cleanSharedFiles`),
        // so a `Uri.fromFile(<staged file>)` would dangle by the time the
        // user actually sends — the byte-read in prepareUserAttachments
        // then fails to open the stream and the image never makes it into
        // the LLM payload, leaving the model staring at "what is this?" with no
        // picture. Copy the staged bytes into our own private dir so the
        // attachment outlives the share-extension cleanup.
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        try {
            file.inputStream().use { input ->
                durable.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
            return null
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    /**
     * [T-android-rerun-from-tool-block-position] Re-run the conversation from
     * the exact point a specific tool_use block was about to be issued —
     * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
     * tool_use in the same assistant turn as context, retains the original
     * subtree, and creates a sibling so the model re-decides from that point.
     *
     * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
     * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
     * equals its `id`) — stable + unique, NOT a positional count, so streaming
     * / merged-turn alignment can't drift the cut point.
     *
     * Degenerate case: when the target is the first real block of its turn,
     * delegate to [retryFromMessage], which creates a whole-reply sibling.
     *
     * Android persists any kept prefix as a fresh sibling assistant row. Its
     * completed tool calls are history only and are never dispatched again.
     * Consecutive assistant rows merge back into one visible bubble.
     *
     * No-op (returns false) when streaming, when the message/block isn't
     * found, or when the block isn't a tool_use. The caller gates the menu
     * item with the same `!isStreaming` rule, but the guard here is the source
     * of truth.
     */
    fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        if (_isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        // Only a real tool_use block anchors a block cut — its id is the
        // tool_use id we match against in agentHistory / parts_json.
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        val targetToolUseId = targetBlock.id

        // Degenerate: nothing of substance precedes the target in this turn —
        // a block cut here is identical to truncating at the preceding user
        // message, so reuse the existing whole-turn path. "Substance" = any
        // earlier block that isn't an empty text block (mirrors iOS
        // hasPrecedingContent).
        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate
        // shortcut is ONLY equivalent to truncating at the preceding user
        // message when there is NOTHING between that user message and this
        // assistant turn. If an EARLIER assistant turn/bubble sits right before
        // this one (asstIdx-1 is also assistant), retryFromMessage(precedingUser)
        // would delete that earlier turn's tools too — exactly the "rerun from
        // the last tool wiped the tools above it / re-ran from the very start"
        // bug (logged: historySize 29 → 3 on the 2nd consecutive rerun). In
        // that case fall through to the DB-precise cut below, which keeps every
        // row before the target row (its cutPartIdx==0 branch deletes only the
        // target row onward) and preserves the earlier turns.
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return false
        }
        _canResume.value = false
        _error.value = null

        // Claim the streaming flag synchronously so a rapid second tap is
        // rejected by the entry guard (same rationale as retryFromMessage T145).
        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                // Locate the DB assistant row holding the target tool_use, and
                // the parts-array index of that tool_use within it.
                val dbMessages = chatRepository.historyFor(sid)
                var cutRow: MessageRow? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    // Anchor not in DB (shouldn't happen for a rendered tool
                    // block). Abort cleanly without a half-applied truncation.
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                // Trim the row's parts to those strictly before the target
                // tool_use, preserving array order (parts_json mirrors block
                // order). An assistant turn may hold text + several tool_use
                // parts; we keep everything ahead of the matched index.
                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                // Retain the original row and its complete descendant tree.
                // A non-empty kept prefix is persisted as a new sibling row;
                // already-completed tools in that prefix are context only and
                // are never dispatched again.
                chatRepository.forkEditedMessageFrom(sid, row.id)
                if (cutPartIdx > 0) {
                    chatRepository.appendMessage(
                        sessionId = sid,
                        role = "assistant",
                        partsJson = keptArr.toString(),
                        reasoningContent = row.reasoningContent,
                    )
                }
                val conversation = chatRepository.loadActiveConversation(sid)
                installActiveConversation(conversation)
                Log.i(
                    TAG,
                    "rerunFromToolBlock retained old branch tuId=${targetToolUseId.take(12)} " +
                        "partIdx=$cutPartIdx row=${row.id.take(8)}",
                )

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return true
    }

    /** [feat/ui-rikkahub] Retry the turn that produced an assistant reply —
     * walks back to the reply's preceding user message and retries from
     * there, so the old reply stays as a sibling branch. */
    fun retryFromAssistantMessage(assistantMessageId: String) {
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == assistantMessageId }
        if (index < 0) return
        val userId = messages.subList(0, index).lastOrNull { it.role == "user" }?.id ?: return
        retryFromMessage(userId)
    }

    /** Retry from a user turn by retaining its existing reply as a sibling. */
    fun retryFromMessage(messageId: String) {
        if (_isStreaming.value) return
        // [T-run-phase] D2：清空进行中不重试。
        if (isWipingSession) {
            appendSystemInfo(text = "正在清空对话，请稍候再重试。", iconKind = "compact")
            return
        }
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        val provider: LLMProvider = initialProvider
        _error.value = null

        // A queued bubble is already persisted. Remove only its queue entry;
        // the active-path reload below restores the same user row unqueued.
        message.queuedPromptId?.let { promptId ->
            _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        }
        val persistedUserId = message.branchAnchorDbId
            ?: message.sourceDbIds.firstOrNull()
            ?: message.id

        // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
        // (or any concurrent send/retry attempt) is rejected by the entry
        // guard. Previously this was set inside the suspended outer launch,
        // leaving a multi-second window during DB cleanup + OAuth refresh
        // where two retries could slip through and spawn duplicate streamJobs.
        // The orphaned first job's `_isStreaming = false` at completion would
        // then flip the UI to "stopped" while the second job was still running.
        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            // If setup throws before the inner streamJob is launched, the
            // streaming flag would be stuck true forever. Reset on the
            // unhappy paths; happy path resets in the streamJob's tail.
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            val conversation = chatRepository.forkReplyFrom(sid, persistedUserId)
            installActiveConversation(conversation)

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }

    /**
     * Select a persisted sibling path. This only rebuilds local projections;
     * it never enters the agent loop or dispatches a tool.
     */
    fun switchMessageBranch(messageId: String, delta: Int) {
        if (_isStreaming.value || delta == 0) return
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        if (sid.isEmpty()) return
        viewModelScope.launch {
            conversationBranchMutex.lock()
            try {
                withContext(Dispatchers.IO) { novexLearningSession.pauseActive() }
                val conversation = withContext(Dispatchers.IO) {
                    chatRepository.switchMessageSibling(sid, messageId, delta)
                }
                installActiveConversation(conversation)
                _canResume.value = false
            } catch (error: Exception) {
                AppLogger.error(
                    TAG_STREAM,
                    "branch switch failed ${error::class.java.simpleName}: ${error.message}",
                )
                _error.value = "切换分支失败：${error.message ?: error::class.java.simpleName}"
            } finally {
                conversationBranchMutex.unlock()
            }
        }
    }

    /** Rebuild every branch-sensitive in-memory view from one DB snapshot. */
    /** External writers (side-conversation handoff) append rows directly; this pulls them into the live view. */
    suspend fun reloadActiveConversation() {
        installActiveConversation(chatRepository.loadActiveConversation(activeSessionId))
    }

    // ── 侧边回传（2026-09-15 重做：状态下沉 ViewModel）────────────────────
    // 此前回传的收尾协程活在侧边页面的 UI 里——用户生成期间退出页面，协程被
    // 取消、简报永不写入主线且无任何提示（用户报告"根本没办法回传"）。本
    // ViewModel 按会话常驻（ChatViewModelStore），把状态机搬到这里后无论用户
    // 何时离开页面，简报生成都一定会落账并给出结果。
    sealed interface SideHandoffState {
        data object Idle : SideHandoffState
        data object Running : SideHandoffState
        data class Succeeded(val brief: String) : SideHandoffState
        data class Failed(val reason: String) : SideHandoffState
    }

    private val _sideHandoffState = MutableStateFlow<SideHandoffState>(SideHandoffState.Idle)
    val sideHandoffState: StateFlow<SideHandoffState> = _sideHandoffState.asStateFlow()
    private var sideHandoffBaseIds = setOf<String>()
    private var sideHandoffJob: kotlinx.coroutines.Job? = null

    /** 触发一次回传：让侧边模型产出增量交接简报并并入主线。守卫：只认点击后
     *  新产生的助手回复；生成失败/超时绝不把旧回复当简报（决策 18）。 */
    // [T-handoff-queue] 2026-09-16 用户批④·决策 7：生成中点回传→排队，本轮
    // 结束自动执行（此前直接吞掉点击，用户以为回传失灵）。
    @Volatile private var sideHandoffQueued = false

    fun startSideHandoff(sideParentId: String) {
        if (_sideHandoffState.value is SideHandoffState.Running) return
        if (_isStreaming.value) {
            sideHandoffQueued = true
            appendSystemInfo("正在生成中：回传已排队，本轮结束后自动执行", "handoff")
            return
        }
        sideHandoffJob?.cancel()
        sideHandoffBaseIds = _messages.value.map { it.id }.toSet()
        _sideHandoffState.value = SideHandoffState.Running
        sendMessage(NovexSideHandoff.instruction(NovexSideHandoff.readWatermark(context, sessionId)))
        sideHandoffJob = viewModelScope.launch {
            // Pair(生成确实开始过, 新产出的助手回复)：外层 null = 150 秒超时。
            var sawStreaming = false
            val result: Pair<Boolean, ChatMessage?>? = withTimeoutOrNull(150_000) {
                kotlinx.coroutines.flow.combine(_isStreaming, _messages) { streaming, _ -> streaming }
                    .first { streaming ->
                        if (streaming) {
                            sawStreaming = true
                            false
                        } else {
                            // 首个 false 且从未见过 true = 生成根本没开始。
                            true
                        }
                    }
                sawStreaming to _messages.value.lastOrNull {
                    it.role == "assistant" && it.content.isNotBlank() && it.id !in sideHandoffBaseIds
                }
            }
            val fresh = result?.second
            _sideHandoffState.value = when {
                result == null -> SideHandoffState.Failed("交接未完成：等待新回复超时")
                !result.first -> SideHandoffState.Failed("交接未完成：发送没有生效，请重试")
                fresh == null -> SideHandoffState.Failed("交接未完成：生成被打断，没有产出简报")
                else -> runCatching {
                    chatRepository.appendMessage(sideParentId, "user", NovexSideHandoff.parts(fresh.content))
                    NovexSideHandoff.writeWatermark(context, sessionId, fresh.content)
                    NovexSideHandoff.markParentDirty(context, sideParentId)
                }.fold(
                    { SideHandoffState.Succeeded(fresh.content) },
                    { SideHandoffState.Failed(it.message ?: "交接未写入主对话") },
                )
            }
            if (_sideHandoffState.value is SideHandoffState.Succeeded) {
                kotlinx.coroutines.delay(3_000)
                if (_sideHandoffState.value is SideHandoffState.Succeeded) {
                    _sideHandoffState.value = SideHandoffState.Idle
                }
            }
        }
    }

    fun retrySideHandoff(sideParentId: String) {
        _sideHandoffState.value = SideHandoffState.Idle
        startSideHandoff(sideParentId)
    }

    /** [T-handoff-queue] 流结束后由页面调用：消费排队的回传。 */
    fun runQueuedSideHandoff(sideParentId: String) {
        if (!sideHandoffQueued || _isStreaming.value) return
        sideHandoffQueued = false
        if (_sideHandoffState.value is SideHandoffState.Idle) startSideHandoff(sideParentId)
    }

    // ── [T-cross-sync] /sync 双向沟通（2026-09-16 用户批δ，决策 1/2/3）──────
    // 任意一边调用：后台起一个独立压缩请求（不占两边上下文），把"自己的记忆"
    // 压成简报；命令本身不落史，简报本体作为用户消息并入两边历史。侧边→主线；
    // 主线→侧边（多条侧边时 /sync <编号> 指定，主线只压分裂点之后的增量）。
    private val _crossSyncState = MutableStateFlow<String?>(null)
    val crossSyncState: StateFlow<String?> = _crossSyncState.asStateFlow()

    fun startCrossSync(argument: String = "") {
        if (_isStreaming.value) {
            appendSystemInfo("正在生成中；本轮结束后再执行 /sync 沟通", "sync")
            return
        }
        if (_crossSyncState.value != null) return
        _crossSyncState.value = "正在压缩记忆并发送…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                runCrossSync(argument)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                appendSystemInfo("沟通失败：${failure.message ?: "未知错误"}", "sync")
            } finally {
                _crossSyncState.value = null
            }
        }
    }

    private suspend fun runCrossSync(argument: String) {
        // [T-run-phase] D2：清空进行中不做双向沟通。
        if (isWipingSession) {
            appendSystemInfo(text = "正在清空对话，请稍候再使用 /sync。", iconKind = "compact")
            return
        }
        val session = chatRepository.sessionById(activeSessionId) ?: return
        val sideOf = session.sideOfSession
        val targetId: String
        val fromSide: Boolean
        if (sideOf != null) {
            targetId = sideOf; fromSide = true
        } else {
            val sides = chatRepository.sideSessionsOf(activeSessionId)
            when {
                sides.isEmpty() -> {
                    appendSystemInfo("没有沟通对象：主线用 /sync 前请先开侧边对话；在侧边对话里用 /sync 是把摘要发给主线。", "sync"); return
                }
                sides.size == 1 -> targetId = sides.first().id
                else -> {
                    val byNumber = argument.toIntOrNull()?.let { n -> sides.firstOrNull { com.openminis.app.data.repository.sideConversationNumber(it.title ?: "") == n } }
                    val byName = sides.firstOrNull { argument.isNotBlank() && (it.title ?: "").contains(argument.trim()) }
                    val pick = byNumber ?: byName
                    if (pick == null) {
                        appendSystemInfo("这条主线有多条侧边，请用 /sync <编号> 指定（${sides.joinToString("、") { "${com.openminis.app.data.repository.sideConversationNumber(it.title ?: "")}·${it.title}" }}）", "sync"); return
                    }
                    targetId = pick.id
                }
            }
            fromSide = false
        }
        // 压缩源：自己的可见文本历史；主线→侧边只压分裂点之后的增量。
        val snapshotIds = if (!fromSide) SideSnapshotStore.read(context, targetId)?.messageIds?.toSet() else null
        val transcript = chatRepository.loadActiveMessages(activeSessionId)
            .filter { row -> row.role == "user" || row.role == "assistant" }
            .filter { row -> snapshotIds == null || row.id !in snapshotIds }
            .mapNotNull { row ->
                val text = runCatching {
                    val arr = org.json.JSONArray(row.partsJson)
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.takeIf { it.optString("type") == "text" }?.optString("value")?.trim()
                    }.filter { it.isNotEmpty() }.joinToString(" ")
                }.getOrNull().orEmpty().ifBlank { null }
                "${if (row.role == "user") "[用户]" else "[助手]"} $text"
            }
            .takeLast(60)
            .joinToString("\n")
        if (transcript.isBlank()) {
            appendSystemInfo("还没有可沟通的内容（没有新的对话记录）", "sync"); return
        }
        val provider = currentProvider ?: run {
            appendSystemInfo("请先选择模型再执行沟通", "sync"); return
        }
        val brief = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "以下是本对话的自有记录，请压缩成一份给另一条平行对话看的沟通简报：\n\n$transcript")),
            "你是对话记忆压缩器。把输入的对话压缩为一份交接简报：保留关键事实、决定、数值、人物状态与未完成事项；丢弃寒暄、客套与过程描述。不超过 500 字，直接输出简报正文，不要任何前后缀说明。",
            1024,
        ).text.trim()
        if (brief.isBlank()) {
            appendSystemInfo("沟通失败：压缩结果为空", "sync"); return
        }
        val header = if (fromSide) "【沟通简报 · 来自侧边】" else "【沟通简报 · 来自主线】"
        val text = "$header\n$brief"
        val parts = org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("value", text)).toString()
        val mine = chatRepository.appendMessage(activeSessionId, "user", parts)
        recordActiveBranchMessage(mine.id)
        // 净眼 P1-1 关账：/sync 的用户行 add 在 IO 线程——入锁+自增，否则落入
        // 取消清理的世代号盲区（跨线程并发写 + partial 行落序）。
        synchronized(historyWriteLock) {
            agentHistory.add(LLMMessage(LLMMessage.Role.USER, text, dbMessageId = mine.id))
            historyGeneration.incrementAndGet()
        }
        chatRepository.appendMessage(targetId, "user", parts)
        if (fromSide) NovexSideHandoff.markParentDirty(context, targetId)
        appendSystemInfo(
            "已沟通：简报已并入两边的历史（${if (fromSide) "侧边 → 主线" else "主线 → 侧边"}）",
            "sync",
            payload = brief,
        )
    }

    fun dismissSideHandoff() {
        if (_sideHandoffState.value !is SideHandoffState.Running) {
            _sideHandoffState.value = SideHandoffState.Idle
        }
    }

    internal data class InjectedTurn(val newAssistantId: String)

    fun cancelStream() {
        compactionJob?.cancel()
        integratedCards.stop()
        AppLogger.info(TAG_STREAM, "cancelStream invoked _isStreaming=false (sid=$activeSessionId)")
        streamJob?.cancel()
        _isStreaming.value = false
        // T-streaming-side-channel: flush any in-flight delta back into the
        // canonical message so the rest of cancelStream's cleanup (publish
        // overlay excerpt, persist, retry-eligible state) sees the real
        // content rather than a stale pre-stream snapshot.
        flushAllStreamingDeltas()
        // T171: drop activity tracker immediately, don't wait for the
        // streamJob's finally block. When OkHttp is wedged in a blocking
        // execute() call.cancel() may unwind eventually but the finally
        // doesn't run until then — meanwhile RPC chat.session.status would
        // still report isRunning=true and the user thinks the stop button
        // did nothing.
        // [T-android-overlay-reply-status-34599] User-initiated cancel:
        // surface any reply we already streamed + tag outcome as
        // Cancelled so the overlay's glyph reflects the actual end
        // state (⊘) instead of carrying over the prior tool's outcome.
        publishOverlayReplyExcerpt(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        handleUserCancelledCleanup()

        // T189: iOS parity (AIChatViewModel.swift L2592-2610). If the user
        // enqueued prompts during the cancelled stream, auto-resume the drain
        // instead of leaving them stuck as dashed bubbles waiting for a manual
        // long-press retry.
        val pending = _promptQueue.value
        if (pending.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }

    /**
     * T189: spawn a fresh agent loop to drain whatever the user queued during
     * the cancelled stream. 200ms delay matches iOS resumeQueueAfterCancel
     * (Task.sleep(200_000_000)) — gives the cancelled streamJob's finally block
     * room to release the concurrency slot + write back state. Race-guards on
     * entry: empty queue (user withdrew) or already streaming (user manually
     * retried) → noop return.
     *
     * Provider / systemPrompt / fallback resolution mirrors [sendMessage]
     * verbatim (incl. OAuth token refresh + Claude Code prefix), so a queued
     * prompt drain after cancel uses the same plumbing as a fresh send.
     */
    private fun resumeQueueAfterCancel() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            if (_promptQueue.value.isEmpty()) return@launch
            if (_isStreaming.value) return@launch
            // [T-android-compact-queued-drain] Defer while a compact is in
            // flight — draining would mutate agentHistory mid-marker-write.
            // Safe to just return: every SUCCESSFUL compact re-kicks this
            // function from its own tail, so a deferred drain is never lost
            // (and a failed compact leaves the queue pending by design).
            if (_isCompacting.value) {
                AppLogger.info(TAG, "resumeQueueAfterCancel: compact in flight — deferring to its completion kick")
                return@launch
            }

            val initialProvider = currentProvider
            if (initialProvider == null) {
                AppLogger.warning(TAG, "resumeQueueAfterCancel: no provider, dropping queue")
                _promptQueue.value = emptyList()
                _messages.value = _messages.value.filterNot { it.isQueued }
                return@launch
            }
            var provider: LLMProvider = initialProvider

            // Refresh OAuth token if needed (mirrors sendMessage L2477-2501).
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
                    Log.w(TAG, "OAuth token refresh failed (resumeQueueAfterCancel): ${e.message}")
                }
            }

            val baseSystemPrompt = buildSystemPrompt()
            val systemPrompt = if ((provider as? novex.android.transport.NovexTransportProvider)?.isAnthropicOAuth == true) {
                val prefix = com.openminis.app.auth.ClaudeOAuthManager.ANTHROPIC_OAUTH_IDENTIFIER_PROMPT
                if (baseSystemPrompt?.startsWith(prefix) == true) baseSystemPrompt
                else "$prefix\n\n${baseSystemPrompt ?: ""}"
            } else baseSystemPrompt

            // T145: claim the streaming flag synchronously before launching
            // the streamJob so a concurrent send/retry tap is rejected by the
            // entry guard. Mirrors sendMessage discipline.
            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=true (sync, sid=$activeSessionId)")
            _isStreaming.value = true
            _canResume.value = false
            _error.value = null

            streamJob = launch(Dispatchers.IO) {
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob ENTER sid=$activeSessionId")
                val leasedSessionId = activeSessionId
                var slotAcquired = false
                var slotReleased = false
                try {
                    SessionConcurrencyManager.acquireSlot(leasedSessionId)
                    slotAcquired = true
                    AppLogger.debug(TAG_STREAM, "resumeQueueAfterCancel streamJob slot acquired")
                    SessionActivityTracker.setActive(activeSessionId, onStop = { cancelStream() })

                    val activeFallbackStrategy = run {
                        val groupId = _selectedGroupId.value
                        groupId?.let { providerRepository.config.value.modelGroups.find { g -> g.id == it }?.fallbackStrategy }
                            ?: novex.android.data.model.FallbackStrategy.default
                    }
                    val fallbackProviders = buildFallbackProviders(provider)

                    try {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts CALL")
                        drainQueuedPrompts(
                            provider = provider,
                            systemPrompt = systemPrompt,
                            fallbackProviders = fallbackProviders,
                            fallbackStrategy = activeFallbackStrategy,
                        )
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drainQueuedPrompts RETURN")
                    } catch (e: CancellationException) {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel drain CANCELLED")
                    } catch (e: Exception) {
                        AppLogger.error(TAG_STREAM, "resumeQueueAfterCancel drain EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
                        Log.e(TAG, "Queued drain error (resumeQueueAfterCancel)", e)
                        if (streamJob === coroutineContext[Job]) {
                            setInlineError(e.message ?: "Unknown error")
                        } else {
                            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel stale stream ignored exception UI update")
                        }
                    } finally {
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY enter")
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
                            AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel stale stream skipped tracker finalization")
                        }
                            SessionConcurrencyManager.releaseSlot(leasedSessionId)
                            slotReleased = true
                        AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob FINALLY exit")
                    }
                } catch (e: CancellationException) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob CANCELLED waiting for slot")
                }
                if (slotAcquired && !slotReleased) SessionConcurrencyManager.releaseSlot(leasedSessionId)
                // [T-android-stale-streamjob-clears-isstreaming] guard.
                if (streamJob === coroutineContext[Job]) {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming=false (about to set)")
                    _isStreaming.value = false
                } else {
                    AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel _isStreaming SKIPPED (stale job)")
                }
                AppLogger.info(TAG_STREAM, "resumeQueueAfterCancel streamJob EXIT")
            }
        }
    }

    /**
     * After the user stops a streaming turn, reconcile UI + agentHistory so
     * the conversation is valid on the next API call and resumable via
     * [resume]. Mirrors iOS AIChatViewModel.handleUserCancelledCleanup
     * (Case 1: tool cancel, Case 2: text cancel).
     *
     *  - Case 1: any in-flight tool block is flipped to [ToolBlockStatus.CANCELLED]
     *    and a synthetic tool_result with [CANCELLED_MARKER] is persisted so
     *    tool_use/tool_result stays paired.
     *  - Case 2: if there was partial assistant text streamed (and no tool
     *    cancel), commit the partial text + a truncation `<system-reminder>`
     *    to agentHistory so the model knows the prior turn was cut short.
     *
     * Always sets [_canResume] = true when there is something to resume from.
     */
    private fun handleUserCancelledCleanup() {
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastIdx < 0) return
        var last = msgs[lastIdx]

        // T73: clear "Minis is thinking…" the moment the user taps Stop.
        // isAwaitingModelResponse is set true at runAgentLoop entry (≈ line
        // 2785) so the typing indicator shows during the initial request
        // gap before the first stream chunk. The cancel paths below didn't
        // reset it, so after Stop the indicator stayed live forever even
        // though the streamJob was already torn down. Reset before either
        // case runs so both tool-cancel and text-cancel paths benefit.
        if (last.isAwaitingModelResponse) {
            last = last.copy(isAwaitingModelResponse = false)
            msgs[lastIdx] = last
            _messages.value = msgs
        }

        // Case 1: cancel during tool execution. Flip in-flight tool blocks to
        // CANCELLED and persist matching tool_result rows.
        val cancelledIds = mutableListOf<Pair<String, String>>() // (toolUseId, toolName)
        val updatedBlocks = last.toolBlocks.map { b ->
            val s = b.toolStatus
            if (s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING || s == ToolBlockStatus.RUNNING) {
                if (b.kind == "tool_use") cancelledIds.add(b.id to b.toolName)
                b.copy(toolStatus = ToolBlockStatus.CANCELLED)
            } else b
        }
        val hadInflightTools = cancelledIds.isNotEmpty()
        if (hadInflightTools) {
            msgs[lastIdx] = last.copy(toolBlocks = updatedBlocks)
            _messages.value = msgs
            val parts = cancelledIds.map { (id, name) ->
                AgentContentPart.ToolResult(
                    id = id,
                    name = name,
                    content = CANCELLED_MARKER,
                    isError = true,
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistToolResultMessage(parts)
            }
            _canResume.value = true
            return
        }

        // Case 2: cancel during text streaming. If partial assistant text
        // exists and agentHistory does not already end with the assistant
        // turn we're on, commit the partial text + truncation marker so the
        // model sees an interrupted prior turn on the next call.
        val partialText = buildString {
            if (last.content.isNotEmpty()) append(last.content)
            for (b in last.toolBlocks) {
                if (b.kind == "text" && b.content.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(b.content)
                }
            }
        }
        val historyEndsWithAssistant =
            agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT

        // Case 0 (T-ios-stop-clear-thinking-and-partial — Android port):
        // Stop fired while still in the pre-first-chunk thinking gap (no
        // partial text, no tool_use emitted, no committed history for this
        // turn). The placeholder ChatMessage runAgentLoop pushed at L5248 is
        // not in the DB and would otherwise render as an empty "Minis" header
        // bubble with no body. Drop it so the UI snaps back to idle the
        // instant the user taps Stop. Mirrors the iOS #566/#569 boundary:
        // a candidate WITH real text or any emitted tool_use is kept (handled
        // by Case 1 / Case 2 below); a thinking-only placeholder is not.
        val hasAnyToolUse = last.toolBlocks.any { it.kind == "tool_use" }
        if (partialText.isEmpty() && !hasAnyToolUse && !historyEndsWithAssistant) {
            msgs.removeAt(lastIdx)
            _messages.value = msgs
            return
        }

        if (partialText.isNotEmpty() && !historyEndsWithAssistant) {
            val parts = listOf<AgentContentPart>(
                AgentContentPart.Text(partialText),
                AgentContentPart.Text(
                    "<system-reminder>The user stopped this response. Content may be incomplete.</system-reminder>"
                ),
            )
            // [T-single-writer] PR2b ②：DB 先行——先落库拿 dbMessageId 再入内存
            // （旧序内存先 add 且不带 dbId，影子指纹天然看不见这条消息；崩溃
            // 窗口内重启则丢中断标记）。落库毫秒级，_canResume 延迟不可感知。
            // 净眼 P1-1 纪元门：落库往返期间若有别的写者推进了历史，不再直接
            // add（agentHistory 非同步，并发写有 CME 面）——等流落定后走 install
            // 权威对账（注意：与 7858 那类"自有流序言内的 install"不同，这里是
            // 唯一可能与外部流并发的调用点，故必须先 join——净眼 N-P1-a）。
            // 会话本身被切换则行属旧会话，无需动。
            val sidAtCancel = activeSessionId
            // [T-run-phase] ⑤（N-P2-a 关账）+ PR3 世代号：纪元读在锁内快照
            // （世代号取代 size——同长度换内容/重建也算推进，语义超集）。
            val historyEpochAtCancel = synchronized(historyWriteLock) { historyGeneration.get() }
            viewModelScope.launch {
                runCatching {
                    val partsJson = buildAssistantPartsJson(parts)
                    // D3：与 wipe 的 DELETE 互斥，消灭"插入落在删除后"的行复活。
                    val entity = dbWriteSerial.withLock {
                        chatRepository.appendMessage(sidAtCancel, "assistant", partsJson)
                    }
                    val needsReconcile = if (sidAtCancel != activeSessionId) {
                        AppLogger.info(TAG_STREAM, "cancel-cleanup partial row landed in old session $sidAtCancel — memory untouched")
                        false
                    } else {
                        // ⑤：复验与 add 同锁原子——写者已推进则落对账分支，绝不并发 add。
                        synchronized(historyWriteLock) {
                            if (historyGeneration.get() == historyEpochAtCancel) {
                                agentHistory.add(
                                    LLMMessage(
                                        role = LLMMessage.Role.ASSISTANT,
                                        content = partialText,
                                        contentParts = parts,
                                        dbMessageId = entity.id,
                                    )
                                )
                                historyGeneration.incrementAndGet()
                                false
                            } else {
                                true
                            }
                        }
                    }
                    if (needsReconcile) {
                        // 净眼 N-P1-a：install 不能与活跃循环并发；⑥"已声明未启动"
                        // 空档自旋等待（上限 ~4s 放行留痕）。
                        var spinGuard = 0
                        while (true) {
                            val job = streamJob
                            when {
                                job == null -> break
                                !job.isActive && !_isStreaming.value -> break
                                !job.isActive && _isStreaming.value -> {
                                    spinGuard += 1
                                    if (spinGuard > 160) {
                                        AppLogger.warning(TAG_STREAM, "cancel-cleanup join spin guard tripped (~4s) — proceeding")
                                        break
                                    }
                                    kotlinx.coroutines.delay(25)
                                }
                                else -> job.join()
                            }
                        }
                        if (sidAtCancel == activeSessionId) {
                            AppLogger.info(TAG_STREAM, "cancel-cleanup reconciling via install after streams settled")
                            installActiveConversation(chatRepository.loadActiveConversation(sidAtCancel))
                        }
                    }
                }.onFailure { error ->
                    // 净眼 N-P1-b：runCatching 吞 CancellationException 同型复发
                    // （PR0 P1-1 之后第三次）——取消必须穿透；throw 顺带跳过下方
                    // _canResume 置位，CE 逃逸 launch 后 SupervisorJob 静默收尾。
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    AppLogger.error(TAG_STREAM, "cancel-cleanup partial persist failed: ${error::class.java.simpleName}: ${error.message}")
                    synchronized(historyWriteLock) {
                        // 净眼 P2-2：落库失败期间他写者已推进则跳过回退 add——
                        // DB 无此行，install 权威对账会补，盲插只会错序。
                        if (historyGeneration.get() == historyEpochAtCancel) {
                            agentHistory.add(
                                LLMMessage(
                                    role = LLMMessage.Role.ASSISTANT,
                                    content = partialText,
                                    contentParts = parts,
                                )
                            )
                            historyGeneration.incrementAndGet()
                        } else {
                            AppLogger.info(TAG_STREAM, "cancel-cleanup fallback add skipped — epoch advanced (install will reconcile)")
                        }
                    }
                }
                _canResume.value = true
            }
        } else if (historyEndsWithAssistant) {
            // Already committed (tool cancel path above handled or prior turn
            // wrote an assistant row). Still allow resume.
            _canResume.value = true
        }
    }

    /**
     * Build a JSON parts array matching the ChatRepository schema so a
     * committed interrupted-assistant turn round-trips across app restarts.
     * Only emits text parts — tool_use / tool_result paths are handled by
     * the existing persistence code in the agent loop.
     */
    override fun onCleared() {
        super.onCleared()
    }

}
