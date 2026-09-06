package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** All choices are captured before showing the picker. Selecting never rereads mutable cards. */
object NovexGamePlayerChoices {
    fun read(snapshot: ActiveInteractiveFictionSnapshot): List<ConversationPlayerIdentity> {
        val array = JSONObject(snapshot.contentJson).optJSONArray("playerIdentityChoices")
            ?: return listOfNotNull(snapshot.playerIdentity)
        return (0 until array.length()).map { index -> array.getJSONObject(index).let {
            ConversationPlayerIdentity(it.getString("id"), it.getString("label"), it.getString("description"))
        } }
    }

    fun prepare(snapshot: ActiveInteractiveFictionSnapshot, identities: List<ConversationPlayerIdentity>): ActiveInteractiveFictionSnapshot {
        val choices = identities.distinctBy { it.id }
        return save(snapshot, choices, choices.singleOrNull())
    }

    fun needsSelection(snapshot: ActiveInteractiveFictionSnapshot): Boolean =
        read(snapshot).let { it.isNotEmpty() && snapshot.playerIdentity !in it }

    fun select(snapshot: ActiveInteractiveFictionSnapshot, id: String): ActiveInteractiveFictionSnapshot {
        val choices = read(snapshot)
        return save(snapshot, choices, requireNotNull(choices.singleOrNull { it.id == id }) { "玩家身份不在本次准备的候选中，请重新选择" })
    }

    fun useCurrent(snapshot: ActiveInteractiveFictionSnapshot, identity: ConversationPlayerIdentity): ActiveInteractiveFictionSnapshot =
        save(snapshot, (read(snapshot).filterNot { it.id == identity.id } + identity), identity)

    private fun save(snapshot: ActiveInteractiveFictionSnapshot, choices: List<ConversationPlayerIdentity>, selected: ConversationPlayerIdentity?): ActiveInteractiveFictionSnapshot {
        val content = JSONObject(snapshot.contentJson).apply {
            put("playerIdentityChoices", JSONArray(choices.map {
                JSONObject().put("id", it.id).put("label", it.label).put("description", it.description)
            }))
            put("selectedPlayerIdentityId", selected?.id)
        }.toString()
        return snapshot.copy(contentJson = content, snapshotId = NovexFrozenContextCodec.digest(content), playerIdentity = selected)
    }
}
