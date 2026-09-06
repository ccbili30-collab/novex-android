package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterVersionEntity
import org.json.JSONObject

/** Describes another version relative to the source; independent of the default/original flag. */
enum class NovexCharacterVersionRelationKind(val wireName: String, val label: String) {
    EARLIER_STAGE("earlier_stage", "更早人生阶段"),
    LATER_STAGE("later_stage", "更晚人生阶段"),
    PARALLEL("parallel", "平行分身");

    fun reversed(): NovexCharacterVersionRelationKind = when (this) {
        EARLIER_STAGE -> LATER_STAGE
        LATER_STAGE -> EARLIER_STAGE
        PARALLEL -> PARALLEL
    }
}

data class NovexCharacterVersionRelation(
    val id: String,
    val sourceVersionId: String,
    val targetVersionId: String,
    val kind: NovexCharacterVersionRelationKind,
    val preservedJson: String = "{}",
    val unresolvedTargetVersionId: String? = null,
) {
    init {
        require(id.isNotBlank() && sourceVersionId.isNotBlank() && targetVersionId.isNotBlank()) { "版本关系必须包含稳定编号" }
    }
    fun otherVersionId(versionId: String): String {
        require(versionId == sourceVersionId || versionId == targetVersionId) { "该版本不在此关系中" }
        return if (sourceVersionId == versionId) targetVersionId else sourceVersionId
    }

    fun kindFor(versionId: String): NovexCharacterVersionRelationKind {
        otherVersionId(versionId)
        return if (sourceVersionId == versionId) kind else kind.reversed()
    }
}

internal interface NovexCharacterVersionRelationPort {
    suspend fun get(id: String): NovexCharacterVersionRelation?
    suspend fun forCharacter(characterId: String): List<NovexCharacterVersionRelation>
    suspend fun forVersion(versionId: String): List<NovexCharacterVersionRelation>
    suspend fun save(characterId: String, relation: NovexCharacterVersionRelation)
    suspend fun delete(id: String)
}

internal object UnavailableNovexVersionRelations : NovexCharacterVersionRelationPort {
    override suspend fun get(id: String): NovexCharacterVersionRelation? = null
    override suspend fun forCharacter(characterId: String) = emptyList<NovexCharacterVersionRelation>()
    override suspend fun forVersion(versionId: String) = emptyList<NovexCharacterVersionRelation>()
    override suspend fun save(characterId: String, relation: NovexCharacterVersionRelation) = error("尚未配置人物版本关系存储")
    override suspend fun delete(id: String) = error("尚未配置人物版本关系存储")
}

internal object NovexCharacterVersionRelationCodec {
    fun encode(relation: NovexCharacterVersionRelation): String = JSONObject(relation.preservedJson)
        .put("id", relation.id).put("sourceVersionId", relation.sourceVersionId).put("targetVersionId", relation.targetVersionId)
        .put("kind", relation.kind.name)
        .put("unresolvedTargetVersionId", relation.unresolvedTargetVersionId).toString()

    fun decode(raw: String): NovexCharacterVersionRelation {
        val json = JSONObject(raw)
        val extensions = JSONObject(raw).apply {
            listOf("id", "sourceVersionId", "targetVersionId", "kind", "unresolvedTargetVersionId").forEach(::remove)
        }
        return NovexCharacterVersionRelation(json.getString("id"), json.getString("sourceVersionId"), json.getString("targetVersionId"),
            NovexCharacterVersionRelationKind.valueOf(json.getString("kind")), extensions.toString(),
            json.optString("unresolvedTargetVersionId").takeIf { it.isNotBlank() && it != "null" })
    }
}

object NovexCharacterVersionRelationRules {
    fun validate(relation: NovexCharacterVersionRelation, versions: List<CharacterVersionEntity>,
        existing: List<NovexCharacterVersionRelation>, allowMissingTarget: Boolean = false) {
        require(relation.sourceVersionId != relation.targetVersionId) { "一个版本不能与自己建立阶段或平行关系" }
        val source = requireNotNull(versions.singleOrNull { it.id == relation.sourceVersionId }) { "关系来源版本不存在" }
        val target = versions.singleOrNull { it.id == relation.targetVersionId }
        require(target != null || (allowMissingTarget && relation.unresolvedTargetVersionId != null)) { "关系目标版本不存在" }
        require(target == null || source.characterId == target.characterId) { "人生阶段和平行分身必须属于同一个人物；不同人物请使用人物关系" }
        require(target == null || relation.unresolvedTargetVersionId == null) { "已解析的目标不能同时标为缺失" }
        existing.singleOrNull { it.id == relation.id }?.let {
            require(it.sourceVersionId == relation.sourceVersionId) { "关系编号属于另一来源版本" }
        }
        val pair = setOf(relation.sourceVersionId, relation.targetVersionId)
        require(existing.none { it.id != relation.id && setOf(it.sourceVersionId, it.targetVersionId) == pair }) {
            "这两个版本已有关系，请明确修改已有关系"
        }
        val proposed = relation.chronologicalEdge() ?: return
        val adjacency = existing.filter { it.id != relation.id }.mapNotNull { it.chronologicalEdge() }
            .groupBy({ it.first }, { it.second })
        val queue = ArrayDeque<String>().apply { add(proposed.second) }
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            require(current != proposed.first) { "人生阶段的先后关系形成循环，请修正顺序" }
            if (visited.add(current)) adjacency[current].orEmpty().forEach(queue::add)
        }
    }

    private fun NovexCharacterVersionRelation.chronologicalEdge(): Pair<String, String>? = when (kind) {
        NovexCharacterVersionRelationKind.EARLIER_STAGE -> targetVersionId to sourceVersionId
        NovexCharacterVersionRelationKind.LATER_STAGE -> sourceVersionId to targetVersionId
        NovexCharacterVersionRelationKind.PARALLEL -> null
    }
}
