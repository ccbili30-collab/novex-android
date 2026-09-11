package com.openminis.app.novex.domain

import com.openminis.app.data.character.*
import org.json.JSONArray
import org.json.JSONObject

/** Saved edits of one card, independent of stages and parallel variants. */
data class NovexCardRevision(val subject: NovexContentAddress, val sequence: Int, val savedAt: Long, val contentJson: String)

internal interface NovexCardRevisionPort {
    suspend fun list(subject: NovexContentAddress): List<NovexCardRevision>
    suspend fun append(subject: NovexContentAddress, at: Long, content: String)
}
internal object UnavailableNovexCardRevisions : NovexCardRevisionPort {
    override suspend fun list(subject: NovexContentAddress) = emptyList<NovexCardRevision>()
    override suspend fun append(subject: NovexContentAddress, at: Long, content: String) = Unit
}

/** The caller encloses before/edit/after in the existing workspace transaction. */
internal class NovexCardRevisionJournal(private val workspace: NovexWorkspace, private val records: NovexCardRevisionPort) {
    suspend fun record(subject: NovexContentAddress, at: Long) {
        val value: JSONObject
        val modules: List<ContentModuleEntity>
        val images: Map<MediaAssetSlot, MediaAssetEntity>
        val moduleImages: Map<String, MediaAssetEntity>
        val entryImages: Map<String, Map<String, MediaAssetEntity>>
        when (subject.kind) {
            NovexContentKind.WORLD -> {
                val page = workspace.world(subject.id) ?: return
                value = JSONObject().put("name", page.world.name).put("overview", page.world.overview)
                    .put("tags", revisionJsonValue(page.world.tagsJson)).put("legacySnapshot", page.world.legacySnapshotJson ?: JSONObject.NULL)
                value.put("characterVersions", JSONArray(page.versions.map { it.id }))
                modules = page.modules; images = page.media; moduleImages = page.moduleImages; entryImages = page.moduleItemImages
            }
            NovexContentKind.INTERACTIVE_FICTION -> {
                val page = workspace.interactiveFiction(subject.id) ?: return
                value = JSONObject().put("name", page.project.name).put("summary", page.project.summary)
                    .put("launchMode", page.project.launchMode.name).put("playerIdentity", page.project.playerIdentity)
                    .put("sourceId", page.project.sourceId ?: JSONObject.NULL)
                    .put("sourceDocument", page.project.sourceDocumentJson ?: JSONObject.NULL)
                modules = page.modules; images = page.media; moduleImages = page.moduleImages; entryImages = page.moduleItemImages
            }
            else -> return
        }
        value.put("modules", JSONArray(modules.sortedBy { it.position }.map { module -> JSONObject().apply {
            put("id", module.id); put("type", module.type.name); put("name", module.name); put("position", module.position)
            put("content", revisionJsonValue(module.contentJson))
            put("references", JSONArray(workspace.moduleReferences(module.id).map { ref -> JSONObject()
                .put("type", ref.targetType.name).put("id", ref.targetId).put("position", ref.position) }))
        } }))
        value.put("references", JSONArray(workspace.referencesFrom(subject).sortedBy { it.id }.map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
        value.put("imageSignatures", JSONObject().apply {
            images.forEach { (slot, asset) -> put(slot.name, asset.contentHash) }
            modules.forEach { module ->
                moduleImages[module.id]?.let { put("module:${module.id}", it.contentHash) }
                entryImages[module.id].orEmpty().forEach { (entry, asset) -> put("entry:${module.id}:$entry", asset.contentHash) }
            }
        })
        records.append(subject, at, canonicalRevisionJson(value))
    }

    suspend fun existingTargets(command: NovexCommand): List<NovexContentAddress> {
        if(command is NovexCommand.CopyCard && command.plan.policy == NovexCardCopyPolicy.REUSE)
            return command.plan.items.filter { it.key.kind == NovexCardKind.CHARACTER }.flatMap { item ->
                workspace.character(item.key.id)?.worldsByVersion.orEmpty().values.flatten().map { NovexContentAddress.world(it.id) }
            }.distinct()

        fun subject(type: ModuleOwnerType, id: String): NovexContentAddress? = when(type) {
            ModuleOwnerType.WORLD -> NovexContentAddress.world(id)
            ModuleOwnerType.INTERACTIVE_FICTION -> NovexContentAddress.interactiveFiction(id)
            else -> null
        }
        suspend fun module(id: String) = workspace.moduleContent(ModuleOwner.contentModuleId(id))?.let { subject(it.ownerType, it.ownerId) }
        suspend fun owner(value: ModuleOwner) = if(value.type == ModuleOwnerType.CONTENT_MODULE) module(value.id) else subject(value.type, value.id)
        val target = when(command) {
            is NovexCommand.LinkCharacterVersion -> NovexContentAddress.world(command.worldId)
            is NovexCommand.UnlinkCharacterVersion -> NovexContentAddress.world(command.worldId)
            is NovexCommand.SaveAsWorldVariant -> NovexContentAddress.world(command.worldId)
            is NovexCommand.SaveWorld -> NovexContentAddress.world(command.world.id)
            is NovexCommand.SaveWorldPage -> command.worldId?.let { NovexContentAddress.world(it) }
            is NovexCommand.SaveInteractiveFictionPage -> command.projectId?.let { NovexContentAddress.interactiveFiction(it) }
            is NovexCommand.FillConversationDraft -> command.subject
            is NovexCommand.AddModule -> owner(command.owner)
            is NovexCommand.SaveModules -> owner(command.owner)
            is NovexCommand.SaveModule -> module(command.moduleId)
            is NovexCommand.MoveModule -> module(command.moduleId)
            is NovexCommand.DeleteModule -> module(command.moduleId)
            is NovexCommand.AddModuleReference -> module(command.moduleId)
            is NovexCommand.RemoveModuleReference -> module(command.moduleId)
            is NovexCommand.AttachImage -> owner(command.owner)
            is NovexCommand.DetachImage -> owner(command.owner)
            is NovexCommand.PutCardReference -> command.reference.source
            is NovexCommand.RemoveCardReference -> command.expectedSource
            else -> null
        }
        return listOfNotNull(target).filter { it.kind != NovexContentKind.CHARACTER_VERSION }
    }
    fun resultingTargets(result: NovexChange): List<NovexContentAddress> = when(result) {
        is NovexChange.CardsCopied -> result.result.addresses.values.filter { it.kind != NovexContentKind.CHARACTER_VERSION }
        is NovexChange.WorldSaved -> listOf(NovexContentAddress.world(result.world.id))
        is NovexChange.InteractiveFictionSaved -> listOf(NovexContentAddress.interactiveFiction(result.project.id))
        is NovexChange.NativeCardImported -> when(result.kind) {
            NovexCardKind.WORLD -> listOf(NovexContentAddress.world(result.localId))
            NovexCardKind.GAME -> listOf(NovexContentAddress.interactiveFiction(result.localId))
            else -> emptyList()
        }
        else -> emptyList()
    }
}

internal fun revisionJsonValue(raw: String): Any = runCatching { org.json.JSONTokener(raw).nextValue() ?: raw }.getOrDefault(raw)
internal fun canonicalRevisionJson(value: Any?): String = buildString { appendCanonicalRevision(value) }

// Share one output buffer across the tree. Recursive joinToString copied each large
// descendant again at every parent, multiplying allocations in revision/directory saves.
private fun StringBuilder.appendCanonicalRevision(value: Any?) {
    when (value) {
        is JSONObject -> {
            append('{')
            value.keys().asSequence().toList().sorted().forEachIndexed { index, key ->
                if (index > 0) append(',')
                append(JSONObject.quote(key)).append(':')
                appendCanonicalRevision(value.get(key))
            }
            append('}')
        }
        is JSONArray -> {
            append('[')
            repeat(value.length()) { index ->
                if (index > 0) append(',')
                appendCanonicalRevision(value.get(index))
            }
            append(']')
        }
        is String -> append(JSONObject.quote(value))
        null, JSONObject.NULL -> append("null")
        else -> append(value.toString())
    }
}

/** Exact structured changes. No inferred meaning and no restoration of media. */
object NovexRevisionDifference {
    data class Change(val path: String, val before: String?, val after: String?)
    fun compare(before: String, after: String): List<Change> {
        val changes = mutableListOf<Change>()
        fun visit(path: String, a: Any?, b: Any?, hasA: Boolean = true, hasB: Boolean = true) {
            if(hasA && hasB && canonicalRevisionJson(a) == canonicalRevisionJson(b)) return
            if(a is JSONObject && b is JSONObject) {
                (a.keys().asSequence().toSet() + b.keys().asSequence().toSet()).sorted().forEach { key ->
                    visit("$path/${key.replace("~", "~0").replace("/", "~1")}", a.opt(key), b.opt(key), a.has(key), b.has(key))
                }
            } else if(a is JSONArray && b is JSONArray) {
                (0 until maxOf(a.length(), b.length())).forEach { index -> visit("$path/$index", a.opt(index), b.opt(index), index < a.length(), index < b.length()) }
            } else changes += Change(path.ifEmpty { "/" }, if(hasA) canonicalRevisionJson(a) else null, if(hasB) canonicalRevisionJson(b) else null)
        }
        visit("", JSONObject(before), JSONObject(after))
        return changes
    }
}
