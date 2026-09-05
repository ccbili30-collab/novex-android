package com.openminis.app.novex.domain

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class NovexBatchDocumentRequest(
    val sourceRef: NovexResourceRef,
    val file: File,
    val mimeType: String?,
    val originalName: String,
) {
    init {
        require(sourceRef.value.startsWith("novex://sources/")) { "批量资料必须使用来源引用" }
        require(file.isFile) { "批量资料必须指向可读取文件" }
        require(originalName.isNotBlank()) { "批量资料名称不能为空" }
    }
}

fun interface NovexDocumentImportWorker {
    suspend fun import(request: NovexBatchDocumentRequest): NovexDocumentSnapshot?
}

data class NovexBatchDocumentOutcome(
    val sourceRef: NovexResourceRef,
    val title: String,
    val sha256: String,
    val snapshot: NovexDocumentSnapshot?,
    val failureCode: String?,
) {
    init {
        require(snapshot != null || !failureCode.isNullOrBlank()) { "批量解析结果必须包含快照或失败原因" }
    }

    fun toSourceImportResult() = NovexSourceImportResult(
        ref = sourceRef,
        title = title,
        sha256 = sha256,
        document = snapshot,
        failureCode = failureCode,
    )
}

/**
 * Runs independent document imports with a hard concurrency limit.
 * A failed source is returned as data so sibling imports keep running.
 */
class NovexBatchDocumentImporter(
    maxParallelism: Int,
    private val worker: NovexDocumentImportWorker,
) {
    private val semaphore = Semaphore(maxParallelism.also {
        require(it in 1..MAX_PARALLELISM) { "批量文档解析并行度必须在一到四之间" }
    })

    suspend fun importAll(requests: List<NovexBatchDocumentRequest>): List<NovexBatchDocumentOutcome> {
        require(requests.map { it.sourceRef }.distinct().size == requests.size) { "批量资料引用不能重复" }
        return supervisorScope {
            requests.map { request ->
                async {
                    semaphore.withPermit { importOne(request) }
                }
            }.awaitAll()
        }
    }

    private suspend fun importOne(request: NovexBatchDocumentRequest): NovexBatchDocumentOutcome {
        val sha256 = request.file.sha256()
        return try {
            val snapshot = worker.import(request)
            NovexBatchDocumentOutcome(
                sourceRef = request.sourceRef,
                title = request.originalName,
                sha256 = sha256,
                snapshot = snapshot,
                failureCode = if (snapshot == null) "document.unsupported" else null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NovexBatchDocumentOutcome(
                sourceRef = request.sourceRef,
                title = request.originalName,
                sha256 = sha256,
                snapshot = null,
                failureCode = "document.parse_failed",
            )
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    companion object {
        private const val MAX_PARALLELISM = 4
    }
}
