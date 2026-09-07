package com.openminis.app.novex.domain

import kotlinx.coroutines.flow.Flow

/** Library organization only. Membership never adopts content or grants editing rights. */
data class NovexWorkGroup(val id: String, val name: String, val members: Set<NovexContentAddress>)
data class NovexWorkConversation(val id: String, val title: String,
    val used: Set<NovexContentAddress> = emptySet(), val managed: Set<NovexContentAddress> = emptySet())

object NovexConversationSubjectProjection {
    fun used(configuration: NovexConversationConfigurationSnapshot): Set<NovexContentAddress> = buildSet {
        (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.let { add(NovexContentAddress.characterVersion(it.versionId)) }
        addAll(configuration.backgroundSettings.filter { NovexSettingUse.enabled(configuration, NovexReferenceTarget(it.subject)) }.map { it.subject })
        configuration.activeInteractiveFiction?.let { add(NovexContentAddress.interactiveFiction(it.projectId)) }
        addAll(NovexEffectiveFrozenContext.sources(configuration).map { it.target.subject })
    }
}

data class NovexWorkGroupSnapshot(val groups: List<NovexWorkGroup>, val selection: String) {
    val selectedGroup get() = groups.firstOrNull { it.id == selection }
    val label get() = selectedGroup?.name ?: if (selection == UNCLASSIFIED) "未归类" else "全部作品"
    fun includes(address: NovexContentAddress): Boolean = when (selection) {
        ALL -> true
        UNCLASSIFIED -> groups.none { address in it.members }
        else -> selectedGroup?.members?.contains(address) == true
    }
    companion object {
        const val ALL = "all"
        const val UNCLASSIFIED = "unclassified"
    }
}

interface NovexWorkGroups {
    val snapshots: Flow<NovexWorkGroupSnapshot>
    /** Existing peer conversations; this directory is not group ownership. */
    val conversations: Flow<List<NovexWorkConversation>>
    suspend fun select(id: String)
    suspend fun create(name: String): String
    suspend fun rename(id: String, expectedName: String, name: String)
    suspend fun replaceMembers(id: String, expected: Set<NovexContentAddress>, members: Set<NovexContentAddress>)
    suspend fun dissolve(id: String)
}
