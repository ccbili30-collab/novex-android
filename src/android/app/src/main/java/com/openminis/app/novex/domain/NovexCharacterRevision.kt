package com.openminis.app.novex.domain

import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.ModuleOwnerType
import org.json.JSONArray
import org.json.JSONObject

/** An edit of one character version, never a new life stage or parallel person. */
data class NovexCharacterRevision(
    val versionId: String,
    val sequence: Int,
    val savedAt: Long,
    val contentJson: String,
)

internal interface NovexCharacterRevisionPort {
    suspend fun list(versionId: String): List<NovexCharacterRevision>
    suspend fun append(versionId: String, savedAt: Long, contentJson: String)
}

internal object UnavailableNovexCharacterRevisions : NovexCharacterRevisionPort {
    override suspend fun list(versionId: String) = emptyList<NovexCharacterRevision>()
    override suspend fun append(versionId: String, savedAt: Long, contentJson: String) = Unit
}

/** Called inside the same workspace transaction as the edit. */
internal class NovexCharacterRevisionJournal(
    private val workspace: NovexWorkspace,
    private val records: NovexCharacterRevisionPort,
) {
    suspend fun record(versionId: String, at: Long) {
        val page = workspace.characterForVersion(versionId) ?: return
        val version = page.character.allVersions.single { it.id == versionId }
        val modules = page.modulesByVersion[versionId].orEmpty()
        val value = JSONObject().apply {
            put("label", version.label)
            put("profile", jsonOrText(version.profileJson))
            // A root rename belongs to the default version, not every parallel version.
            if (version.id == page.character.original.id) put("rootName", page.character.character.name)
            put("modules", JSONArray(modules.map { module -> JSONObject().apply {
                put("id", module.id); put("type", module.type.name); put("name", module.name)
                put("content", jsonOrText(module.contentJson))
                put("references", JSONArray(workspace.module(module.id)?.references.orEmpty().map { reference -> JSONObject().apply {
                    put("type", reference.targetType.name); put("id", reference.targetId); put("position", reference.position)
                } }))
            } }))
            put("references", JSONArray(workspace.referencesFrom(NovexContentAddress.characterVersion(versionId))
                .map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
            // History records image identities; it does not promise restoration of deleted files.
            put("imageSignatures", JSONObject().apply {
                page.mediaByVersion[versionId].orEmpty().forEach { (slot, asset) -> put(slot.name, asset.contentHash) }
                modules.forEach { module ->
                    page.moduleImages[module.id]?.let { put("module:${module.id}", it.contentHash) }
                    page.moduleItemImages[module.id].orEmpty().forEach { (entry, asset) -> put("entry:${module.id}:$entry", asset.contentHash) }
                }
            })
        }
        records.append(versionId, at, canonical(value))
    }

    suspend fun existingTargets(command: NovexCommand): List<String> {
        suspend fun module(id: String): String? = workspace.module(ModuleOwner.contentModuleId(id))?.module
            ?.takeIf { it.ownerType == ModuleOwnerType.CHARACTER_VERSION }?.ownerId
        suspend fun owner(value: ModuleOwner): String? = when (value.type) {
            ModuleOwnerType.CHARACTER_VERSION -> value.id
            ModuleOwnerType.CONTENT_MODULE -> module(value.id)
            else -> null
        }
        val id = when (command) {
            is NovexCommand.SaveCharacterVersion -> command.versionId
            is NovexCommand.SaveCharacterPage -> command.versionId?.takeUnless { command.createVariant }
            is NovexCommand.FillConversationDraft -> command.subject.takeIf { it.kind == NovexContentKind.CHARACTER_VERSION }?.id
            is NovexCommand.AddModule -> owner(command.owner)
            is NovexCommand.SaveModules -> owner(command.owner)
            is NovexCommand.SaveModule -> module(command.moduleId)
            is NovexCommand.MoveModule -> module(command.moduleId)
            is NovexCommand.DeleteModule -> module(command.moduleId)
            is NovexCommand.AddModuleReference -> module(command.moduleId)
            is NovexCommand.RemoveModuleReference -> module(command.moduleId)
            is NovexCommand.AttachImage -> owner(command.owner)
            is NovexCommand.DetachImage -> owner(command.owner)
            is NovexCommand.PutCardReference -> command.reference.source.takeIf { it.kind == NovexContentKind.CHARACTER_VERSION }?.id
            is NovexCommand.RemoveCardReference -> command.expectedSource?.takeIf { it.kind == NovexContentKind.CHARACTER_VERSION }?.id
            else -> null
        }
        return listOfNotNull(id)
    }

    suspend fun resultingTargets(result: NovexChange): List<String> = when (result) {
        is NovexChange.CharacterSaved -> result.character.allVersions.map { it.id }
        is NovexChange.VersionSaved -> listOf(result.version.id)
        is NovexChange.NativeCardImported -> if (result.kind == com.openminis.app.data.character.NovexCardKind.CHARACTER)
            workspace.character(result.localId)?.character?.allVersions.orEmpty().map { it.id } else emptyList()
        else -> emptyList()
    }

    private fun jsonOrText(raw: String): Any = runCatching { JSONObject(raw) }.getOrElse { raw }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}

internal fun NovexCommand.revisionTime(): Long = when (this) {
    is NovexCommand.CreateWorld -> now
    is NovexCommand.SaveWorld -> now
    is NovexCommand.SaveWorldPage -> now
    is NovexCommand.SaveInteractiveFictionPage -> now
    is NovexCommand.CreateCharacter -> now
    is NovexCommand.SaveCharacterVersion -> now
    is NovexCommand.SaveCharacterPage -> now
    is NovexCommand.CreateVariant -> now
    is NovexCommand.DuplicateCharacter -> now
    is NovexCommand.SaveAsWorldVariant -> now
    is NovexCommand.ImportCharacter -> now
    is NovexCommand.ImportNativeCard -> now
    is NovexCommand.AddModule -> now
    is NovexCommand.SaveModules -> now
    is NovexCommand.SaveModule -> now
    is NovexCommand.MoveModule -> now
    is NovexCommand.AttachImage -> now
    is NovexCommand.FillConversationDraft -> creation.revisionTime()
    else -> System.currentTimeMillis()
}
