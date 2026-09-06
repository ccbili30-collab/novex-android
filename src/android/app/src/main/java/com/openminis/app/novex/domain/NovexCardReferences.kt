package com.openminis.app.novex.domain

import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import org.json.JSONObject

enum class NovexReferencePurpose(val wireName: String, val label: String) {
    BACKGROUND("background", "背景资料"),
    ANSWER_IDENTITY("answer_identity", "回答身份"),
    PLAYER_IDENTITY("player_identity", "玩家身份"),
    RULES("rules", "玩法与规则"),
    MANAGEMENT("management", "管理与创作目标"),
}

enum class NovexReferenceTargetStatus(val label: String) {
    AVAILABLE("可用"), MISSING_CARD("目标卡片缺失"), MISSING_MODULE("目标模块缺失"), MISSING_ENTRY("目标条目缺失"),
}

data class NovexReferenceTarget(
    val subject: NovexContentAddress,
    val moduleId: String? = null,
    val entryId: String? = null,
) {
    init {
        require(moduleId == null || moduleId.isNotBlank()) { "目标模块编号不能为空" }
        require(entryId == null || (entryId.isNotBlank() && moduleId != null)) { "条目引用必须包含模块及条目编号" }
    }
}

data class NovexCardReference(
    val id: String,
    val source: NovexContentAddress,
    val target: NovexReferenceTarget,
    val purpose: NovexReferencePurpose,
    val sourceModuleId: String? = null,
    val position: Int = 0,
    val targetLabel: String = "",
    /** Foreign address retained when a dependency is absent; never used to look up local content. */
    val unresolvedTarget: NovexReferenceTarget? = null,
) {
    init {
        require(id.isNotBlank()) { "引用编号不能为空" }
        require(sourceModuleId == null || sourceModuleId.isNotBlank()) { "来源模块编号不能为空" }
        require(position >= 0) { "引用顺序不能为负数" }
        require(source.kind != NovexContentKind.CREATIVE_ARTIFACT && target.subject.kind != NovexContentKind.CREATIVE_ARTIFACT) {
            "卡片互引适用于世界、角色版本和文游；文件沿用成果附加关系"
        }
    }
}

internal interface NovexCardReferencePort {
    suspend fun get(id: String): NovexCardReference?
    suspend fun outgoing(source: NovexContentAddress): List<NovexCardReference>
    suspend fun incoming(target: NovexContentAddress): List<NovexCardReference>
    suspend fun save(reference: NovexCardReference)
    suspend fun delete(id: String)
    suspend fun deleteSource(source: NovexContentAddress)
    suspend fun deleteSourceModule(moduleId: String)
}

internal object UnavailableNovexCardReferences : NovexCardReferencePort {
    override suspend fun get(id: String): NovexCardReference? = null
    override suspend fun outgoing(source: NovexContentAddress) = emptyList<NovexCardReference>()
    override suspend fun incoming(target: NovexContentAddress) = emptyList<NovexCardReference>()
    override suspend fun save(reference: NovexCardReference) = error("尚未配置卡片引用存储")
    override suspend fun delete(id: String) = error("尚未配置卡片引用存储")
    override suspend fun deleteSource(source: NovexContentAddress) = Unit
    override suspend fun deleteSourceModule(moduleId: String) = Unit
}

/** Reference edits validate exact addresses; adding a link never mounts or activates its target. */
internal class NovexCardReferences(
    private val links: NovexCardReferencePort,
    private val catalog: NovexCatalogPort,
    private val fiction: NovexInteractiveFictionPort,
    private val content: NovexContentPort,
) {
    suspend fun put(reference: NovexCardReference, allowMissingTarget: Boolean = false): NovexCardReference {
        require(exists(reference.source)) { "引用来源卡片不存在" }
        val status = status(reference.target)
        require(allowMissingTarget || status == NovexReferenceTargetStatus.AVAILABLE) { "${status.label}；请按编号选择，不能按重名连接" }
        links.get(reference.id)?.let { require(it.source == reference.source) { "引用编号属于另一张来源卡片" } }
        reference.sourceModuleId?.let { requireModule(it, reference.source) }
        reference.target.moduleId?.let { if (!allowMissingTarget || content.module(it) != null) requireModule(it, reference.target.subject) }
        if (reference.purpose == NovexReferencePurpose.ANSWER_IDENTITY) {
            require(reference.target.subject.kind == NovexContentKind.CHARACTER_VERSION && reference.target.moduleId == null) {
                "回答身份必须指向完整的具体角色版本"
            }
        }
        if (reference.purpose in setOf(NovexReferencePurpose.ANSWER_IDENTITY, NovexReferencePurpose.PLAYER_IDENTITY)) {
            require(links.outgoing(reference.source).none {
                it.id != reference.id && it.sourceModuleId == reference.sourceModuleId && it.purpose == reference.purpose
            }) { "已有${reference.purpose.label}引用；请明确替换已有引用" }
        }
        links.save(reference)
        return reference
    }

    suspend fun status(target: NovexReferenceTarget): NovexReferenceTargetStatus {
        if (!exists(target.subject)) return NovexReferenceTargetStatus.MISSING_CARD
        val moduleId = target.moduleId ?: return NovexReferenceTargetStatus.AVAILABLE
        val module = content.module(moduleId) ?: return NovexReferenceTargetStatus.MISSING_MODULE
        if (module.owner != owner(target.subject)) return NovexReferenceTargetStatus.MISSING_MODULE
        target.entryId?.let { entryId ->
            val collection = ContentModuleDocumentCodec.decode(module.type, module.contentJson) as? ContentModuleDocument.Collection
            if (collection?.items.orEmpty().none { it.id == entryId }) return NovexReferenceTargetStatus.MISSING_ENTRY
        }
        return NovexReferenceTargetStatus.AVAILABLE
    }

    private suspend fun exists(subject: NovexContentAddress): Boolean = when (subject.kind) {
        NovexContentKind.WORLD -> catalog.world(subject.id) != null
        NovexContentKind.CHARACTER_VERSION -> catalog.version(subject.id) != null
        NovexContentKind.INTERACTIVE_FICTION -> fiction.project(subject.id) != null
        NovexContentKind.CREATIVE_ARTIFACT -> false
    }

    private suspend fun requireModule(id: String, subject: NovexContentAddress) {
        val module = requireNotNull(content.module(id)) { "引用模块不存在：$id" }
        require(module.owner == owner(subject)) { "引用模块不属于指定卡片" }
    }

    private fun owner(subject: NovexContentAddress) = when (subject.kind) {
        NovexContentKind.WORLD -> ModuleOwner.world(subject.id)
        NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(subject.id)
        NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(subject.id)
        NovexContentKind.CREATIVE_ARTIFACT -> error("文件不拥有卡片模块")
    }
}

internal object NovexCardReferenceCodec {
    fun encode(value: NovexCardReference): String = JSONObject()
        .put("id", value.id).put("source", address(value.source))
        .put("target", address(value.target.subject).put("moduleId", value.target.moduleId).put("entryId", value.target.entryId))
        .put("purpose", value.purpose.name).put("sourceModuleId", value.sourceModuleId)
        .put("position", value.position).put("targetLabel", value.targetLabel)
        .put("unresolvedTarget", value.unresolvedTarget?.let { address(it.subject).put("moduleId", it.moduleId).put("entryId", it.entryId) }).toString()

    fun decode(raw: String): NovexCardReference {
        val value = JSONObject(raw)
        val target = value.getJSONObject("target")
        return NovexCardReference(value.getString("id"), address(value.getJSONObject("source")),
            NovexReferenceTarget(address(target), target.optionalText("moduleId"), target.optionalText("entryId")),
            NovexReferencePurpose.valueOf(value.getString("purpose")), value.optionalText("sourceModuleId"),
            value.optInt("position"), value.optString("targetLabel"), value.optJSONObject("unresolvedTarget")?.let {
                NovexReferenceTarget(address(it), it.optionalText("moduleId"), it.optionalText("entryId"))
            })
    }

    private fun JSONObject.optionalText(key: String) = optString(key).takeIf { it.isNotBlank() }
    private fun address(value: NovexContentAddress) = JSONObject().put("kind", value.kind.name).put("id", value.id)
    private fun address(value: JSONObject) = NovexContentAddress(NovexContentKind.valueOf(value.getString("kind")), value.getString("id"))
}
