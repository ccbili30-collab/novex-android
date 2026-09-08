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
        NovexWorkGroupSnapshot(groups.map { group -> NovexLibraryOrganization.decode(group.id, group.name,
            members.filter { it.groupId == group.id }.map { it.address() }.toSet(), group.organizationJson) },
            selected.takeIf { it in setOf(NovexWorkGroupSnapshot.ALL, NovexWorkGroupSnapshot.UNCLASSIFIED) || groups.any { g -> g.id == it } }
                ?: NovexWorkGroupSnapshot.ALL)
    }
    override suspend fun select(id: String) = database.withTransaction {
        require(id in setOf(NovexWorkGroupSnapshot.ALL, NovexWorkGroupSnapshot.UNCLASSIFIED) || dao.find(id) != null) { "创作库已不存在，请重新选择" }
        dao.select(NovexWorkGroupSelectionEntity(selection = id))
    }
    override suspend fun create(name: String): String = database.withTransaction {
        val id = UUID.randomUUID().toString()
        dao.insert(NovexWorkGroupEntity(id, validName(name)))
        dao.select(NovexWorkGroupSelectionEntity(selection = id))
        id
    }
    override suspend fun rename(id: String, expectedName: String, name: String) = database.withTransaction {
        require(dao.find(id)?.name == expectedName) { "创作库名称已变化，请重新打开后修改" }
        dao.rename(id, validName(name))
    }
    override suspend fun replaceMembers(id: String, expected: Set<NovexContentAddress>, members: Set<NovexContentAddress>, newMemberFolderId: String?) = database.withTransaction {
        require(dao.find(id) != null) { "创作库已不存在" }
        require(dao.members(id).map { it.address() }.toSet() == expected) { "创作库收录已变化，请重新打开后选择" }
        val privateCards = database.novexConversationDraftDao().list().flatMap {
            NovexConversationDraftCodec.decode(it.contentJson).cards
        }.filter { it.isPrivate }.map { it.subject }.toSet()
        require((members - expected).none { it in privateCards }) { "空白占位卡尚未成为仓库内容" }
        val old = load(id)
        for (member in members - expected) require(dao.targetExists(member.kind.name, member.id)) {
            "待加入的内容已不存在，请重新读取选择列表"
        }
        dao.clearMembers(id)
        dao.insertMembers(members.map { NovexWorkGroupMemberEntity(id, it.kind.name, it.id) })
        dao.organize(id, NovexLibraryOrganization.encode(old.copy(members = members, locations = old.locations.filterKeys { it in members } +
            (if (newMemberFolderId == null) emptyMap() else (members - expected).associateWith { newMemberFolderId }))))
    }
    private suspend fun load(id: String): NovexWorkGroup {
        val row = requireNotNull(dao.find(id)) { "创作库已不存在" }
        return NovexLibraryOrganization.decode(id, row.name, dao.members(id).map { it.address() }.toSet(), row.organizationJson)
    }
    override suspend fun createFolder(id: String, parentId: String?, name: String): String = database.withTransaction {
        val group = load(id)
        val folder = NovexLibraryFolder(UUID.randomUUID().toString(), validName(name), parentId)
        dao.organize(id, NovexLibraryOrganization.encode(group.copy(folders = group.folders + folder)))
        folder.id
    }
    override suspend fun renameFolder(id: String, folderId: String, expectedName: String, name: String) = database.withTransaction {
        val group = load(id)
        require(group.folders.firstOrNull { it.id == folderId }?.name == expectedName) { "文件夹已变化，请重新打开" }
        dao.organize(id, NovexLibraryOrganization.encode(group.copy(folders = group.folders.map {
            if (it.id == folderId) it.copy(name = validName(name)) else it
        })))
    }
    override suspend fun removeFolder(id: String, folderId: String) = database.withTransaction {
        val group = load(id)
        require(group.folders.any { it.id == folderId }) { "文件夹已不存在" }
        require(group.folders.none { it.parentId == folderId } && group.contents(folderId).isEmpty()) { "请先移出文件夹里的内容和子文件夹" }
        dao.organize(id, NovexLibraryOrganization.encode(group.copy(folders = group.folders.filterNot { it.id == folderId })))
    }
    override suspend fun moveMembers(id: String, members: Set<NovexContentAddress>, expectedFolderId: String?, folderId: String?) = database.withTransaction {
        val group = load(id)
        require(members.all { it in group.members && group.locations[it] == expectedFolderId }) { "内容位置已变化，请重新打开" }
        val locations = group.locations.toMutableMap()
        members.forEach { if (folderId == null) locations.remove(it) else locations[it] = folderId }
        dao.organize(id, NovexLibraryOrganization.encode(group.copy(locations = locations)))
    }
    override suspend fun dissolve(id: String) = database.withTransaction {
        dao.clearSelection(id)
        dao.deleteGroup(id) // The only cascade is membership; source cards have no ownership FK here.
    }
    private fun NovexWorkGroupMemberEntity.address() = NovexContentAddress(NovexContentKind.valueOf(kind), targetId)
    private fun validName(name: String) = name.trim().also { require(it.isNotEmpty() && it.length <= 120) { "创作库名称需为 1—120 字符" } }
}
