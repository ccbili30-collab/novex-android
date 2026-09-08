package com.openminis.app.novex.domain

import com.openminis.app.tools.ToolExecutionResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A concrete call, not a natural-language permission inferred from a document. */
data class NovexToolOperation(
    val conversationId: String,
    val replyId: String,
    val callId: String,
    val name: String,
    val arguments: String,
    val title: String,
    val reviewDetails: String = "",
) {
    init { require(listOf(conversationId, replyId, callId, name).all { it.isNotBlank() }) }
    val id: String get() = operationDigest("$conversationId|$replyId|$callId")
    val fingerprint: String get() = operationDigest("$name|${canonicalRevisionJson(JSONObject(arguments))}")
}

enum class NovexOperationStatus { WAITING, APPROVED, RUNNING, SUCCEEDED, FAILED, DENIED, INTERRUPTED }
data class NovexOperationRecord(val operation: NovexToolOperation, val status: NovexOperationStatus, val result: ToolExecutionResult? = null)

/** Private application records; model-editable workspace files cannot grant execution. */
class NovexOperationJournal(private val directory: File) {
    internal class Coordination {
        val mutex = Mutex()
        val active = mutableSetOf<String>()
        val decisions = mutableMapOf<String, CompletableDeferred<Boolean>>()
    }
    internal val coordination = coordinators.computeIfAbsent(directory.canonicalPath) { Coordination() }
    companion object {
        private val coordinators = java.util.concurrent.ConcurrentHashMap<String, Coordination>()
    }
    @Synchronized fun read(id: String): NovexOperationRecord? {
        val file = path(id)
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val operation = NovexToolOperation(json.getString("conversationId"), json.getString("replyId"),
            json.getString("callId"), json.getString("name"), json.getString("arguments"), json.getString("title"), json.optString("reviewDetails"))
        require(operation.id == id) { "操作记录归属不一致，未执行" }
        val result = json.optJSONObject("result")?.let {
            ToolExecutionResult(it.getString("output"), it.getBoolean("success"),
                it.optString("imageData").takeIf(String::isNotBlank)?.let(Base64.getDecoder()::decode),
                it.optional("imageMimeType"), it.optString("toolTitle"), it.optional("pageURL"),
                it.optional("imageFilePath"), it.optional("imageLinuxPath"), it.optBoolean("timedOut"))
        }
        return NovexOperationRecord(operation, NovexOperationStatus.valueOf(json.getString("status")), result)
    }
    @Synchronized fun list(conversationId: String): List<NovexOperationRecord> = directory.listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedBy { it.lastModified() }.mapNotNull { read(it.nameWithoutExtension) }
        .filter { it.operation.conversationId == conversationId }

    @Synchronized fun exportRecords(conversationId: String): Map<String, String> =
        list(conversationId).associate { it.operation.id to path(it.operation.id).readText() }

    @Synchronized fun save(record: NovexOperationRecord) {
        directory.mkdirs()
        val op = record.operation
        val json = JSONObject().put("conversationId", op.conversationId).put("replyId", op.replyId)
            .put("callId", op.callId).put("name", op.name).put("arguments", op.arguments).put("title", op.title)
            .put("status", record.status.name).put("reviewDetails", op.reviewDetails)
        record.result?.let { r -> json.put("result", JSONObject().put("output", r.output).put("success", r.success)
            .put("imageData", r.imageData?.let(Base64.getEncoder()::encodeToString))
            .put("imageMimeType", r.imageMimeType).put("toolTitle", r.toolTitle).put("pageURL", r.pageURL)
            .put("imageFilePath", r.imageFilePath).put("imageLinuxPath", r.imageLinuxPath).put("timedOut", r.timedOut)) }
        val target = path(op.id)
        val temp = File.createTempFile("operation-", ".pending", directory)
        try {
            java.io.FileOutputStream(temp).use { stream -> stream.write(json.toString().toByteArray()); stream.fd.sync() }
            try { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }
    private fun path(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "操作编号无效" }
        return File(directory, "$id.json")
    }
}

/** One execution seam for all tools. No tool handler owns a second approval policy. */
class NovexToolExecution(private val journal: NovexOperationJournal) {
    private val lock = journal.coordination.mutex
    private val decisions = journal.coordination.decisions
    private val active = journal.coordination.active
    private val mutablePending = MutableStateFlow<List<NovexToolOperation>>(emptyList())
    val pending: StateFlow<List<NovexToolOperation>> = mutablePending

    suspend fun restore(conversationId: String) = lock.withLock {
        journal.list(conversationId).filter { it.status == NovexOperationStatus.RUNNING && it.operation.id !in active }
            .forEach { journal.save(it.copy(status = NovexOperationStatus.INTERRUPTED)) }
        mutablePending.value = journal.list(conversationId).filter { it.status == NovexOperationStatus.WAITING }.map { it.operation }
    }

    /** Runtime must be stopped first; retain receipts, retire only unexecuted requests. */
    suspend fun closeConversation(conversationId: String) = lock.withLock {
        val records = journal.list(conversationId)
        require(records.none { it.operation.id in active }) { "对话仍有操作正在结束，请稍后重试删除" }
        records.filter { it.status in setOf(NovexOperationStatus.WAITING, NovexOperationStatus.APPROVED,
            NovexOperationStatus.RUNNING) }.forEach { record ->
            journal.save(record.copy(status = if (record.status == NovexOperationStatus.RUNNING)
                NovexOperationStatus.INTERRUPTED else NovexOperationStatus.DENIED))
            decisions.remove(record.operation.id)?.complete(false)
        }
        mutablePending.value = mutablePending.value.filterNot { it.conversationId == conversationId }
    }

    suspend fun decide(operation: NovexToolOperation, approve: Boolean) = lock.withLock {
        val record = journal.read(operation.id) ?: return@withLock
        require(record.operation == operation) { "待批准操作已改变，请重新查看" }
        if (approve && record.status in setOf(NovexOperationStatus.APPROVED, NovexOperationStatus.RUNNING,
                NovexOperationStatus.SUCCEEDED, NovexOperationStatus.FAILED) ||
            !approve && record.status == NovexOperationStatus.DENIED) return@withLock
        require(record.status == NovexOperationStatus.WAITING) { "操作状态已改变，请重新查看" }
        journal.save(record.copy(status = if (approve) NovexOperationStatus.APPROVED else NovexOperationStatus.DENIED))
        mutablePending.value = mutablePending.value.filterNot { it.id == operation.id }
        decisions.remove(operation.id)?.complete(approve)
    }

    /** Read historical evidence without re-running the tool or rebuilding a now-stale proposal. */
    suspend fun recordedResult(operation: NovexToolOperation): ToolExecutionResult? = lock.withLock {
        val record = journal.read(operation.id) ?: return@withLock null
        require(record.operation.fingerprint == operation.fingerprint) { "操作参数与已保存回执不一致" }
        record.result
    }

    /** Recovery may discover that a waiting call is no longer available in this branch/mode. */
    suspend fun retireUnexecutable(operation: NovexToolOperation, reason: String): ToolExecutionResult = lock.withLock {
        val previous = journal.read(operation.id)
        require(previous == null || previous.operation.fingerprint == operation.fingerprint) { "操作参数与已保存记录不一致" }
        previous?.result?.let { return@withLock it }
        require(operation.id !in active) { "该操作仍在执行，不能替换结果" }
        val result = denied(reason)
        journal.save(NovexOperationRecord(previous?.operation ?: operation, NovexOperationStatus.FAILED, result))
        mutablePending.value = mutablePending.value.filterNot { it.id == operation.id }
        decisions.remove(operation.id)?.complete(false)
        result
    }

    suspend fun execute(operation: NovexToolOperation, mode: () -> NovexExecutionMode,
        effect: suspend () -> ToolExecutionResult): ToolExecutionResult {
        if (mode() == NovexExecutionMode.READ_ONLY) return denied("当前对话为只读，本次没有执行工具")
        var waiting: CompletableDeferred<Boolean>? = null
        var previous: ToolExecutionResult? = null
        lock.withLock {
            val record = journal.read(operation.id)
            require(record == null || record.operation.name == operation.name && record.operation.fingerprint == operation.fingerprint) {
                "同一操作的参数已改变，旧批准不能复用"
            }
            if (record?.result != null) { previous = record.result; return@withLock }
            if (record?.status in setOf(NovexOperationStatus.DENIED, NovexOperationStatus.INTERRUPTED, NovexOperationStatus.RUNNING)) {
                previous = denied(if (record?.status == NovexOperationStatus.DENIED) "此操作已被拒绝，未执行" else "先前操作已中断，结果需要核对，未重复执行")
                return@withLock
            }
            if (!active.add(operation.id)) { previous = denied("此操作已在处理中，未重复执行"); return@withLock }
            if (mode() == NovexExecutionMode.APPROVAL && record?.status != NovexOperationStatus.APPROVED) {
                journal.save(NovexOperationRecord(operation, NovexOperationStatus.WAITING))
                waiting = CompletableDeferred<Boolean>().also { decisions[operation.id] = it }
                mutablePending.value = mutablePending.value.filterNot { it.id == operation.id } + operation
            }
        }
        previous?.let { return it }
        try {
            if (waiting?.await() == false) return denied("你已拒绝此操作，本次没有执行")
            if (mode() == NovexExecutionMode.READ_ONLY) {
                lock.withLock { journal.save(NovexOperationRecord(operation, NovexOperationStatus.DENIED)) }
                return denied("对话已切换为只读，本次没有执行工具")
            }
            // A free call cannot silently inherit approval after switching to approval mode.
            if (mode() == NovexExecutionMode.APPROVAL && journal.read(operation.id)?.status != NovexOperationStatus.APPROVED) {
                lock.withLock { active.remove(operation.id) }
                return execute(operation, mode, effect)
            }
            lock.withLock { journal.save(NovexOperationRecord(operation, NovexOperationStatus.RUNNING)) }
            val result = NovexActiveToolAuthorization.during(operation, mode, effect)
            lock.withLock { journal.save(NovexOperationRecord(operation,
                if (result.success) NovexOperationStatus.SUCCEEDED else NovexOperationStatus.FAILED, result)) }
            return result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { lock.withLock {
                val record = journal.read(operation.id)
                if (record?.status == NovexOperationStatus.RUNNING) journal.save(record.copy(status = NovexOperationStatus.INTERRUPTED))
            } }
            throw cancelled
        } catch (error: Exception) {
            lock.withLock {
                val record = journal.read(operation.id)
                if (record?.status == NovexOperationStatus.RUNNING) journal.save(record.copy(status = NovexOperationStatus.INTERRUPTED))
            }
            throw error
        } finally {
            withContext(NonCancellable) { lock.withLock { active.remove(operation.id); decisions.remove(operation.id) } }
        }
    }
    private fun denied(message: String) = ToolExecutionResult(message, false, toolTitle = "未执行")
}

private fun JSONObject.optional(key: String): String? = optString(key).takeIf { has(key) && !isNull(key) }
private fun operationDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
