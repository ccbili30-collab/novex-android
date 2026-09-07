package com.openminis.app.novex.domain

import org.json.JSONObject
import java.security.MessageDigest

fun interface NovexLearningPreflightResolver {
    fun prepare(collectionRef: NovexResourceRef, modelId: String?): NovexLearningPreflightSnapshot?
    /** Only return state that the current conversation branch is allowed to read. */
    fun readState(collectionRef: NovexResourceRef): NovexLearningState? = null
}

/** Read-only model seam. Starting and confirming a task remain application-internal operations. */
class NovexLearningTools(
    private val preflights: NovexLearningPreflightResolver,
) {
    fun learningRead(collectionRef: NovexResourceRef, arguments: JSONObject): NovexToolResult {
        val state = preflights.readState(collectionRef)?.takeIf { it.collection.ref == collectionRef }
            ?: return NovexToolResult.failure("learning.collection_not_found", "找不到当前对话可读的资料集", affectedRefs = listOf(collectionRef))
        val notes = state.notes.sortedByDescending { it.level.ordinal }
        if (notes.isEmpty()) return NovexToolResult.success("learning.notes_empty", "尚无已保存的学习笔记；不能声称已经完成通读",
            data = mapOf("collection_ref" to collectionRef.value, "task_status" to state.task?.status?.name),
            affectedRefs = listOf(collectionRef))
        val digest = MessageDigest.getInstance("SHA-256")
        notes.forEach { note ->
            listOf(note.ref.value, note.title, note.body).forEach { digest.update(it.toByteArray(Charsets.UTF_8)); digest.update(0) }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val snapshot = NovexDocumentSnapshot(NovexResourceRef("novex://documents/learning-$sha"), sha,
            "learning-notes-v1", state.collection.title, NovexDocumentFormat.TEXT, NovexDocumentStatus.READY,
            notes.mapIndexed { index, note ->
                val anchor = NovexDocumentSourceAnchor(note.ref.value, index)
                NovexDocumentBlock(NovexDocumentBlockId.from(sha, anchor), NovexDocumentBlockKind.NOTE,
                    index, note.body, listOf(note.title), source = anchor)
            })
        val selectedRef = arguments.optString("note_ref").trim().ifBlank { null }
        val noteIndex = selectedRef?.let { ref -> notes.indexOfFirst { it.ref.value == ref }.also { index ->
            require(index >= 0) { "note_ref 不属于当前资料集；请使用 learning_read 返回的笔记引用" }
        } }
        val result = NovexDocumentTools(NovexDocumentSnapshotStore { snapshot }).documentRead(NovexDocumentReadRequest(
            documentRef = snapshot.ref,
            blockIds = noteIndex?.let { listOf(snapshot.blocks[it].id) }.orEmpty(),
            query = arguments.optString("query").trim().ifBlank { null },
            cursor = arguments.optString("cursor").trim().ifBlank { null },
            firstBlock = if (arguments.has("first_note")) arguments.getInt("first_note") else null,
            maxChars = if (arguments.has("max_chars")) arguments.getInt("max_chars") else 24_000,
            maxBlocks = 20,
        ))
        if (!result.ok) return NovexToolResult.failure(result.code,
            if (result.code == "document.invalid_cursor") "笔记游标无效或笔记已更新。请原样使用上次 next_cursor；也可移除 cursor，用 first_note 从已知笔记位置重读，不要手工修改游标。"
            else result.summary.replace("first_block", "first_note"), affectedRefs = listOf(collectionRef))
        val returnedIds = (result.data["blocks"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }.orEmpty().toSet()
        val returnedNotes = notes.filterIndexed { index, _ -> snapshot.blocks[index].id in returnedIds }
        return NovexToolResult.success("learning.notes_read", "已读取 ${returnedNotes.size} 条学习笔记；笔记不是原文，通读覆盖以阅读记录为准",
            data = (result.data - "document_ref") + mapOf(
                "collection_ref" to collectionRef.value,
                "read_via" to "learning_read",
                "total_notes" to notes.size,
                "task_status" to state.task?.status?.name,
                "reviewed_source_blocks" to state.reviewLedger.reviewedBlocks,
                "total_source_blocks" to state.reviewLedger.totalReadableBlocks,
                "unreadable_source_count" to state.reviewLedger.unreadableSourceRefs.size,
                "incomplete_or_unreadable_source_refs" to state.reviewLedger.unreadableSourceRefs.take(16).map { it.value },
                "incomplete_sources_truncated" to (state.reviewLedger.unreadableSourceRefs.size > 16),
                "note_sources" to returnedNotes.map { note -> mapOf(
                    "note_ref" to note.ref.value, "title" to note.title, "level" to note.level.wireName,
                    "source_document_refs" to note.sourceDocumentRefs.take(16).map { it.value },
                    "source_document_count" to note.sourceDocumentRefs.size,
                    "source_revisions" to note.sourceRevisions.entries.take(16).associate { it.key.value to it.value },
                    "source_revision_status" to if (note.sourceRevisions.keys.containsAll(note.sourceDocumentRefs)) "recorded" else "legacy_unknown",
                    "source_block_ids" to note.sourceBlockIds.take(16), "source_block_count" to note.sourceBlockIds.size,
                    "source_anchors_truncated" to (note.sourceDocumentRefs.size > 16 || note.sourceBlockIds.size > 16),
                ) },
            ), affectedRefs = listOf(collectionRef),
            nextActions = if (result.data["next_cursor"] != null) listOf(NovexToolNextAction("learning_read", "携带原样游标继续读取笔记")) else emptyList())
    }

    fun learningPrepare(
        collectionRef: NovexResourceRef,
        modelId: String?,
    ): NovexToolResult {
        val preflight = preflights.prepare(collectionRef, modelId) ?: return NovexToolResult.failure(
            code = "learning.collection_not_found",
            summary = "找不到当前对话可用的资料集",
            affectedRefs = listOf(collectionRef),
        )
        if (preflight.taskStatus != NovexLearningTaskStatus.NOT_STARTED) {
            return NovexToolResult.success(
                code = if (NovexLearningControlPolicy.blocksReplacementPreflight(preflight.taskStatus))
                    "learning.task_active" else "learning.task_saved",
                summary = "这份资料集已有学习整理记录；先读取已保存笔记，需要继续时使用原生进度界面，不重复建立任务",
                data = mapOf(
                    "preflight_id" to preflight.id,
                    "collection_ref" to preflight.collectionRef.value,
                    "task_status" to preflight.taskStatus.name,
                ),
                nextActions = listOf(
                    NovexToolNextAction("open_native_learning_status", "查看资料学习进度"),
                    NovexToolNextAction("learning_read", "读取已完成的阶段笔记"),
                ),
                affectedRefs = listOf(preflight.collectionRef),
            )
        }
        return NovexToolResult.success(
            code = "learning.preflight_ready",
            summary = if (preflight.requiresConfirmation) {
                "学习预检已准备好，需要等待用户在原生界面确认"
            } else {
                "资料规模较小，可以在当前对话中按需读取"
            },
            data = mapOf(
                "preflight_id" to preflight.id,
                "collection_ref" to preflight.collectionRef.value,
                "model_id" to preflight.modelId,
                "model_provider" to preflight.modelProviderName,
                "source_count" to preflight.sourceCount,
                "page_count" to preflight.pageCount,
                "image_count" to preflight.imageCount,
                "ocr_source_count" to preflight.ocrSourceCount,
                "network_source_count" to preflight.networkSourceCount,
                "estimated_source_tokens" to preflight.estimatedSourceTokens,
                "estimated_model_rounds" to preflight.estimatedModelRounds,
                "review_batch_count" to preflight.reviewBatchCount,
                "review_input_reservation_tokens" to preflight.reviewInputReservationTokens,
                "input_reservation_basis" to "utf8_bytes_plus_message_headroom_not_billing",
                "synthesis_rounds_are_estimated" to true,
                "estimated_cost" to preflight.estimatedCost?.let { cost ->
                    mapOf(
                        "currency" to cost.currencyCode,
                        "minimum_minor_units" to cost.minimumMinorUnits,
                        "maximum_minor_units" to cost.maximumMinorUnits,
                    )
                },
                "estimated_duration" to mapOf(
                    "minimum_minutes" to preflight.estimatedDuration.minimumMinutes,
                    "maximum_minutes" to preflight.estimatedDuration.maximumMinutes,
                ),
                "data_exposure" to mapOf(
                    "destination" to preflight.dataExposure.destination,
                    "source_content_may_leave_device" to
                        preflight.dataExposure.sourceContentMayLeaveDevice,
                    "content_scope" to preflight.dataExposure.contentScope,
                ),
                "planned_steps" to preflight.plannedSteps,
                "requires_confirmation" to preflight.requiresConfirmation,
                "prohibited_outcomes" to preflight.prohibitedOutcomes.toList().sorted(),
            ),
            warnings = preflight.risks.map { risk -> NovexToolWarning(risk.code, risk.message) },
            nextActions = if (preflight.requiresConfirmation) {
                listOf(NovexToolNextAction("wait_for_native_confirmation", "等待用户确认整理计划"))
            } else {
                listOf(NovexToolNextAction("read_documents", "按需读取资料"))
            },
            affectedRefs = listOf(preflight.collectionRef),
        )
    }

}

class NovexLearningToolRouter(
    private val tools: NovexLearningTools,
) {
    fun execute(name: String, argumentsJson: String): NovexToolResult {
        if (name != LEARNING_PREPARE && name != LEARNING_READ) {
            return NovexToolResult.failure(
                code = "tool.unknown",
                summary = "当前学习工具不存在",
                allowedValues = listOf(LEARNING_PREPARE, LEARNING_READ),
            )
        }
        return runCatching {
            val arguments = JSONObject(argumentsJson.ifBlank { "{}" })
            val collection = arguments.optString("collection_ref").trim()
            require(collection.startsWith("novex://source-collections/")) {
                "collection_ref 必须是 Novex 资料集引用"
            }
            if (name == LEARNING_READ) tools.learningRead(NovexResourceRef(collection), arguments) else tools.learningPrepare(
                collectionRef = NovexResourceRef(collection),
                modelId = arguments.optString("model_id").trim().ifBlank { null },
            )
        }.getOrElse { failure ->
            NovexToolResult.failure(
                code = "tool.invalid_arguments",
                summary = failure.message?.takeIf(String::isNotBlank) ?: "学习工具参数无效",
                allowedValues = listOf(LEARNING_PREPARE, LEARNING_READ),
            )
        }
    }

    companion object {
        const val LEARNING_PREPARE = "learning_prepare"
        const val LEARNING_READ = "learning_read"
    }
}
