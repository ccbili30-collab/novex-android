package com.openminis.app.novex.domain

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class NovexMemoryScopeKind(val wireName: String) {
    NOVA("nova"),
    ROLE("role"),
}

/** A durable memory namespace. Role memory is isolated by the complete role tuple. */
data class NovexMemoryScope(
    val kind: NovexMemoryScopeKind,
    val worldId: String? = null,
    val playerIdentityId: String? = null,
    val characterVersionId: String? = null,
) {
    init {
        if (kind == NovexMemoryScopeKind.ROLE) {
            require(!characterVersionId.isNullOrBlank()) { "角色记忆必须指定角色版本" }
        }
    }

    internal val storageKey: String
        get() = memorySha256(
            listOf(
                kind.wireName,
                worldId.orEmpty(),
                playerIdentityId.orEmpty(),
                characterVersionId.orEmpty(),
            ).joinToString("\u001f").toByteArray(Charsets.UTF_8),
        )

    companion object {
        fun nova() = NovexMemoryScope(NovexMemoryScopeKind.NOVA)

        fun role(
            worldId: String?,
            playerIdentityId: String?,
            characterVersionId: String,
        ) = NovexMemoryScope(
            kind = NovexMemoryScopeKind.ROLE,
            worldId = worldId?.takeIf(String::isNotBlank),
            playerIdentityId = playerIdentityId?.takeIf(String::isNotBlank),
            characterVersionId = characterVersionId,
        )
    }
}

data class NovexMemoryReadContext(
    val conversationId: String,
    val activeBranchIds: List<String>,
) {
    init {
        require(conversationId.isNotBlank()) { "记忆读取对话编号不能为空" }
        require(activeBranchIds.none(String::isBlank)) { "记忆读取分支编号不能为空" }
    }
}

data class NovexMemoryRef(
    val value: String,
    val entryId: String,
) {
    fun asResourceRef() = NovexResourceRef(value)

    companion object {
        fun create(scope: NovexMemoryScope, entryId: String): NovexMemoryRef {
            require(entryId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "记忆编号无效" }
            return NovexMemoryRef(
                value = "novex://memories/${scope.storageKey}/entries/$entryId",
                entryId = entryId,
            )
        }

        fun parse(scope: NovexMemoryScope, value: String): NovexMemoryRef {
            NovexResourceRef(value)
            val prefix = "novex://memories/${scope.storageKey}/entries/"
            require(value.startsWith(prefix)) { "记忆引用不属于当前记忆空间" }
            return create(scope, value.removePrefix(prefix))
        }
    }
}

data class NovexMemoryEntry(
    val ref: NovexMemoryRef,
    val content: String,
    val tags: List<String>,
    val sourceConversationId: String,
    val sourceBranchId: String,
    val sourceMessageId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val revision: String,
) {
    init {
        require(content.isNotBlank()) { "记忆内容不能为空" }
        require(content.length <= MAX_MEMORY_CONTENT_CHARS) { "单条记忆超过字符上限" }
        require(tags.size <= MAX_MEMORY_TAGS && tags.none(String::isBlank)) { "记忆标签无效" }
        require(sourceConversationId.isNotBlank() && sourceBranchId.isNotBlank()) { "记忆来源不能为空" }
        require(createdAtMillis >= 0 && updatedAtMillis >= createdAtMillis) { "记忆时间无效" }
        require(revision.matches(Regex("[0-9a-f]{64}"))) { "记忆版本必须是小写 SHA-256" }
    }

    fun isVisibleFrom(context: NovexMemoryReadContext): Boolean =
        sourceConversationId != context.conversationId || sourceBranchId in context.activeBranchIds
}

data class NovexMemoryInspection(
    val scope: NovexMemoryScope,
    val entries: List<NovexMemoryEntry>,
    val replayed: Boolean = false,
)

data class NovexMemoryCommit(val entries: List<NovexMemoryEntry>, val replayed: Boolean)

sealed interface NovexMemoryChange {
    data class Add(val entry: NovexMemoryEntry) : NovexMemoryChange

    data class Replace(
        val ref: NovexMemoryRef,
        val expectedRevision: String,
        val content: String,
        val tags: List<String>,
        val sourceConversationId: String,
        val sourceBranchId: String,
        val sourceMessageId: String?,
        val updatedAtMillis: Long,
    ) : NovexMemoryChange

    data class Remove(
        val ref: NovexMemoryRef,
        val expectedRevision: String,
    ) : NovexMemoryChange
}

data class NovexMemoryPlan(
    val id: String,
    val scope: NovexMemoryScope,
    val changes: List<NovexMemoryChange>,
    val summary: String,
) {
    init {
        require(id.isNotBlank()) { "记忆变更计划编号不能为空" }
        require(changes.isNotEmpty()) { "记忆变更计划不能为空" }
        require(summary.isNotBlank()) { "记忆变更摘要不能为空" }
    }

    val affectedRefs: List<NovexResourceRef>
        get() = changes.map { change ->
            when (change) {
                is NovexMemoryChange.Add -> change.entry.ref.asResourceRef()
                is NovexMemoryChange.Replace -> change.ref.asResourceRef()
                is NovexMemoryChange.Remove -> change.ref.asResourceRef()
            }
        }.distinct()

}

interface NovexMemoryStore {
    fun entries(scope: NovexMemoryScope): List<NovexMemoryEntry>
    fun apply(scope: NovexMemoryScope, changes: List<NovexMemoryChange>): List<NovexMemoryEntry>
    fun applyPlan(plan: NovexMemoryPlan): NovexMemoryCommit
}

class NovexMemoryService(
    private val store: NovexMemoryStore,
    private val entryIdFactory: () -> String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun inspect(
        scope: NovexMemoryScope,
        source: NovexMemoryReadContext,
        keywords: String = "",
        limit: Int = 100,
    ): NovexMemoryInspection {
        require(limit in 1..500) { "记忆读取上限必须在一到五百之间" }
        val terms = keywords.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
        return NovexMemoryInspection(
            scope = scope,
            entries = store.entries(scope)
                .asSequence()
                .filter { it.isVisibleFrom(source) }
                .filter { entry ->
                    terms.isEmpty() || terms.all { term ->
                        entry.content.lowercase().contains(term) || entry.tags.any { it.lowercase().contains(term) }
                    }
                }
                .take(limit)
                .toList(),
        )
    }

    fun propose(
        scope: NovexMemoryScope,
        changesJson: String,
        source: NovexMemoryReadContext,
        sourceBranchId: String,
        sourceMessageId: String?,
        planId: String,
    ): NovexMemoryPlan {
        require(sourceBranchId in source.activeBranchIds) { "记忆来源必须位于当前消息分支" }
        val visible = inspect(scope, source, limit = 500).entries.associateBy { it.ref.value }
        val raw = JSONArray(changesJson)
        require(raw.length() in 1..20) { "一次只能提出一到二十项记忆变更" }
        val now = nowMillis()
        val changes = (0 until raw.length()).map { index ->
            val value = raw.optJSONObject(index) ?: throw IllegalArgumentException("第 ${index + 1} 项记忆变更无效")
            when (value.getString("operation")) {
                "add" -> {
                    val id = entryIdFactory()
                    val content = normalizeMemoryContent(value.getString("content"))
                    val tags = normalizeMemoryTags(value.optJSONArray("tags").stringList())
                    NovexMemoryChange.Add(
                        NovexMemoryEntry(
                            ref = NovexMemoryRef.create(scope, id),
                            content = content,
                            tags = tags,
                            sourceConversationId = source.conversationId,
                            sourceBranchId = sourceBranchId,
                            sourceMessageId = sourceMessageId,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                            revision = memoryRevision(id, content, tags),
                        ),
                    )
                }

                "replace" -> {
                    val ref = NovexMemoryRef.parse(scope, value.getString("memory_ref"))
                    val existing = requireNotNull(visible[ref.value]) { "当前分支看不到指定记忆" }
                    val expected = value.getString("expected_revision")
                    require(existing.revision == expected) { "记忆已经变化，请重新检查" }
                    NovexMemoryChange.Replace(
                        ref = ref,
                        expectedRevision = expected,
                        content = normalizeMemoryContent(value.getString("content")),
                        tags = normalizeMemoryTags(
                            value.optJSONArray("tags")?.stringList() ?: existing.tags,
                        ),
                        sourceConversationId = source.conversationId,
                        sourceBranchId = sourceBranchId,
                        sourceMessageId = sourceMessageId,
                        updatedAtMillis = now,
                    )
                }

                "remove" -> {
                    val ref = NovexMemoryRef.parse(scope, value.getString("memory_ref"))
                    val existing = requireNotNull(visible[ref.value]) { "当前分支看不到指定记忆" }
                    val expected = value.getString("expected_revision")
                    require(existing.revision == expected) { "记忆已经变化，请重新检查" }
                    NovexMemoryChange.Remove(ref, expected)
                }

                else -> throw IllegalArgumentException("不支持的记忆操作；合法值：add, replace, remove")
            }
        }
        return NovexMemoryPlan(
            id = planId,
            scope = scope,
            changes = changes,
            summary = changes.joinToString("；") { change ->
                when (change) {
                    is NovexMemoryChange.Add -> "新增记忆“${change.entry.content.take(32)}”"
                    is NovexMemoryChange.Replace -> "更新记忆 ${change.ref.entryId}"
                    is NovexMemoryChange.Remove -> "删除记忆 ${change.ref.entryId}"
                }
            },
        )
    }

    /** The caller passes through the conversation execution gate before reaching this storage service. */
    fun apply(plan: NovexMemoryPlan): NovexMemoryInspection {
        val commit = store.applyPlan(plan)
        val changedRefs = plan.changes.map { change ->
            when (change) {
                is NovexMemoryChange.Add -> change.entry.ref.value
                is NovexMemoryChange.Replace -> change.ref.value
                is NovexMemoryChange.Remove -> change.ref.value
            }
        }.toSet()
        return NovexMemoryInspection(
            plan.scope,
            commit.entries.filter { it.ref.value in changedRefs },
            replayed = commit.replayed,
        )
    }
}

private const val MAX_MEMORY_CONTENT_CHARS = 8_000
private const val MAX_MEMORY_TAGS = 20

internal fun normalizeMemoryContent(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "记忆内容不能为空" }
    require(it.length <= MAX_MEMORY_CONTENT_CHARS) { "单条记忆超过字符上限" }
}

internal fun normalizeMemoryTags(values: List<String>): List<String> = values
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()
    .also { require(it.size <= MAX_MEMORY_TAGS) { "单条记忆最多二十个标签" } }

internal fun memoryRevision(id: String, content: String, tags: List<String>): String = memorySha256(
    (id + "\u001f" + content + "\u001f" + tags.joinToString("\u001f")).toByteArray(Charsets.UTF_8),
)

internal fun memorySha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal fun NovexMemoryScope.toJson() = JSONObject()
    .put("kind", kind.wireName)
    .put("world_id", worldId)
    .put("player_identity_id", playerIdentityId)
    .put("character_version_id", characterVersionId)

internal fun NovexMemoryEntry.toJson() = JSONObject()
    .put("memory_ref", ref.value)
    .put("content", content)
    .put("tags", JSONArray(tags))
    .put("source_conversation_id", sourceConversationId)
    .put("source_branch_id", sourceBranchId)
    .put("source_message_id", sourceMessageId)
    .put("created_at_millis", createdAtMillis)
    .put("updated_at_millis", updatedAtMillis)
    .put("revision", revision)

internal fun JSONObject.toMemoryEntry(scope: NovexMemoryScope) = NovexMemoryEntry(
    ref = NovexMemoryRef.parse(scope, getString("memory_ref")),
    content = getString("content"),
    tags = getJSONArray("tags").stringList(),
    sourceConversationId = getString("source_conversation_id"),
    sourceBranchId = getString("source_branch_id"),
    sourceMessageId = if (!has("source_message_id") || isNull("source_message_id")) null else getString("source_message_id"),
    createdAtMillis = getLong("created_at_millis"),
    updatedAtMillis = getLong("updated_at_millis"),
    revision = getString("revision"),
)

internal fun JSONArray?.stringList(): List<String> = if (this == null) emptyList() else
    (0 until length()).map { getString(it) }
