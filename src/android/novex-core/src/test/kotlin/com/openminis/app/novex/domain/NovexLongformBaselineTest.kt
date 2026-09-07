package com.openminis.app.novex.domain

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in QA driver: production parser, planner, coordinator, runner, tools and file stores. */
class NovexLongformBaselineTest {
    @Test fun runRequestedBaseline() = runBlocking {
        val inputPath = System.getenv("NOVEX_BASELINE_INPUT")
        val outputPath = System.getenv("NOVEX_BASELINE_OUTPUT")
        assumeTrue("独立验收需显式指定合成输入与新的输出目录", inputPath != null && outputPath != null)
        val input = File(requireNotNull(inputPath))
        val output = File(requireNotNull(outputPath))
        require(!output.exists()) { "不能覆盖已有基线记录" }
        check(output.mkdirs())
        val mode = System.getenv("NOVEX_BASELINE_MODE") ?: "prepare"
        val useRecovery = System.getenv("NOVEX_BASELINE_USE_RECOVERY") == "true"
        require(mode in setOf("prepare", "offline", "live"))
        val documents = FileNovexDocumentSnapshotRepository(File(output, "documents"))
        val pipeline = NovexDocumentSnapshotPipeline(documents)
        val outcomes = NovexBatchDocumentImporter(2, NovexDocumentImportWorker { request ->
            val sha = hash(request.file.readBytes())
            pipeline.resolveStructured(NovexDocumentDescriptor(NovexResourceRef("novex://documents/$sha"),
                sha, request.originalName, NovexDocumentFormat.DOCX, "docx-streaming-v1+poi-fallback-v1+android-compatibility-v1")) {
                NovexDocxStreamingParser().parse(request.file, sha)
            }
        }).importAll(requireNotNull(File(input, "sources").listFiles()).sortedBy { it.name }.map { file ->
            NovexBatchDocumentRequest(NovexResourceRef("novex://sources/${hash(file.name.toByteArray())}"), file,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", file.name)
        })
        val collection = NovexSourceCollectionBuilder.create(NovexResourceRef("novex://source-collections/baseline"),
            NovexResourceRef("novex://conversation-branches/synthetic-baseline"), "生生·验收用本（测试资料）",
            outcomes.map { it.toSourceImportResult() }, 1_000L)
        val sourceDocuments = collection.sources.mapNotNull { source ->
            source.documentRef?.let(documents::find)?.let { source.ref to it }
        }.toMap()
        val estimates = collection.sources.map { source ->
            val snapshot = sourceDocuments[source.ref]
            NovexLearningSourceEstimate(source.ref, snapshot?.let {
                NovexLearningBudgetPolicy.inputReservation(NovexLearningPrompt.review(it.title, it.blocks))
            } ?: 0, imageCount = snapshot?.blocks.orEmpty().count { it.kind == NovexDocumentBlockKind.IMAGE },
                requiresOcr = snapshot?.status == NovexDocumentStatus.OCR_REQUIRED,
                unsupportedReason = if (snapshot == null) source.failureCode else null)
        }
        val total = estimates.sumOf { it.estimatedTokens.toLong() }
        val budget = NovexLearningTokenBudget((total * 2).coerceIn(16_000L, 2_000_000L).toInt(),
            (total / 5).coerceIn(8_000L, 128_000L).toInt())
        val preflight = NovexLearningPreflight.prepare(NovexLearningPreflightRequest(
            collection.ref, estimates, "deepseek-v4-flash", "DeepSeek", 32_768, 0, 12_000,
            budget, modelMaxOutputTokens = 4096, sourceDocuments = sourceDocuments))
        val state = NovexLearningState(collection, NovexReviewLedger.start(collection), preflight = preflight)
        FileNovexLearningRepository(File(output, "prepared")).save(state)
        val plan = JSONObject().put("mode", mode).put("synthetic_only", true)
            .put("context_tokens", 32_768).put("model_max_output_tokens", 4096)
            .put("request_assembly", "NovexLearningPrompt; current production dedicated learning prompts, not V6")
            .put("source_files", outcomes.size).put("unique_documents", collection.uniqueDocumentRefs.size)
            .put("readable_blocks", state.reviewLedger.totalReadableBlocks)
            .put("unreadable_sources", JSONArray(state.reviewLedger.unreadableSourceRefs.map { it.value }))
            .put("parsed_characters", collection.uniqueDocumentRefs.mapNotNull(documents::find).sumOf { d -> d.blocks.sumOf { it.text.length } })
            .put("parsed_text_characters", collection.uniqueDocumentRefs.mapNotNull(documents::find).sumOf { d ->
                d.blocks.filter { it.kind !in setOf(NovexDocumentBlockKind.IMAGE, NovexDocumentBlockKind.PAGE_BREAK) }.sumOf { it.text.length }
            })
            .put("review_batches", preflight.reviewBatchCount)
            .put("review_input_reservation", preflight.reviewInputReservationTokens)
            .put("estimated_model_rounds", preflight.estimatedModelRounds)
            .put("budget_input_tokens", budget.inputTokens).put("budget_output_tokens", budget.outputTokens)
            .put("sources", JSONArray(outcomes.map { result -> JSONObject().put("file", result.title)
                .put("sha256", result.sha256).put("document_ref", result.snapshot?.ref?.value)
                .put("status", result.snapshot?.status?.name).put("failure", result.failureCode) }))
        File(output, "plan.json").writeText(plan.toString(2))
        if (mode == "prepare") return@runBlocking
        val initial = state.copy(task = NovexLearningCoordinator().start(preflight, NovexLearningConfirmation(
            preflight.id, preflight.modelId, preflight.sourceRefs, budget.inputTokens, budget.outputTokens, 2_000L)))
        if (mode == "live") {
            runLive(initial, documents, input, output)
            return@runBlocking
        }
        val findings = JSONArray()
        fun finding(id: String, passed: Boolean, detail: JSONObject) {
            findings.put(JSONObject().put("id", id).put("passed", passed).put("evidence", detail))
        }
        for (fault in listOf("none", "before_model_send", "after_model_before_save", "after_save_before_ui")) {
            val directory = File(output, fault)
            directory.mkdirs()
            var repository = FileNovexLearningRepository(File(directory, "learning"))
            repository.save(initial)
            val requests = JSONArray()
            var trigger = fault != "none"
            var observedInput = 0
            var observedOutput = 0
            fun event(kind: String, payload: JSONObject) {
                File(directory, "events.jsonl").appendText(JSONObject().put("kind", kind).put("payload", payload).toString()+"\n")
            }
            val reviewer = object : NovexLearningReviewer {
                override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                    if (trigger && fault == "before_model_send") {
                        trigger = false
                        event("interrupt_before_model", JSONObject())
                        throw CancellationException("基线：正文送模型前中断")
                    }
                    requests.put(JSONObject().put("document", request.documentRef.value)
                        .put("ranges", JSONArray(request.sourceRanges.map { JSONObject().put("block", it.blockId).put("start", it.start).put("end", it.end) })))
                    event("offline_request", JSONObject().put("system", request.prompt.system).put("user", request.prompt.user)
                        .put("estimated_input", request.estimatedInputTokens).put("max_output", request.maxOutputTokens))
                    observedInput += 100
                    observedOutput += 20
                    event("offline_response", JSONObject().put("input_tokens", 100).put("output_tokens", 20))
                    return NovexLearningReviewOutput(request.documentTitle, "离线控制响应，仅验证保存路径；不评价模型理解。", 100, 20)
                }
                override suspend fun synthesize(request: NovexLearningSynthesisRequest): NovexLearningReviewOutput {
                    observedInput += 100
                    observedOutput += 20
                    return NovexLearningReviewOutput("离线总览", "离线控制响应，不是语义总结。", 100, 20)
                }
            }
            val runner = NovexLearningReviewRunner(documents, reviewer, { checkpoint ->
                if (trigger && fault == "after_model_before_save" && checkpoint.notes.isNotEmpty()) {
                    trigger = false
                    event("interrupt_after_model_before_save", JSONObject())
                    throw IOException("基线：模型已返回、笔记未落盘")
                }
                repository.save(checkpoint)
                event("state_saved", JSONObject().put("notes", checkpoint.notes.size)
                    .put("input_tokens", checkpoint.task?.usage?.usedInputTokens)
                    .put("reviewed_blocks", checkpoint.reviewLedger.reviewedBlocks))
                if (trigger && fault == "after_save_before_ui" && checkpoint.notes.isNotEmpty()) {
                    trigger = false
                    event("interrupt_after_save_before_ui", JSONObject())
                    throw CancellationException("基线：已落盘、界面未更新")
                }
            }, responseJournal = if (useRecovery) repository else null)
            val firstFailure = runCatching { runner.run(initial) }.exceptionOrNull()
            repository = FileNovexLearningRepository(File(directory, "learning"))
            val restored = requireNotNull(repository.find(collection.ref))
            val observedBeforeResume = observedInput + observedOutput
            val recordedBeforeResume = restored.task!!.usage.usedInputTokens + restored.task.usage.usedOutputTokens
            if (fault == "after_model_before_save") finding("paid_usage_survives_failed_note_save",
                observedBeforeResume == recordedBeforeResume,
                JSONObject().put("provider_stub_observed_tokens", observedBeforeResume).put("persisted_tokens", recordedBeforeResume)
                    .put("saved_notes", restored.notes.size).put("failure", firstFailure?.message))
            val priorRequests = requests.length()
            val priorNotes = restored.notes.map { it.ref.value }
            val result = if (firstFailure != null) runner.run(restored) else restored
            val afterRestart = requireNotNull(FileNovexLearningRepository(File(directory, "learning")).find(collection.ref))
            val duplicateRequests = requests.let { array -> (0 until array.length()).map { array.getJSONObject(it).toString() } }
                .groupingBy { it }.eachCount().filterValues { it > 1 }
            finding("$fault:resume", if (fault == "after_model_before_save" && !useRecovery) true else duplicateRequests.isEmpty(),
                JSONObject().put("first_failure", firstFailure?.message).put("calls_before_resume", priorRequests)
                    .put("calls_after_resume", requests.length()).put("duplicate_ranges", JSONObject(duplicateRequests))
                    .put("notes_before_resume", JSONArray(priorNotes)).put("notes_after_resume", afterRestart.notes.size)
                    .put("status", afterRestart.task?.status?.name).put("observed_tokens", observedInput+observedOutput)
                    .put("persisted_tokens", afterRestart.task!!.usage.usedInputTokens+afterRestart.task.usage.usedOutputTokens))
            finding("$fault:incomplete_not_full", result.task?.status == NovexLearningTaskStatus.PARTIAL_FAILURE &&
                result.reviewLedger.unreadableSourceRefs.isNotEmpty(), JSONObject().put("status", result.task?.status?.name))
            File(directory, "final-state.json").writeText(NovexLearningStateJsonCodec.encode(afterRestart))
        }
        val first = collection.uniqueDocumentRefs.mapNotNull(documents::find).first { it.blocks.sumOf { b -> b.text.length } > 200 }
        val reader = NovexDocumentTools(documents)
        val slice = reader.documentRead(NovexDocumentReadRequest(first.ref, maxChars = 100))
        val original = requireNotNull(File(input, "sources").listFiles()).first { hash(it.readBytes()) == first.sha256 }
        val editedFile = File(output, "changed-source.docx")
        ZipInputStream(original.inputStream()).use { archive ->
            ZipOutputStream(editedFile.outputStream()).use { edited ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val bytes = archive.readBytes()
                    edited.putNextEntry(ZipEntry(entry.name))
                    edited.write(if (entry.name == "word/document.xml") bytes.toString(Charsets.UTF_8)
                        .replace("</w:body>", "<w:p><w:r><w:t>新增修订：仅用于验证来源变更，旧笔记尚未处理本段。</w:t></w:r></w:p></w:body>")
                        .toByteArray(Charsets.UTF_8) else bytes)
                    edited.closeEntry()
                }
            }
        }
        val newHash = hash(editedFile.readBytes())
        val revision = pipeline.resolveStructured(NovexDocumentDescriptor(NovexResourceRef("novex://documents/$newHash"),
            newHash, first.title, NovexDocumentFormat.DOCX, first.parserVersion)) {
            NovexDocxStreamingParser().parse(editedFile, newHash)
        }
        val newDocument = revision
        val newContinuation = reader.documentRead(NovexDocumentReadRequest(newDocument.ref, cursor = slice.data["next_cursor"] as String))
        val oldNotes = requireNotNull(FileNovexLearningRepository(File(output, "none/learning")).find(collection.ref)).notes
        finding("changed_source_rejects_old_cursor", newHash != first.sha256 && !newContinuation.ok,
            JSONObject().put("old_document", first.ref.value).put("new_document", newDocument.ref.value)
                .put("result", JSONObject(newContinuation.toJson())))
        finding("old_notes_do_not_cover_changed_source", oldNotes.flatMap { it.readRanges }.none { it.documentRef == newDocument.ref },
            JSONObject().put("old_notes", oldNotes.size).put("new_document", newDocument.ref.value)
                .put("source_revision_basis", "document reference contains original file SHA-256; parser revision absent from note"))
        val changed = first.copy(parserVersion = first.parserVersion+"-changed", blocks = first.blocks.mapIndexed { i,b ->
            if (i == 2) b.copy(text = "修订后的测试正文，不是原文。") else b
        })
        documents.store(NovexDocumentSnapshotCacheKey(changed.sha256, changed.parserVersion), changed)
        val continuation = reader.documentRead(NovexDocumentReadRequest(first.ref, cursor = slice.data["next_cursor"] as String))
        finding("changed_parser_rejects_old_cursor", !continuation.ok,
            JSONObject().put("result", JSONObject(continuation.toJson())))
        File(output, "findings.json").writeText(JSONObject().put("mode", mode).put("real_model_calls", 0)
            .put("scope", "production core on JVM; Android UI/provider adapter not executed")
            .put("findings", findings).toString(2))
        val failed = (0 until findings.length()).map { findings.getJSONObject(it) }.filterNot { it.getBoolean("passed") }
        assertTrue("基线发现 ${failed.size} 项未通过；证据已保存到 ${output.absolutePath}", failed.isEmpty())
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sendLive(prompt: NovexLearningPrompt, maxOutput: Int, messages: JSONArray? = null, tools: JSONArray? = null): JSONObject {
        val payload = JSONObject().put("model", "deepseek-v4-flash").put("stream", false)
            .put("thinking", JSONObject().put("type", "disabled")).put("max_tokens", maxOutput)
            .put("messages", messages ?: JSONArray().put(JSONObject().put("role", "system").put("content", prompt.system))
                .put(JSONObject().put("role", "user").put("content", prompt.user)))
        tools?.let { payload.put("tools", it) }
        val process = ProcessBuilder("python3", requireNotNull(System.getenv("NOVEX_BASELINE_BRIDGE")),
            "--authorization", requireNotNull(System.getenv("NOVEX_BASELINE_AUTHORIZATION")),
            "--vault-script", requireNotNull(System.getenv("NOVEX_BASELINE_VAULT")),
            "--ledger", requireNotNull(System.getenv("NOVEX_BASELINE_LEDGER"))).start()
        process.outputStream.bufferedWriter().use { it.write(payload.toString()) }
        val response = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { errors.take(1000) }
        return JSONObject(response)
    }

    private suspend fun runLive(initial: NovexLearningState, documents: FileNovexDocumentSnapshotRepository, input: File, output: File) {
        var repository = FileNovexLearningRepository(File(output, "learning"))
        repository.save(initial)
        var phase = 0
        var saveNumber = 0
        var completedCalls = 0
        var observedInput = 0
        var observedOutput = 0
        val interruptions = JSONArray()
        val history = File(output, "state-history").apply { mkdirs() }
        fun received(title: String, prompt: NovexLearningPrompt, reservedInput: Int, reservedOutput: Int): NovexLearningReviewOutput {
            val response = sendLive(prompt, reservedOutput)
            completedCalls++
            val usage = response.optJSONObject("usage")
            val result = NovexLearningReviewOutput.fromProvider(title,
                response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content"),
                usage?.optInt("prompt_tokens")?.takeIf { it > 0 }, usage?.optInt("completion_tokens")?.takeIf { it > 0 },
                reservedInput, reservedOutput, response.getJSONArray("choices").getJSONObject(0).optString("finish_reason").takeIf { it.isNotBlank() })
            observedInput += result.inputTokens
            observedOutput += result.outputTokens
            return result
        }
        val reviewer = object : NovexLearningReviewer {
            override suspend fun review(request: NovexLearningReviewRequest): NovexLearningReviewOutput {
                if (phase == 0) { phase = 1; throw CancellationException("QA_INJECT:before_model_send") }
                File(output, "source-requests.jsonl").appendText(JSONObject().put("document", request.documentRef.value)
                    .put("ranges", JSONArray(request.sourceRanges.map { JSONObject().put("block", it.blockId).put("start", it.start).put("end", it.end) }))
                    .put("system_sha256", hash(request.prompt.system.toByteArray())).put("phase", phase).toString()+"\n")
                return received("${request.documentTitle} · 通读笔记", request.prompt, request.estimatedInputTokens, request.maxOutputTokens)
            }
            override suspend fun synthesize(request: NovexLearningSynthesisRequest) =
                received("${request.collectionTitle} · 总结", request.prompt, request.estimatedInputTokens, request.maxOutputTokens)
        }
        val runner = NovexLearningReviewRunner(documents, reviewer, { checkpoint ->
            if (phase == 1 && checkpoint.notes.isNotEmpty()) {
                phase = 2
                throw IOException("QA_INJECT:after_model_before_save")
            }
            repository.save(checkpoint)
            File(history, "%04d.json".format(++saveNumber)).writeText(NovexLearningStateJsonCodec.encode(checkpoint))
            if (phase == 2 && checkpoint.notes.isNotEmpty()) {
                phase = 3
                throw CancellationException("QA_INJECT:after_save_before_ui")
            }
        }, responseJournal = if (System.getenv("NOVEX_BASELINE_USE_RECOVERY") == "true") repository else null)
        var state = initial
        var failure: Throwable? = null
        for (attempt in 1..4) {
            failure = runCatching { runner.run(state) }.exceptionOrNull()
            repository = FileNovexLearningRepository(File(output, "learning"))
            state = requireNotNull(repository.find(initial.collection.ref))
            interruptions.put(JSONObject().put("attempt", attempt).put("failure", failure?.message)
                .put("completed_model_calls", completedCalls).put("observed_input", observedInput).put("observed_output", observedOutput)
                .put("persisted_input", state.task!!.usage.usedInputTokens).put("persisted_output", state.task!!.usage.usedOutputTokens)
                .put("saved_notes", state.notes.size).put("status", state.task!!.status.name))
            File(output, "interruptions.json").writeText(interruptions.toString(2))
            if (failure == null || !failure.message.orEmpty().startsWith("QA_INJECT:")) break
            val paused = state.copy(task = state.task!!.pause())
            repository.save(paused)
            state = paused.copy(task = paused.task!!.resume())
            if (phase == 3) runQueries(state, documents, input, output, "during-partial", setOf("Q01"))
        }
        File(output, "final-state.json").writeText(NovexLearningStateJsonCodec.encode(state))
        File(output, "live-summary.json").writeText(JSONObject().put("completed_model_calls", completedCalls)
            .put("observed_input", observedInput).put("observed_output", observedOutput)
            .put("persisted_input", state.task!!.usage.usedInputTokens).put("persisted_output", state.task!!.usage.usedOutputTokens)
            .put("failure", failure?.message).put("status", state.task!!.status.name)
            .put("notes", state.notes.size).put("reviewed_blocks", state.reviewLedger.reviewedBlocks)
            .put("total_readable_blocks", state.reviewLedger.totalReadableBlocks)
            .put("query_scope", "current dedicated learning prompt and production core tools; full Android chat assembly not executed")
            .toString(2))
        if (failure == null) runQueries(state, documents, input, output, "after-review", null)
        assertTrue("真实小规模链路有未处理失败；原始证据已保存", failure == null)
    }

    private fun runQueries(state: NovexLearningState, documents: NovexDocumentSnapshotStore, input: File, output: File,
        stage: String, selected: Set<String>?) {
        val directory = File(output, "queries/$stage").apply { mkdirs() }
        val learning = NovexLearningTools(object : NovexLearningPreflightResolver {
            override fun prepare(collectionRef: NovexResourceRef, modelId: String?) = state.preflight
            override fun readState(collectionRef: NovexResourceRef) = state.takeIf { it.collection.ref == collectionRef }
        })
        val allowed = state.collection.uniqueDocumentRefs.toSet()
        val documentTools = NovexDocumentToolRouter(NovexDocumentTools(NovexDocumentSnapshotStore { ref ->
            if (ref in allowed) documents.find(ref) else null
        }))
        val definitions = NovexToolCatalog.forCapabilities(setOf(NovexToolCapability.DOCUMENTS, NovexToolCapability.LEARNING))
            .filter { it.name in setOf("document_inspect", "document_read", "learning_read") }
        val tools = JSONArray(definitions.map { definition ->
            val properties = JSONObject()
            definition.parameters.forEach { parameter ->
                val schema = JSONObject().put("description", parameter.description)
                when (parameter.kind) {
                    NovexToolParameterKind.STRING -> schema.put("type", "string")
                    NovexToolParameterKind.INTEGER -> schema.put("type", "integer")
                    NovexToolParameterKind.BOOLEAN -> schema.put("type", "boolean")
                    NovexToolParameterKind.STRING_LIST -> schema.put("type", "array").put("items", JSONObject().put("type", "string"))
                    NovexToolParameterKind.PAGE_RANGE -> schema.put("type", "object").put("properties", JSONObject()
                        .put("first", JSONObject().put("type", "integer")).put("last", JSONObject().put("type", "integer")))
                        .put("required", JSONArray(listOf("first", "last")))
                }
                properties.put(parameter.name, schema)
            }
            JSONObject().put("type", "function").put("function", JSONObject().put("name", definition.name)
                .put("description", definition.description).put("parameters", JSONObject().put("type", "object")
                    .put("properties", properties).put("required", JSONArray(definition.parameters.filter { it.required }.map { it.name }))))
        })
        val questions = JSONArray(File(input, "questions.json").readText())
        for (index in 0 until questions.length()) {
            val question = questions.getJSONObject(index)
            if (selected != null && question.getString("id") !in selected) continue
            val prompt = NovexLearningPrompt.synthesis(state.collection.title, emptyList(), null)
            val inventory = state.collection.sources.map { source -> JSONObject().put("title", source.title)
                .put("document_ref", source.documentRef?.value).put("status", source.status.name) }
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", prompt.system))
                .put(JSONObject().put("role", "user").put("content", "请回答资料查询并说明来源；本轮不是重做整份总览。问题：${question.getString("question")}\n"+
                    "资料集：${state.collection.ref.value}\n已整理 ${state.reviewLedger.reviewedBlocks}/${state.reviewLedger.totalReadableBlocks} 个可读块。"+
                    "原文目录仅用于定位，不代表读过全文：${JSONArray(inventory)}"))
            val exchanges = JSONArray()
            var complete = false
            for (turn in 1..6) {
                val response = sendLive(prompt, 2048, messages, tools)
                val answer = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
                messages.put(answer)
                val calls = answer.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) { complete = true; break }
                for (i in 0 until calls.length()) {
                    val call = calls.getJSONObject(i)
                    val function = call.getJSONObject("function")
                    val name = function.getString("name")
                    val arguments = function.getString("arguments")
                    val result = if (name == "learning_read") runCatching {
                        val args = JSONObject(arguments)
                        learning.learningRead(NovexResourceRef(args.getString("collection_ref")), args)
                    }.getOrElse { NovexToolResult.failure("tool.invalid_arguments", it.message.orEmpty()) }
                    else documentTools.execute(name, arguments)
                    exchanges.put(JSONObject().put("name", name).put("arguments", runCatching { JSONObject(arguments) }.getOrElse { arguments })
                        .put("result", JSONObject(result.toJson())))
                    messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.getString("id")).put("content", result.toJson()))
                }
                File(directory, "${question.getString("id")}.json").writeText(JSONObject().put("question", question)
                    .put("messages", messages).put("tools", exchanges).put("complete", false).toString(2))
            }
            File(directory, "${question.getString("id")}.json").writeText(JSONObject().put("question", question)
                .put("messages", messages).put("tools", exchanges).put("complete", complete).toString(2))
        }
    }
}
