package com.openminis.app.novex.adapter

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.toPlainText
import com.openminis.app.novex.domain.*

/** Called only for an answering role's companion or an explicit player-purpose reference. */
class NovexPlayerIdentityReader(private val workspace: NovexWorkspace) {
    /** Explicit whole-character selection means the player is that character, not its companion. */
    suspend fun readReference(target: NovexReferenceTarget): List<ConversationPlayerIdentity> {
        if (target.subject.kind != NovexContentKind.CHARACTER_VERSION || target.moduleId != null) return read(target)
        require(workspace.referenceStatus(target) == NovexReferenceTargetStatus.AVAILABLE) { "玩家身份引用缺失，请修复后采用" }
        val role = requireNotNull(workspace.characterForVersion(target.subject.id)) { "玩家角色不存在" }.character
        val version = requireNotNull(role.allVersions.singleOrNull { it.id == target.subject.id }) { "玩家角色版本不存在" }
        val sources = NovexReferenceContextReader(workspace).read(target).orEmpty()
        val description = sources.filter { it.content.isNotBlank() }.joinToString("\n\n") { "${it.label}\n${it.content}" }
        if (description.isBlank()) return emptyList()
        val label = "${role.character.name} · ${version.label}"
        return listOf(ConversationPlayerIdentity("card-player:${target.subject.id}:profile:${NovexFrozenContextCodec.digest(description)}", label, description))
    }

    suspend fun read(target: NovexReferenceTarget): List<ConversationPlayerIdentity> {
        require(workspace.referenceStatus(target) == NovexReferenceTargetStatus.AVAILABLE) { "玩家身份引用缺失，请修复后采用" }
        require(target.subject.kind in setOf(NovexContentKind.CHARACTER_VERSION, NovexContentKind.INTERACTIVE_FICTION)) {
            "玩家身份只能引用角色的配套身份或文游的玩家身份"
        }
        if (target.moduleId == null && target.subject.kind == NovexContentKind.INTERACTIVE_FICTION) {
            return listOfNotNull(InteractiveFictionRuntimeSnapshotFactory.create(requireNotNull(workspace.interactiveFiction(target.subject.id))).playerIdentity)
        }
        val modules = target.moduleId?.let { listOf(requireNotNull(workspace.module(it)).module) }
            ?: workspace.modules(ModuleOwner.characterVersion(target.subject.id)).modules.filter { NovexModuleVisibility.isPlayerIdentity(it.type) }
        return modules.sortedBy { it.position }.flatMap { module ->
            require(NovexModuleVisibility.isPlayerIdentity(module.type)) { "所选模块不是玩家身份模块" }
            val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
            val descriptions = if (document is ContentModuleDocument.Collection) {
                document.items.filter { target.entryId == null || it.id == target.entryId }.map {
                    Triple(it.id, it.name.ifBlank { module.name }, document.copy(items = listOf(it)).toPlainText())
                }
            } else listOf(Triple("", module.name, document.toPlainText()))
            descriptions.mapNotNull { (entry, label, text) -> text.trim().takeIf(String::isNotBlank)?.let { description ->
                ConversationPlayerIdentity("card-player:${target.subject.id}:${module.id}:$entry:${NovexFrozenContextCodec.digest(description)}", label, description)
            } }
        }
    }
}
