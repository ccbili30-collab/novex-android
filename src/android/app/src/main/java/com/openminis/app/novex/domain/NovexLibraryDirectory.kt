package com.openminis.app.novex.domain

import com.openminis.app.data.character.CharacterVersionProfile

data class NovexLibraryEntry(val address: NovexContentAddress, val title: String, val type: String)

/** Uses the same public catalogs as library pages. Never append conversation placeholders. */
suspend fun NovexWorkspace.libraryDirectory(artifacts: NovexCreativeArtifactReader): List<NovexLibraryEntry> = buildList {
    worlds().forEach { add(NovexLibraryEntry(NovexContentAddress.world(it.world.id), it.world.name, "世界")) }
    characters().forEach { card -> card.character.allVersions.forEach { version ->
        val profile = CharacterVersionProfile.fromJson(version.profileJson, card.character.character.name)
        val name = profile.name.ifBlank { card.character.character.name }
        val suffix = if (version.id == card.character.original.id) "" else " · ${version.label}"
        add(NovexLibraryEntry(NovexContentAddress.characterVersion(version.id), name + suffix, "角色"))
    } }
    interactiveFictions().forEach { add(NovexLibraryEntry(NovexContentAddress.interactiveFiction(it.project.id), it.project.name, "文游")) }
    artifacts.availableArtifacts().forEach { add(NovexLibraryEntry(it.address, NovexDisplayName.file(it.title), "文件")) }
}.distinctBy { it.address }

/** Presentation only: original names, paths and exported records remain untouched. */
object NovexDisplayName {
    private val generatedSuffix = Regex("[-_](?:[0-9a-fA-F]{32,64}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})(?=\\.[^.]+$|$)")
    private val extension = Regex("(?i)\\.(?:txt|md|markdown|json|pdf|docx|png|jpe?g|webp|novexworld|novexcharacter|novexgame)$")
    fun file(raw: String): String {
        var name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = name.replace(generatedSuffix, "")
        if (cleaned != name) {
            name = cleaned
            // Keep the actual file format; remove only the duplicated source extension of a generated wrapper.
            val last = extension.find(name)?.value
            if (last != null) {
                val stem = name.dropLast(last.length)
                name = stem.replace(extension, "") + last
            }
        }
        return name.ifBlank { "未命名文件" }
    }
}
