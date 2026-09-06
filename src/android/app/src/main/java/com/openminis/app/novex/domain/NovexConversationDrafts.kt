package com.openminis.app.novex.domain

import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleReferenceTargetType
import org.json.JSONArray
import org.json.JSONObject

/** Ownership is explicit; names never locate an object or confer access to it. */
data class NovexConversationDraftCard(
    val subject: NovexContentAddress,
    val rootId: String,
    val isPrivate: Boolean = true,
)

data class NovexDraftWriteReservation(
    val id: String,
    val subjects: Set<NovexContentAddress>,
    val planJson: String,
)

data class NovexConversationDraftSnapshot(
    val conversationId: String,
    val cards: List<NovexConversationDraftCard>,
    val pendingWrites: List<NovexDraftWriteReservation> = emptyList(),
) {
    init {
        require(conversationId.isNotBlank()) { "草稿来源对话不能为空" }
        require(cards.map { it.subject }.distinct().size == cards.size) { "对话草稿编号不能重复" }
        require(cards.all { it.rootId.isNotBlank() && it.subject.kind in setOf(
            NovexContentKind.WORLD, NovexContentKind.CHARACTER_VERSION, NovexContentKind.INTERACTIVE_FICTION,
        ) }) { "私有草稿必须是世界、角色或文游" }
    }
    val subjects: List<NovexContentAddress> get() = cards.map { it.subject }
}

internal interface NovexDraftOwnershipPort {
    suspend fun load(conversationId: String): NovexConversationDraftSnapshot?
    suspend fun list(): List<NovexConversationDraftSnapshot>
    suspend fun save(snapshot: NovexConversationDraftSnapshot)
    suspend fun referencedSubjects(): Set<NovexContentAddress> = emptySet()
}

data class NovexDraftFinalization(
    val snapshot: NovexConversationDraftSnapshot,
    val removedSubjects: Set<NovexContentAddress>,
    val promotedSubjects: Set<NovexContentAddress>,
)

internal object UnavailableNovexDraftOwnership : NovexDraftOwnershipPort {
    override suspend fun load(conversationId: String): NovexConversationDraftSnapshot? = null
    override suspend fun list(): List<NovexConversationDraftSnapshot> = emptyList()
    override suspend fun save(snapshot: NovexConversationDraftSnapshot) = error("尚未配置私有草稿存储")
}

/** Called inside the workspace's existing database and managed-media transaction. */
internal class NovexConversationDrafts(
    private val catalog: NovexCatalogPort,
    private val fiction: NovexInteractiveFictionPort,
    private val ownership: NovexDraftOwnershipPort,
    private val content: NovexContentPort,
    private val media: NovexMediaPort,
    private val references: NovexCardReferencePort,
    private val delete: suspend (NovexConversationDraftCard) -> Unit,
) {
    suspend fun ensure(conversationId: String, now: Long): NovexConversationDraftSnapshot {
        require(conversationId.isNotBlank()) { "草稿来源对话不能为空" }
        val before = ownership.load(conversationId) ?: NovexConversationDraftSnapshot(conversationId, emptyList())
        val cards = before.cards.filter { card -> when (card.subject.kind) {
            NovexContentKind.WORLD -> catalog.world(card.rootId) != null
            NovexContentKind.CHARACTER_VERSION -> catalog.character(card.rootId) != null
            NovexContentKind.INTERACTIVE_FICTION -> fiction.project(card.rootId) != null
            NovexContentKind.CREATIVE_ARTIFACT -> false
        } }.toMutableList()
        for (kind in listOf(NovexContentKind.WORLD, NovexContentKind.CHARACTER_VERSION, NovexContentKind.INTERACTIVE_FICTION)) {
            if (cards.none { it.subject.kind == kind }) cards += createCard(kind, now)
        }
        val snapshot = before.copy(cards = cards)
        if (snapshot == before) return before
        ownership.save(snapshot)
        return snapshot
    }

    suspend fun add(conversationId: String, kind: NovexContentKind, now: Long): NovexConversationDraftSnapshot {
        val before = requireNotNull(ownership.load(conversationId)) { "对话草稿尚未准备好" }
        val after = before.copy(cards = before.cards + createCard(kind, now))
        ownership.save(after)
        return after
    }

    private suspend fun createCard(kind: NovexContentKind, now: Long): NovexConversationDraftCard = when (kind) {
        NovexContentKind.WORLD -> catalog.createWorld("未命名世界", "", "[]", null, now).let { world ->
            NovexConversationDraftCard(NovexContentAddress.world(world.id), world.id)
        }
        NovexContentKind.CHARACTER_VERSION -> catalog.createCharacter("未命名角色", "本体", "{}", now).let { character ->
            NovexConversationDraftCard(NovexContentAddress.characterVersion(character.original.id), character.character.id)
        }
        NovexContentKind.INTERACTIVE_FICTION -> fiction.create("未命名文游", "", InteractiveFictionLaunchMode.FREE_SANDBOX, "", now).let { game ->
            NovexConversationDraftCard(NovexContentAddress.interactiveFiction(game.id), game.id)
        }
        NovexContentKind.CREATIVE_ARTIFACT -> error("创作文件不属于卡片草稿")
    }

    suspend fun reserve(conversationId: String, reservation: NovexDraftWriteReservation): NovexConversationDraftSnapshot {
        require(reservation.id.isNotBlank() && reservation.planJson.isNotBlank()) { "待写入计划不能为空" }
        val before = requireNotNull(ownership.load(conversationId)) { "对话草稿尚未准备好" }
        val after = before.copy(pendingWrites = before.pendingWrites.filterNot { it.id == reservation.id } + reservation)
        ownership.save(after)
        return after
    }

    suspend fun release(conversationId: String, planId: String): NovexConversationDraftSnapshot {
        val before = requireNotNull(ownership.load(conversationId)) { "对话草稿尚未准备好" }
        val after = before.copy(pendingWrites = before.pendingWrites.filterNot { it.id == planId })
        ownership.save(after)
        return after
    }

    suspend fun emptyCards(conversationId: String): List<NovexConversationDraftCard> {
        val reserved = ownership.list().flatMap { it.pendingWrites }.flatMap { it.subjects }.toSet()
        return ownership.load(conversationId)?.cards.orEmpty().filter {
            it.isPrivate && it.subject !in reserved && !hasContent(it)
        }
    }

    suspend fun fillCommand(conversationId: String, subject: NovexContentAddress, creation: NovexCommand): NovexCommand {
        val card = ownership.load(conversationId)?.cards?.singleOrNull { it.subject == subject }
        require(card != null && card.isPrivate) { "创建目标不属于本对话的私有草稿" }
        require(!hasContent(card)) { "空卡已被填写，请重新生成计划；不会覆盖现有内容" }
        return when (creation) {
            is NovexCommand.CreateWorld -> {
                require(subject.kind == NovexContentKind.WORLD)
                NovexCommand.SaveWorldPage(card.rootId, creation.name, creation.overview, creation.tagsJson, now = creation.now)
            }
            is NovexCommand.SaveWorldPage -> {
                require(subject.kind == NovexContentKind.WORLD && creation.worldId == null)
                creation.copy(worldId = card.rootId)
            }
            is NovexCommand.CreateCharacter -> {
                require(subject.kind == NovexContentKind.CHARACTER_VERSION)
                NovexCommand.SaveCharacterPage(card.rootId, subject.id, null, false, creation.name, "本体", creation.profileJson, now = creation.now)
            }
            is NovexCommand.SaveCharacterPage -> {
                require(subject.kind == NovexContentKind.CHARACTER_VERSION && creation.characterId == null &&
                    creation.versionId == null && !creation.createVariant && creation.sourceVersionId == null)
                creation.copy(characterId = card.rootId, versionId = subject.id)
            }
            is NovexCommand.SaveInteractiveFictionPage -> {
                require(subject.kind == NovexContentKind.INTERACTIVE_FICTION && creation.projectId == null)
                creation.copy(projectId = card.rootId)
            }
            else -> error("私有空卡只能承接对应的新建卡片操作")
        }
    }

    suspend fun finalize(conversationId: String, protectedSubjects: Set<NovexContentAddress>): NovexDraftFinalization {
        val before = ownership.load(conversationId) ?: NovexConversationDraftSnapshot(conversationId, emptyList())
        val protected = protectedSubjects + ownership.referencedSubjects() +
            ownership.list().flatMap { it.pendingWrites }.flatMap { it.subjects }
        val incoming = content.all().flatMap { content.references(it.id) }.mapNotNull { reference ->
            when (reference.targetType) {
                ModuleReferenceTargetType.WORLD -> NovexContentAddress.world(reference.targetId)
                ModuleReferenceTargetType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(reference.targetId)
                ModuleReferenceTargetType.MODULE -> null // Owning a module already makes its card nonempty.
            }
        }.toSet()
        val removed = linkedSetOf<NovexContentAddress>()
        val promoted = linkedSetOf<NovexContentAddress>()
        val kept = mutableListOf<NovexConversationDraftCard>()
        for (card in before.cards) {
            when {
                !card.isPrivate -> kept += card
                hasContent(card) -> {
                    kept += card.copy(isPrivate = false)
                    promoted += card.subject
                }
                card.subject in protected || card.subject in incoming || references.incoming(card.subject).isNotEmpty() || hasCatalogLinks(card) -> kept += card
                else -> {
                    delete(card)
                    removed += card.subject
                }
            }
        }
        val after = before.copy(cards = kept.toList())
        ownership.save(after)
        return NovexDraftFinalization(after, removed, promoted)
    }

    private suspend fun hasCatalogLinks(card: NovexConversationDraftCard): Boolean = when (card.subject.kind) {
        NovexContentKind.WORLD -> catalog.versionsForWorld(card.rootId).isNotEmpty()
        NovexContentKind.CHARACTER_VERSION -> catalog.worldsForVersion(card.subject.id).isNotEmpty()
        else -> false
    }

    private suspend fun hasContent(card: NovexConversationDraftCard): Boolean = references.outgoing(card.subject).isNotEmpty() || when (card.subject.kind) {
        NovexContentKind.WORLD -> {
            val world = requireNotNull(catalog.world(card.rootId)) { "私有世界草稿已不存在，未执行清理" }
            world.name != "未命名世界" || world.overview.isNotBlank() || world.tagsJson.trim() != "[]" ||
                !world.legacySnapshotJson.isNullOrBlank() ||
                ownerHasContent(ModuleOwner.world(world.id), listOf(MediaAssetSlot.WORLD_COVER, MediaAssetSlot.WORLD_LOGO, MediaAssetSlot.WORLD_BACKGROUND))
        }
        NovexContentKind.CHARACTER_VERSION -> {
            val character = requireNotNull(catalog.character(card.rootId)) { "私有角色草稿已不存在，未执行清理" }
            character.character.name != "未命名角色" || character.allVersions.size != 1 ||
                character.original.label != "本体" || profileHasContent(character.original.profileJson) ||
                ownerHasContent(ModuleOwner.characterVersion(character.original.id), listOf(MediaAssetSlot.CHARACTER_AVATAR, MediaAssetSlot.CHARACTER_PAGE_BACKGROUND))
        }
        NovexContentKind.INTERACTIVE_FICTION -> {
            val game = requireNotNull(fiction.project(card.rootId)) { "私有文游草稿已不存在，未执行清理" }
            game.name != "未命名文游" || game.summary.isNotBlank() || game.playerIdentity.isNotBlank() ||
                game.launchMode != InteractiveFictionLaunchMode.FREE_SANDBOX || !game.sourceDocumentJson.isNullOrBlank() ||
                !game.sourceId.isNullOrBlank() || ownerHasContent(ModuleOwner.interactiveFiction(game.id),
                    listOf(MediaAssetSlot.INTERACTIVE_FICTION_COVER, MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND))
        }
        NovexContentKind.CREATIVE_ARTIFACT -> error("创作文件不属于空卡清理范围")
    }

    private suspend fun ownerHasContent(owner: ModuleOwner, slots: List<MediaAssetSlot>): Boolean =
        content.list(owner).isNotEmpty() || slots.any { media.assetFor(owner, it) != null }

    private fun profileHasContent(raw: String): Boolean = runCatching {
        val value = JSONObject(raw.ifBlank { "{}" })
        val known = setOf("name", "tags", "gender", "age", "race", "occupation", "summary", "customAttributes", "relationships")
        value.keys().asSequence().any { key ->
            key !in known || if (key == "name") value.optString(key).let { it.isNotBlank() && it != "未命名角色" }
            else hasValue(value.opt(key))
        }
    }.getOrDefault(true) // Unknown or damaged content is preserved, never treated as an empty draft.

    private fun hasValue(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> false
        is String -> value.isNotBlank()
        is JSONArray -> (0 until value.length()).any { hasValue(value.opt(it)) }
        is JSONObject -> value.keys().asSequence().any { hasValue(value.opt(it)) }
        else -> true
    }
}

object NovexConversationDraftCodec {
    fun encode(snapshot: NovexConversationDraftSnapshot): String = JSONObject()
        .put("version", 1).put("conversationId", snapshot.conversationId)
        .put("cards", JSONArray(snapshot.cards.map { card ->
            JSONObject().put("kind", card.subject.kind.name).put("id", card.subject.id)
                .put("rootId", card.rootId).put("private", card.isPrivate)
        })).put("pendingWrites", JSONArray(snapshot.pendingWrites.map { reservation ->
            JSONObject().put("id", reservation.id).put("planJson", reservation.planJson)
                .put("subjects", JSONArray(reservation.subjects.map { subject ->
                    JSONObject().put("kind", subject.kind.name).put("id", subject.id)
                }))
        })).toString()

    fun decode(raw: String): NovexConversationDraftSnapshot {
        val root = JSONObject(raw)
        val cards = root.getJSONArray("cards")
        return NovexConversationDraftSnapshot(root.getString("conversationId"), List(cards.length()) { index ->
            val card = cards.getJSONObject(index)
            NovexConversationDraftCard(
                NovexContentAddress(NovexContentKind.valueOf(card.getString("kind")), card.getString("id")),
                card.getString("rootId"), card.optBoolean("private", true),
            )
        }, pendingWrites = root.optJSONArray("pendingWrites")?.let { values -> List(values.length()) { index ->
            val value = values.getJSONObject(index)
            val subjects = value.getJSONArray("subjects")
            NovexDraftWriteReservation(value.getString("id"), (0 until subjects.length()).map { i ->
                subjects.getJSONObject(i).let { NovexContentAddress(NovexContentKind.valueOf(it.getString("kind")), it.getString("id")) }
            }.toSet(), value.getString("planJson"))
        } }.orEmpty())
    }
}
