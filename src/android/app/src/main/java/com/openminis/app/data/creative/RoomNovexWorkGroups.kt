package com.openminis.app.data.creative

import androidx.room.withTransaction
import com.openminis.app.data.db.*
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.flow.combine
import java.util.UUID

class RoomNovexWorkGroups(private val database: AppDatabase) : NovexWorkGroups {
    private val dao get() = database.novexWorkGroupDao()
    override val conversations = combine(database.chatDao().observeSessions(), database.novexConversationDraftDao().observeList()) { rows, drafts ->
        val created = drafts.mapNotNull { runCatching { NovexConversationDraftCodec.decode(it.contentJson) }.getOrNull() }
            .associate { it.conversationId to it.cards.filterNot { card -> card.isPrivate }.map { card -> card.subject } }
        rows.sortedByDescending { it.updatedAt }.map { row ->
            val configuration = NovexConversationConfigurationCodec.decode(row.novexConfigurationJson, row.id)
            val used = NovexConversationSubjectProjection.used(configuration).toMutableSet()
            if (row.novexConfigurationJson.isNullOrBlank()) {
                (row.worldId?.takeIf(String::isNotBlank) ?: row.worldSnapshotJson?.let { raw ->
                    runCatching { org.json.JSONObject(raw).optString("id").trim().takeIf(String::isNotBlank) }.getOrNull()
                })?.let { used += NovexContentAddress.world(it) }
                row.characterVersionId?.takeIf(String::isNotBlank)?.let { used += NovexContentAddress.characterVersion(it) }
            }
            NovexWorkConversation(row.id, row.title?.takeIf(String::isNotBlank) ?: "未命名对话", used,
                configuration.managedSubjects.map { it.subject }.toSet() + created[row.id].orEmpty())
        }
    }
    override val snapshots = combine(dao.observeGroups(), dao.observeMembers(), dao.observeSelection()) { groups, members, selection ->
        val selected = selection?.selection ?: NovexWorkGroupSnapshot.ALL
        NovexWorkGroupSnapshot(groups.map { group -> NovexWorkGroup(group.id, group.name,
            members.filter { it.groupId == group.id }.map { it.address() }.toSet()) },
            selected.takeIf { it in setOf(NovexWorkGroupSnapshot.ALL, NovexWorkGroupSnapshot.UNCLASSIFIED) || groups.any { g -> g.id == it } }
                ?: NovexWorkGroupSnapshot.ALL)
    }
    override suspend fun select(id: String) = database.withTransaction {
        require(id in setOf(NovexWorkGroupSnapshot.ALL, NovexWorkGroupSnapshot.UNCLASSIFIED) || dao.find(id) != null) { "作品已不存在，请重新选择" }
        dao.select(NovexWorkGroupSelectionEntity(selection = id))
    }
    override suspend fun create(name: String): String = database.withTransaction {
        val id = UUID.randomUUID().toString()
        dao.insert(NovexWorkGroupEntity(id, validName(name)))
        dao.select(NovexWorkGroupSelectionEntity(selection = id))
        id
    }
    override suspend fun rename(id: String, expectedName: String, name: String) = database.withTransaction {
        require(dao.find(id)?.name == expectedName) { "作品名称已变化，请重新打开后修改" }
        dao.rename(id, validName(name))
    }
    override suspend fun replaceMembers(id: String, expected: Set<NovexContentAddress>, members: Set<NovexContentAddress>) = database.withTransaction {
        require(dao.find(id) != null) { "作品已不存在" }
        require(dao.members(id).map { it.address() }.toSet() == expected) { "作品收录已变化，请重新打开后选择" }
        require(members.none { it.kind == NovexContentKind.CREATIVE_ARTIFACT }) { "作品分组当前收录世界、角色版本和文游" }
        for (member in members - expected) require(dao.targetExists(member.kind.name, member.id)) {
            "待加入的卡片已不存在，请重新读取选择列表"
        }
        dao.clearMembers(id)
        dao.insertMembers(members.map { NovexWorkGroupMemberEntity(id, it.kind.name, it.id) })
    }
    override suspend fun dissolve(id: String) = database.withTransaction {
        dao.clearSelection(id)
        dao.deleteGroup(id) // The only cascade is membership; source cards have no ownership FK here.
    }
    private fun NovexWorkGroupMemberEntity.address() = NovexContentAddress(NovexContentKind.valueOf(kind), targetId)
    private fun validName(name: String) = name.trim().also { require(it.isNotEmpty() && it.length <= 120) { "作品名称需为 1—120 字符" } }
}
