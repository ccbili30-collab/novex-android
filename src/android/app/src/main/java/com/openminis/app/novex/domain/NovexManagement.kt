package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ContentModuleDocumentContract
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.ModuleReferenceTarget
import com.openminis.app.data.character.ModuleReferenceTargetType
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import org.json.JSONArray
import org.json.JSONObject

sealed interface NovexManagedChange {
    data class PutVersionRelation(val relation: NovexCharacterVersionRelation) : NovexManagedChange
    data class RemoveVersionRelation(val sourceVersionId: String, val relationId: String) : NovexManagedChange
    data class PutCardReference(val reference: NovexCardReference) : NovexManagedChange
    data class RemoveCardReference(val source: NovexContentAddress, val referenceId: String) : NovexManagedChange
    data class AddModule(
        val owner: ModuleOwner,
        val type: ContentModuleType,
        val name: String,
        val contentJson: String,
    ) : NovexManagedChange

    data class UpdateModule(
        val moduleId: String,
        val name: String? = null,
        val contentJson: String? = null,
    ) : NovexManagedChange

    data class MoveModule(val moduleId: String, val toIndex: Int) : NovexManagedChange
    data class DeleteModule(val moduleId: String) : NovexManagedChange
    data class AddModuleReference(
        val moduleId: String,
        val target: ModuleReferenceTarget,
        val position: Int,
    ) : NovexManagedChange

    data class RemoveModuleReference(
        val moduleId: String,
        val target: ModuleReferenceTarget,
    ) : NovexManagedChange

    data class CreateWorld(
        val name: String, val overview: String, val modules: List<NovexModuleDraft> = emptyList(),
    ) : NovexManagedChange
    data class CreateCharacter(
        val name: String, val profileJson: String, val modules: List<NovexModuleDraft> = emptyList(),
    ) : NovexManagedChange
    data class CreateCharacterVersion(
        val sourceVersionId: String,
        val label: String,
        val profileJson: String,
    ) : NovexManagedChange

    data class CreateInteractiveFiction(
        val name: String,
        val summary: String,
        val launchMode: InteractiveFictionLaunchMode,
        val playerIdentity: String,
        val modules: List<NovexModuleDraft> = emptyList(),
    ) : NovexManagedChange

    data class LinkCharacterVersion(
        val worldId: String,
        val versionId: String,
        val position: Int,
    ) : NovexManagedChange

    data class UnlinkCharacterVersion(
        val worldId: String,
        val versionId: String,
    ) : NovexManagedChange

    data class AttachArtifact(
        val artifactId: String,
        val owner: NovexContentAddress,
        val moduleId: String? = null,
        val slot: String? = null,
    ) : NovexManagedChange

    data class DetachArtifact(
        val artifactId: String,
        val owner: NovexContentAddress,
        val moduleId: String? = null,
        val slot: String? = null,
    ) : NovexManagedChange
}

data class NovexManagementFacts(
    val moduleOwners: Map<String, ModuleOwner> = emptyMap(),
    val versionCharacterIds: Map<String, String> = emptyMap(),
    val versionWorldCounts: Map<String, Int> = emptyMap(),
    val existingArtifactIds: Set<String> = emptySet(),
)

enum class NovexManagementRisk {
    SHARED_CHANGE,
    CROSS_PROJECT,
    DESTRUCTIVE,
    CREATE_GLOBAL,
    CREATE_PRIVATE,
}

data class NovexManagementPlan(
    val id: String,
    val conversationId: String,
    val changes: List<NovexManagedChange>,
    val targets: Set<NovexContentAddress>,
    val risk: NovexManagementRisk,
    val summary: String,
    val impact: List<String> = emptyList(),
    val draftTargets: Map<Int, NovexContentAddress> = emptyMap(),
    val authorizedUserRequest: String? = null,
    val directEditIndices: Set<Int> = emptySet(),
    val expectedModuleContents: Map<String, String> = emptyMap(),
    val creationRequestScope: String? = null,
) {
    init {
        require(id.isNotBlank()) { "变更计划编号不能为空" }
        require(conversationId.isNotBlank()) { "对话编号不能为空" }
        require(changes.isNotEmpty()) { "变更计划不能为空" }
    }


}

object NovexManagementPolicy {
    fun canRead(
        configuration: NovexConversationConfigurationSnapshot,
        target: NovexContentAddress,
    ): Boolean = configuration.managedSubjects.any { it.subject == target }

    fun plan(
        configuration: NovexConversationConfigurationSnapshot,
        changes: List<NovexManagedChange>,
        facts: NovexManagementFacts,
        latestUserRequest: String,
        planId: String,
        priorUserRequests: List<String> = emptyList(),
    ): NovexManagementPlan {
        require(changes.isNotEmpty()) { "至少需要一项变更" }
        require(changes.size <= 20) { "一次最多修改二十项内容" }
        val targets = changes.flatMap { it.targets(facts) }.toSet()
        val createChanges = changes.filter { it.isCreation() }
        // Natural language is interpreted by the model. This layer validates actual objects;
        // the shared tool execution seam is the only source of operation approval.
        targets.forEach { target ->
            val access = configuration.managedSubjects.firstOrNull { it.subject == target }?.access
            require(access == ManagedAccess.EDIT) { "没有修改${target.kind.displayName()}的授权：${target.id}" }
        }
        changes.forEach { change ->
            when (change) {
                is NovexManagedChange.AttachArtifact -> require(change.artifactId in facts.existingArtifactIds) {
                    "创作成果不存在：${change.artifactId}"
                }.also { requireArtifactModuleOwner(change.owner, change.moduleId, facts) }
                is NovexManagedChange.DetachArtifact -> require(change.artifactId in facts.existingArtifactIds) {
                    "创作成果不存在：${change.artifactId}"
                }.also { requireArtifactModuleOwner(change.owner, change.moduleId, facts) }
                is NovexManagedChange.CreateCharacterVersion -> require(
                    change.sourceVersionId in facts.versionCharacterIds,
                ) { "来源角色版本不存在" }
                else -> Unit
            }
        }

        val risk = when {
            changes.any { it is NovexManagedChange.DeleteModule || it is NovexManagedChange.DetachArtifact } ->
                NovexManagementRisk.DESTRUCTIVE
            changes.any {
                it is NovexManagedChange.LinkCharacterVersion ||
                    it is NovexManagedChange.UnlinkCharacterVersion ||
                    it is NovexManagedChange.AddModuleReference ||
                it is NovexManagedChange.RemoveModuleReference
                    || it is NovexManagedChange.PutCardReference || it is NovexManagedChange.RemoveCardReference
            } -> NovexManagementRisk.CROSS_PROJECT
            createChanges.any {
                it is NovexManagedChange.CreateWorld ||
                    it is NovexManagedChange.CreateCharacter ||
                    it is NovexManagedChange.CreateInteractiveFiction
            } -> NovexManagementRisk.CREATE_GLOBAL
            else -> NovexManagementRisk.SHARED_CHANGE
        }
        return NovexManagementPlan(
            id = planId,
            conversationId = configuration.conversationId,
            changes = changes,
            targets = targets,
            risk = risk,
            summary = changes.joinToString("；") { it.summary() },
            impact = targets.mapNotNull { target ->
                if (target.kind != NovexContentKind.CHARACTER_VERSION) return@mapNotNull null
                facts.versionWorldCounts[target.id]?.takeIf { it > 0 }?.let { count ->
                    "角色版本 ${target.id} 当前被 $count 个世界使用"
                }
            },
        )
    }
}

interface NovexManagementArtifactPort {
    suspend fun exists(artifactId: String): Boolean
    suspend fun describe(artifactId: String): NovexManagedArtifactDescription?
    suspend fun attach(attachment: CreativeArtifactAttachment)
    suspend fun detach(attachment: CreativeArtifactAttachment)
}

data class NovexManagedArtifactDescription(
    val id: String,
    val title: String,
    val kind: CreativeArtifactKind,
    val mimeType: String,
    val sizeBytes: Long,
    val sourcePath: String?,
)

fun interface NovexManagementTransaction {
    suspend fun run(block: suspend () -> Unit)
}

data class NovexManagedSubjectInspection(
    val subject: NovexContentAddress,
    val access: ManagedAccess,
    val label: String,
)

data class NovexManagementInspection(
    val subjects: List<NovexManagedSubjectInspection>,
    val selectedSubject: NovexContentAddress?,
    val selectedSubjectJson: String?,
    val modules: List<com.openminis.app.data.character.ContentModuleEntity>,
    val selectedModule: NovexModuleDetail?,
    val draftTargets: List<NovexConversationDraftCard> = emptyList(),
    val cardReferences: List<NovexCardReference> = emptyList(),
    val cardBacklinks: List<NovexCardReference> = emptyList(),
    val referenceStatuses: Map<String, NovexReferenceTargetStatus> = emptyMap(),
    val privateModuleIds: Map<String, String> = emptyMap(),
    val versionRelations: List<NovexCharacterVersionRelation> = emptyList(),
)

data class NovexManagementApplyResult(
    val changes: List<NovexChange>,
    val createdSubjects: List<NovexContentAddress>,
    val appliedChanges: Int = changes.size,
    val replayed: Boolean = false,
    val changedModuleIds: List<String> = emptyList(),
)

data class NovexManagedModuleType(
    val value: String,
    val label: String,
    val repeatable: Boolean,
    val internalType: ContentModuleType,
)

/** Stable model-facing names mapped onto the current database enums at one boundary. */
object NovexManagementModuleTypeCatalog {
    private val stableNames = mapOf(
        ContentModuleType.TIMELINE to "timeline",
        ContentModuleType.ERA_EVENT to "era_event",
        ContentModuleType.MAP to "map",
        ContentModuleType.REGION to "region",
        ContentModuleType.FACTION to "faction",
        ContentModuleType.RACE to "race",
        ContentModuleType.QUOTES to "quotes",
        ContentModuleType.WORLD_EXPERIENCE to "world_experience",
        ContentModuleType.ATTRIBUTE_PANEL to "attribute_panel",
        ContentModuleType.EQUIPMENT to "equipment",
        ContentModuleType.TALENT_SKILL to "talent_skill",
        ContentModuleType.APPEARANCE_PERSONALITY to "appearance_personality",
        ContentModuleType.INTEREST to "interest",
        ContentModuleType.ROLE_INSTRUCTIONS to "role_instructions",
        ContentModuleType.ROLE_PLAYER_IDENTITY to "companion_player_identity",
        ContentModuleType.GAME_ANSWER_IDENTITY to "answer_identity",
        ContentModuleType.GAME_PLAYER_IDENTITY to "player_identity",
        ContentModuleType.GAME_OPENING to "opening",
        ContentModuleType.GAME_NARRATIVE_RULES to "narrative_rules",
        ContentModuleType.GAME_POWER_SYSTEM to "power_system",
        ContentModuleType.GAME_ATTRIBUTES to "attributes",
        ContentModuleType.GAME_SKILLS to "skills",
        ContentModuleType.GAME_EQUIPMENT to "equipment",
        ContentModuleType.GAME_ITEMS to "items",
        ContentModuleType.GAME_QUESTS to "quests",
        ContentModuleType.GAME_CHECKS to "checks",
        ContentModuleType.GAME_ENDINGS to "endings",
        ContentModuleType.GAME_CHARACTER_STATUS to "character_status",
        ContentModuleType.GAME_QUICK_ACTIONS to "quick_actions",
        ContentModuleType.CUSTOM to "custom",
    )

    fun definitions(ownerType: ModuleOwnerType): List<NovexManagedModuleType> {
        val scope = ContentModuleCatalog.scopeFor(ownerType)
            ?: throw IllegalArgumentException("内容模块不能拥有根模块")
        return ContentModuleCatalog.definitions(scope).map { definition ->
            NovexManagedModuleType(
                value = requireNotNull(stableNames[definition.type]) { "内容模块缺少稳定名称" },
                label = definition.displayName,
                repeatable = definition.repeatable,
                internalType = definition.type,
            )
        }
    }

    fun decode(ownerType: ModuleOwnerType, value: String): ContentModuleType {
        val definitions = definitions(ownerType)
        return definitions.firstOrNull { definition ->
            definition.value == value.trim().lowercase() ||
                definition.internalType.name.equals(value.trim(), ignoreCase = true)
        }?.internalType ?: throw IllegalArgumentException(
            "模块类型“$value”不受支持；合法值：${definitions.joinToString(", ") { it.value }}",
        )
    }

    fun wireName(ownerType: ModuleOwnerType, type: ContentModuleType): String =
        definitions(ownerType).firstOrNull { it.internalType == type }?.value
            ?: throw IllegalArgumentException("该对象不支持此模块类型")

    fun definitions(subjectKind: NovexContentKind): List<NovexManagedModuleType> = when (subjectKind) {
        NovexContentKind.WORLD -> definitions(ModuleOwnerType.WORLD)
        NovexContentKind.CHARACTER_VERSION -> definitions(ModuleOwnerType.CHARACTER_VERSION)
        NovexContentKind.INTERACTIVE_FICTION -> definitions(ModuleOwnerType.INTERACTIVE_FICTION)
        NovexContentKind.CREATIVE_ARTIFACT -> emptyList()
    }
}

/** External values are independent from database enum serialization and obfuscation. */
object NovexManagementLaunchModes {
    private val modes = linkedMapOf(
        "fixed_identity" to InteractiveFictionLaunchMode.FIXED_IDENTITY,
        "user_created_identity" to InteractiveFictionLaunchMode.USER_CREATED_IDENTITY,
        "co_create_world" to InteractiveFictionLaunchMode.CO_CREATE_WORLD,
        "free_sandbox" to InteractiveFictionLaunchMode.FREE_SANDBOX,
    )

    fun decode(value: String): InteractiveFictionLaunchMode = modes[value.trim().lowercase()]
        ?: throw IllegalArgumentException("启动方式“$value”不受支持；合法值：${modes.keys.joinToString(", ")}")

    fun toJson(): JSONArray = JSONArray(modes.map { (value, mode) ->
        JSONObject().put("value", value).put("label", mode.displayName)
    })
}

/** Provider-neutral inspection payload, including the legal values needed for the next call. */
fun NovexManagementInspection.toToolJson(): JSONObject = JSONObject().apply {
    put("private_modules", JSONArray(privateModuleIds.map { (id, type) ->
        JSONObject().put("id", id).put("type", type).put("read_scope", "请明确指定模块编号后读取")
    }))
    if (selectedSubject?.kind == NovexContentKind.CHARACTER_VERSION) {
        put("profile_sections", JSONArray(listOf("public", "role_instructions", "exchange_source")))
    }
    put("reference_purposes", JSONArray(NovexReferencePurpose.entries.map {
        JSONObject().put("value", it.wireName).put("label", it.label)
    }))
    put("card_references", JSONArray(cardReferences.map { it.managementJson(referenceStatuses[it.id]) }))
    put("card_backlinks", JSONArray(cardBacklinks.map { it.managementJson(null) }))
    put("version_relation_kinds", JSONArray(NovexCharacterVersionRelationKind.entries.map {
        JSONObject().put("value", it.wireName).put("label", it.label)
    }))
    put("version_relations", JSONArray(versionRelations.map {
        JSONObject().put("relation_id", it.id).put("source_version_id", it.sourceVersionId)
            .put("target_version_id", it.targetVersionId).put("relation_kind", it.kind.wireName)
            .put("unresolved_target_version_id", it.unresolvedTargetVersionId)
    }))
    put("game_launch_modes", NovexManagementLaunchModes.toJson())
    put("private_creation_targets", JSONArray(draftTargets.map { card ->
        JSONObject().put("kind", card.subject.kind.managementWireName()).put("id", card.subject.id)
            .put("role", "仅作为当前明确创建请求的目标，不自动启用背景或身份")
    }))
    put("mounted_subjects", JSONArray().apply {
        subjects.forEach { value ->
            put(JSONObject()
                .put("kind", value.subject.kind.managementWireName())
                .put("id", value.subject.id)
                .put("label", value.label)
                .put("access", value.access.name.lowercase()))
        }
    })
    selectedSubject?.let {
        put("selected_subject", JSONObject().put("kind", it.kind.managementWireName()).put("id", it.id))
    }
    selectedSubjectJson?.let { subjectJson ->
        put("subject", runCatching { JSONObject(subjectJson) }.getOrElse { subjectJson })
    }
    put("module_type_catalog", JSONObject().apply {
        listOf(
            "world" to NovexContentKind.WORLD,
            "character_version" to NovexContentKind.CHARACTER_VERSION,
            "game" to NovexContentKind.INTERACTIVE_FICTION,
        ).forEach { (name, kind) ->
            put(name, JSONArray(NovexManagementModuleTypeCatalog.definitions(kind).map { definition ->
                JSONObject()
                    .put("value", definition.value)
                    .put("label", definition.label)
                    .put("repeatable", definition.repeatable)
                    .put("content_example", ContentModuleDocumentContract.example(definition.internalType))
            }))
        }
    })
    put("modules", JSONArray().apply {
        modules.forEach { module ->
            put(JSONObject()
                .put("id", module.id)
                .put("type", NovexManagementModuleTypeCatalog.wireName(module.ownerType, module.type))
                .put("name", module.name)
                .put("position", module.position)
                .put("content", runCatching { JSONObject(module.contentJson) }.getOrElse { module.contentJson }))
        }
    })
    selectedModule?.let { detail ->
        put("references", JSONArray().apply {
            detail.references.forEach { reference ->
                put(JSONObject()
                    .put("kind", reference.targetType.name.lowercase())
                    .put("id", reference.targetId)
                    .put("position", reference.position))
            }
        })
    }
}

/**
 * Application-facing management seam shared by agent tools and future UI automation.
 * It resolves facts through [NovexWorkspace], validates authorization, then applies one
 * structured plan inside a caller-provided transaction boundary.
 */
class NovexManagementService(
    private val workspace: NovexWorkspace,
    private val artifacts: NovexManagementArtifactPort,
    private val transaction: NovexManagementTransaction = NovexManagementTransaction { block -> block() },
) {
    suspend fun readExchangeSource(configuration: NovexConversationConfigurationSnapshot,
        subject: NovexContentAddress, offset: Int, limit: Int, revision: String?): JSONObject {
        require(subject.kind == NovexContentKind.CHARACTER_VERSION && NovexManagementPolicy.canRead(ownedDirectoryConfiguration(configuration), subject)) {
            "酒馆原始数据只能通过已挂载的角色管理对象读取；背景使用不授予原件读取权限"
        }
        val version = workspace.characterForVersion(subject.id)?.character?.allVersions?.singleOrNull { it.id == subject.id }
            ?: error("角色版本不存在")
        return NovexTavernExchange.readSource(version.profileJson, version.id, offset, limit, revision)
    }

    suspend fun inspect(
        configuration: NovexConversationConfigurationSnapshot,
        subject: NovexContentAddress?,
        moduleId: String?,
        profileSection: String = "public",
    ): NovexManagementInspection {
        require(profileSection in setOf("public", "role_instructions")) { "资料范围只能是 public（公开资料）或 role_instructions（专属扮演指令）" }
        require(profileSection == "public" || (subject?.kind == NovexContentKind.CHARACTER_VERSION && moduleId == null)) {
            "读取专属扮演资料时，请指定角色版本，且不要同时指定模块"
        }
        val owned = workspace.conversationDrafts(configuration.conversationId)?.cards.orEmpty()
            .map { it.subject }.toSet()
        fun canRead(target: NovexContentAddress) = target in owned || NovexManagementPolicy.canRead(configuration, target)
        if (subject != null) require(canRead(subject)) {
            "该对象不属于本对话作品，也未加入管理区；请由用户加入管理区。新建卡片请使用 novex_write_card，不需要先读取其他对象"
        }
        val selectedModule = moduleId?.let { id ->
            val value = requireNotNull(workspace.module(id)) { "模块不存在" }
            val owner = value.module.owner.toAddress()
            require(canRead(owner)) {
                "该模块不属于当前对话的管理对象"
            }
            if (subject != null) require(owner == subject) { "模块不属于指定管理对象" }
            value
        }
        val availableModules = when {
            selectedModule != null -> listOf(selectedModule.module)
            subject != null && subject.kind != NovexContentKind.CREATIVE_ARTIFACT ->
                workspace.modules(subject.toModuleOwner()).modules
            else -> emptyList()
        }
        val hiddenModules = availableModules.filter {
            selectedModule == null && it.ownerType == ModuleOwnerType.CHARACTER_VERSION && NovexModuleVisibility.isPrivate(it.type)
        }
        val hiddenIds = hiddenModules.map { it.id }.toSet()
        val modules = if (profileSection == "public") availableModules.filter { it.id !in hiddenIds } else emptyList()
        val referenceOwner = subject ?: selectedModule?.module?.owner?.toAddress()
        val references = referenceOwner?.let { workspace.referencesFrom(it) }.orEmpty()
            .filter { moduleId == null || it.sourceModuleId == moduleId }
            .filter { it.sourceModuleId !in hiddenIds && profileSection == "public" }
        val backlinks = referenceOwner?.let { workspace.referencesTo(it) }.orEmpty()
            .filter { moduleId == null || it.target.moduleId == moduleId }
            .filter { it.target.moduleId !in hiddenIds && profileSection == "public" }
        return NovexManagementInspection(
            subjects = (configuration.managedSubjects + owned.filter { target ->
                configuration.managedSubjects.none { it.subject == target }
            }.map { ManagedSubject(it, ManagedAccess.EDIT) }).map { managed ->
                NovexManagedSubjectInspection(
                    subject = managed.subject,
                    access = managed.access,
                    label = subjectLabel(managed.subject),
                )
            },
            selectedSubject = subject,
            selectedSubjectJson = subject?.let { subjectContentJson(it, profileSection) },
            modules = modules,
            selectedModule = selectedModule,
            draftTargets = workspace.emptyConversationDrafts(configuration.conversationId),
            cardReferences = references,
            cardBacklinks = backlinks,
            referenceStatuses = references.associate { it.id to workspace.referenceStatus(it.target) },
            privateModuleIds = hiddenModules.associate { it.id to NovexManagementModuleTypeCatalog.wireName(it.ownerType, it.type) },
            versionRelations = subject?.takeIf { it.kind == NovexContentKind.CHARACTER_VERSION && moduleId == null && profileSection == "public" }
                ?.let { workspace.versionRelations(it.id) }.orEmpty(),
        )
    }

    suspend fun propose(
        configuration: NovexConversationConfigurationSnapshot,
        changesJson: String,
        latestUserRequest: String,
        planId: String,
        priorUserRequests: List<String> = emptyList(),
        creationRequestScope: String? = null,
    ): NovexManagementPlan {
        workspace.conversationDrafts(configuration.conversationId)?.let { journal ->
            val previous = journal.pendingWrites.singleOrNull { it.id == planId }?.planJson
                ?: journal.completedWrites.singleOrNull { it.id == planId }?.planJson
            if (previous != null) {
                require(canonicalRevisionJson(JSONObject(previous).getJSONArray("changes")) == canonicalRevisionJson(JSONArray(changesJson))) {
                    "计划编号已用于其他内容，请用新编号提出变更"
                }
                return decodeOwnedPlan(configuration, planId, previous)
            }
        }
        val changes = NovexManagementChangeCodec.decode(changesJson)
        val facts = factsFor(changes)
        val plan = NovexManagementPolicy.plan(
            configuration = ownedDirectoryConfiguration(configuration),
            changes = changes,
            facts = facts,
            latestUserRequest = latestUserRequest,
            planId = planId,
            priorUserRequests = priorUserRequests,
        )
        val privateSubjects = privateEditableSubjects(configuration)
        val available = workspace.emptyConversationDrafts(configuration.conversationId).filter { card ->
            card.subject in privateSubjects && configuration.managedSubjects.none {
                it.subject == card.subject && it.access == ManagedAccess.READ_ONLY
            }
        }.toMutableList()
        val ownsDrafts = workspace.conversationDrafts(configuration.conversationId) != null
        val targets = changes.mapIndexedNotNull { index, change ->
            val kind = change.draftKind() ?: return@mapIndexedNotNull null
            val card = available.firstOrNull { it.subject.kind == kind } ?: if (ownsDrafts) {
                // The shared execution gate has already approved this concrete operation.
                workspace.apply(NovexCommand.AddConversationDraft(configuration.conversationId, kind))
                    .requireConversationDrafts().cards.last()
            } else return@mapIndexedNotNull null
            available.remove(card)
            index to card.subject
        }.toMap()
        val directEdits = emptySet<Int>()
        val expectedModules = changes.mapNotNull { change -> change.editedModuleId() }.associateWith { id -> moduleEditFingerprint(requireNotNull(workspace.module(id)).module) }
        val resolved = plan.copy(draftTargets = targets, authorizedUserRequest = latestUserRequest,
            directEditIndices = directEdits, expectedModuleContents = expectedModules, creationRequestScope = creationRequestScope,
            risk = if (targets.size == changes.size) NovexManagementRisk.CREATE_PRIVATE else plan.risk)
        if (workspace.conversationDrafts(configuration.conversationId) != null) {
            workspace.apply(NovexCommand.ReserveConversationDraftWrite(configuration.conversationId,
                NovexDraftWriteReservation(resolved.id, resolved.targets + targets.values,
                    NovexManagementPlanCodec.encode(resolved, changesJson))))
        }
        return resolved
    }

    suspend fun pendingPlan(configuration: NovexConversationConfigurationSnapshot, id: String): NovexManagementPlan? =
        workspace.conversationDrafts(configuration.conversationId)?.pendingWrites?.singleOrNull { it.id == id }?.let {
            decodeOwnedPlan(configuration, id, it.planJson)
        }

    suspend fun planForExecution(configuration: NovexConversationConfigurationSnapshot, id: String): NovexManagementPlan? =
        pendingPlan(configuration, id) ?: workspace.conversationDrafts(configuration.conversationId)
            ?.completedWrites?.singleOrNull { it.id == id }?.let { decodeOwnedPlan(configuration, id, it.planJson) }

    private fun decodeOwnedPlan(configuration: NovexConversationConfigurationSnapshot, id: String, raw: String) =
        NovexManagementPlanCodec.decode(raw).also { plan ->
            require(plan.conversationId == configuration.conversationId && plan.id == id) { "保存的计划归属不一致" }
        }

    suspend fun apply(
        configuration: NovexConversationConfigurationSnapshot,
        plan: NovexManagementPlan,
        confirmationText: String,
    ): NovexManagementApplyResult {
        require(plan.conversationId == configuration.conversationId) { "变更计划不属于当前对话" }
        var outcome: NovexManagementApplyResult? = null
        transaction.run {
            val journal = workspace.conversationDrafts(configuration.conversationId)
            journal?.completedWrites?.singleOrNull { it.id == plan.id }?.let { receipt ->
                require(NovexManagementPlanCodec.decode(receipt.planJson) == plan) { "此编号已用于另一份变更，未执行新写入" }
                outcome = NovexManagementApplyResult(emptyList(), receipt.createdSubjects, receipt.appliedChanges, replayed = true, changedModuleIds = receipt.changedModuleIds)
                return@run
            }
            val savedPlan = journal?.pendingWrites?.singleOrNull { it.id == plan.id }
            if (journal != null) require(savedPlan != null && NovexManagementPlanCodec.decode(savedPlan.planJson) == plan) {
                "持久化计划不存在或已变化，请重新提出变更"
            }
            require(configuration.executionMode != NovexExecutionMode.READ_ONLY) { "当前对话只读，未修改内容" }
            val currentPrivate = privateEditableSubjects(configuration)
            require(plan.draftTargets.values.all { target -> target in currentPrivate && configuration.managedSubjects.none {
                it.subject == target && it.access == ManagedAccess.READ_ONLY
            } }) { "原空卡的管理权限或共享引用已改变，未写入；请重新提出创建计划，使用新的可写空卡" }
            val facts = factsFor(plan.changes)
            val currentTargets = plan.changes.flatMap { it.targets(facts) }.toSet()
            require(currentTargets == plan.targets) { "内容关系已经变化，请重新生成变更计划" }
            val effectiveConfiguration = ownedDirectoryConfiguration(configuration)
            currentTargets.forEach { target ->
                require(effectiveConfiguration.managedSubjects.any {
                    it.subject == target && it.access == ManagedAccess.EDIT
                }) { "管理授权已经变化，请重新生成变更计划" }
            }
            plan.changes.forEach { change ->
                when (change) {
                    is NovexManagedChange.AttachArtifact -> require(change.artifactId in facts.existingArtifactIds) {
                        "创作成果已经不存在，请重新生成变更计划"
                    }
                    is NovexManagedChange.DetachArtifact -> require(change.artifactId in facts.existingArtifactIds) {
                        "创作成果已经不存在，请重新生成变更计划"
                    }
                    else -> Unit
                }
            }
            val editedIds = plan.changes.mapNotNull { it.editedModuleId() }.toSet()
            require(plan.expectedModuleContents.keys == editedIds) { "旧计划缺少完整模块修订，请读取当前内容并重新生成计划" }
            plan.expectedModuleContents.forEach { (id, expected) ->
                require(workspace.module(id)?.module?.let(::moduleEditFingerprint) == expected) {
                    "模块在提出计划后已经修改，请读取当前内容并重新生成计划：$id"
                }
            }
            val changes = mutableListOf<NovexChange>()
            val created = mutableListOf<NovexContentAddress>()
            plan.changes.forEachIndexed { index, managed ->
                when (managed) {
                    is NovexManagedChange.AttachArtifact -> artifacts.attach(managed.toAttachment())
                    is NovexManagedChange.DetachArtifact -> artifacts.detach(managed.toAttachment())
                    else -> {
                        // Resolve each partial edit inside the transaction, after earlier changes
                        // in this plan, rather than filling omitted fields with an empty document.
                        val current = if (managed is NovexManagedChange.UpdateModule) {
                            requireNotNull(workspace.module(managed.moduleId)) { "模块已不存在，请重新生成计划" }.module
                        } else null
                        val creation = managed.toCommand(facts, current)
                        val command = plan.draftTargets[index]?.let { target ->
                            NovexCommand.FillConversationDraft(configuration.conversationId, target, creation)
                        } ?: creation
                        val result = workspace.apply(command)
                        changes += result
                        result.createdSubject()?.let(created::add)
                    }
                }
            }
            val changedModuleIds = changes.filterIsInstance<NovexChange.ModuleSaved>().map { it.module.id }.distinct()
            if (savedPlan != null) {
                workspace.apply(NovexCommand.CompleteConversationDraftWrite(configuration.conversationId,
                    NovexManagementWriteReceipt(plan.id, savedPlan.planJson, plan.changes.size, created.toList(), System.currentTimeMillis(), changedModuleIds)))
            }
            outcome = NovexManagementApplyResult(changes, created, plan.changes.size, changedModuleIds = changedModuleIds)
        }
        return requireNotNull(outcome)
    }

    /** Saving into the library does not revoke this conversation's access to its own works. */
    private suspend fun ownedDirectoryConfiguration(configuration: NovexConversationConfigurationSnapshot): NovexConversationConfigurationSnapshot {
        val owned = workspace.conversationDrafts(configuration.conversationId)?.cards.orEmpty()
            .map { it.subject }
        return configuration.copy(managedSubjects = configuration.managedSubjects + owned.filter { target ->
            configuration.managedSubjects.none { it.subject == target }
        }.map { ManagedSubject(it, ManagedAccess.EDIT) })
    }

    private suspend fun privateEditableSubjects(configuration: NovexConversationConfigurationSnapshot): Set<NovexContentAddress> {
        val owned = workspace.conversationDrafts(configuration.conversationId)?.cards.orEmpty()
            .filter { it.isPrivate }.map { it.subject }.toSet()
        return owned.filter { subject ->
            workspace.referencesTo(subject).none { it.source !in owned } &&
                (subject.kind != NovexContentKind.CHARACTER_VERSION ||
                    workspace.characterForVersion(subject.id)?.worldsByVersion?.get(subject.id).orEmpty()
                        .all { NovexContentAddress.world(it.id) in owned })
        }.toSet()
    }

    private suspend fun factsFor(changes: List<NovexManagedChange>): NovexManagementFacts {
        changes.filterIsInstance<NovexManagedChange.RemoveVersionRelation>().forEach { change ->
            require(workspace.versionRelations(change.sourceVersionId).any { it.id == change.relationId && it.sourceVersionId == change.sourceVersionId }) {
                "版本关系不存在或不属于指定来源版本"
            }
        }
        changes.filterIsInstance<NovexManagedChange.RemoveCardReference>().forEach { change ->
            require(workspace.referencesFrom(change.source).any { it.id == change.referenceId }) { "引用不存在或不属于指定来源卡片" }
        }
        val moduleIds = changes.flatMap { change ->
            when (change) {
                is NovexManagedChange.UpdateModule -> listOf(change.moduleId)
                is NovexManagedChange.MoveModule -> listOf(change.moduleId)
                is NovexManagedChange.DeleteModule -> listOf(change.moduleId)
                is NovexManagedChange.AddModuleReference -> buildList {
                    add(change.moduleId)
                    if (change.target.type == ModuleReferenceTargetType.MODULE) add(change.target.id)
                }
                is NovexManagedChange.RemoveModuleReference -> buildList {
                    add(change.moduleId)
                    if (change.target.type == ModuleReferenceTargetType.MODULE) add(change.target.id)
                }
                is NovexManagedChange.AttachArtifact -> listOfNotNull(change.moduleId)
                is NovexManagedChange.DetachArtifact -> listOfNotNull(change.moduleId)
                else -> emptyList()
            }
        }.distinct()
        val moduleOwners = moduleIds.associateWith { id ->
            requireNotNull(workspace.module(id)) { "模块不存在：$id" }.module.owner
        }
        val versionIds = (changes.flatMap { change ->
            when (change) {
                is NovexManagedChange.CreateCharacterVersion -> listOf(change.sourceVersionId)
                is NovexManagedChange.LinkCharacterVersion -> listOf(change.versionId)
                is NovexManagedChange.UnlinkCharacterVersion -> listOf(change.versionId)
                is NovexManagedChange.AddModule -> listOfNotNull(
                    change.owner.id.takeIf { change.owner.type == ModuleOwnerType.CHARACTER_VERSION },
                )
                else -> emptyList()
            }
        } + moduleOwners.values.mapNotNull { owner ->
            owner.id.takeIf { owner.type == ModuleOwnerType.CHARACTER_VERSION }
        }).distinct()
        val versionCharacterIds = versionIds.associateWith { versionId ->
            workspace.characterForVersion(versionId)?.character?.character?.id ?: error("角色版本不存在：$versionId")
        }
        val versionWorldCounts = versionCharacterIds.entries.associate { (versionId, characterId) ->
            val snapshot = requireNotNull(workspace.character(characterId)) { "角色不存在：$characterId" }
            versionId to snapshot.worldsByVersion[versionId].orEmpty().size
        }
        val artifactIds = changes.mapNotNull { change ->
            when (change) {
                is NovexManagedChange.AttachArtifact -> change.artifactId
                is NovexManagedChange.DetachArtifact -> change.artifactId
                else -> null
            }
        }.distinct()
        return NovexManagementFacts(
            moduleOwners = moduleOwners,
            versionCharacterIds = versionCharacterIds,
            versionWorldCounts = versionWorldCounts,
            existingArtifactIds = artifactIds.filterTo(mutableSetOf()) { artifacts.exists(it) },
        )
    }

    private suspend fun subjectLabel(subject: NovexContentAddress): String = when (subject.kind) {
        NovexContentKind.WORLD -> workspace.world(subject.id)?.world?.name ?: "已删除世界"
        NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(subject.id)?.let { card ->
            card.character.allVersions.firstOrNull { it.id == subject.id }?.let { version ->
                "${card.character.character.name} · ${version.label}"
            }
        } ?: "已删除角色版本"
        NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(subject.id)?.project?.name ?: "已删除文游"
        NovexContentKind.CREATIVE_ARTIFACT -> artifacts.describe(subject.id)?.title ?: "已删除创作成果"
    }

    private suspend fun subjectContentJson(subject: NovexContentAddress, profileSection: String): String = when (subject.kind) {
        NovexContentKind.WORLD -> {
            val world = requireNotNull(workspace.world(subject.id)) { "世界不存在：${subject.id}" }.world
            JSONObject().apply {
                put("id", world.id)
                put("name", world.name)
                put("overview", world.overview)
                put("tags", runCatching { JSONArray(world.tagsJson) }.getOrElse { JSONArray() })
            }.toString()
        }
        NovexContentKind.CHARACTER_VERSION -> {
            val pair = workspace.characterForVersion(subject.id)?.let { card ->
                card.character.allVersions.firstOrNull { it.id == subject.id }?.let { card to it }
            } ?: error("角色版本不存在：${subject.id}")
            val (card, version) = pair
            JSONObject().apply {
                put("id", version.id)
                put("character_id", version.characterId)
                put("character_name", card.character.character.name)
                put("kind", version.kind.name)
                put("label", version.label)
                val rawProfile = runCatching { JSONObject(version.profileJson) }.getOrElse { JSONObject() }
                val fields = if (profileSection == "role_instructions") listOf(
                    "personality", "scenario", "greeting", "exampleDialogue", "systemPrompt", "postHistoryInstructions", "contentBoundary",
                ) else listOf("profileSchema", "name", "tags", "gender", "age", "race", "occupation", "summary", "customAttributes", "relationships")
                put("profile", JSONObject().apply { fields.filter(rawProfile::has).forEach { put(it, rawProfile.get(it)) } })
                put("profile_section", profileSection)
                if (profileSection == "public") NovexTavernExchange.sourceSummary(version.profileJson)?.let {
                    put("exchange_compatibility", it)
                    put("exchange_source_access", "明确分析酒馆原件时使用 profile_section=exchange_source（交换原件），按偏移与修订分段读取；不从公开总览展开原文")
                }
            }.toString()
        }
        NovexContentKind.INTERACTIVE_FICTION -> {
            val project = requireNotNull(workspace.interactiveFiction(subject.id)) {
                "文游不存在：${subject.id}"
            }.project
            JSONObject().apply {
                put("id", project.id)
                put("name", project.name)
                put("summary", project.summary)
                put("launch_mode", project.launchMode.name)
                put("player_identity", project.playerIdentity)
            }.toString()
        }
        NovexContentKind.CREATIVE_ARTIFACT -> {
            val artifact = requireNotNull(artifacts.describe(subject.id)) {
                "创作成果不存在：${subject.id}"
            }
            JSONObject().apply {
                put("id", artifact.id)
                put("title", artifact.title)
                put("kind", artifact.kind.name)
                put("mime_type", artifact.mimeType)
                put("size_bytes", artifact.sizeBytes)
                artifact.sourcePath?.let { put("source_path", it) }
            }.toString()
        }
    }
}

object NovexManagementChangeCodec {
    fun decode(raw: String): List<NovexManagedChange> {
        val values = JSONArray(raw)
        require(values.length() in 1..20) { "变更数量必须在一到二十项之间" }
        return List(values.length()) { index ->
            val value = values.getJSONObject(index)
            when (value.getString("operation")) {
                "put_version_relation" -> NovexManagedChange.PutVersionRelation(NovexCharacterVersionRelation(
                    value.getString("relation_id"), value.getString("source_version_id"), value.getString("target_version_id"),
                    NovexCharacterVersionRelationKind.entries.firstOrNull { it.wireName == value.getString("relation_kind") }
                        ?: error("版本关系类型无效；合法值：${NovexCharacterVersionRelationKind.entries.joinToString { it.wireName }}"),
                ))
                "remove_version_relation" -> NovexManagedChange.RemoveVersionRelation(value.getString("source_version_id"), value.getString("relation_id"))
                "put_card_reference" -> NovexManagedChange.PutCardReference(NovexCardReference(
                    id = value.getString("reference_id"), source = value.contentAddress(),
                    target = NovexReferenceTarget(value.cardTargetAddress(),
                        value.optString("target_module_id").ifBlank { null }, value.optString("target_entry_id").ifBlank { null }),
                    purpose = NovexReferencePurpose.entries.firstOrNull { it.wireName == value.getString("purpose") }
                        ?: error("引用用途无效；合法值：${NovexReferencePurpose.entries.joinToString { it.wireName }}"),
                    sourceModuleId = value.optString("source_module_id").ifBlank { null },
                    position = value.optInt("position"), targetLabel = value.optString("target_label"),
                ))
                "remove_card_reference" -> NovexManagedChange.RemoveCardReference(value.contentAddress(), value.getString("reference_id"))
                "add_module" -> value.subjectOwner().let { owner ->
                    NovexManagedChange.AddModule(
                        owner = owner,
                        type = NovexManagementModuleTypeCatalog.decode(
                            owner.type,
                            value.getString("module_type"),
                        ),
                        name = value.getString("name").trim(),
                        contentJson = value.jsonText("content_json"),
                    )
                }
                "update_module" -> NovexManagedChange.UpdateModule(
                    moduleId = value.getString("module_id"),
                    name = if (value.has("name")) value.getString("name").trim() else null,
                    contentJson = if (value.has("content_json")) value.jsonText("content_json") else null,
                )
                "move_module" -> NovexManagedChange.MoveModule(
                    value.getString("module_id"),
                    value.getInt("to_index"),
                )
                "delete_module" -> NovexManagedChange.DeleteModule(value.getString("module_id"))
                "add_reference" -> NovexManagedChange.AddModuleReference(
                    value.getString("module_id"),
                    value.referenceTarget(),
                    value.optInt("position", 0),
                )
                "remove_reference" -> NovexManagedChange.RemoveModuleReference(
                    value.getString("module_id"),
                    value.referenceTarget(),
                )
                "create_world" -> NovexManagedChange.CreateWorld(
                    value.getString("name").trim(),
                    value.optString("overview"),
                    value.initialModules(ModuleOwnerType.WORLD),
                )
                "create_character" -> NovexManagedChange.CreateCharacter(
                    value.getString("name").trim(),
                    value.jsonText("profile_json"),
                    value.initialModules(ModuleOwnerType.CHARACTER_VERSION),
                )
                "create_character_version" -> NovexManagedChange.CreateCharacterVersion(
                    value.getString("source_version_id"),
                    value.getString("label").trim(),
                    value.jsonText("profile_json"),
                )
                "create_game" -> NovexManagedChange.CreateInteractiveFiction(
                    name = value.getString("name").trim(),
                    summary = value.optString("summary"),
                    launchMode = NovexManagementLaunchModes.decode(value.optString("launch_mode", "free_sandbox")),
                    playerIdentity = value.optString("player_identity"),
                    modules = value.initialModules(ModuleOwnerType.INTERACTIVE_FICTION),
                )
                "link_character_version" -> NovexManagedChange.LinkCharacterVersion(
                    value.getString("world_id"),
                    value.getString("version_id"),
                    value.optInt("position", 0),
                )
                "unlink_character_version" -> NovexManagedChange.UnlinkCharacterVersion(
                    value.getString("world_id"),
                    value.getString("version_id"),
                )
                "attach_artifact", "detach_artifact" -> {
                    val owner = value.contentAddress()
                    val artifactId = value.getString("artifact_id")
                    val moduleId = value.optString("module_id").ifBlank { null }
                    val slot = value.optString("slot").ifBlank { null }
                    if (value.getString("operation") == "attach_artifact") {
                        NovexManagedChange.AttachArtifact(artifactId, owner, moduleId, slot)
                    } else {
                        NovexManagedChange.DetachArtifact(artifactId, owner, moduleId, slot)
                    }
                }
                else -> error("未知管理操作：${value.getString("operation")}")
            }.also(::validateChange)
        }
    }

    private fun JSONObject.initialModules(ownerType: ModuleOwnerType): List<NovexModuleDraft> {
        if (!has("modules")) return emptyList()
        val values = optJSONArray("modules") ?: throw IllegalArgumentException("modules 必须是模块数组")
        require(values.length() <= 1_000) { "一次初始创建最多一千个模块，更多内容可在创建后继续添加" }
        val scope = requireNotNull(ContentModuleCatalog.scopeFor(ownerType))
        val usedTypes = mutableListOf<ContentModuleType>()
        return List(values.length()) { index ->
            val value = values.optJSONObject(index) ?: throw IllegalArgumentException("modules[$index] 必须是对象")
            val type = NovexManagementModuleTypeCatalog.decode(ownerType, value.getString("module_type"))
            ContentModuleCatalog.requireCanAdd(scope, type, usedTypes)
            usedTypes += type
            val name = value.getString("name").trim()
            require(name.isNotBlank()) { "modules[$index].name 不能为空" }
            val content = value.jsonText("content_json")
            ContentModuleDocumentContract.validate(content, type)
            NovexModuleDraft(
                id = java.util.UUID.randomUUID().toString(), type = type, name = name, contentJson = content,
            )
        }
    }

    private fun validateChange(change: NovexManagedChange) {
        when (change) {
            is NovexManagedChange.AddModule -> {
                require(change.name.isNotBlank()) { "模块名称不能为空" }
                ContentModuleDocumentContract.validate(change.contentJson, change.type)
            }
            is NovexManagedChange.UpdateModule -> {
                require(change.moduleId.isNotBlank()) { "模块编号不能为空" }
                require(change.name != null || change.contentJson != null) { "至少提供 name 或 content_json；未提供的字段保持原样" }
                change.name?.let { require(it.isNotBlank()) { "模块名称不能为空" } }
                change.contentJson?.let { ContentModuleDocumentContract.validate(it) }
            }
            is NovexManagedChange.MoveModule -> {
                require(change.moduleId.isNotBlank()) { "模块编号不能为空" }
                require(change.toIndex >= 0) { "模块位置不能为负数" }
            }
            is NovexManagedChange.DeleteModule -> require(change.moduleId.isNotBlank()) { "模块编号不能为空" }
            is NovexManagedChange.CreateWorld -> require(change.name.isNotBlank()) { "世界名称不能为空" }
            is NovexManagedChange.CreateCharacter -> {
                require(change.name.isNotBlank()) { "角色名称不能为空" }
                JSONObject(change.profileJson)
            }
            is NovexManagedChange.CreateCharacterVersion -> {
                require(change.label.isNotBlank()) { "角色版本名称不能为空" }
                JSONObject(change.profileJson)
            }
            is NovexManagedChange.CreateInteractiveFiction -> require(change.name.isNotBlank()) { "文游名称不能为空" }
            is NovexManagedChange.AttachArtifact -> require(
                change.owner.kind != NovexContentKind.CREATIVE_ARTIFACT,
            ) { "创作成果只能附加到世界、角色版本或文游" }
            is NovexManagedChange.DetachArtifact -> require(
                change.owner.kind != NovexContentKind.CREATIVE_ARTIFACT,
            ) { "创作成果只能从世界、角色版本或文游移除" }
            else -> Unit
        }
    }
}

private fun requireArtifactModuleOwner(
    owner: NovexContentAddress,
    moduleId: String?,
    facts: NovexManagementFacts,
) {
    if (moduleId == null) return
    val moduleOwner = requireNotNull(facts.moduleOwners[moduleId]) { "模块不存在：$moduleId" }
    require(moduleOwner.toAddress() == owner) { "指定模块不属于创作成果的附加对象" }
}

private fun NovexManagedChange.targets(facts: NovexManagementFacts): List<NovexContentAddress> = when (this) {
    is NovexManagedChange.PutVersionRelation -> listOf(NovexContentAddress.characterVersion(relation.sourceVersionId))
    is NovexManagedChange.RemoveVersionRelation -> listOf(NovexContentAddress.characterVersion(sourceVersionId))
    is NovexManagedChange.PutCardReference -> listOf(reference.source)
    is NovexManagedChange.RemoveCardReference -> listOf(source)
    is NovexManagedChange.AddModule -> listOf(owner.toAddress())
    is NovexManagedChange.UpdateModule -> listOf(requireNotNull(facts.moduleOwners[moduleId]) { "模块不存在" }.toAddress())
    is NovexManagedChange.MoveModule -> listOf(requireNotNull(facts.moduleOwners[moduleId]) { "模块不存在" }.toAddress())
    is NovexManagedChange.DeleteModule -> listOf(requireNotNull(facts.moduleOwners[moduleId]) { "模块不存在" }.toAddress())
    is NovexManagedChange.AddModuleReference -> listOf(
        requireNotNull(facts.moduleOwners[moduleId]) { "模块不存在" }.toAddress(),
        target.toAddress(facts),
    )
    is NovexManagedChange.RemoveModuleReference -> listOf(
        requireNotNull(facts.moduleOwners[moduleId]) { "模块不存在" }.toAddress(),
        target.toAddress(facts),
    )
    is NovexManagedChange.CreateWorld,
    is NovexManagedChange.CreateCharacter,
    is NovexManagedChange.CreateInteractiveFiction -> emptyList()
    is NovexManagedChange.CreateCharacterVersion -> listOf(NovexContentAddress.characterVersion(sourceVersionId))
    is NovexManagedChange.LinkCharacterVersion -> listOf(
        NovexContentAddress.world(worldId),
        NovexContentAddress.characterVersion(versionId),
    )
    is NovexManagedChange.UnlinkCharacterVersion -> listOf(
        NovexContentAddress.world(worldId),
        NovexContentAddress.characterVersion(versionId),
    )
    is NovexManagedChange.AttachArtifact -> listOf(owner)
    is NovexManagedChange.DetachArtifact -> listOf(owner)
}

private fun NovexManagedChange.editedModuleId(): String? = when (this) {
    is NovexManagedChange.UpdateModule -> moduleId
    is NovexManagedChange.MoveModule -> moduleId
    is NovexManagedChange.DeleteModule -> moduleId
    else -> null
}

private fun moduleEditFingerprint(module: com.openminis.app.data.character.ContentModuleEntity): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(module.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun NovexManagedChange.isCreation(): Boolean = when (this) {
    is NovexManagedChange.CreateWorld,
    is NovexManagedChange.CreateCharacter,
    is NovexManagedChange.CreateCharacterVersion,
    is NovexManagedChange.CreateInteractiveFiction -> true
    else -> false
}

private fun NovexManagedChange.summary(): String = when (this) {
    is NovexManagedChange.PutVersionRelation -> "设置${relation.kind.label}关系（目标版本 ${relation.targetVersionId}）"
    is NovexManagedChange.RemoveVersionRelation -> "移除版本关系 $relationId，保留各版本"
    is NovexManagedChange.PutCardReference -> "设置${reference.purpose.label}引用（目标 ${reference.target.subject.id}）"
    is NovexManagedChange.RemoveCardReference -> "移除卡片引用 $referenceId，保留目标卡片"
    is NovexManagedChange.AddModule -> "新增模块“$name”"
    is NovexManagedChange.UpdateModule -> "修改模块“${name ?: moduleId}”"
    is NovexManagedChange.MoveModule -> "调整模块顺序"
    is NovexManagedChange.DeleteModule -> "删除模块 $moduleId"
    is NovexManagedChange.AddModuleReference -> "增加内容关联"
    is NovexManagedChange.RemoveModuleReference -> "解除内容关联"
    is NovexManagedChange.CreateWorld -> "创建世界“$name”${modules.creationSummary()}"
    is NovexManagedChange.CreateCharacter -> "创建角色“$name”${modules.creationSummary()}"
    is NovexManagedChange.CreateCharacterVersion -> "创建角色版本“$label”"
    is NovexManagedChange.CreateInteractiveFiction -> "创建文游“$name”${modules.creationSummary()}"
    is NovexManagedChange.LinkCharacterVersion -> "关联世界与角色版本"
    is NovexManagedChange.UnlinkCharacterVersion -> "解除世界与角色版本关联"
    is NovexManagedChange.AttachArtifact -> "附加创作成果 $artifactId"
    is NovexManagedChange.DetachArtifact -> "移除创作成果引用 $artifactId"
}

private fun ModuleOwner.toAddress(): NovexContentAddress = when (type) {
    ModuleOwnerType.WORLD -> NovexContentAddress.world(id)
    ModuleOwnerType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(id)
    ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(id)
    ModuleOwnerType.CONTENT_MODULE -> error("内容模块不能作为管理根对象")
}

private fun NovexContentAddress.toModuleOwner(): ModuleOwner = when (kind) {
    NovexContentKind.WORLD -> ModuleOwner.world(id)
    NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(id)
    NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(id)
    NovexContentKind.CREATIVE_ARTIFACT -> error("创作成果不能直接拥有内容模块")
}

private fun NovexManagedChange.AttachArtifact.toAttachment() = CreativeArtifactAttachment(
    artifactId = artifactId,
    owner = owner,
    moduleId = moduleId,
    slot = slot,
)

private fun NovexManagedChange.DetachArtifact.toAttachment() = CreativeArtifactAttachment(
    artifactId = artifactId,
    owner = owner,
    moduleId = moduleId,
    slot = slot,
)

private fun List<NovexModuleDraft>.creationSummary(): String = if (isEmpty()) "（未提供初始模块）" else
    "（${size} 个模块，按顺序：${take(10).joinToString("、") { it.name }}${if (size > 10) "等" else ""}）"

private fun NovexManagedChange.toCommand(
    facts: NovexManagementFacts,
    currentModule: com.openminis.app.data.character.ContentModuleEntity? = null,
): NovexCommand = when (this) {
    is NovexManagedChange.PutVersionRelation -> NovexCommand.PutVersionRelation(relation)
    is NovexManagedChange.RemoveVersionRelation -> NovexCommand.RemoveVersionRelation(relationId, sourceVersionId)
    is NovexManagedChange.PutCardReference -> NovexCommand.PutCardReference(reference)
    is NovexManagedChange.RemoveCardReference -> NovexCommand.RemoveCardReference(referenceId, source)
    is NovexManagedChange.AddModule -> NovexCommand.AddModule(
        owner = owner,
        type = type,
        name = name,
        contentJson = contentJson,
    )
    is NovexManagedChange.UpdateModule -> requireNotNull(currentModule) { "模块不存在" }.let { current ->
        NovexCommand.SaveModule(moduleId, name ?: current.name, contentJson ?: current.contentJson)
    }
    is NovexManagedChange.MoveModule -> NovexCommand.MoveModule(moduleId, toIndex)
    is NovexManagedChange.DeleteModule -> NovexCommand.DeleteModule(moduleId)
    is NovexManagedChange.AddModuleReference -> NovexCommand.AddModuleReference(moduleId, target, position)
    is NovexManagedChange.RemoveModuleReference -> NovexCommand.RemoveModuleReference(moduleId, target)
    is NovexManagedChange.CreateWorld -> if (modules.isEmpty()) NovexCommand.CreateWorld(name, overview) else
        NovexCommand.SaveWorldPage(worldId = null, name = name, overview = overview, modules = modules)
    is NovexManagedChange.CreateCharacter -> if (modules.isEmpty()) NovexCommand.CreateCharacter(name, profileJson) else
        NovexCommand.SaveCharacterPage(
            characterId = null, versionId = null, sourceVersionId = null, createVariant = false,
            rootName = name, label = "本体",
            profileJson = JSONObject(profileJson).apply {
                if (optString("name").isBlank()) put("name", name)
            }.toString(),
            modules = modules,
        )
    is NovexManagedChange.CreateCharacterVersion -> NovexCommand.CreateVariant(
        characterId = requireNotNull(facts.versionCharacterIds[sourceVersionId]) { "来源角色版本不存在" },
        label = label,
        profileJson = profileJson,
    )
    is NovexManagedChange.CreateInteractiveFiction -> NovexCommand.SaveInteractiveFictionPage(
        projectId = null,
        name = name,
        summary = summary,
        launchMode = launchMode,
        playerIdentity = playerIdentity,
        modules = modules,
    )
    is NovexManagedChange.LinkCharacterVersion -> NovexCommand.LinkCharacterVersion(worldId, versionId, position)
    is NovexManagedChange.UnlinkCharacterVersion -> NovexCommand.UnlinkCharacterVersion(worldId, versionId)
    is NovexManagedChange.AttachArtifact,
    is NovexManagedChange.DetachArtifact -> error("创作成果引用不属于工作区命令")
}

private fun NovexChange.createdSubject(): NovexContentAddress? = when (this) {
    is NovexChange.WorldSaved -> NovexContentAddress.world(world.id)
    is NovexChange.CharacterSaved -> NovexContentAddress.characterVersion(character.original.id)
    is NovexChange.InteractiveFictionSaved -> NovexContentAddress.interactiveFiction(project.id)
    is NovexChange.VersionSaved -> NovexContentAddress.characterVersion(version.id)
    else -> null
}

private fun ModuleReferenceTarget.toAddress(facts: NovexManagementFacts): NovexContentAddress = when (type) {
    ModuleReferenceTargetType.WORLD -> NovexContentAddress.world(id)
    ModuleReferenceTargetType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(id)
    ModuleReferenceTargetType.MODULE -> requireNotNull(facts.moduleOwners[id]) { "关联模块不存在" }.toAddress()
}

private fun NovexContentKind.displayName(): String = when (this) {
    NovexContentKind.WORLD -> "世界"
    NovexContentKind.CHARACTER_VERSION -> "角色版本"
    NovexContentKind.INTERACTIVE_FICTION -> "文游"
    NovexContentKind.CREATIVE_ARTIFACT -> "创作成果"
}

internal fun NovexContentKind.managementWireName(): String = when (this) {
    NovexContentKind.WORLD -> "world"
    NovexContentKind.CHARACTER_VERSION -> "character_version"
    NovexContentKind.INTERACTIVE_FICTION -> "game"
    NovexContentKind.CREATIVE_ARTIFACT -> "artifact"
}

private fun JSONObject.subjectOwner(): ModuleOwner = when (getString("subject_kind")) {
    "world" -> ModuleOwner.world(getString("subject_id"))
    "character_version" -> ModuleOwner.characterVersion(getString("subject_id"))
    "game" -> ModuleOwner.interactiveFiction(getString("subject_id"))
    else -> error("未知内容对象类型")
}

private fun JSONObject.contentAddress(): NovexContentAddress = when (getString("subject_kind")) {
    "world" -> NovexContentAddress.world(getString("subject_id"))
    "character_version" -> NovexContentAddress.characterVersion(getString("subject_id"))
    "game" -> NovexContentAddress.interactiveFiction(getString("subject_id"))
    "artifact" -> NovexContentAddress.creativeArtifact(getString("subject_id"))
    else -> error("未知内容对象类型")
}

private fun JSONObject.referenceTarget(): ModuleReferenceTarget = when (getString("target_kind")) {
    "world" -> ModuleReferenceTarget.world(getString("target_id"))
    "character_version" -> ModuleReferenceTarget.characterVersion(getString("target_id"))
    "module" -> ModuleReferenceTarget.module(getString("target_id"))
    else -> error("未知关联对象类型")
}

private fun JSONObject.cardTargetAddress(): NovexContentAddress = when (getString("target_kind")) {
    "world" -> NovexContentAddress.world(getString("target_id"))
    "character_version" -> NovexContentAddress.characterVersion(getString("target_id"))
    "game" -> NovexContentAddress.interactiveFiction(getString("target_id"))
    else -> error("卡片引用目标类型无效；合法值：world（世界）、character_version（角色版本）、game（文游）")
}

private fun NovexCardReference.managementJson(status: NovexReferenceTargetStatus?): JSONObject = JSONObject()
    .put("reference_id", id).put("subject_kind", source.kind.managementWireName()).put("subject_id", source.id)
    .put("source_module_id", sourceModuleId).put("target_kind", target.subject.kind.managementWireName())
    .put("target_id", target.subject.id).put("target_module_id", target.moduleId).put("target_entry_id", target.entryId)
    .put("purpose", purpose.wireName).put("purpose_label", purpose.label).put("target_label", targetLabel)
    .put("status", status?.label).put("position", position)

private fun JSONObject.jsonText(key: String): String = when (val value = opt(key)) {
    is JSONObject, is JSONArray -> value.toString()
    is String -> value
    null -> "{}"
    else -> error("$key 必须是结构化内容")
}

private fun NovexManagedChange.draftKind(): NovexContentKind? = when (this) {
    is NovexManagedChange.CreateWorld -> NovexContentKind.WORLD
    is NovexManagedChange.CreateCharacter -> NovexContentKind.CHARACTER_VERSION
    is NovexManagedChange.CreateInteractiveFiction -> NovexContentKind.INTERACTIVE_FICTION
    else -> null
}

/** Local proposal journal; raw source operations preserve complete module documents. */
internal object NovexManagementPlanCodec {
    fun encode(plan: NovexManagementPlan, changesJson: String): String = JSONObject().apply {
        put("id", plan.id); put("conversationId", plan.conversationId); put("changes", JSONArray(changesJson))
        put("targets", JSONArray(plan.targets.map(::address)))
        put("moduleIds", JSONArray(plan.changes.map { change -> JSONArray(change.initialModules().map { it.id }) }))
        put("risk", plan.risk.name); put("summary", plan.summary); put("impact", JSONArray(plan.impact))
        put("authorizedUserRequest", plan.authorizedUserRequest)
        put("directEditIndices", JSONArray(plan.directEditIndices.sorted()))
        put("expectedModuleContents", JSONObject(plan.expectedModuleContents))
        put("creationRequestScope", plan.creationRequestScope)
        put("draftTargets", JSONArray(plan.draftTargets.map { (index, subject) -> address(subject).put("index", index) }))
    }.toString()

    fun decode(raw: String): NovexManagementPlan {
        val value = JSONObject(raw)
        val targets = value.getJSONArray("targets")
        val drafts = value.getJSONArray("draftTargets")
        val impact = value.getJSONArray("impact")
        return NovexManagementPlan(value.getString("id"), value.getString("conversationId"),
            NovexManagementChangeCodec.decode(value.getJSONArray("changes").toString()).mapIndexed { index, change ->
                val ids = value.getJSONArray("moduleIds").getJSONArray(index)
                val modules = change.initialModules()
                require(ids.length() == modules.size) { "保存的模块编号不完整" }
                val restored = modules.mapIndexed { i, module -> module.copy(id = ids.getString(i)) }
                when (change) {
                    is NovexManagedChange.CreateWorld -> change.copy(modules = restored)
                    is NovexManagedChange.CreateCharacter -> change.copy(modules = restored)
                    is NovexManagedChange.CreateInteractiveFiction -> change.copy(modules = restored)
                    else -> change
                }
            },
            (0 until targets.length()).map { address(targets.getJSONObject(it)) }.toSet(),
            NovexManagementRisk.valueOf(value.getString("risk")), value.getString("summary"),
            (0 until impact.length()).map { impact.getString(it) },
            (0 until drafts.length()).associate { drafts.getJSONObject(it).let { target -> target.getInt("index") to address(target) } },
            value.optString("authorizedUserRequest").ifBlank { null },
            value.optJSONArray("directEditIndices")?.let { indices -> (0 until indices.length()).map { indices.getInt(it) }.toSet() }.orEmpty(),
            value.optJSONObject("expectedModuleContents")?.let { entries -> entries.keys().asSequence().associateWith { entries.getString(it) } }.orEmpty(),
            value.optString("creationRequestScope").ifBlank { null })
    }

    private fun NovexManagedChange.initialModules(): List<NovexModuleDraft> = when (this) {
        is NovexManagedChange.CreateWorld -> modules
        is NovexManagedChange.CreateCharacter -> modules
        is NovexManagedChange.CreateInteractiveFiction -> modules
        else -> emptyList()
    }

    private fun address(value: NovexContentAddress) = JSONObject().put("kind", value.kind.name).put("id", value.id)
    private fun address(value: JSONObject) = NovexContentAddress(NovexContentKind.valueOf(value.getString("kind")), value.getString("id"))
}
