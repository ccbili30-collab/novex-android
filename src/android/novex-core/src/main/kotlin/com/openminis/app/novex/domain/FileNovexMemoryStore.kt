package com.openminis.app.novex.domain

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** One-file-per-scope adapter; every change batch is validated and replaced atomically. */
class FileNovexMemoryStore(
    private val root: File,
) : NovexMemoryStore {
    init {
        require(root.exists() || root.mkdirs()) { "无法创建 Novex 记忆目录" }
        require(root.isDirectory) { "Novex 记忆目录无效" }
    }

    private val lock = locks.computeIfAbsent(root.canonicalPath) { Any() }
    private data class Stored(val entries: List<NovexMemoryEntry>, val appliedPlans: Map<String, String> = emptyMap())
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>() }

    override fun entries(scope: NovexMemoryScope): List<NovexMemoryEntry> = synchronized(lock) { read(scope).entries }
    override fun apply(scope: NovexMemoryScope, changes: List<NovexMemoryChange>): List<NovexMemoryEntry> =
        applyBatch(scope, changes, null).entries
    override fun applyPlan(plan: NovexMemoryPlan): NovexMemoryCommit =
        applyBatch(plan.scope, plan.changes, plan.id to NovexMemoryPlanCodec.fingerprint(plan))

    private fun applyBatch(scope: NovexMemoryScope, changes: List<NovexMemoryChange>, receipt: Pair<String, String>?): NovexMemoryCommit = synchronized(lock) {
        require(changes.isNotEmpty()) { "记忆变更不能为空" }
        val stored = read(scope)
        receipt?.let { (id, fingerprint) -> stored.appliedPlans[id]?.let { saved ->
            require(saved == fingerprint) { "同一记忆计划的内容发生变化，未执行" }
            return@synchronized NovexMemoryCommit(stored.entries, true)
        } }
        val current = stored.entries.associateByTo(linkedMapOf(), { it.ref.value }, { it })
        changes.forEach { change ->
            when (change) {
                is NovexMemoryChange.Add -> {
                    require(change.entry.ref.value !in current) { "记忆编号已经存在" }
                    current[change.entry.ref.value] = change.entry
                }

                is NovexMemoryChange.Replace -> {
                    val existing = requireNotNull(current[change.ref.value]) { "记忆已经不存在" }
                    require(existing.revision == change.expectedRevision) { "记忆已经变化，请重新检查" }
                    val content = normalizeMemoryContent(change.content)
                    val tags = normalizeMemoryTags(change.tags)
                    current[change.ref.value] = existing.copy(
                        content = content,
                        tags = tags,
                        sourceConversationId = change.sourceConversationId,
                        sourceBranchId = change.sourceBranchId,
                        sourceMessageId = change.sourceMessageId,
                        updatedAtMillis = change.updatedAtMillis,
                        revision = memoryRevision(existing.ref.entryId, content, tags),
                    )
                }

                is NovexMemoryChange.Remove -> {
                    val existing = requireNotNull(current[change.ref.value]) { "记忆已经不存在" }
                    require(existing.revision == change.expectedRevision) { "记忆已经变化，请重新检查" }
                    current.remove(change.ref.value)
                }
            }
        }
        val result = current.values.sortedWith(
            compareByDescending<NovexMemoryEntry>(NovexMemoryEntry::updatedAtMillis)
                .thenBy { it.ref.value },
        )
        write(scope, Stored(result, stored.appliedPlans + listOfNotNull(receipt).toMap()))
        NovexMemoryCommit(result, false)
    }

    private fun read(scope: NovexMemoryScope): Stored {
        val file = scopeFile(scope)
        if (!file.isFile) return Stored(emptyList())
        return runCatching {
            val rootJson = JSONObject(file.readText())
            require(rootJson.getInt("version") == 1) { "不支持的记忆存储版本" }
            val entries = rootJson.getJSONArray("entries")
            val receipts = rootJson.optJSONObject("applied_plans") ?: JSONObject()
            Stored((0 until entries.length()).map { index -> entries.getJSONObject(index).toMemoryEntry(scope) },
                receipts.keys().asSequence().associateWith { receipts.getString(it) })
        }.getOrElse { failure -> throw IllegalStateException("Novex 记忆存储损坏", failure) }
    }

    private fun write(scope: NovexMemoryScope, stored: Stored) {
        val bytes = JSONObject()
            .put("version", 1)
            .put("scope", scope.toJson())
            .put("entries", JSONArray(stored.entries.map(NovexMemoryEntry::toJson)))
            .put("applied_plans", JSONObject(stored.appliedPlans))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val target = scopeFile(scope)
        val temporary = File(root, ".${target.name}.${System.nanoTime()}.tmp")
        java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        try {
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun scopeFile(scope: NovexMemoryScope): File = File(root, "${scope.storageKey}.json")
}

