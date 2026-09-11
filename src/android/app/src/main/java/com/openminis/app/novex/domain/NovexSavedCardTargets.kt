package com.openminis.app.novex.domain

import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType

/** Resolves actual saved objects, independently of navigation, model wording and revision history. */
internal class NovexSavedCardTargets(private val workspace: NovexWorkspace) {
    private suspend fun owner(value: ModuleOwner): NovexContentAddress? = when (value.type) {
        ModuleOwnerType.WORLD -> NovexContentAddress.world(value.id)
        ModuleOwnerType.CHARACTER_VERSION -> NovexContentAddress.characterVersion(value.id)
        ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(value.id)
        ModuleOwnerType.CONTENT_MODULE -> workspace.moduleContent(ModuleOwner.contentModuleId(value.id))
            ?.let { owner(ModuleOwner(it.ownerType, it.ownerId)) }
    }

    suspend fun resolve(command: NovexCommand, result: NovexChange): Set<NovexContentAddress> {
        val saved = when (result) {
            is NovexChange.WorldSaved -> listOf(NovexContentAddress.world(result.world.id))
            is NovexChange.CharacterSaved -> result.character.allVersions.map { NovexContentAddress.characterVersion(it.id) }
            is NovexChange.VersionSaved -> listOf(NovexContentAddress.characterVersion(result.version.id))
            is NovexChange.InteractiveFictionSaved -> listOf(NovexContentAddress.interactiveFiction(result.project.id))
            is NovexChange.ModuleSaved -> listOfNotNull(owner(ModuleOwner(result.module.ownerType, result.module.ownerId)))
            is NovexChange.ModulesSaved -> result.modules.mapNotNull { owner(ModuleOwner(it.ownerType, it.ownerId)) }
            is NovexChange.CardReferenceSaved -> listOf(result.reference.source)
            else -> emptyList()
        }
        val affected = when (command) {
            is NovexCommand.AttachImage -> owner(command.owner)
            is NovexCommand.AddModuleReference -> owner(ModuleOwner.contentModule(command.moduleId))
            else -> null
        }
        return (saved + listOfNotNull(affected)).toSet()
    }
}
