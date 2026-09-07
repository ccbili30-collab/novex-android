package com.openminis.app.novex.domain

import kotlinx.coroutines.flow.Flow

/** Library organization only. Membership never adopts content or grants editing rights. */
data class NovexWorkGroup(val id: String, val name: String, val members: Set<NovexContentAddress>)

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
    suspend fun select(id: String)
    suspend fun create(name: String): String
    suspend fun rename(id: String, expectedName: String, name: String)
    suspend fun replaceMembers(id: String, expected: Set<NovexContentAddress>, members: Set<NovexContentAddress>)
    suspend fun dissolve(id: String)
}
