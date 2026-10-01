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

// 历史行投影：MessageRow → ChatMessage / LLMMessage，工具标签与参数解析。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结。

internal fun List<MessageRow>.toChatMessages(
        vm: ChatViewModel,
    branchGraph: com.openminis.app.data.ConversationBranchGraph? = null,
    contextUsageByRequest: Map<String, ContextUsageRecord> = emptyMap(),
): List<ChatMessage> {
    // First pass: extract all toolResult data keyed by toolUseId
    val toolResultMap = mutableMapOf<String, ToolResultData>()
    for (entity in this) {
        if (entity.role != "user") continue
        try {
            val array = org.json.JSONArray(entity.partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("type") == "toolResult") {
                    val value = obj.getJSONObject("value")
                    val toolUseId = value.optString("toolUseId", "")
                    if (toolUseId.isNotEmpty()) {
                        toolResultMap[toolUseId] = ToolResultData(
                            output = value.optString("output", ""),
                            success = value.optBoolean("success", true),
                        )
                    }
                }
            }
        } catch (_: Exception) { /* skip malformed */ }
    }

    // Second pass: convert messages, merging tool results into blocks
    // Filter out user messages that only contain toolResult parts (no visible text)
    return mapNotNull { entity ->
        var text = ""
        val blocks = mutableListOf<AssistantBlock>()
        // T128: media attachments persisted under user messages as `mediaRef`
        // parts. Restored to file:// URIs (stable across app restarts) and
        // their original filenames so UserAttachmentList renders the same
        // tiles after a session reload.
        val restoredImageUris = mutableListOf<Uri>()
        val restoredAttachmentNames = mutableListOf<String>()
        // T150: file:// URIs of restored non-image attachments, in the
        // same order as the non-image suffix of restoredAttachmentNames.
        // Powers the user-bubble file chip → FilePreviewScreen tap after
        // a session reload.
        val restoredAttachmentUris = mutableListOf<Uri>()

        // [T-stage1-activation] 持久化 system 行 = 开局资料包：读回为居中
        // divider + ⓘ 详情 sheet（与 compact divider 同管线）。text part
        // 全文进 toolArgs，标签固定短文案。
        if (entity.role == "system") {
            val packageText = runCatching {
                org.json.JSONArray(entity.partsJson).optJSONObject(0)?.takeIf {
                    it.optString("type") == "text"
                }?.optString("value").orEmpty()
            }.getOrDefault("")
            // [净眼 P2-2] 提前返回：全文只进 ⓘ sheet，绝不落入 content——
            // 否则后续通用 text 循环会把它打进正文，经 legacy 回退把整包
            // 资料渲染成 markdown 块。
            return@mapNotNull ChatMessage(
                id = entity.id,
                role = "system",
                content = "",
                toolBlocks = listOf(AssistantBlock(
                    id = "activation:${entity.id}",
                    kind = "info",
                    content = "已载入开局资料",
                    toolName = com.openminis.app.cards.NovexCardActivation.SYSTEM_ICON_KIND,
                    toolArgs = packageText,
                    toolStatus = ToolBlockStatus.SUCCESS,
                )),
                sourceDbIds = listOf(entity.id),
            )
        }

        if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
            blocks.add(AssistantBlock(
                id = "thinking_restored_${entity.id}",
                kind = "thinking",
                content = entity.reasoningContent,
                toolTitle = "Thinking",
                toolStatus = ToolBlockStatus.SUCCESS,
            ))
        }

        try {
            val array = org.json.JSONArray(entity.partsJson)
            var textBlockCounter = 0
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val raw = obj.optString("value", "")
                        // Strip <system-reminder>...</system-reminder> blocks
                        // here only — agentHistory in memory and the DB row
                        // both keep the raw text, so the LLM still sees the
                        // reminder on subsequent turns. UI just hides it.
                        // If a part was *only* a reminder, the cleaned
                        // string is empty and we skip it so we don't render
                        // a phantom blank text block.
                        // [T-android-retry-attachment-loss] Also strip the
                        // now-persisted <user-attached-files> XML so it
                        // doesn't render in the user bubble (file chips come
                        // from mediaRef parts). The DB row + agentHistory
                        // keep the raw XML so the model still sees paths.
                        val t = stripAgentAttachmentMetadata(vm.stripSystemReminders(raw)).let {
                            if (it != raw) it.trim() else it
                        }
                        if (t.isEmpty()) continue
                        text += t
                        // For assistant messages, also push the text as a block so
                        // the renderer can preserve the original text↔tool ordering.
                        // For user messages we keep using the `text` field only.
                        if (entity.role == "assistant") {
                            blocks.add(AssistantBlock(
                                id = "text_restored_${entity.id}_${textBlockCounter++}",
                                kind = "text",
                                content = t,
                                // Preserve an explicit process-channel marker
                                // written by the runtime.  Do not infer it
                                // merely because this assistant message also
                                // contains a tool; that older heuristic is
                                // what caused normal story text to disappear.
                                executionText = obj.optBoolean("execution", false),
                            ))
                        }
                    }
                    NOVEX_STORY_IMAGE -> {
                        if (entity.role == "assistant") runCatching {
                            novex.core.NovexSnapshotMediaCodec.decode(obj.getJSONObject("value"))
                        }.getOrNull()?.let { blocks.add(storyImageBlock(it, entity.id)) }
                    }
                    "novexCardTask" -> {
                        val value = obj.optJSONObject("value")
                        if (entity.role == "assistant" && value != null) blocks.add(AssistantBlock(
                            id = "card-task:${entity.id}", kind = "info", content = value.optString("label"),
                            toolName = NovexCardCreationTask.MARKER, toolArgs = value.toString()))
                    }
                    "toolUse", "uiToolUse" -> {
                        val value = obj.getJSONObject("value")
                        val toolId = value.optString("toolUseId", "")
                        if (toolId.startsWith("thinking_")) continue
                        val toolInput = value.optString("input", "")
                        // Merge tool result output (iOS: block.content = tr.output)
                        val operationRecord = runCatching {
                            val operation = novex.core.NovexToolOperation(
                                entity.sessionId, entity.id, toolId, value.optString("name"), toolInput, "")
                            vm.novexOperationJournal.read(operation.id)
                        }.getOrNull()
                        val result = toolResultMap[toolId] ?: operationRecord?.result?.let {
                            ToolResultData(it.output, it.success)
                        }
                        val pageURL = value.optString("pageURL", "").ifEmpty { null }
                        val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                        blocks.add(AssistantBlock(
                            id = toolId,
                            kind = "tool_use",
                            toolName = value.optString("name", ""),
                            toolTitle = value.optString("description", ""),
                            toolArgs = toolInput,
                            content = result?.output?.lines()?.takeLast(80)?.joinToString("\n")
                                ?: restoredOperationNotice(operationRecord),
                            toolStatus = when {
                                result == null && obj.optString("type") == "uiToolUse" -> ToolBlockStatus.SUCCESS
                                result == null -> restoredOperationStatus(operationRecord)
                                !result.success && (
                                    result.output.startsWith(ChatViewModel.CANCELLED_MARKER) ||
                                        result.output.startsWith(ChatViewModel.LEGACY_CANCELLED_MARKER)
                                ) -> ToolBlockStatus.CANCELLED
                                result.success -> ToolBlockStatus.SUCCESS
                                else -> ToolBlockStatus.FAILED
                            },
                            browserURL = pageURL,
                            imageFilePath = imgPath,
                            // [T-android-gemini3-thoughtsig / #179] Restore the
                            // persisted signature onto the rebuilt block.
                            thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            executionArgs = if (value.has("executionInput") && !value.isNull("executionInput")) value.getString("executionInput") else null,
                        ))
                    }
                    "mediaRef" -> {
                        if (entity.role != "user") continue
                        val value = obj.optJSONObject("value") ?: continue
                        val rel = value.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val file = java.io.File(vm.mediaStore.mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val mime = value.optString("mimeType", "")
                        val name = value.optString("originalFileName", "").ifEmpty { file.name }
                        // T150: branch on mime so non-image mediaRefs land
                        // in the file-chip column instead of polluting
                        // imageUris (which feeds the image gallery).
                        if (mime.startsWith("image/")) {
                            restoredImageUris.add(Uri.fromFile(file))
                        } else {
                            restoredAttachmentUris.add(Uri.fromFile(file))
                        }
                        restoredAttachmentNames.add(name)
                    }
                    // toolResult in user messages handled in first pass above
                }
            }
        } catch (e: Exception) {
            // T-PARTS-FALLBACK: previously this catch dumped the entire
            // partsJson into `text` as a degraded fallback. That meant
            // any malformed (or unexpectedly large) row rendered its
            // raw JSON — including any inlined base64 — as a plain
            // user/assistant bubble, which then locked up Compose's
            // StaticLayout for tens of seconds (see HangDetector report
            // for session e84882d7 / 820 KB partsJson). Replace with a
            // short, fixed-size placeholder so the row still appears
            // (so the user can delete or scroll past it) but no longer
            // pulls megabytes through the layout pass.
            Log.w(
                ChatViewModel.TAG,
                "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                    "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
            )
            text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                "${entity.partsJson.length} bytes)"
        }

        // Skip user messages with no visible content (toolResult-only internal messages,
        // or messages that were entirely a system-reminder). A user message that is
        // *only* an image attachment (no caption) still has visible content and must
        // not be skipped — restoredImageUris carries it.
        if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
        // Skip assistant messages that became empty after stripping system-reminders
        // and have no tool / thinking blocks to fall back on — would otherwise
        // render as a phantom blank assistant bubble.
        if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty()) return@mapNotNull null
        val sibling = branchGraph?.siblingPosition(entity.id)
        ChatMessage(
            id = entity.id,
            role = entity.role,
            content = text,
            novexContextUsage = contextUsageByRequest[entity.id],
            imageUris = restoredImageUris,
            attachmentNames = restoredAttachmentNames,
            attachmentUris = restoredAttachmentUris,
            toolBlocks = blocks,
            sourceDbIds = listOf(entity.id),
            branchAnchorDbId = entity.id,
            branchIndex = sibling?.index ?: 1,
            branchCount = sibling?.count ?: 1,
            // [T-error-persist-android] Restore the persisted terminal error
            // so the inline error banner + Retry button survive a reload.
            // Coalesce a blank value to null: the UI gate is `error?.let`, so
            // a non-null "" would render an empty banner. Defends against any
            // legacy/other-writer "" row.
            error = entity.errorInfo?.takeIf { it.isNotBlank() },
        )
    }.let { messages ->
        // Merge consecutive assistant messages into one:
        // agent loop persists each turn separately, but UI should show them as a single message.
        val merged = mutableListOf<ChatMessage>()
        for (msg in messages) {
            val prev = merged.lastOrNull()
            if (msg.role == "assistant" && prev?.role == "assistant") {
                // Merge: combine tool blocks, append text, keep the last id.
                // Deduplicate by block.id — the agent loop may persist the same tool
                // use in multiple consecutive turns (as it carries tool state across),
                // and duplicated ids would crash LazyColumn's key uniqueness check.
                // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                // output) wins over an earlier STREAMING placeholder.
                val seen = mutableSetOf<String>()
                val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                    .asReversed()
                    .filter { seen.add(it.id) }
                    .asReversed()
                val combinedText = when {
                    prev.content.isBlank() -> msg.content
                    msg.content.isBlank() -> prev.content
                    else -> prev.content + "\n\n" + msg.content
                }
                // Tool-result rows are hidden, so several persisted
                // assistant rows can collapse into one visible bubble. If
                // a rerun branched at a later row, carry that deepest
                // visible branch control onto the merged bubble instead of
                // silently keeping the first row's non-branch metadata.
                val branchSource = if (msg.branchCount > 1) msg else prev
                merged[merged.lastIndex] = prev.copy(
                    id = msg.id,
                    content = combinedText,
                    toolBlocks = combinedBlocks,
                    // T126-marker: keep every source dbId so Phase 2.5
                    // can resolve markers that point at any of the
                    // pre-merge rows (lastCompactedMessageId is often
                    // an assistant row that gets folded into a later
                    // assistant turn).
                    sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                    branchAnchorDbId = branchSource.branchAnchorDbId,
                    branchIndex = branchSource.branchIndex,
                    branchCount = branchSource.branchCount,
                    // [T-error-persist-android] The error sticker is written
                    // to the LAST assistant row of the turn, so the later row
                    // (`msg`) wins; fall back to `prev` if only it carried one.
                    error = msg.error ?: prev.error,
                )
            } else {
                merged.add(msg)
            }
        }
        merged
    }
}

private data class ToolResultData(val output: String, val success: Boolean)

internal fun ChatViewModel.novexDocumentRefsInHistory(history: List<LLMMessage>): Set<String> = history
    .asSequence()
    .flatMap { message -> message.contentParts.asSequence() }
    .filterIsInstance<AgentContentPart.Text>()
    .flatMap { part -> novexDocumentRefsInPrompt(part.text).asSequence() }
    .toSet()

internal fun ChatViewModel.novexSourceCollectionRefsInHistory(history: List<LLMMessage>): Set<String> = history
    .asSequence()
    .flatMap { message -> message.contentParts.asSequence() }
    .filterIsInstance<AgentContentPart.Text>()
    .flatMap { part -> novexSourceCollectionRefsInPrompt(part.text).asSequence() }
    .toSet()

internal fun MessageRow.toLLMMessage(vm: ChatViewModel): LLMMessage {
    // [T-stage1-activation] 持久化 system 行（开局资料包）投影为对话流
    // 位置的 user 上下文块——"像用户直接发的一样"，在历史里、AI 记得
    // 自己开局读过。内存态 system 行不落库，不受此分支影响。
    val r = if (role == "user" || role == "system") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
    val contentParts = mutableListOf<AgentContentPart>()
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    var textContent = ""

    try {
        val array = org.json.JSONArray(partsJson)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            when (obj.optString("type")) {
                "text" -> {
                    val value = obj.optString("value", "")
                    // [T-android-retry-attachment-loss] The persisted
                    // <user-attached-files> XML must reach the model via a
                    // contentPart (provider prefers contentParts), but it
                    // must NOT fold into `content`. On a FRESH send the
                    // `content` field is the clean caption (`trimmed`) and
                    // the XML lives only in contentParts; keep restored
                    // messages byte-identical so `.content` consumers
                    // (summary, title fallback, edit) see the same string
                    // as a fresh turn and don't get the XML twice.
                    // [T-choice-instruction-lifecycle] 选项回应标记同款
                    // 特判（净眼 P2-2）：标记部件只进 contentParts 给模
                    // 型，不折进重载后的 content——否则 live 侧行 content
                    // 是玩家原文、DB 重建侧变成原文+标记，影子装配
                    // fingerprint（含 content.hashCode）每次 turn-0 基线
                    // 记一条弱信号噪音，latestVisibleUserRequest 也随之
                    // live/重载不对称。
                    if (containsAgentAttachmentMetadata(value) || value.startsWith("<system-reminder>")) {
                        contentParts.add(AgentContentPart.Text(value))
                    } else {
                        textContent += value
                        contentParts.add(AgentContentPart.Text(value))
                    }
                }
                "toolUse" -> {
                    val v = obj.getJSONObject("value")
                    val inputStr = v.optString("input", "{}")
                    val inputJson = try {
                        JSONObject(inputStr)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                    contentParts.add(AgentContentPart.ToolUse(
                        id = v.optString("toolUseId", ""),
                        name = v.optString("name", ""),
                        input = inputJson,
                        // [T-android-gemini3-thoughtsig / #179] Restore the
                        // persisted Gemini 3.x signature so a reloaded session
                        // replays it (else the next gemini-3 turn 400s).
                        thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                    ))
                }
                "toolResult" -> {
                    val v = obj.getJSONObject("value")
                    contentParts.add(AgentContentPart.ToolResult(
                        id = v.optString("toolUseId", ""),
                        name = v.optString("name", ""),
                        content = v.optString("output", ""),
                        isError = !v.optBoolean("success", true),
                    ))
                }
                "mediaRef" -> {
                    // T128: load persisted user-message images so the model
                    // sees them on subsequent turns after a session reload.
                    // T150: skip non-image mediaRefs here — their bytes
                    // shouldn't be re-inlined into the LLM payload (parity
                    // with the on-send path, which only inlines images).
                    // The original turn's <user-attached-files> XML stayed
                    // in the persisted text part, and the file is still
                    // on disk under attachments/uploads, so the agent can
                    // re-fetch via shell tools.
                    val v = obj.optJSONObject("value") ?: continue
                    val rel = v.optString("relativePath", "")
                    if (rel.isEmpty()) continue
                    val mime = v.optString("mimeType", "image/jpeg")
                    if (!mime.startsWith("image/")) continue
                    val file = java.io.File(vm.mediaStore.mediaBaseDir, rel)
                    if (!file.exists()) continue
                    val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                    val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                    // [T-android-vision-group / GH#182] Seed the read_image
                    // hint on restored images too, so a non-vision main model
                    // with a Vision Group configured gets steered to read_image
                    // on subsequent turns after a session reload (not the bare
                    // "can't see it" literal).
                    val restoredPlaceholder = vm.visionPlaceholderFor(restoredPath)
                    imageParts.add(LLMMessage.ImagePart(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                    contentParts.add(AgentContentPart.ImageData(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                }
            }
        }
    } catch (_: Exception) {
        textContent = partsJson
        contentParts.add(AgentContentPart.Text(partsJson))
    }

    return LLMMessage(
        role = r,
        content = textContent,
        imageParts = imageParts,
        contentParts = contentParts,
        dbMessageId = id,
        reasoningContent = reasoningContent,
    )
}

/**
 * Humanize a snake_case tool name into a Title-Case label for pill headers
 * while the model's own `tool_title` arg has not yet streamed in.
 * e.g. `file_write` → "Write File", `shell_execute` → "Execute Shell".
 */
internal fun ChatViewModel.friendlyToolTitle(toolName: String): String = com.openminis.app.cards.IntegratedCardToolLabels.values[toolName] ?: when (toolName) {
    "search_conversation_history" -> "搜索对话历史"
    "read_conversation_history" -> "读取历史原文"
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "generate_image" -> "生成图片"
    "memory_write" -> "Write Memory"
    "present_choices" -> "提供行动选项"
    "render_panel", "panel", "present_system_panel" -> "显示资料面板"
    "save_checkpoint" -> "保存对话进度"
    "inspect_story_images" -> "查看剧情插图"
    "select_story_image" -> "选择剧情插图"
    "inspect_worldbook_choices" -> "查看世界书选择"
    "set_game_worldbooks" -> "设置文游世界书"
    "set_current_worldbooks" -> "调整本局世界书"
    "select_answer_identity" -> "选择回答身份"
    "set_player_identity" -> "保存玩家身份"
    "start_interactive_fiction" -> "启动文游"
    "register_controls" -> "更新快捷操作"
    "end_interactive_fiction" -> "结束文游"
    "update_playthrough_state" -> "更新本局状态"
    "novex_read_context" -> "读取当前采用资料"
    "novex_inspect_content" -> "查看卡片目录"
    "novex_write_card" -> "填写并保存卡片"
    "novex_update_card" -> "修改卡片资料"
    "novex_write_module" -> "写入卡片模块"
    "novex_move_module" -> "调整模块顺序"
    "novex_link_cards" -> "关联卡片"
    "novex_propose_content_changes" -> "提出内容变更"
    "novex_apply_content_changes" -> "执行内容变更"
    "novex_inspect_memory" -> "查看长期记忆"
    "novex_propose_memory_changes" -> "提出记忆变更"
    "novex_apply_memory_changes" -> "执行记忆变更"
    "document_inspect" -> "检查文档"
    "document_read" -> "读取文档"
    "learning_prepare" -> "准备资料学习"
    "workspace_inspect" -> "检查工作区"
    "workspace_read" -> "读取工作区"
    "workspace_search" -> "查找仓库资料"
    "workspace_write" -> "写入工作区"
    "workspace_edit" -> "编辑工作区"
    "workspace_compute" -> "处理工作区"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

/**
 * Parse the JSON tool-arguments string into a plain Map for the loop
 * detector. Malformed JSON degrades gracefully to an empty map — the
 * detector still hashes the tool name, so identical bad calls are still
 * detected as a loop.
 */
internal fun ChatViewModel.parseToolParams(argsJson: String): Map<String, Any?> {
    if (argsJson.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(argsJson)
        val out = HashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
