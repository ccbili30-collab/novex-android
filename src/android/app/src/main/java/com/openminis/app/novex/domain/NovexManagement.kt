package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ContentModuleCatalog
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import com.openminis.app.data.character.ModuleReferenceTarget
import com.openminis.app.data.character.ModuleReferenceTargetType
import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import org.json.JSONArray
import org.json.JSONObject

sealed interface NovexManagedChange {
    data class AddModule(
        val owner: ModuleOwner,
        val type: ContentModuleType,
        val name: String,
        val contentJson: String,
    ) : NovexManagedChange

    data class UpdateModule(
        val moduleId: String,
        val name: String,
        val contentJson: String,
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

    data class CreateWorld(val name: String, val overview: String) : NovexManagedChange
    data class CreateCharacter(val name: String, val profileJson: String) : NovexManagedChange
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
}

data class NovexManagementPlan(
    val id: String,
    val conversationId: String,
    val changes: List<NovexManagedChange>,
    val targets: Set<NovexContentAddress>,
    val risk: NovexManagementRisk,
    val summary: String,
    val impact: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "变更计划编号不能为空" }
        require(conversationId.isNotBlank()) { "对话编号不能为空" }
        require(changes.isNotEmpty()) { "变更计划不能为空" }
    }

    val requiresConfirmation: Boolean get() = true
    val confirmationPhrase: String get() = "确认执行 ${id.take(8)}"

    /** Confirmation text is supplied by the real user turn, never by tool arguments. */
    fun isConfirmedBy(userText: String): Boolean = userText.trim() == confirmationPhrase
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
    ): NovexManagementPlan {
        require(changes.isNotEmpty()) { "至少需要一项变更" }
        require(changes.size <= 20) { "一次最多修改二十项内容" }
        val targets = changes.flatMap { it.targets(facts) }.toSet()
        val createChanges = changes.filter { it.isCreation() }
        createChanges.forEach { change ->
            require(change.matchesCreationRequest(latestUserRequest)) {
                "当前用户消息没有明确要求创建${change.creationLabel()}"
            }
        }
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
)

data class NovexManagementApplyResult(
    val changes: List<NovexChange>,
    val createdSubjects: List<NovexContentAddress>,
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

/** Provider-neutral inspection payload, including the legal values needed for the next call. */
fun NovexManagementInspection.toToolJson(): JSONObject = JSONObject().apply {
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
    suspend fun inspect(
        configuration: NovexConversationConfigurationSnapshot,
        subject: NovexContentAddress?,
        moduleId: String?,
    ): NovexManagementInspection {
        if (subject != null) require(NovexManagementPolicy.canRead(configuration, subject)) {
            "该内容没有挂载到当前对话的管理区"
        }
        val selectedModule = moduleId?.let { id ->
            val value = requireNotNull(workspace.module(id)) { "模块不存在" }
            val owner = value.module.owner.toAddress()
            require(NovexManagementPolicy.canRead(configuration, owner)) {
                "该模块不属于当前对话的管理对象"
            }
            if (subject != null) require(owner == subject) { "模块不属于指定管理对象" }
            value
        }
        val modules = when {
            selectedModule != null -> listOf(selectedModule.module)
            subject != null && subject.kind != NovexContentKind.CREATIVE_ARTIFACT ->
                workspace.modules(subject.toModuleOwner()).modules
            else -> emptyList()
        }
        return NovexManagementInspection(
            subjects = configuration.managedSubjects.map { managed ->
                NovexManagedSubjectInspection(
                    subject = managed.subject,
                    access = managed.access,
                    label = subjectLabel(managed.subject),
                )
            },
            selectedSubject = subject,
            selectedSubjectJson = subject?.let { subjectContentJson(it) },
            modules = modules,
            selectedModule = selectedModule,
        )
    }

    suspend fun propose(
        configuration: NovexConversationConfigurationSnapshot,
        changesJson: String,
        latestUserRequest: String,
        planId: String,
    ): NovexManagementPlan {
        val changes = NovexManagementChangeCodec.decode(changesJson)
        return NovexManagementPolicy.plan(
            configuration = configuration,
            changes = changes,
            facts = factsFor(changes),
            latestUserRequest = latestUserRequest,
            planId = planId,
        )
    }

    suspend fun apply(
        configuration: NovexConversationConfigurationSnapshot,
        plan: NovexManagementPlan,
        confirmationText: String,
    ): NovexManagementApplyResult {
        require(plan.conversationId == configuration.conversationId) { "变更计划不属于当前对话" }
        require(plan.isConfirmedBy(confirmationText)) { "需要用户发送“${plan.confirmationPhrase}”" }
        val facts = factsFor(plan.changes)
        val currentTargets = plan.changes.flatMap { it.targets(facts) }.toSet()
        require(currentTargets == plan.targets) { "内容关系已经变化，请重新生成变更计划" }
        currentTargets.forEach { target ->
            require(configuration.managedSubjects.any {
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
        val changes = mutableListOf<NovexChange>()
        val created = mutableListOf<NovexContentAddress>()
        transaction.run {
            plan.changes.forEach { managed ->
                when (managed) {
                    is NovexManagedChange.AttachArtifact -> artifacts.attach(managed.toAttachment())
                    is NovexManagedChange.DetachArtifact -> artifacts.detach(managed.toAttachment())
                    else -> {
                        val result = workspace.apply(managed.toCommand(facts))
                        changes += result
                        result.createdSubject()?.let(created::add)
                    }
                }
            }
        }
        return NovexManagementApplyResult(changes, created)
    }

    private suspend fun factsFor(changes: List<NovexManagedChange>): NovexManagementFacts {
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
        val characterCards = if (versionIds.isEmpty()) emptyList() else workspace.characters()
        val versionCharacterIds = versionIds.associateWith { versionId ->
            characterCards.firstNotNullOfOrNull { card ->
                card.character.allVersions.firstOrNull { it.id == versionId }?.characterId
            } ?: error("角色版本不存在：$versionId")
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
        NovexContentKind.CHARACTER_VERSION -> workspace.characters().firstNotNullOfOrNull { card ->
            card.character.allVersions.firstOrNull { it.id == subject.id }?.let { version ->
                "${card.character.character.name} · ${version.label}"
            }
        } ?: "已删除角色版本"
        NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(subject.id)?.project?.name ?: "已删除文游"
        NovexContentKind.CREATIVE_ARTIFACT -> artifacts.describe(subject.id)?.title ?: "已删除创作成果"
    }

    private suspend fun subjectContentJson(subject: NovexContentAddress): String = when (subject.kind) {
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
            val pair = workspace.characters().firstNotNullOfOrNull { card ->
                card.character.allVersions.firstOrNull { it.id == subject.id }?.let { card to it }
            } ?: error("角色版本不存在：${subject.id}")
            val (card, version) = pair
            JSONObject().apply {
                put("id", version.id)
                put("character_id", version.characterId)
                put("character_name", card.character.character.name)
                put("kind", version.kind.name)
                put("label", version.label)
                put("profile", runCatching { JSONObject(version.profileJson) }.getOrElse { version.profileJson })
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
                    name = value.getString("name").trim(),
                    contentJson = value.jsonText("content_json"),
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
                )
                "create_character" -> NovexManagedChange.CreateCharacter(
                    value.getString("name").trim(),
                    value.jsonText("profile_json"),
                )
                "create_character_version" -> NovexManagedChange.CreateCharacterVersion(
                    value.getString("source_version_id"),
                    value.getString("label").trim(),
                    value.jsonText("profile_json"),
                )
                "create_game" -> NovexManagedChange.CreateInteractiveFiction(
                    name = value.getString("name").trim(),
                    summary = value.optString("summary"),
                    launchMode = value.optString("launch_mode", InteractiveFictionLaunchMode.FREE_SANDBOX.name)
                        .let(InteractiveFictionLaunchMode::valueOf),
                    playerIdentity = value.optString("player_identity"),
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

    private fun validateChange(change: NovexManagedChange) {
        when (change) {
            is NovexManagedChange.AddModule -> {
                require(change.name.isNotBlank()) { "模块名称不能为空" }
                JSONObject(change.contentJson)
            }
            is NovexManagedChange.UpdateModule -> {
                require(change.moduleId.isNotBlank()) { "模块编号不能为空" }
                require(change.name.isNotBlank()) { "模块名称不能为空" }
                JSONObject(change.contentJson)
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

private fun NovexManagedChange.isCreation(): Boolean = when (this) {
    is NovexManagedChange.CreateWorld,
    is NovexManagedChange.CreateCharacter,
    is NovexManagedChange.CreateCharacterVersion,
    is NovexManagedChange.CreateInteractiveFiction -> true
    else -> false
}

private fun NovexManagedChange.creationLabel(): String = when (this) {
    is NovexManagedChange.CreateWorld -> "世界"
    is NovexManagedChange.CreateCharacter -> "角色"
    is NovexManagedChange.CreateCharacterVersion -> "角色版本或分身"
    is NovexManagedChange.CreateInteractiveFiction -> "文游"
    else -> "内容"
}

private fun NovexManagedChange.matchesCreationRequest(text: String): Boolean {
    val normalized = text.trim()
    val hasCreateVerb = listOf("创建", "新建", "生成", "做一个", "写一个", "增加", "添加")
        .any(normalized::contains)
    if (!hasCreateVerb) return false
    return when (this) {
        is NovexManagedChange.CreateWorld -> normalized.contains("世界") || normalized.contains(name)
        is NovexManagedChange.CreateCharacter -> normalized.contains("角色") || normalized.contains("人物") || normalized.contains(name)
        is NovexManagedChange.CreateCharacterVersion ->
            listOf("分身", "版本", "变体", label).any(normalized::contains)
        is NovexManagedChange.CreateInteractiveFiction ->
            listOf("文游", "游戏", "模拟器", name).any(normalized::contains)
        else -> true
    }
}

private fun NovexManagedChange.summary(): String = when (this) {
    is NovexManagedChange.AddModule -> "新增模块“$name”"
    is NovexManagedChange.UpdateModule -> "修改模块“$name”"
    is NovexManagedChange.MoveModule -> "调整模块顺序"
    is NovexManagedChange.DeleteModule -> "删除模块 $moduleId"
    is NovexManagedChange.AddModuleReference -> "增加内容关联"
    is NovexManagedChange.RemoveModuleReference -> "解除内容关联"
    is NovexManagedChange.CreateWorld -> "创建世界“$name”"
    is NovexManagedChange.CreateCharacter -> "创建角色“$name”"
    is NovexManagedChange.CreateCharacterVersion -> "创建角色版本“$label”"
    is NovexManagedChange.CreateInteractiveFiction -> "创建文游“$name”"
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

private fun NovexManagedChange.toCommand(facts: NovexManagementFacts): NovexCommand = when (this) {
    is NovexManagedChange.AddModule -> NovexCommand.AddModule(
        owner = owner,
        type = type,
        name = name,
        contentJson = contentJson,
    )
    is NovexManagedChange.UpdateModule -> NovexCommand.SaveModule(moduleId, name, contentJson)
    is NovexManagedChange.MoveModule -> NovexCommand.MoveModule(moduleId, toIndex)
    is NovexManagedChange.DeleteModule -> NovexCommand.DeleteModule(moduleId)
    is NovexManagedChange.AddModuleReference -> NovexCommand.AddModuleReference(moduleId, target, position)
    is NovexManagedChange.RemoveModuleReference -> NovexCommand.RemoveModuleReference(moduleId, target)
    is NovexManagedChange.CreateWorld -> NovexCommand.CreateWorld(name, overview)
    is NovexManagedChange.CreateCharacter -> NovexCommand.CreateCharacter(name, profileJson)
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

private fun NovexContentKind.managementWireName(): String = when (this) {
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

private fun JSONObject.jsonText(key: String): String = when (val value = opt(key)) {
    is JSONObject, is JSONArray -> value.toString()
    is String -> value
    null -> "{}"
    else -> error("$key 必须是结构化内容")
}
