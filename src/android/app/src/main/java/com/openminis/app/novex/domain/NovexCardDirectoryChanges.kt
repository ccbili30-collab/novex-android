package com.openminis.app.novex.domain

import com.openminis.app.data.character.*

/** Actual ownership, not display names or the current conversation's navigation route. */
internal class NovexCardDirectoryChanges(private val workspace: NovexWorkspace) {
    suspend fun root(subject: NovexContentAddress): NovexCardCopyKey? = when (subject.kind) {
        NovexContentKind.WORLD -> NovexCardCopyKey(NovexCardKind.WORLD, subject.id)
        NovexContentKind.INTERACTIVE_FICTION -> NovexCardCopyKey(NovexCardKind.GAME, subject.id)
        NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(subject.id)?.character?.character?.id?.let { NovexCardCopyKey(NovexCardKind.CHARACTER, it) }
        else -> null
    }
    private suspend fun owner(owner: ModuleOwner): NovexCardCopyKey? = when (owner.type) {
        ModuleOwnerType.WORLD -> NovexCardCopyKey(NovexCardKind.WORLD, owner.id)
        ModuleOwnerType.INTERACTIVE_FICTION -> NovexCardCopyKey(NovexCardKind.GAME, owner.id)
        ModuleOwnerType.CHARACTER_VERSION -> root(NovexContentAddress.characterVersion(owner.id))
        ModuleOwnerType.CONTENT_MODULE -> workspace.moduleContent(ModuleOwner.contentModuleId(owner.id))?.let { owner(ModuleOwner(it.ownerType, it.ownerId)) }
    }
    suspend fun before(command: NovexCommand): Set<NovexCardCopyKey> {
        val result = linkedSetOf<NovexCardCopyKey>()
        suspend fun version(id: String) { root(NovexContentAddress.characterVersion(id))?.let(result::add) }
        suspend fun module(id: String) { owner(ModuleOwner.contentModule(id))?.let(result::add) }
        suspend fun character(id: String, withLinkedWorlds: Boolean = false) {
            result += NovexCardCopyKey(NovexCardKind.CHARACTER, id)
            if (withLinkedWorlds) workspace.character(id)?.worldsByVersion.orEmpty().values.flatten().forEach { result += NovexCardCopyKey(NovexCardKind.WORLD, it.id) }
        }
        when(command) {
            is NovexCommand.SaveWorld -> result += NovexCardCopyKey(NovexCardKind.WORLD, command.world.id)
            is NovexCommand.SaveWorldPage -> command.worldId?.let { result += NovexCardCopyKey(NovexCardKind.WORLD, it) }
            is NovexCommand.DeleteWorld -> { result += NovexCardCopyKey(NovexCardKind.WORLD, command.worldId); workspace.world(command.worldId)?.versions.orEmpty().forEach { version(it.id) } }
            is NovexCommand.SaveInteractiveFictionPage -> command.projectId?.let { result += NovexCardCopyKey(NovexCardKind.GAME, it) }
            is NovexCommand.DeleteInteractiveFiction -> result += NovexCardCopyKey(NovexCardKind.GAME, command.projectId)
            is NovexCommand.SaveCharacterVersion -> version(command.versionId)
            is NovexCommand.SaveCharacterPage -> command.versionId?.let { version(it) }
            is NovexCommand.CreateVariant -> character(command.characterId)
            is NovexCommand.DeleteCharacter -> character(command.characterId, true)
            is NovexCommand.DeleteVariant -> workspace.characterForVersion(command.versionId)?.character?.character?.id?.let { character(it, true) }
            is NovexCommand.SaveAsWorldVariant -> { result += NovexCardCopyKey(NovexCardKind.WORLD, command.worldId); version(command.sourceVersionId) }
            is NovexCommand.LinkCharacterVersion -> { result += NovexCardCopyKey(NovexCardKind.WORLD, command.worldId); version(command.versionId) }
            is NovexCommand.UnlinkCharacterVersion -> { result += NovexCardCopyKey(NovexCardKind.WORLD, command.worldId); version(command.versionId) }
            is NovexCommand.AddModule -> owner(command.owner)?.let(result::add)
            is NovexCommand.SaveModules -> owner(command.owner)?.let(result::add)
            is NovexCommand.SaveModule -> module(command.moduleId)
            is NovexCommand.MoveModule -> module(command.moduleId)
            is NovexCommand.DeleteModule -> module(command.moduleId)
            is NovexCommand.AddModuleReference -> module(command.moduleId)
            is NovexCommand.RemoveModuleReference -> module(command.moduleId)
            is NovexCommand.AttachImage -> owner(command.owner)?.let(result::add)
            is NovexCommand.DetachImage -> owner(command.owner)?.let(result::add)
            is NovexCommand.PutCardReference -> root(command.reference.source)?.let(result::add)
            is NovexCommand.RemoveCardReference -> command.expectedSource?.let { root(it) }?.let(result::add)
            is NovexCommand.PutVersionRelation -> version(command.relation.sourceVersionId)
            is NovexCommand.RemoveVersionRelation -> version(command.expectedSourceVersionId)
            is NovexCommand.FinalizeConversationDrafts -> workspace.conversationDrafts(command.conversationId)?.cards.orEmpty().forEach { result += NovexCardCopyKey(when(it.subject.kind) { NovexContentKind.WORLD -> NovexCardKind.WORLD; NovexContentKind.CHARACTER_VERSION -> NovexCardKind.CHARACTER; else -> NovexCardKind.GAME }, it.rootId) }
            is NovexCommand.FillConversationDraft -> root(command.subject)?.let(result::add)
            is NovexCommand.CopyCard -> command.plan.items.forEach { result += it.key }
            is NovexCommand.CreateParallelWorld -> { result += NovexCardCopyKey(NovexCardKind.WORLD, command.plan.worldId); command.plan.people.forEach { character(it.characterId) } }
            else -> Unit
        }
        return result
    }
    suspend fun after(change: NovexChange): Set<NovexCardCopyKey> = when(change) {
        is NovexChange.WorldSaved -> setOf(NovexCardCopyKey(NovexCardKind.WORLD, change.world.id))
        is NovexChange.CharacterSaved -> setOf(NovexCardCopyKey(NovexCardKind.CHARACTER, change.character.character.id))
        is NovexChange.VersionSaved -> setOf(NovexCardCopyKey(NovexCardKind.CHARACTER, change.version.characterId))
        is NovexChange.InteractiveFictionSaved -> setOf(NovexCardCopyKey(NovexCardKind.GAME, change.project.id))
        is NovexChange.NativeCardImported -> setOf(NovexCardCopyKey(change.kind, change.localId))
        is NovexChange.CardsCopied -> change.result.addresses.values.mapNotNull { root(it) }.toSet()
        is NovexChange.WorldParallelCreated -> setOf(NovexCardCopyKey(NovexCardKind.WORLD, change.result.worldId)) + change.result.versions.values.mapNotNull { root(NovexContentAddress.characterVersion(it)) }
        is NovexChange.ConversationDraftsPrepared -> change.snapshot.cards.mapNotNull { root(it.subject) }.toSet()
        else -> emptySet()
    }
}
