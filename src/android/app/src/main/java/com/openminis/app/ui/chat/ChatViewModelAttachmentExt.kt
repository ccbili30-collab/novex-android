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

// 发送管线：用户附件准备、图片压缩、媒体部件 JSON。
// 均为 ChatViewModel 的内部扩展，签名与行为冻结。

internal fun ChatViewModel.resizeImageBytes(
    rawBytes: ByteArray,
    mimeType: String,
    maxEdge: Int = 2000,
): ByteArray? {
    return try {
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        if (original.width <= maxEdge && original.height <= maxEdge) {
            original.recycle()
            return null
        }
        val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
        val w = (original.width * scale).toInt()
        val h = (original.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(original, w, h, true)
        val out = ByteArrayOutputStream()
        val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
        else Bitmap.CompressFormat.JPEG
        scaled.compress(format, 85, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}

/**
 * Bundle of everything derived from a user-message's input attachments:
 * the resized in-memory image bytes for the LLM, file:// URIs of the
 * persisted copies (for stable rendering across app restarts), the
 * filenames in original attachment order (images first, then non-image
 * files — matches the rendering convention in UserAttachmentList), and
 * the mediaRef JSON parts that need to be embedded in parts_json so the
 * attachments survive a session reload (T128).
 */
internal data class PreparedAttachments(
    val imageParts: List<LLMMessage.ImagePart>,
    val imageUris: List<Uri>,
    val attachmentNames: List<String>,
    val mediaRefPartsJson: List<String>,
    // T132: iOS-parity additions so the model sees the attachment as
    // a real file in the agent's sandbox (read_image / shell_execute can
    // open these paths).
    //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
    //     inlined image, in the same order as `imageParts`.
    //   attachedFilesXml:  null when no attachments, otherwise the
    //     <user-attached-files> XML block iOS appends to the user turn.
    val imageUploadPaths: List<String>,
    val attachedFilesXml: String?,
    // T150: file:// URIs of persisted non-image attachments, in the same
    // order as the non-image suffix of `attachmentNames`. Carried into
    // ChatMessage so the user-bubble file chip can route a tap directly
    // to FilePreviewScreen without re-resolving by filename.
    val nonImageUris: List<Uri>,
)

/**
 * Resize each image attachment, copy the bytes into MediaStore (private
 * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
 * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
 * part (for persistence + reload). T150: non-image attachments take the
 * same persistence + uploadsHostDir path so they survive session reload
 * and remain visible to the agent's shell tools — but their content is
 * NOT inlined into the LLM payload (parity with iOS processAttachments,
 * AIChatViewModel.swift L1552-1645).
 */
internal suspend fun ChatViewModel.prepareUserAttachments(
    attachments: List<InputAttachment>,
    sessionId: String,
): PreparedAttachments = withContext(Dispatchers.IO) {
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    val imageUris = mutableListOf<Uri>()
    val imageNames = mutableListOf<String>()
    val nonImageNames = mutableListOf<String>()
    val nonImageUris = mutableListOf<Uri>()
    // T150: separate buffers so the persisted mediaRefPartsJson is
    // image-first, matching the on-screen UserAttachmentList ordering
    // and `attachmentNames = imageNames + nonImageNames`. On restore,
    // `loadSessionMessages` walks parts_json in array order — keeping
    // the persisted order image-first means restoredAttachmentNames
    // and restoredAttachmentUris also come out image-first/non-image-suffix.
    val imageMediaRefPartsJson = mutableListOf<String>()
    val nonImageMediaRefPartsJson = mutableListOf<String>()
    val imageUploadPaths = mutableListOf<String>()
    // T132: also write the resized bytes into the session's iSH-bound
    // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
    // which is mounted at /var/minis/attachments/ inside iSH. This makes
    // the same image accessible to the agent via shell tools (read_image
    // / cat / file) and matches the iOS uploads-directory convention.
    val uploadsHostDir = java.io.File(
        context.filesDir,
        "minis-sessions/$sessionId/attachments/uploads",
    ).apply { mkdirs() }
    // Metadata captured per attachment for the <user-attached-files> XML.
    val metas = mutableListOf<UserAttachedFilePromptMeta>()
    val documentRequests = mutableListOf<Pair<Int, NovexBatchDocumentRequest>>()
    val nowMs = System.currentTimeMillis()
    val isoFormatter = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        java.util.Locale.US,
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val nowStr = isoFormatter.format(java.util.Date(nowMs))

    for (attachment in attachments) {
        if (attachment.isImage) {
            // T209: read the original image bytes once and reuse them
            // for storage + uploads dir; only the LLM inference payload
            // gets the resized copy. Pre-T209 the resized JPEG was used
            // for all three, so chat history fullscreen view and agent
            // shell tools (read_image / cat) saw a 1024px JPEG instead
            // of the user's original picture. Matches iOS canonical
            // (AIChatViewModel.swift L1595-1617).
            val rawBytes = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                null
            } ?: continue
            val ref = try {
                mediaStore.saveMedia(
                    data = rawBytes,
                    mimeType = attachment.mimeType,
                    sessionId = sessionId,
                    originalFileName = attachment.fileName,
                )
            } catch (e: Exception) {
                Log.e(ChatViewModel.TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                continue
            }
            // Resize only for the LLM payload — token-efficient and a
            // close-enough sketch of the picture for the model. Falls
            // back to raw bytes if the source is already small or the
            // decode/compress step fails.
            val inferenceBytes = resizeImageBytes(rawBytes, attachment.mimeType, maxEdge = 2000)
                ?: rawBytes

            // Mirror ORIGINAL bytes into the iSH uploads dir under a
            // unique safe name so agent shell tools see the full-res
            // image. Don't fail the send if this write fails —
            // image_url in the request still carries (resized) bytes;
            // the model just won't be able to ask the agent to re-read
            // the same file from shell.
            //
            // Done BEFORE ImagePart construction so the linuxPath is
            // attached to the part — request-level image budgeting
            // uses it to emit a re-fetchable text placeholder when
            // the cumulative payload would exceed the per-request cap.
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                false
            }
            val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
            if (linuxPath != null) {
                imageUploadPaths.add(linuxPath)
                metas.add(UserAttachedFilePromptMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
            }

            imageParts.add(LLMMessage.ImagePart(inferenceBytes, attachment.mimeType, linuxPath = linuxPath))
            val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
            imageUris.add(Uri.fromFile(savedFile))
            imageNames.add(attachment.fileName)
            imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
            continue
        }

        // T150: non-image attachment — stream-copy to disk (no
        // resize), persist a mediaRef so the chip survives session
        // reload (T151), and put a copy in the iSH uploads dir so
        // the agent can `cat` it via shell tools. iOS parity: the
        // file content is NOT inlined into the LLM payload — it
        // only appears in <user-attached-files> XML metadata, the
        // model fetches content on demand.
        //
        // CRITICAL: we deliberately do NOT `readBytes()` the
        // attachment here. A 400MB APK shared in by the user would
        // OOM on a low-RAM device (heap growth limit ~500MB on
        // Pixel 4a); the file's not even going into the LLM
        // payload, so loading the full byte array is pointless.
        // Stream-copy to the uploads dest first, then hand that
        // file to MediaStore.saveMediaStreamed so a second
        // streaming pass produces the durable mediaRef.
        nonImageNames.add(attachment.fileName)
        val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
        val dest = java.io.File(uploadsHostDir, safeName)
        val uploadOk = try {
            context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
            runCatching { dest.delete() }
            false
        }
        if (!uploadOk) continue

        val ref = try {
            dest.inputStream().use { input ->
                mediaStore.saveMediaStreamed(
                    source = input,
                    mimeType = attachment.mimeType,
                    sessionId = sessionId,
                    originalFileName = attachment.fileName,
                )
            }
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
            null
        }
        if (ref != null) {
            nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
            nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
        }

        val linuxPath = "/var/minis/attachments/uploads/$safeName"
        metas.add(
            UserAttachedFilePromptMeta(
                linuxPath = linuxPath,
                size = dest.length(),
                modifiedIso = nowStr,
            ),
        )
        documentRequests += metas.lastIndex to NovexBatchDocumentRequest(
            sourceRef = NovexResourceRef(
                "novex://sources/${ref?.id ?: java.util.UUID.randomUUID()}",
            ),
            file = dest,
            mimeType = attachment.mimeType,
            originalName = attachment.fileName,
        )
    }

    val batchOutcomes = if (documentRequests.isEmpty()) {
        emptyList()
    } else {
        NovexBatchDocumentImporter(maxParallelism = 2) { request ->
            novexDocumentSnapshotExtractor.extract(
                context = context,
                file = request.file,
                mimeType = request.mimeType.orEmpty(),
                originalName = request.originalName,
            )
        }.importAll(documentRequests.map { it.second })
    }
    batchOutcomes.forEachIndexed { index, outcome ->
        val metaIndex = documentRequests[index].first
        metas[metaIndex] = metas[metaIndex].copy(documentSnapshot = outcome.snapshot)
        outcome.snapshot?.warnings?.forEach { warning ->
            AppLogger.warning(
                ChatViewModel.TAG,
                "novex_document_snapshot file=${outcome.title} " +
                    "size=${documentRequests[index].second.file.length()} code=${warning.code}",
            )
        }
        if (outcome.failureCode != null) {
            AppLogger.warning(
                ChatViewModel.TAG,
                "novex_document_snapshot file=${outcome.title} code=${outcome.failureCode}",
            )
        }
    }

    val sourceCollection = batchOutcomes.takeIf { it.isNotEmpty() }?.let { outcomes ->
        val activeBranchAnchor = activeBranchPathIds.lastOrNull() ?: "root"
        val scopeRef = novexHashedRef(
            kind = "conversation-branches",
            material = "$sessionId\u0000$activeBranchAnchor",
        )
        val collectionRef = novexHashedRef(
            kind = "source-collections",
            material = "$sessionId\u0000$activeBranchAnchor\u0000$nowMs\u0000" +
                outcomes.joinToString("\u0000") { it.sourceRef.value },
        )
        NovexSourceCollectionBuilder.create(
            ref = collectionRef,
            scopeRef = scopeRef,
            title = if (outcomes.size == 1) outcomes.first().title else "本轮资料 · ${outcomes.size} 项",
            imports = outcomes.map { it.toSourceImportResult() },
            nowMillis = nowMs,
        ).also { collection ->
            novexLearningRepository.save(
                NovexLearningState(
                    collection = collection,
                    reviewLedger = NovexReviewLedger.start(collection),
                ),
            )
        }
    }

    // T-imgsize: byte-level budget enforcement. The resizeImageBytes pass
    // above caps *resolution* at 2000px but does nothing for the JPEG byte
    // size when the source is a 12-megapixel photo — Anthropic 413s once
    // cumulative inline image payload crosses ~30MB. ImageBudget walks
    // every image part, re-encodes oversize ones via the quality ladder,
    // and drops the tail when cumulative bytes would exceed 20MB. Result
    // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
    // tell the user we touched their attachments.
    if (imageParts.isNotEmpty()) {
        val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
        // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
        // parallel image-only lists symmetrically. Re-encoded bytes always
        // come out as JPEG so flip the mimeType on any part whose bytes
        // changed size (cheap proxy — never a false positive that hurts
        // semantics because the byte stream itself is the JPEG header).
        val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
            val orig = imageParts[idx]
            if (kept === orig.data) orig
            else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
        }
        val newSize = newImageParts.size
        imageParts.clear()
        imageParts.addAll(newImageParts)
        while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
        while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
        while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
        while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
        if (budgetResult.mutated) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
            )
            _imageBudgetEvent.tryEmit(budgetResult)
        }
    }

    // Parsed documents become bounded Novex receipts; ordinary files retain
    // compatibility metadata until the controlled workspace replaces raw paths.
    val xml = buildUserAttachedFilesPrompt(
        metas = metas,
        sourceCollectionRef = sourceCollection?.ref,
        sourceCount = sourceCollection?.sources?.size ?: 0,
    )
    val newDocumentRefs = metas.mapNotNull { meta -> meta.documentSnapshot?.ref?.value }.toSet()
    if (newDocumentRefs.isNotEmpty()) {
        activeNovexDocumentRefs = activeNovexDocumentRefs + newDocumentRefs
    }
    sourceCollection?.let { collection ->
        activeNovexSourceCollectionRefs = activeNovexSourceCollectionRefs + collection.ref.value
    }

    // Order matches UserAttachmentList convention: images first, then files.
    PreparedAttachments(
        imageParts = imageParts,
        imageUris = imageUris,
        attachmentNames = imageNames + nonImageNames,
        mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
        imageUploadPaths = imageUploadPaths,
        attachedFilesXml = xml,
        nonImageUris = nonImageUris,
    )
}

/**
 * Compute a unique-on-disk filename inside [dir] for [original]. Strips
 * path separators, falls back to "image.jpg" if the input is empty, and
 * appends `_N` before the extension when the target already exists.
 */
internal fun ChatViewModel.uniqueUploadFileName(dir: java.io.File, original: String): String {
    val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
    // Sanitize control / path-hostile chars without going overboard;
    // safe POSIX path chars are kept.
    val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    if (!java.io.File(dir, sanitized).exists()) return sanitized
    val dot = sanitized.lastIndexOf('.')
    val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
    val ext = if (dot > 0) sanitized.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "${base}_$n$ext"
        if (!java.io.File(dir, candidate).exists()) return candidate
        n++
    }
}

internal fun ChatViewModel.buildMediaRefPartJson(
    ref: novex.android.data.model.MediaRef,
    linuxPath: String? = null,
): String {
    val value = JSONObject()
        .put("id", ref.id)
        .put("relativePath", ref.relativePath)
        .put("mimeType", ref.mimeType)
    if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
    // Carry the iSH-visible uploads path through persistence so that
    // restored history can reconstruct AgentContentPart.ImageData with
    // its original linuxPath. Restored images that miss this field
    // (older rows written before this column existed) get linuxPath=null
    // and fall back to spillover at budget-elide time.
    if (linuxPath != null) value.put("linuxPath", linuxPath)
    return JSONObject().put("type", "mediaRef").put("value", value).toString()
}

/**
 * Build the parts_json array for a user message: a `text` part (omitted
 * when the user only sent attachments with no caption) followed by one
 * `mediaRef` part per persisted image. Mirrors the existing single-part
 * shape when there are no attachments.
 */
internal fun ChatViewModel.buildUserPartsJson(
    text: String,
    mediaRefPartsJson: List<String>,
    // [T-android-retry-attachment-loss] The <user-attached-files> XML
    // inventory (non-image file paths/sizes the model uses to `cat` the
    // file). iOS persists this same XML as a trailing text part so it
    // round-trips through retry / rerun / session-reload unchanged — the
    // model keeps seeing the /var/minis/attachments/uploads/... paths.
    // Android previously only added it to the in-memory agentHistory and
    // never persisted it, so a retry silently dropped the file inventory.
    // Persist it here as a text part (iOS parity); toLLMMessage restores
    // it via the plain "text" case with zero special-casing.
    attachedFilesXml: String? = null,
    // [T-choice-instruction-lifecycle] 追加落盘文本部件（当前仅选项回应
    // 标记）。与内存侧 userContentParts 同源同序，保证影子装配平价。
    extraTextParts: List<String> = emptyList(),
): String {
    val parts = mutableListOf<String>()
    if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
        parts.add("""{"type":"text","value":${escapeJson(text)}}""")
    }
    parts.addAll(mediaRefPartsJson)
    attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
    extraTextParts.forEach { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
    return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
}

