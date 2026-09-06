package com.openminis.app.novex.domain

import com.openminis.app.data.character.ImmersiveChatProfile
import com.openminis.app.data.character.MediaAssetSlot
import org.json.JSONObject

object NovexSnapshotMediaProjection {
    fun visible(configuration: NovexConversationConfigurationSnapshot): List<NovexSnapshotMedia> {
        val gameMedia = configuration.activeInteractiveFiction?.let { game ->
            JSONObject(game.contentJson).optJSONArray("adoptedMedia")?.let { images ->
                (0 until images.length()).map { NovexSnapshotMediaCodec.decode(images.getJSONObject(it)) }
            }
        }.orEmpty()
        return (NovexEffectiveFrozenContext.sources(configuration).flatMap { it.media } + gameMedia)
            .distinctBy { listOf(it.owner, it.slot, it.moduleId, it.entryId) }
    }

    fun profile(configuration: NovexConversationConfigurationSnapshot, profile: ImmersiveChatProfile): ImmersiveChatProfile {
        val images = visible(configuration)
        val replacements = images.filter { it.asset.sourcePath.isNotBlank() }.distinctBy { it.asset.sourcePath }
            .associate { it.asset.sourcePath to it.asset.path }
        fun retained(path: String?) = replacements[path] ?: path
        val actor = (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.versionId
        val avatar = images.firstOrNull { actor != null && it.owner == NovexContentAddress.characterVersion(actor) && it.slot == MediaAssetSlot.CHARACTER_AVATAR }
        return profile.copy(
            assistantAvatarPath = retained(profile.assistantAvatarPath)?.takeIf(String::isNotBlank) ?: avatar?.asset?.path,
            backgroundPath = retained(profile.backgroundPath),
            character = profile.character?.let { it.copy(avatarPath = retained(it.avatarPath), coverPath = retained(it.coverPath),
                defaultBackgroundPath = retained(it.defaultBackgroundPath)) },
            world = profile.world?.let { it.copy(backgroundPath = retained(it.backgroundPath)) },
        )
    }
}
