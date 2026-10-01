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

// 会话装载/模型绑定/回退链：loadSession、标记修复、条目绑定、回退解析。
// 均为 ChatViewModel 扩展，签名与行为冻结。

internal fun ChatViewModel.loadSession() {
    // A reload (compact revert, branch change, or recovery) must rebuild
    // the agent history from the database exactly once.  Keeping the old
    // list here duplicates every message on the next load and inflates
    // context estimates until the provider rejects the request.
    agentHistory.clear()
    retainedTranscriptRows = emptyList()
    retainedTranscriptSessionId = null
    // T-android-crash-detected-halt: when CrashFrequencyDetector
    // tripped (#459, ≥3 crashes in last hour), skip the heavy
    // session-restore path entirely. Re-running the same persisted
    // state is exactly what produced the burst, so we'd just feed
    // a re-crash loop while the user is staring at the share dialog.
    // The flag clears the moment the dialog closes (share / dismiss /
    // cancel) — see CrashFrequencyDetector.maybeShowOnActivity.
    if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
        android.util.Log.w(ChatViewModel.TAG, "loadSession: safe-mode active, skipping session restore")
        sessionLoaded.value = true
        // [T-android-perf-logging] Surface the skip on the Perf timeline
        // too — when a crash_or_stall recovery loop is suspected, this
        // distinguishes "loadSession ran and was slow" from "loadSession
        // was skipped (safe-mode), so the stall is elsewhere".
        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "loadSession.skipped",
            "reason=safeMode",
        )
        return
    }
    viewModelScope.launch {
        // [T-HANG-DIAG] timing markers to localise where session entry
        // stalls. Sentinel-tagged so a single grep -v can strip them
        // when this diagnostic is removed. Declared OUTSIDE the try
        // block so the EXIT log in `finally` can still read it after
        // an early-return / exception path.
        val tHangDiagStart = System.currentTimeMillis()
        println("[T-HANG-DIAG] loadSession ENTER session=$sessionId isDraft=$isDraft")
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "loadSession.enter", "isDraft=$isDraft")
        try {
        val config = providerRepository.config.value
        _availableGroups.value = config.modelGroups

        if (isDraft) {
            // Draft session: just set up provider using default group or first entry
            _sessionTitle.value = initialCardEntry?.first ?: "New Chat"
            _sessionCategory.value = null
            val draftPersona = com.openminis.app.data.character.CharacterCardStore.persona(context, initialPersonaId)
            if (initialCharacterVersionId != null && initialWorldId != null) {
                val database = novex.android.data.NovexMainDatabase.getInstance(context)
                val snapshot = com.openminis.app.data.character.CharacterConversationSnapshotFactory(
                    catalog = com.openminis.app.data.character.CharacterCatalogRepository(
                        database.characterCatalogDao(),
                    ),
                    modules = com.openminis.app.data.character.ContentModuleRepository(
                        database.contentModuleDao(),
                    ),
                    media = com.openminis.app.data.character.MediaAssetRepository(
                        database.mediaAssetDao(),
                    ) { path -> java.io.File(path).delete() },
                ).create(initialWorldId, initialCharacterVersionId, draftPersona)
                _immersiveProfile.value = snapshot.profile
            } else if (initialWorldId != null) {
                val database = novex.android.data.NovexMainDatabase.getInstance(context)
                val catalogProfile = runCatching {
                    com.openminis.app.data.character.CharacterConversationSnapshotFactory(
                        catalog = com.openminis.app.data.character.CharacterCatalogRepository(
                            database.characterCatalogDao(),
                        ),
                        modules = com.openminis.app.data.character.ContentModuleRepository(
                            database.contentModuleDao(),
                        ),
                        media = com.openminis.app.data.character.MediaAssetRepository(
                            database.mediaAssetDao(),
                        ) { path -> java.io.File(path).delete() },
                    ).createWorldProfile(initialWorldId, draftPersona)
                }.getOrNull()
                val legacyWorld = com.openminis.app.data.character.CharacterCardStore.world(
                    context,
                    initialWorldId,
                )
                _immersiveProfile.value = catalogProfile
                    ?: com.openminis.app.data.character.ImmersiveChatProfile(
                        world = legacyWorld,
                        persona = draftPersona,
                        worldId = initialWorldId,
                        backgroundPath = legacyWorld?.backgroundPath,
                        rolePresentationEnabled = false,
                    )
            } else {
                val draftCharacter = com.openminis.app.data.character.CharacterCardStore.character(
                    context,
                    initialCharacterId,
                )
                val draftWorld = when {
                    draftCharacter != null -> com.openminis.app.data.character.CharacterCardStore.world(
                        context,
                        draftCharacter.worldId,
                    )
                    initialWorldId != null -> com.openminis.app.data.character.CharacterCardStore.world(
                        context,
                        initialWorldId,
                    )
                    else -> null
                }
                _immersiveProfile.value = com.openminis.app.data.character.ImmersiveChatProfile(
                    world = draftWorld,
                    character = draftCharacter,
                    persona = draftPersona,
                    backgroundPath = draftCharacter?.defaultBackgroundPath ?: draftWorld?.backgroundPath,
                    rolePresentationEnabled = draftCharacter != null,
                )
            }
            var startingConfiguration = legacyNovexConfiguration(
                conversationId = sessionId,
                worldId = _immersiveProfile.value.worldId,
                characterVersionId = _immersiveProfile.value.characterVersionId,
            )
            if(initialCharacterId==null && initialCharacterVersionId==null && initialWorldId==null && initialInteractiveFictionId==null)
                startingConfiguration=startingConfiguration.copy(cardBindingJson=(initialCardEntry?.second ?: com.openminis.app.cards.CardBinding()).encode())
            if (initialInteractiveFictionId == null) {
                val role = startingConfiguration.answerIdentity as? novex.core.AnswerIdentity.CharacterVersion
                val workspace = (context.applicationContext as? com.openminis.app.MinisApp)?.novexWorkspace
                if (role != null && workspace?.characterForVersion(role.versionId) != null) {
                    val companions = novex.android.adapter.NovexPlayerIdentityReader(workspace).read(
                        novex.core.NovexReferenceTarget(
                            novex.core.NovexContentAddress.characterVersion(role.versionId)))
                    val current = startingConfiguration.playerIdentity
                    if (companions.size > 1 || (companions.isNotEmpty() && current != null && companions.singleOrNull() != current)) {
                        _error.value = "角色提供了不同的配套玩家身份，已保留当前选择。请在对话编辑中选择回答角色，再明确采用哪个玩家身份。"
                    } else if (current == null && companions.size == 1) {
                        startingConfiguration = startingConfiguration.copy(playerIdentity = companions.single())
                    }
                }
            }
            startingConfiguration = novexApplication().database.withTransaction {
                val profile = _immersiveProfile.value
                novex.android.adapter.NovexConversationContextAdoption(novexApplication().novexWorkspace,
                    novex.android.adapter.NovexLegacyContext(profile.characterVersionId, profile.character, profile.world), novexApplication().novexSnapshotMediaStore)
                    .adopt(startingConfiguration)
            }
            val draftConfiguration = applyDraftManagedSubjects(
                draftId = sessionId,
                configuration = startingConfiguration,
            )
            installNovexConfiguration(draftConfiguration)
            _conversationPrompt.value = inheritedEditablePrompt(startingConfiguration.answerIdentity)
            val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId
            var resolved = false
            if (effectiveGroupId != null) {
                resolved = resolveProviderFromGroup(effectiveGroupId)
                if (resolved) {
                    _selectedGroupId.value = effectiveGroupId
                    // T312: pull group session defaults onto the new draft.
                    // ensureSession will persist the override once the
                    // first message is sent and the DB row materialises.
                    applyGroupSessionDefaults(effectiveGroupId)
                }
            }
            if (!resolved) {
                // [T-newchat-default-model-fallback-android] No default
                // group (or it had no usable model) → last-used model, then
                // newest-provider/newest-text-model. Was firstOrNull().
                applyNewChatDefaultModel()
            }
            if (initialInteractiveFictionId != null) gameEntry.start()
            else prepareNovexConversationForEntry()
            return@launch
        }

        // Existing session: load from DB
        val session = chatRepository.sessionById(sessionId) ?: return@launch
        if (!inputEditedBeforeLoad && _inputText.value.isEmpty()) _inputText.value = session.composerDraft.orEmpty()
        _sessionTitle.value = session.title ?: "New Chat"
        _sessionCategory.value = session.category
        val sessionCharacter = session.characterSnapshotJson?.let {
            runCatching {
                com.openminis.app.data.character.CharacterCard.fromJson(org.json.JSONObject(it))
            }.getOrNull()
        }
        val sessionPersona = session.personaSnapshotJson?.let {
            runCatching {
                com.openminis.app.data.character.PlayerPersona.fromJson(org.json.JSONObject(it))
            }.getOrNull()
        }
        val sessionWorld = session.worldSnapshotJson?.let {
            runCatching {
                com.openminis.app.data.character.StoryWorld.fromJson(org.json.JSONObject(it))
            }.getOrNull()
        }
        _immersiveProfile.value = com.openminis.app.data.character.ImmersiveChatProfile(
            world = sessionWorld,
            character = sessionCharacter,
            persona = sessionPersona,
            worldId = session.worldId ?: sessionWorld?.id,
            characterVersionId = session.characterVersionId,
            backgroundPath = session.chatBackgroundPath
                ?: sessionCharacter?.defaultBackgroundPath
                ?: sessionWorld?.backgroundPath,
            rolePresentationEnabled = session.rolePresentationEnabled != 0 || sessionCharacter != null,
            assistantDisplayName = session.assistantDisplayName,
            assistantAvatarPath = session.assistantAvatarPath,
            playerDisplayName = session.playerDisplayName,
            playerAvatarPath = session.playerAvatarPath,
        )
        _conversationBackgroundPathOverride.value = session.chatBackgroundPath
        _conversationPrompt.value = session.conversationPrompt
        _imageStylePrompt.value = session.imageStylePrompt.orEmpty()
        _perTurnPrompt.value = session.perTurnPrompt.orEmpty()
        _textStylePrompt.value = session.textStylePrompt.orEmpty()
        _diceInjectionEnabled.value = session.runtimeDiceEnabled != 0
        _ledgerInjectionEnabled.value = session.runtimeLedgerEnabled != 0
        _novexConfigurationJson.value = session.novexConfigurationJson?.takeIf(String::isNotBlank)
            ?: novex.core.NovexConversationConfigurationCodec.encode(
                legacyNovexConfiguration(
                    conversationId = session.id,
                    worldId = session.worldId ?: sessionWorld?.id,
                    characterVersionId = session.characterVersionId,
                ),
            )
        refreshNovexRuntimeProjection()
        _memoryEnabled.value = session.memoryEnabled != 0
        // T239: hydrate persisted thinking-mode override. null = unset
        // (use OFF as the legacy default); non-null = explicit user
        // choice persisted across cold-start. runCatching guards against
        // a stale enum name from a future rename — fall back silently
        // rather than crashing the session load.
        _thinkingLevel.value = session.thinkingOverride
            ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
            ?: ThinkingLevel.OFF

        // Priority 1: restore from persisted model_binding (group or entry)
        var resolved = restoreFromBinding(session.modelBinding)

        // Priority 2: fall back to stored model_id
        if (!resolved) {
            val entry = findModelEntry(session.modelId)
            if (entry != null) {
                val instance = providerRepository.instance(entry.providerInstanceId)
                if (instance != null) {
                    val apiKey = providerRepository.usableApiKey(instance)
                    if (apiKey != null) {
                        bindChatEntry(entry, instance, apiKey)
                        resolved = true
                        // No binding row (e.g. a synced session that only
                        // carried model_id). If the entry belongs to the
                        // default group, adopt that group so group fallback
                        // works — otherwise buildFallbackProviders returns
                        // empty and provider errors never fall back. NOT
                        // applied to an explicit "entry" binding (user pin),
                        // which restoreFromBinding handles above. Mirrors
                        // the iOS runAgentLoop group-discovery fix.
                        val defaultGroupId = providerRepository.defaultPrimaryGroupId
                        if (defaultGroupId != null &&
                            providerRepository.group(defaultGroupId)?.memberEntryIds?.contains(entry.id) == true
                        ) {
                            _selectedGroupId.value = defaultGroupId
                        }
                    }
                }
            }
        }

        // Priority 3: fall back to default group
        if (!resolved) {
            val defaultGroupId = providerRepository.defaultPrimaryGroupId
            if (defaultGroupId != null) {
                resolved = resolveProviderFromGroup(defaultGroupId)
                if (resolved) _selectedGroupId.value = defaultGroupId
            }
        }

        // [T-HANG-DIAG] measure DB load + transform separately so a long
        // load on one stage is obvious in the trace.
        //
        // T-android-gc-storm-hang-crash (P0, issue #17): on a 405-message
        // session with one 397KB user row, loadMessages + toChatMessages
        // + the agentHistory rebuild below ran on Main and triggered a
        // GC storm (34MB freed, repeated) that blocked the frame loop for
        // 58s → crash_or_stall restart. Hoist the heavy DB + JSON-parse
        // work off Main so the UI thread stays responsive even when one
        // row is large. Stays inside the existing safe-mode guard above
        // (#466/#470) — we only move work, not gating.
        val tHangDiagBeforeLoad = System.currentTimeMillis()
        data class LoadedSessionData(
            val messages: List<novex.android.data.chat.MessageRow>,
            val ordered: List<ChatMessage>,
            val llmHistory: List<LLMMessage>,
            val excludedMemoryWrites: Map<String, Int>,
            val loadMs: Long,
            val transformMs: Long,
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "db.query.begin")
        val loaded = withContext(Dispatchers.IO) {
            val tIoBeforeLoad = System.currentTimeMillis()
            val conversation = chatRepository.loadActiveConversation(sessionId)
            val rows = conversation.activeMessages
            val activeRowIds = rows.mapTo(hashSetOf()) { it.id }
            val usageRecords = chatRepository.novexContextUsage(sessionId)
            val usageByRequest = NovexContextUsageLedger.open(
                NovexContextUsageLedgerSnapshot(sessionId, usageRecords),
            ).latestByRequestForActivePath(activeRowIds)
            val tIoAfterLoad = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "db.query.end",
                "count=${rows.size}",
            )
            val chatUi = rows.toChatMessages(this@loadSession, conversation.graph, usageByRequest)
            val tIoAfterTransform = System.currentTimeMillis()
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toChatMessages.end",
                "count=${chatUi.size}",
            )
            // Pre-build the LLM history list off-Main too — toLLMMessage
            // re-parses partsJson for every row, which is the second
            // contributor to the GC storm. Build into a local list and
            // bulk-append to `agentHistory` on Main below; loadSession
            // runs once at init before any other writer touches
            // agentHistory, so a bulk addAll is race-free.
            val llm = ArrayList<LLMMessage>(rows.size)
            var totalPartsChars = 0L
            for (entity in rows) {
                totalPartsChars += entity.partsJson.length
                llm.add(entity.toLLMMessage(this@loadSession))
            }
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "toLLMMessage.end",
                "count=${llm.size} totalPartsChars=$totalPartsChars",
            )
            LoadedSessionData(
                messages = rows,
                ordered = chatUi,
                llmHistory = llm,
                excludedMemoryWrites = com.openminis.app.data.ConversationBranchMemory
                    .excludedWriteCounts(
                        allMessages = conversation.allMessages,
                        activeMessageIds = rows.mapTo(hashSetOf()) { it.id },
                    ),
                loadMs = tIoAfterLoad - tIoBeforeLoad,
                transformMs = tIoAfterTransform - tIoAfterLoad,
            )
        }
        val messages = loaded.messages
        activeBranchMessageIds = messages.mapTo(hashSetOf()) { it.id }
        activeBranchPathIds = messages.map { it.id }
        refreshNovexRuntimeProjection()
        excludedBranchMemoryWrites = loaded.excludedMemoryWrites
        val ordered = loaded.ordered
        val tHangDiagAfterLoad = tHangDiagBeforeLoad + loaded.loadMs
        val tHangDiagAfterTransform = tHangDiagAfterLoad + loaded.transformMs
        println(
            "[T-HANG-DIAG] loadMessages session=$sessionId count=${messages.size} " +
                "tookMs=${loaded.loadMs}",
        )
        println(
            "[T-HANG-DIAG] toChatMessages session=$sessionId tookMs=${loaded.transformMs}",
        )
        // Per-message size sketch + oversize-row scan. Pure diagnostics —
        // does a full second pass over partsJson with several substring
        // searches per row, so on a 405-row session with 1MB total it
        // adds material main-thread time. Fire-and-forget on the IO
        // dispatcher so it can't contribute to the GC-storm hang the
        // rest of this task is trying to fix.
        viewModelScope.launch(Dispatchers.IO) {
            var totalChars = 0L
            var maxChars = 0
            var withTools = 0
            var withAttachments = 0
            for (m in messages) {
                val len = m.partsJson.length
                totalChars += len
                if (len > maxChars) maxChars = len
                // ContentPart serialises its discriminator in camelCase
                // ("toolUse" / "toolResult" — see ContentPart.PartType), so
                // the snake_case probe this used to run matched NOTHING and
                // reported toolMessages=0 on every session, including ones
                // whose history is almost entirely tool traffic. That is
                // the opposite of the signal this diagnostic exists to give
                // — it is here to finger oversized tool_result inlines as
                // the GC-storm culprit, and it was reporting them absent.
                if (m.partsJson.contains("\"toolUse\"") || m.partsJson.contains("\"toolResult\"")) {
                    withTools++
                }
                // Same casing trap: attachments serialise as "mediaRef",
                // never as "image"/"attachment".
                if (m.partsJson.contains("\"mediaRef\"")) {
                    withAttachments++
                }
            }
            println(
                "[T-HANG-DIAG] messages-shape session=$sessionId total=${messages.size} " +
                    "totalChars=$totalChars maxChars=$maxChars toolMessages=$withTools " +
                    "attachmentMessages=$withAttachments",
            )

            // [T-HANG-DIAG] for any message ≥ 50_000 chars, log size /
            // role / createdAt / structural type markers only — NEVER
            // the partsJson content (or any prefix/suffix of it). Earlier
            // versions echoed head500/tail500 to localise the culprit;
            // now that the cause is known (oversized tool_result inlines)
            // and FileReadTool / AIChatViewModel.executeFileRead enforce
            // an 80 KB hard cap upstream, only metadata is needed for
            // future audits.
            val OVERSIZE_THRESHOLD = 50_000
            val oversized = messages.filter { it.partsJson.length >= OVERSIZE_THRESHOLD }
            if (oversized.isNotEmpty()) {
                println(
                    "[T-HANG-DIAG] oversized-messages session=$sessionId " +
                        "count=${oversized.size} threshold=${OVERSIZE_THRESHOLD}",
                )
                for (m in oversized) {
                    val raw = m.partsJson
                    val len = raw.length
                    val hasToolUse = raw.contains("\"toolUse\"")
                    val hasToolResult = raw.contains("\"toolResult\"")
                    val hasImage = raw.contains("\"image\"") || raw.contains("\"image_url\"")
                    val hasBase64 = raw.contains("data:image") || raw.contains(";base64,")
                    println(
                        "[T-HANG-DIAG] oversized id=${m.id} role=${m.role} " +
                            "createdAt=${m.createdAt} len=$len " +
                            "hasToolUse=$hasToolUse hasToolResult=$hasToolResult " +
                            "hasImage=$hasImage hasBase64=$hasBase64 " +
                            "streamInterrupts=${m.streamInterruptCount}",
                    )
                }
            }
        }

        // Rebuild agentHistory from persisted messages.
        // Pre-built off-Main inside the withContext(Dispatchers.IO) block
        // above to avoid re-parsing partsJson on the UI thread.
        // 净眼 P2-1 关账："init 一次性"注释已失真（压缩回退/safe-mode 重试
        // 会重入）——重建入锁并推世代号。
        synchronized(historyWriteLock) {
            agentHistory.clear()
            agentHistory.addAll(loaded.llmHistory)
            historyGeneration.incrementAndGet()
        }
        activeNovexDocumentRefs = novexDocumentRefsInHistory(loaded.llmHistory)
        activeNovexSourceCollectionRefs = novexSourceCollectionRefsInHistory(loaded.llmHistory)
        closeNovexLearningResponsePreview()
        closeNovexLearningDetails()
        refreshNovexLearningTaskProjection()
        val tHangDiagAfterAgentHistory = System.currentTimeMillis()
        println(
            "[T-HANG-DIAG] agentHistory rebuilt session=$sessionId tookMs=${tHangDiagAfterAgentHistory - tHangDiagAfterTransform}",
        )

        // Restore the most-recent compact summary, if any, so the first
        // outgoing turn after reopening a compacted session still sees
        // the folded-away context via [effectiveAgentHistory]. Also gray
        // out every UI message that falls before the marker's boundary —
        // mirrors iOS Phase 2.5 restore (AIChatViewModel.swift:3360+).
        val marker = runCatching {
            chatRepository.latestActiveCompactMarker(sessionId, loaded.messages)
        }
            .onFailure { Log.w(ChatViewModel.TAG, "latestCompactMarker failed: ${it.message}") }
            .getOrNull()
        _compactSummary.value = marker?.summary
        // [T-stage3-snapshot] 会话载入恢复最新世界快照（压缩后台生成时刷新内存态）
        _worldSnapshot.value = novex.core.NovexStateSnapshot
            .loadLatest(context, sessionId)
        // [净眼 P1-2] 载入即加载随身笔记（跨重启常驻注入的缓存初始化；
        // 切换会话时本行同时完成"重置"——注入的永远是本会话的笔记）
        _sessionMemory.value = memoryStoreFor(sessionId).load()
        _cachedLatestMarker = marker

        com.openminis.app.diagnostics.PerfLongCtx.step(
            sessionId,
            "stateflow.emit.begin",
            "count=${ordered.size}",
        )
        // [T-android-larky-longsession-followup] Reset the tail
        // window to its initial cap on every session (re)load. Without
        // this a freshly opened session would inherit the previous
        // session's enlarged cap (set via loadOlderMessages), defeating
        // the windowing intent on the first paint of every new session.
        _visibleMessageCap.value = ChatViewModel.INITIAL_VISIBLE_MESSAGE_CAP
        _messages.value = if (marker == null) {
            ordered
        } else {
            // Phase 2.5: build the historyDbIds set used by the
            // createdAt self-heal to filter to anchors that are
            // actually represented in agentHistory. Mirrors iOS
            // AIChatViewModel+Persistence.swift:406-408.
            val historyDbIds: Set<String> = buildSet {
                for (m in loaded.llmHistory) {
                    m.dbMessageId?.takeIf { it.isNotEmpty() }?.let { add(it) }
                }
            }
            applyCompactMarkerGraying(ordered, marker, loaded.messages, historyDbIds)
        }

        prepareNovexConversationForEntry()

        // Cold-start interrupt detection: an agent loop that was killed by
        // the OS (or app force-quit) leaves agentHistory in one of two
        // tell-tale shapes. Detecting any of them lets the user tap
        // Resume to pick up where the model left off — the in-memory
        // [_canResume] flag set by [handleUserCancelledCleanup] is lost
        // across cold starts so we have to re-derive it from the DB.
        // Mirrors iOS AIChatViewModel.loadSession lines 3546-3581.
        //   Case A: last entry is user with all-toolResult parts —
        //           tools completed but the next model call never fired.
        //   Case B: last entry is assistant with any tool_use parts —
        //           the model requested tools that never executed.
        val lastEntry = agentHistory.lastOrNull()
        if (lastEntry != null && !_isStreaming.value) {
            val partTypes = lastEntry.contentParts.map { part ->
                when (part) {
                    is AgentContentPart.ToolResult -> "toolResult"
                    is AgentContentPart.ToolUse -> "toolUse"
                    else -> "text"
                }
            }
            val isInterrupted = isInterruptedAgentTail(lastEntry.role.name, partTypes)
            if (isInterrupted) {
                _canResume.value = true
                Log.i(ChatViewModel.TAG, "loadSession: detected interrupted agent loop, canResume=true (lastRole=${lastEntry.role})")
            }
        }
        } finally {
            // T201: open the gate even on early `return@launch` (draft path,
            // missing-session path) and on exception, so the init-time
            // config.collect can never deadlock waiting for us.
            sessionLoaded.value = true
            // [T-HANG-DIAG] total time spent in loadSession from ENTER to
            // either successful completion or early return. tHangDiagStart
            // was captured just inside `try` so this covers the whole
            // body the user perceives as "loading".
            println(
                "[T-HANG-DIAG] loadSession EXIT session=$sessionId " +
                    "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
            com.openminis.app.diagnostics.PerfLongCtx.step(
                sessionId,
                "loadSession.exit",
                "totalMs=${System.currentTimeMillis() - tHangDiagStart}",
            )
        }
    }
}

/**
 * Mark every non-system UI message that falls before [marker]'s boundary
 * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
 * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
 * over iOS for the compactAll case:
 *
 *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
 *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
 *   3) Both null → compactAll. iOS naively places the divider at the end
 *      and grays every loaded UI message, which incorrectly gray-scales
 *      messages persisted AFTER the marker (e.g. follow-up turns sent
 *      between compact and reload). We instead use
 *      `lastCompactedMessageId` to find the last message included in the
 *      compacted range — anything after it stays active. The divider is
 *      placed immediately after that boundary.
 */
/**
 * Phase 2.5 marker restore (Android port of iOS
 * AIChatViewModel+Persistence.swift:236+).
 *
 * Resolution order (mirrors iOS exactly):
 *   1. v2 marker (`version >= 2`) — use `lastCompactedMessageId`
 *      via sourceDbIds range → divider AFTER that UI row
 *   2. v1 compactAll-shape (firstKept/boundary both null,
 *      lcmId set) — same as 1
 *   3. v1 compactBefore (firstKeptMessageId / boundaryMessageId
 *      set) — divider BEFORE that boundary row
 *   4. **createdAt self-heal** — find the last raw with
 *      `createdAt < marker.createdAt` whose id is still in
 *      agentHistory, use it as the new anchor, REWRITE the
 *      marker as v2 + write back to DB. Next load takes the
 *      v2 fast path (no heal needed).
 *   5. Final fallback — insert divider at idx=0, gray NOTHING.
 *      This deliberately differs from the pre-T-compact-v2
 *      behaviour of "divider at bottom, gray everything" which
 *      grayed newly-sent messages on every reload (the
 *      user-reported "divider at top, new messages keep
 *      turning gray" symptom).
 *
 * Suspending because the self-heal path writes back through
 * the DAO. Caller (loadSession) is already on a coroutine.
 */
internal suspend fun ChatViewModel.applyCompactMarkerGraying(
    messages: List<ChatMessage>,
    marker: novex.android.data.chat.CompactMarkerRow,
    rawMessages: List<novex.android.data.chat.MessageRow>,
    historyDbIds: Set<String>,
): List<ChatMessage> {
    if (marker.version >= 3 && (marker.firstKeptMessageId !in historyDbIds || marker.lastCompactedMessageId !in historyDbIds)) {
        return messages
    }
    // Some legacy rows have empty-string boundaries instead of NULL —
    // treat both as "no boundary" so the compactAll path below kicks in.
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
    val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

    // ─── Resolve insertIdx ────────────────────────────────────────
    //
    // insertIdx semantics: messages[0 until insertIdx] become grayed
    // (isCompactedHistory=true); the divider sits at insertIdx;
    // messages[insertIdx..] stay active.
    //
    // Special value -1 → "unresolved": skip the rewrite below and
    // return the messages untouched with no divider (the marker is
    // effectively invisible until the user reverts or self-heals).
    // Used when even createdAt fallback fails — better to show no
    // divider than to incorrectly gray live messages.
    var insertIdx = -1
    var healedMarker: novex.android.data.chat.CompactMarkerRow? = null

    // Helper: locate the UI message whose sourceDbIds (or id) contains
    // the given dbId. Matches iOS uiIndexForAnchorRaw, which scans by
    // sourceSortOrder range; Android's equivalent is sourceDbIds.
    fun ChatViewModel.uiIdxForDbId(dbId: String): Int =
        messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

    if (firstKeptId == null) {
        // v2 OR v1 compactAll-shape — anchored by lcmId.
        val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
        if (lcmIdx >= 0) {
            // Happy path: lcmId resolves directly. Divider AFTER anchor.
            insertIdx = lcmIdx + 1
        } else {
            // lcmId missing or orphaned. Try createdAt self-heal.
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                        "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                        "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                )
            } else {
                // Even createdAt heal failed. Place divider at top
                // with NO graying — this is iOS's "insertIdx=0, no
                // gray" branch (Persistence.swift:350-351). The
                // pre-T-compact-v2 behaviour of "cutoff = lastIndex,
                // gray everything" produced the user-reported bug:
                // every new message also fell within [0..cutoff]
                // and was repeatedly grayed on each reload.
                insertIdx = 0
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                        "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                )
            }
        }
    } else {
        // v1 compactBefore — anchored by firstKeptId. Divider BEFORE
        // the boundary; boundary is the first active message.
        val bIdx = messages.indexOfFirst { msg ->
            firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
        }
        if (bIdx >= 0) {
            insertIdx = bIdx
        } else {
            // Boundary deleted / orphaned. Try createdAt self-heal —
            // same path as compactAll, then divider AFTER the healed
            // anchor (treating this as an upgrade to v2 compactAll
            // semantics).
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                        "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                )
            } else {
                insertIdx = 0
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                        "divider at top, no graying",
                )
            }
        }
    }

    // ─── Persist healed marker (if any) ───────────────────────────
    //
    // Run BEFORE building the UI list so a future loadSession() picks
    // up the v2 fast path. Failure here is non-fatal — UI still
    // renders against the in-memory healed pointer.
    if (healedMarker != null) {
        runCatching { chatRepository.dao.rewriteMarker(healedMarker) }
            .onFailure { Log.w(ChatViewModel.TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
        // Refresh in-memory cache so effectiveAgentHistory and the
        // next compact pass see the upgraded marker. The caller
        // (loadSession) sets _cachedLatestMarker = marker BEFORE
        // calling us, so overwrite with the healed one now.
        _cachedLatestMarker = healedMarker
        _compactSummary.value = healedMarker.summary
    }

    // ─── Apply graying ────────────────────────────────────────────
    val grayed: List<ChatMessage> = if (insertIdx <= 0) {
        // No graying — either explicit no-gray branch or boundary at
        // index 0 (nothing to gray).
        messages
    } else {
        messages.mapIndexed { idx, msg ->
            if (idx >= insertIdx) msg
            else if (msg.role == "system") msg
            else if (msg.isCompactedHistory) msg
            else msg.copy(isCompactedHistory = true)
        }
    }

    // ─── Insert divider row ───────────────────────────────────────
    // T126-marker: match iOS `"\(insertIdx) messages compacted"`
    // (AIChatViewModel.swift:3432). Count = number of UI bubbles
    // above the divider, not marker.compactedCount (which counts raw
    // agentHistory entries — tool_use/tool_result pairs that never
    // appear as their own UI bubble).
    val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
        .count { grayed[it].role != "system" }
    val dividerLabel = "$compactedUICount messages compacted"
    val markerForDivider = healedMarker ?: marker
    val dividerBlock = AssistantBlock(
        id = "compact-divider-${markerForDivider.id}",
        kind = "info",
        content = dividerLabel,
        toolName = "compact",
        toolArgs = markerForDivider.summary,
    )
    val dividerMsg = ChatMessage(
        id = "compact-divider-msg-${markerForDivider.id}",
        role = "system",
        content = "",
        toolBlocks = listOf(dividerBlock),
    )
    val withDivider = grayed.toMutableList()
    withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
    return withDivider
}

/**
 * createdAt self-heal: return the LAST raw message whose
 * `createdAt < markerCreatedAt` AND whose id is still represented in
 * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
 * empty (no dbIds collected — unusual), the filter degrades to "just
 * the createdAt predicate" so we still recover SOMETHING.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:125.
 */
internal fun ChatViewModel.anchorByCreatedAt(
    rawMessages: List<novex.android.data.chat.MessageRow>,
    markerCreatedAt: Long,
    historyDbIds: Set<String>,
): novex.android.data.chat.MessageRow? {
    return rawMessages.lastOrNull { raw ->
        raw.createdAt < markerCreatedAt &&
            (historyDbIds.isEmpty() || raw.id in historyDbIds)
    }
}

/**
 * Build a healed v2 marker that preserves identity (id, sessionId,
 * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
 * to the recomputed anchor, zeroes legacy fields, and bumps `version`
 * to 2. Future loads resolve through the corrected lcmId directly
 * without re-running the createdAt fallback.
 *
 * Mirrors iOS AIChatViewModel+Compaction.swift:150.
 */
internal fun ChatViewModel.rewriteMarkerForHeal(
    original: novex.android.data.chat.CompactMarkerRow,
    newAnchor: novex.android.data.chat.MessageRow,
    lastRaw: novex.android.data.chat.MessageRow?,
): novex.android.data.chat.CompactMarkerRow {
    // Legacy sort-order fallback writes a past-end sentinel so any
    // hypothetical v1 reader sees "everything compacted, nothing
    // kept" (graceful degradation, no overlap with live tail).
    // Android's MessageRow doesn't carry a sortOrder column —
    // use Int.MAX_VALUE like the original compactAll write path.
    return original.copy(
        firstKeptSortOrder = Int.MAX_VALUE,
        boundaryMessageId = null,
        firstKeptMessageId = null,
        lastCompactedMessageId = newAnchor.id,
        uiBoundarySortOrder = null,
        version = 2,
    )
}

internal fun ChatViewModel.bindChatEntry(entry: novex.android.data.model.ModelEntry,
    instance: novex.android.data.model.ProviderInstance, apiKey: String) {
    // [T-opencode-sunset]（净眼 P2）迁移生效后 sunset 实例在此失败，
    // 到不了 403 映射——绑定层直接给人话，旧会话用户看到的不是
    // "配置不一致"而是明确的停服提示。
    require(instance.isEnabled && entry.providerInstanceId == instance.id) {
        if (com.openminis.app.data.repository.isOpenCodeFreeInstanceId(instance.id)) {
            "OpenCode 免费模型已停止服务：官方已限制仅 OpenCode 客户端内使用，请切换其他模型"
        } else {
            "模型连接已关闭或配置不一致"
        }
    }
    require(novex.android.data.model.ChatModelSelection.eligible(entry)) { "请选择聊天模型，生图模型不能用于此对话" }
    val resolved = ProviderFactory.create(instance, apiKey, entry.model, context)
    check(resolved.model == entry.model) { "模型连接与所选配置不一致" }
    currentProvider = resolved
    currentModel = resolved.model
    _activeEntryId.value = entry.id
    _modelName.value = resolved.model.displayName
    _providerName.value = instance.label.ifEmpty { resolved.model.provider }
}

internal fun ChatViewModel.tryBindChatEntry(entry: novex.android.data.model.ModelEntry,
    instance: novex.android.data.model.ProviderInstance, apiKey: String): Boolean = try {
    bindChatEntry(entry, instance, apiKey)
    true
} catch (failure: Exception) {
    _error.value = failure.message ?: "所选模型无法连接"
    false
}

/** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
internal fun ChatViewModel.restoreFromBinding(bindingJson: String?): Boolean {
    bindingJson ?: return false
    return try {
        val obj = org.json.JSONObject(bindingJson)
        when (obj.optString("type")) {
            "group" -> {
                val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                if (resolved) _selectedGroupId.value = groupId
                resolved
            }
            "entry" -> {
                val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
                val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
                val apiKey = providerRepository.usableApiKey(instance) ?: return false
                bindChatEntry(entry, instance, apiKey)
                _selectedGroupId.value = null
                _selectedGroupName.value = ""
                _contextEstimated.value = true
                _lastTurnContextTokens.value = 0
                _contextUsageReady.value = false

                true
            }
            else -> false
        }
    } catch (e: Exception) {
        // [T-opencode-sunset]（净眼 P2 复审）绑定失败默认静默回落默认
        // 模型/组；但 sunset 实例的旧会话用户必须看到停服原因——
        // 一次性置错误横幅，不阻断回落。
        if (e is IllegalArgumentException &&
            e.message?.contains("OpenCode 免费模型已停止服务") == true
        ) {
            _error.value = e.message
        }
        false
    }
}

internal fun ChatViewModel.resolveProviderFromGroup(groupId: String, preferredEntryId: String? = null): Boolean {
    val group = providerRepository.group(groupId) ?: return false
    // [T-disabled-provider-via-group-android] Resolve through
    // enabledMemberEntries so a member whose provider instance is
    // currently disabled is silently skipped. Without this, a disabled
    // provider sitting at the head of memberEntryIds got loaded and
    // ChatViewModel would attempt to call it — the whole point of
    // disabling the provider was to stop that.
    //
    // preferredEntryId comes from a prior session binding ("user picked
    // this entry inside the group last time"). Honor it only if the
    // entry is still enabled; otherwise fall back to the first enabled
    // member so the session can still proceed on a now-degraded group.
    val enabledMembers = novex.android.data.model.ChatModelSelection.members(providerRepository.config.value, group)
    if (enabledMembers.isEmpty()) return false
    val targetEntry = if (preferredEntryId != null) {
        enabledMembers.firstOrNull { it.id == preferredEntryId } ?: enabledMembers.first()
    } else {
        enabledMembers.first()
    }
    val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
    val apiKey = providerRepository.usableApiKey(instance) ?: return false

    if (!tryBindChatEntry(targetEntry, instance, apiKey)) return false
    _selectedGroupName.value = group.name
    _contextEstimated.value = true
    _lastTurnContextTokens.value = 0
    _contextUsageReady.value = false
    return true
}

fun ChatViewModel.selectGroup(groupId: String) {
    if (_isStreaming.value) {
        _error.value = "请先停止当前回复，再切换模型"
        return
    }
    val resolved = resolveProviderFromGroup(groupId)
    if (resolved) {
        _selectedGroupId.value = groupId
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${_activeEntryId.value}"}""")
        applyGroupSessionDefaults(groupId)
    }
}

/** Select a specific entry within a group (keeps group selected). */
fun ChatViewModel.selectGroupEntry(groupId: String, entryId: String) {
    if (_isStreaming.value) {
        _error.value = "请先停止当前回复，再切换模型"
        return
    }
    val entry = providerRepository.config.value.modelEntries.find { it.id == entryId }
    if (entry == null || !novex.android.data.model.ChatModelSelection.eligible(entry)) {
        _error.value = "请选择聊天模型，生图模型不能用于此对话"
        return
    }
    val resolved = resolveProviderFromGroup(groupId, entryId)
    if (resolved) {
        _selectedGroupId.value = groupId
        persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"${_activeEntryId.value}"}""")
        applyGroupSessionDefaults(groupId)
        // [T-newchat-default-model-fallback-android] Record the actually-
        // resolved active entry as last-used (resolveProviderFromGroup may
        // fall back off a disabled member, so _activeEntryId is the truth).
        _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
    }
}

/**
 * T312: mirrors iOS `AIChatViewModel.applyGroupSessionDefaults`.
 * When a session newly binds to a group (user picks the group, or a
 * draft session resolves the default group), copy the group's
 * `defaultThinkingLevel` into the session's persisted thinking_override.
 * Context limit is in-memory only on iOS; Android has no equivalent
 * runtime field yet, so we only handle thinking level here.
 *
 * Skips when the group has no default override (null) — leaves the
 * session's existing override untouched so manual user choices on a
 * pre-bound chat aren't clobbered by a later group re-select that
 * happens to land on the same default state.
 */
internal fun ChatViewModel.applyGroupSessionDefaults(groupId: String) {
    val group = providerRepository.group(groupId) ?: return
    val level = group.defaultThinkingLevel ?: return
    if (_thinkingLevel.value == level) return
    _thinkingLevel.value = level
    viewModelScope.launch {
        val sid = ensureSession()
        chatRepository.dao.setThinkingChoice(sid, level.name)
    }
}

/**
 * [T-newchat-default-model-fallback-android] Resolve and apply the default
 * model for a NEW chat when no default group produced a model. Fallback
 * chain tiers 2→3 (tier 1, the default group, is handled by the caller
 * before this runs):
 *
 *   2) last-used model — the entry the user last actively selected / used,
 *      if it still exists, is visible, and its provider is enabled.
 *   3) newest provider's newest text-output model — the final catch-all so
 *      a first-ever chat with providers but no group/last-used still gets a
 *      sensible, text-capable default (image/audio-only models excluded).
 *
 * Sets currentModel / currentProvider / the name + activeEntry state flows.
 * Returns true when a model was applied. Mirrors iOS #636. The legacy
 * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
 * which ignored both last-used and add-order — replaced by this chain.
 */
internal fun ChatViewModel.applyNewChatDefaultModel(): Boolean {
    val entry = providerRepository.lastUsedVisibleEntry()
        ?: providerRepository.newestProviderNewestTextEntry()
        ?: return false
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
    val apiKey = providerRepository.usableApiKey(instance) ?: return false
    if (!tryBindChatEntry(entry, instance, apiKey)) return false
    _contextEstimated.value = true
    _lastTurnContextTokens.value = 0
    _contextUsageReady.value = false
    return true
}

/** Select a specific model entry (bypasses group selection). */
fun ChatViewModel.selectEntry(entryId: String) {
    if (_isStreaming.value) {
        _error.value = "请先停止当前回复，再切换模型"
        return
    }
    val config = providerRepository.config.value
    val entry = config.modelEntries.find { it.id == entryId } ?: return
    val instance = providerRepository.instance(entry.providerInstanceId) ?: return
    val apiKey = providerRepository.usableApiKey(instance) ?: return

    if (!novex.android.data.model.ChatModelSelection.eligible(entry)) {
        _error.value = "请选择聊天模型，生图模型不能用于此对话"
        return
    }
    if (!tryBindChatEntry(entry, instance, apiKey)) return
    _selectedGroupId.value = null
    _selectedGroupName.value = ""
    _contextEstimated.value = true
    _lastTurnContextTokens.value = 0
    _contextUsageReady.value = false
    persistBinding("""{"type":"entry","entryId":"$entryId"}""")
    // [T-newchat-default-model-fallback-android] Remember this as the
    // global last-used model so the NEXT new chat (when no default group
    // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
    providerRepository.lastUsedEntryId = entryId
}

/** Persist the model binding to the DB session (no-op for draft sessions). */
internal fun ChatViewModel.persistBinding(bindingJson: String) {
    val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
    val modelId = currentModel?.id ?: return
    viewModelScope.launch {
        chatRepository.rebindSessionModel(sid, bindingJson, modelId)
    }
}

internal fun ChatViewModel.findModelEntry(modelId: String) =
    providerRepository.allVisibleEntries().filter { it.model.id == modelId && novex.android.data.model.ChatModelSelection.eligible(it) }.singleOrNull()

/**
 * Build the ordered list of fallback providers for the current group,
 * starting AFTER the primary provider in the member list and cycling around.
 * This ensures that models already tried (before the primary) are at the end,
 * not the beginning — so retry doesn't re-trigger the same fallback chain.
 */
/**
 * [T-android-fallback-entry-identity] A fallback candidate, carrying the
 * ENTRY it was built from.
 *
 * The entry id is the only unambiguous identity: two different provider
 * instances can expose the SAME `model.id` (observed in the field:
 * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
 * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
 * fact by matching `model.id` therefore picks whichever entry happens to
 * come first in `modelEntries`, which is not necessarily the one that served
 * the request.
 */
internal fun ChatViewModel.installStreamFallback(previous: ChatViewModel.FallbackCandidate, next: ChatViewModel.FallbackCandidate) {
    currentProvider = next.provider
    _modelName.value = next.provider.model.displayName
    val entry = providerRepository.config.value.modelEntries.find { it.id == next.entryId }
    if (entry != null) {
        _contextEstimated.value = true
        _lastTurnContextTokens.value = 0
        _contextUsageReady.value = false
        _activeEntryId.value = entry.id
        currentModel = next.provider.model
        providerRepository.instance(entry.providerInstanceId)?.let {
            _providerName.value = it.label.ifEmpty { entry.model.provider }
        }
        val binding = JSONObject()
        val group = _selectedGroupId.value
        if (group != null) binding.put("type", "group").put("groupId", group).put("lastEntryId", entry.id)
        else binding.put("type", "entry").put("entryId", entry.id)
        persistBinding(binding.toString())
    }
    if (previous.provider.model.id != next.provider.model.id) _fallbackTrigger.value++
}

internal fun ChatViewModel.buildFallbackProviders(primaryProvider: LLMProvider): List<ChatViewModel.FallbackCandidate> {
    val groupId = _selectedGroupId.value ?: return emptyList()
    val config = providerRepository.config.value
    val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
    val members = group.memberEntryIds
    // Find current provider's position in the group.
    // [T-android-fallback-entry-identity] Prefer the ACTIVE ENTRY id — the
    // model-id match below is ambiguous when two instances share a model id
    // and would anchor the cycle at the wrong member.
    val activeEntry = _activeEntryId.value
    val currentIdx = members.indexOfFirst { it == activeEntry }.takeIf { it >= 0 }
        ?: members.indexOfFirst { entryId ->
            config.modelEntries.find { it.id == entryId }?.model?.id == primaryProvider.model.id
        }
    val result = mutableListOf<ChatViewModel.FallbackCandidate>()
    // Iterate starting from the entry AFTER the primary, cycling around
    for (offset in 1 until members.size) {
        val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
        val entryId = members[idx]
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        if (!novex.android.data.model.ChatModelSelection.eligible(entry)) continue
        // Tool-disabled models are a strict pure-chat boundary. Crossing it
        // during fallback could reintroduce tool traffic into a clean turn.
        if (!hasSameToolMode(primaryProvider.model, entry.model)) continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        if (!instance.isEnabled) continue
        val apiKey = providerRepository.usableApiKey(instance) ?: continue
        val p = try {
            ProviderFactory.create(instance, apiKey, entry.model, context)
        } catch (_: Exception) { continue }
        result.add(ChatViewModel.FallbackCandidate(provider = p, entryId = entry.id))
    }
    return result
}

/**
 * Group members that fallback skipped (disabled instance / missing
 * credential / hidden entry), with reasons. Mirrors iOS
 * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
 * needs to know WHY the other group members never got tried — e.g. the
 * Claude subscription was logged out, so every Anthropic entry was
 * silently filtered and fallback kept cycling OpenAI-only.
 */
internal fun ChatViewModel.unavailableGroupMembers(): List<String> {
    val groupId = _selectedGroupId.value ?: return emptyList()
    val config = providerRepository.config.value
    val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
    val result = mutableListOf<String>()
    for (entryId in group.memberEntryIds) {
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        if (!entry.model.isTextOutput || novex.android.data.model.ChatModelSelection.imageOutput(entry.model) ||
            novex.android.data.model.ChatModelSelection.imageOutput(entry.baseModel)) continue
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
        val label = instance.label.ifEmpty { entry.model.provider }
        val reason = when {
            entry.isHidden -> "Hidden"
            !instance.isEnabled -> "Disabled"
            providerRepository.usableApiKey(instance) == null -> "Not logged in"
            else -> continue
        }
        result.add("⚠️ ${entry.model.displayName} ($label): $reason")
    }
    return result
}

internal fun ChatViewModel.resolveNextFallbackProvider(): LLMProvider? {
    val groupId = _selectedGroupId.value ?: return null
    val group = providerRepository.group(groupId) ?: return null
    val currentEntryId = _activeEntryId.value ?: return null
    val currentIdx = group.memberEntryIds.indexOf(currentEntryId)
    if (currentIdx < 0) return null

    val config = providerRepository.config.value
    // Try next entries in the group
    for (i in 1 until group.memberEntryIds.size) {
        val nextIdx = (currentIdx + i) % group.memberEntryIds.size
        val entryId = group.memberEntryIds[nextIdx]
        val entry = config.modelEntries.find { it.id == entryId } ?: continue
        if (!novex.android.data.model.ChatModelSelection.eligible(entry)) continue
        if (currentModel?.let { !hasSameToolMode(it, entry.model) } == true) continue
        val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
        // [T-disabled-provider-via-group-android] Skip disabled
        // providers when walking the group's fallback chain so a
        // disabled provider sitting after the current entry doesn't
        // get picked up. buildFallbackProviders already does this; the
        // single-step variant here had the same bug.
        if (!instance.isEnabled) continue
        val apiKey = providerRepository.usableApiKey(instance) ?: continue

        bindChatEntry(entry, instance, apiKey)
        val provider = requireNotNull(currentProvider)
        val binding = JSONObject().put("type", "group").put("groupId", groupId).put("lastEntryId", entry.id)
        persistBinding(binding.toString())
        return provider
    }
    return null
}

// [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
// moved to ChatViewModelUiStateExt.kt (extension functions).

/**
 * T137: Wipe in-memory and on-disk message state for the current session
 * without touching the session's chat files (workspace/, attachments/,
 * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
 * same "files survive" guarantee.
 *
 * Cancels any in-flight stream first so the UI doesn't race the wipe.
 */
