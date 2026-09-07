package com.openminis.app.novex.domain

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

interface NovexLearningRepository {
    fun find(collectionRef: NovexResourceRef): NovexLearningState?
    fun save(state: NovexLearningState)
}

/** File-backed, branch-scoped learning state. Writes are atomic and never touch source originals. */
class FileNovexLearningRepository(
    private val directory: File,
) : NovexLearningRepository, NovexLearningResponseJournal {
    init {
        directory.mkdirs()
    }

    @Synchronized
    override fun find(collectionRef: NovexResourceRef): NovexLearningState? {
        val state = runCatching {
            NovexLearningStateJsonCodec.decode(fileFor(collectionRef).readText(Charsets.UTF_8))
                .takeIf { it.collection.ref == collectionRef }
        }.getOrNull() ?: return null
        // A damaged receipt must stop recovery, never silently turn paid work into a missing task.
        return state.accountFor(responses(collectionRef))
    }

    @Synchronized
    override fun save(state: NovexLearningState) {
        writeNovexAtomicFile(fileFor(state.collection.ref),
            NovexLearningStateJsonCodec.encode(state.accountFor(responses(state.collection.ref))))
    }

    @Synchronized
    override fun responses(collectionRef: NovexResourceRef): List<NovexLearningResponseReceipt> =
        responseDirectory(collectionRef).listFiles().orEmpty().filter { it.extension == "json" }.map { file ->
            val json = JSONObject(file.readText(Charsets.UTF_8))
            require(json.getString("collection_ref") == collectionRef.value) { "模型回执不属于当前资料集" }
            NovexLearningResponseReceipt(json.getString("id"), json.getString("preflight_id"), json.getString("request_key"),
                NovexLearningReviewOutput(json.getString("title"), json.getString("body"), json.getInt("input_tokens"),
                    json.getInt("output_tokens"), json.getBoolean("usage_estimated"),
                    if (json.isNull("stop_reason")) null else json.getString("stop_reason")), json.optLong("received_at"))
        }

    @Synchronized
    override fun recordResponse(collectionRef: NovexResourceRef, receipt: NovexLearningResponseReceipt) {
        val output = receipt.output
        val target = File(responseDirectory(collectionRef), "${sha256(receipt.id)}.json")
        require(!target.exists()) { "模型回执已经保存，不能覆盖" }
        writeNovexAtomicFile(target, JSONObject().put("collection_ref", collectionRef.value).put("id", receipt.id)
            .put("preflight_id", receipt.preflightId).put("request_key", receipt.requestKey)
            .put("received_at", receipt.receivedAtMillis)
            .put("title", output.title).put("body", output.body).put("input_tokens", output.inputTokens)
            .put("output_tokens", output.outputTokens).put("usage_estimated", output.usageIsEstimated)
            .put("stop_reason", output.stopReason ?: JSONObject.NULL).toString())
    }

    private fun responseDirectory(ref: NovexResourceRef) = File(directory, "${sha256(ref.value)}-responses")

    private fun fileFor(ref: NovexResourceRef): File = File(directory, "${sha256(ref.value)}.json")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

object NovexLearningStateJsonCodec {
    private const val VERSION = 10

    fun encode(state: NovexLearningState): String = JSONObject()
        .put("version", VERSION)
        .put("collection", encodeCollection(state.collection))
        .put("review_ledger", encodeLedger(state.reviewLedger))
        .put("notes", JSONArray(state.notes.map(::encodeNote)))
        .put("preflight", state.preflight?.let(::encodePreflight))
        .put("task", state.task?.let(::encodeTask))
        .put("accounted_responses", JSONArray(state.accountedResponseIds.toList()))
        .put("last_failure", state.lastFailure)
        .toString()

    fun decode(encoded: String): NovexLearningState {
        val json = JSONObject(encoded)
        val version = json.getInt("version")
        require(version in 1..VERSION) { "不支持的学习状态版本" }
        val collection = decodeCollection(json.getJSONObject("collection"))
        val task = json.optionalObject("task")?.let(::decodeTask)
        return NovexLearningState(
            collection = collection,
            reviewLedger = decodeLedger(json.getJSONObject("review_ledger")),
            notes = json.getJSONArray("notes").objects().map(::decodeNote),
            task = task,
            accountedResponseIds = json.optJSONArray("accounted_responses")?.strings()?.toSet().orEmpty(),
            lastFailure = json.optionalString("last_failure"),
            preflight = if (version >= 2) {
                json.optionalObject("preflight")?.let(::decodePreflight)
            } else {
                task?.preflight
            },
        )
    }

    private fun encodeCollection(collection: NovexSourceCollection) = JSONObject()
        .put("ref", collection.ref.value)
        .put("scope_ref", collection.scopeRef.value)
        .put("title", collection.title)
        .put("created_at", collection.createdAtMillis)
        .put("updated_at", collection.updatedAtMillis)
        .put("sources", JSONArray(collection.sources.map(::encodeSource)))

    private fun decodeCollection(json: JSONObject) = NovexSourceCollection(
        ref = NovexResourceRef(json.getString("ref")),
        scopeRef = NovexResourceRef(json.getString("scope_ref")),
        title = json.getString("title"),
        sources = json.getJSONArray("sources").objects().map(::decodeSource),
        createdAtMillis = json.getLong("created_at"),
        updatedAtMillis = json.getLong("updated_at"),
    )

    private fun encodeSource(source: NovexCollectionSource) = JSONObject()
        .put("ref", source.ref.value)
        .put("title", source.title)
        .put("sha256", source.sha256)
        .put("status", source.status.wireName)
        .put("document_ref", source.documentRef?.value)
        .put("block_ids", JSONArray(source.blockIds))
        .put("duplicate_of", source.duplicateOf?.value)
        .put("possible_version_of", source.possibleVersionOf?.value)
        .put("similarity_percent", source.similarityPercent)
        .put("failure_code", source.failureCode)

    private fun decodeSource(json: JSONObject) = NovexCollectionSource(
        ref = NovexResourceRef(json.getString("ref")),
        title = json.getString("title"),
        sha256 = json.getString("sha256"),
        status = enumByWire(json.getString("status"), NovexSourceStatus.entries) { it.wireName },
        documentRef = json.optionalString("document_ref")?.let(::NovexResourceRef),
        blockIds = json.getJSONArray("block_ids").strings(),
        duplicateOf = json.optionalString("duplicate_of")?.let(::NovexResourceRef),
        possibleVersionOf = json.optionalString("possible_version_of")?.let(::NovexResourceRef),
        similarityPercent = json.optionalInt("similarity_percent"),
        failureCode = json.optionalString("failure_code"),
    )

    private fun encodeLedger(ledger: NovexReviewLedger) = JSONObject()
        .put("collection_ref", ledger.collectionRef.value)
        .put("status", ledger.status.name)
        .put("readable", JSONObject().apply {
            ledger.readableBlocksByDocument.forEach { (ref, blocks) -> put(ref.value, JSONArray(blocks)) }
        })
        .put("reviewed", JSONObject().apply {
            ledger.reviewedBlocksByDocument.forEach { (ref, blocks) -> put(ref.value, JSONArray(blocks.toList())) }
        })
        .put("unreadable_sources", JSONArray(ledger.unreadableSourceRefs.map { it.value }))

    private fun decodeLedger(json: JSONObject): NovexReviewLedger {
        val readable = json.getJSONObject("readable").refToStringLists()
        val reviewed = json.getJSONObject("reviewed").refToStringLists()
            .mapValues { (_, blocks) -> blocks.toSet() }
        return NovexReviewLedger.restore(
            collectionRef = NovexResourceRef(json.getString("collection_ref")),
            readableBlocksByDocument = readable,
            reviewedBlocksByDocument = reviewed,
            unreadableSourceRefs = json.getJSONArray("unreadable_sources").strings().map(::NovexResourceRef),
            status = NovexLearningTaskStatus.valueOf(json.getString("status")),
        )
    }

    private fun encodeNote(note: NovexLearningNote) = JSONObject()
        .put("ref", note.ref.value)
        .put("level", note.level.wireName)
        .put("title", note.title)
        .put("body", note.body)
        .put("source_documents", JSONArray(note.sourceDocumentRefs.map { it.value }))
        .put("source_blocks", JSONArray(note.sourceBlockIds))
        .put("input_notes", JSONArray(note.inputNoteRefs.map { it.value }))
        .put("source_revisions", JSONObject(note.sourceRevisions.mapKeys { it.key.value }))
        .put("read_ranges", JSONArray(note.readRanges.map { range ->
            JSONObject().put("document_ref", range.documentRef.value).put("block_id", range.blockId)
                .put("start", range.start).put("end", range.end)
        }))

    private fun decodeNote(json: JSONObject) = NovexLearningNote(
        ref = NovexResourceRef(json.getString("ref")),
        level = enumByWire(json.getString("level"), NovexLearningNoteLevel.entries) { it.wireName },
        title = json.getString("title"),
        body = json.getString("body"),
        sourceDocumentRefs = json.getJSONArray("source_documents").strings().map(::NovexResourceRef),
        sourceBlockIds = json.getJSONArray("source_blocks").strings(),
        inputNoteRefs = json.optJSONArray("input_notes")?.strings()?.map(::NovexResourceRef).orEmpty(),
        sourceRevisions = json.optJSONObject("source_revisions")?.let { revisions ->
            revisions.keys().asSequence().associate { NovexResourceRef(it) to revisions.getString(it) }
        }.orEmpty(),
        readRanges = json.optJSONArray("read_ranges")?.objects()?.map { range ->
            NovexLearningReadRange(NovexResourceRef(range.getString("document_ref")),
                range.getString("block_id"), range.getInt("start"), range.getInt("end"))
        }.orEmpty(),
    )

    private fun encodeTask(task: NovexLearningTaskState) = JSONObject()
        .put("preflight", encodePreflight(task.preflight))
        .put("status", task.status.name)
        .put("resume_status", task.resumeStatus.name)
        .put("usage", JSONObject()
            .put("preflight_id", task.usage.preflightId)
            .put("max_input_tokens", task.usage.maxInputTokens)
            .put("max_output_tokens", task.usage.maxOutputTokens)
            .put("used_input_tokens", task.usage.usedInputTokens)
            .put("used_output_tokens", task.usage.usedOutputTokens)
            .put("contains_estimates", task.usage.containsEstimates)
            .put("contains_unknown_legacy_usage", task.usage.containsUnknownLegacyUsage)
            .put("status", task.usage.status.name))

    private fun decodeTask(json: JSONObject): NovexLearningTaskState {
        val preflight = decodePreflight(json.getJSONObject("preflight"))
        val usageJson = json.getJSONObject("usage")
        val usage = NovexLearningUsageLedger.restore(
            preflightId = usageJson.getString("preflight_id"),
            maxInputTokens = usageJson.getInt("max_input_tokens"),
            maxOutputTokens = usageJson.getInt("max_output_tokens"),
            usedInputTokens = usageJson.getInt("used_input_tokens"),
            usedOutputTokens = usageJson.getInt("used_output_tokens"),
            status = NovexLearningTaskStatus.valueOf(usageJson.getString("status")),
            containsEstimates = usageJson.optBoolean("contains_estimates"),
            containsUnknownLegacyUsage = usageJson.optBoolean("contains_unknown_legacy_usage") ||
                (!usageJson.has("contains_estimates") &&
                    (usageJson.getInt("used_input_tokens") > 0 || usageJson.getInt("used_output_tokens") > 0)),
        )
        return NovexLearningTaskState.restore(
            preflight = preflight,
            status = NovexLearningTaskStatus.valueOf(json.getString("status")),
            usage = usage,
            resumeStatus = NovexLearningTaskStatus.valueOf(json.getString("resume_status")),
        )
    }

    private fun encodePreflight(preflight: NovexLearningPreflightSnapshot) = JSONObject()
        .put("id", preflight.id)
        .put("collection_ref", preflight.collectionRef.value)
        .put("source_refs", JSONArray(preflight.sourceRefs.map { it.value }))
        .put("model_id", preflight.modelId)
        .put("model_provider_name", preflight.modelProviderName)
        .put("route", preflight.route.name)
        .put("source_count", preflight.sourceCount)
        .put("estimated_source_tokens", preflight.estimatedSourceTokens)
        .put("estimated_model_rounds", preflight.estimatedModelRounds)
        .put("review_batch_count", preflight.reviewBatchCount)
        .put("review_input_reservation_tokens", preflight.reviewInputReservationTokens)
        .put("document_revisions", JSONObject(preflight.documentRevisions.mapKeys { it.key.value }))
        .put("page_count", preflight.pageCount)
        .put("image_count", preflight.imageCount)
        .put("ocr_source_count", preflight.ocrSourceCount)
        .put("network_source_count", preflight.networkSourceCount)
        .put("estimated_cost", preflight.estimatedCost?.let { cost ->
            JSONObject()
                .put("currency_code", cost.currencyCode)
                .put("minimum_minor_units", cost.minimumMinorUnits)
                .put("maximum_minor_units", cost.maximumMinorUnits)
        })
        .put("estimated_duration", JSONObject()
            .put("minimum_minutes", preflight.estimatedDuration.minimumMinutes)
            .put("maximum_minutes", preflight.estimatedDuration.maximumMinutes))
        .put("data_exposure", JSONObject()
            .put("destination", preflight.dataExposure.destination)
            .put("source_content_may_leave_device", preflight.dataExposure.sourceContentMayLeaveDevice)
            .put("content_scope", preflight.dataExposure.contentScope))
        .put("planned_steps", JSONArray(preflight.plannedSteps))
        .put("confirmed_budget", JSONObject()
            .put("input_tokens", preflight.confirmedBudget.inputTokens)
            .put("output_tokens", preflight.confirmedBudget.outputTokens))
        .put("risks", JSONArray(preflight.risks.map { risk ->
            JSONObject().put("code", risk.code).put("message", risk.message)
        }))
        .put("unsupported_sources", JSONObject().apply {
            preflight.unsupportedSources.forEach { (ref, reason) -> put(ref.value, reason) }
        })
        .put("task_status", preflight.taskStatus.name)
        .put("prohibited_outcomes", JSONArray(preflight.prohibitedOutcomes.toList().sorted()))
        .put("source_plan_fingerprint", preflight.sourcePlanFingerprint)
        .put("model_limits", preflight.modelLimits?.let { limits ->
            JSONObject().put("context_tokens", limits.contextTokens).put("max_output_tokens", limits.maxOutputTokens)
        })

    private fun decodePreflight(json: JSONObject): NovexLearningPreflightSnapshot {
        val budget = json.getJSONObject("confirmed_budget")
        val unsupportedJson = json.getJSONObject("unsupported_sources")
        val estimatedRounds = json.getInt("estimated_model_rounds")
        val ocrSourceCount = json.getInt("ocr_source_count")
        val networkSourceCount = json.getInt("network_source_count")
        val duration = json.optionalObject("estimated_duration")
        val exposure = json.optionalObject("data_exposure")
        return NovexLearningPreflightSnapshot(
            id = json.getString("id"),
            collectionRef = NovexResourceRef(json.getString("collection_ref")),
            sourceRefs = json.getJSONArray("source_refs").strings().map(::NovexResourceRef),
            modelId = json.getString("model_id"),
            modelProviderName = json.getString("model_provider_name"),
            route = NovexLearningRoute.valueOf(json.getString("route")),
            sourceCount = json.getInt("source_count"),
            estimatedSourceTokens = json.getInt("estimated_source_tokens"),
            estimatedModelRounds = estimatedRounds,
            reviewBatchCount = json.optionalInt("review_batch_count"),
            reviewInputReservationTokens = json.optionalInt("review_input_reservation_tokens"),
            documentRevisions = json.optJSONObject("document_revisions")?.let { revisions ->
                revisions.keys().asSequence().associate { NovexResourceRef(it) to revisions.getString(it) }
            }.orEmpty(),
            pageCount = json.getInt("page_count"),
            imageCount = json.getInt("image_count"),
            ocrSourceCount = ocrSourceCount,
            networkSourceCount = networkSourceCount,
            estimatedCost = json.optionalObject("estimated_cost")?.let { cost ->
                NovexLearningCostEstimate(
                    currencyCode = cost.getString("currency_code"),
                    minimumMinorUnits = cost.getLong("minimum_minor_units"),
                    maximumMinorUnits = cost.getLong("maximum_minor_units"),
                )
            },
            estimatedDuration = duration?.let {
                NovexLearningDurationEstimate(
                    minimumMinutes = it.getInt("minimum_minutes"),
                    maximumMinutes = it.getInt("maximum_minutes"),
                )
            } ?: NovexLearningDurationEstimate(
                minimumMinutes = estimatedRounds + networkSourceCount + ocrSourceCount * 2,
                maximumMinutes = estimatedRounds * 4 + networkSourceCount * 5 + ocrSourceCount * 10,
            ),
            dataExposure = exposure?.let {
                NovexLearningDataExposure(
                    destination = it.getString("destination"),
                    sourceContentMayLeaveDevice = it.getBoolean("source_content_may_leave_device"),
                    contentScope = it.getString("content_scope"),
                )
            } ?: NovexLearningDataExposure(
                destination = json.getString("model_provider_name"),
                sourceContentMayLeaveDevice = true,
            ),
            plannedSteps = json.getJSONArray("planned_steps").strings(),
            confirmedBudget = NovexLearningTokenBudget(
                inputTokens = budget.getInt("input_tokens"),
                outputTokens = budget.getInt("output_tokens"),
            ),
            risks = json.getJSONArray("risks").objects().map { risk ->
                NovexLearningRisk(risk.getString("code"), risk.getString("message"))
            },
            unsupportedSources = unsupportedJson.keys().asSequence().associate { ref ->
                NovexResourceRef(ref) to unsupportedJson.getString(ref)
            },
            taskStatus = NovexLearningTaskStatus.valueOf(json.getString("task_status")),
            prohibitedOutcomes = json.getJSONArray("prohibited_outcomes").strings().toSet(),
            sourcePlanFingerprint = json.optionalString("source_plan_fingerprint"),
            modelLimits = json.optionalObject("model_limits")?.let { limits ->
                NovexLearningModelLimits(limits.optionalInt("context_tokens"), limits.getInt("max_output_tokens"))
            },
        )
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null

    private fun JSONObject.optionalObject(name: String): JSONObject? =
        if (has(name) && !isNull(name)) getJSONObject(name) else null

    private fun JSONObject.refToStringLists(): Map<NovexResourceRef, List<String>> = keys().asSequence()
        .associate { key -> NovexResourceRef(key) to getJSONArray(key).strings() }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> =
        (0 until length()).map(::getString)

    private fun <T> enumByWire(value: String, values: List<T>, wire: (T) -> String): T =
        values.firstOrNull { wire(it) == value }
            ?: throw IllegalArgumentException("学习状态包含不支持的枚举值")
}
