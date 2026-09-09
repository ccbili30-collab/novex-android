package com.openminis.app.novex.domain

import org.json.JSONObject

/** Partial header edits resolve inside the existing management transaction. */
internal object NovexCardHeaderEdit {
    fun key(target: NovexContentAddress) = "${target.kind.name}:${target.id}"

    suspend fun fingerprint(workspace: NovexWorkspace, target: NovexContentAddress): String {
        val value = when (target.kind) {
            NovexContentKind.WORLD -> requireNotNull(workspace.world(target.id)) { "世界不存在" }.world.toString()
            NovexContentKind.CHARACTER_VERSION -> {
                val aggregate = requireNotNull(workspace.characterForVersion(target.id)) { "角色不存在" }.character
                aggregate.character.toString() + requireNotNull(aggregate.allVersions.firstOrNull { it.id == target.id })
            }
            NovexContentKind.INTERACTIVE_FICTION -> requireNotNull(workspace.interactiveFiction(target.id)) { "文游不存在" }.project.toString()
            else -> error("请选择卡片")
        }
        return NovexFrozenContextCodec.digest(value)
    }

    suspend fun command(workspace: NovexWorkspace, edit: NovexManagedChange.UpdateCard): NovexCommand = when (edit.target.kind) {
        NovexContentKind.WORLD -> {
            val world = requireNotNull(workspace.world(edit.target.id)) { "世界不存在" }.world
            NovexCommand.SaveWorld(world.copy(name = edit.name ?: world.name, overview = edit.summary ?: world.overview))
        }
        NovexContentKind.CHARACTER_VERSION -> {
            val aggregate = requireNotNull(workspace.characterForVersion(edit.target.id)) { "角色不存在" }.character
            val version = requireNotNull(aggregate.allVersions.firstOrNull { it.id == edit.target.id }) { "角色版本不存在" }
            val profile = JSONObject(version.profileJson).apply {
                edit.name?.let { put("name", it) }
                edit.summary?.let { put("summary", it) }
            }
            NovexCommand.SaveCharacterVersion(aggregate.character.id, version.id,
                edit.name ?: aggregate.character.name, version.label, profile.toString())
        }
        NovexContentKind.INTERACTIVE_FICTION -> {
            val snapshot = requireNotNull(workspace.interactiveFiction(edit.target.id)) { "文游不存在" }
            val game = snapshot.project
            NovexCommand.SaveInteractiveFictionPage(game.id, edit.name ?: game.name, edit.summary ?: game.summary,
                edit.launchMode ?: game.launchMode, game.playerIdentity, snapshot.modules.map(NovexModuleDraft::from))
        }
        else -> error("请选择卡片")
    }

    suspend fun matches(workspace: NovexWorkspace, edit: NovexManagedChange.UpdateCard): Boolean = when (edit.target.kind) {
        NovexContentKind.WORLD -> workspace.world(edit.target.id)?.world?.let {
            (edit.name == null || edit.name == it.name) && (edit.summary == null || edit.summary == it.overview)
        } == true
        NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(edit.target.id)?.character?.allVersions
            ?.firstOrNull { it.id == edit.target.id }?.let { version ->
                val profile = JSONObject(version.profileJson)
                (edit.name == null || edit.name == profile.optString("name")) &&
                    (edit.summary == null || edit.summary == profile.optString("summary"))
            } == true
        NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(edit.target.id)?.project?.let {
            (edit.name == null || edit.name == it.name) && (edit.summary == null || edit.summary == it.summary) &&
                (edit.launchMode == null || edit.launchMode == it.launchMode)
        } == true
        else -> false
    }
}
