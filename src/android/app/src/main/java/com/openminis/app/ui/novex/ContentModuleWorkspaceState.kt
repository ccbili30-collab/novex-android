package com.openminis.app.ui.novex

import com.openminis.app.data.character.ContentModuleEntity

/**
 * Pure editing state shared by world and character module editors.
 * Expansion is deliberately session-only: reopening an editor starts folded,
 * while ordering is normalized into the draft that will be persisted.
 */
internal class ContentModuleWorkspaceState private constructor(
    val modules: List<ContentModuleEntity>,
    val expandedModuleIds: Set<String>,
) {
    fun toggleExpanded(moduleId: String): ContentModuleWorkspaceState {
        if (modules.none { it.id == moduleId }) return this
        val next = expandedModuleIds.toMutableSet().apply {
            if (!add(moduleId)) remove(moduleId)
        }
        return ContentModuleWorkspaceState(modules = modules, expandedModuleIds = next)
    }

    fun move(moduleId: String, toIndex: Int): ContentModuleWorkspaceState {
        val mutable = modules.toMutableList()
        val fromIndex = mutable.indexOfFirst { it.id == moduleId }
        if (fromIndex < 0) return this
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex.coerceIn(0, mutable.size), moved)
        return ContentModuleWorkspaceState(
            modules = mutable.mapIndexed { index, module -> module.copy(position = index) },
            expandedModuleIds = expandedModuleIds,
        )
    }

    fun remove(moduleId: String): ContentModuleWorkspaceState = ContentModuleWorkspaceState(
        modules = modules.filterNot { it.id == moduleId }
            .mapIndexed { index, module -> module.copy(position = index) },
        expandedModuleIds = expandedModuleIds - moduleId,
    )

    companion object {
        fun fromSaved(modules: List<ContentModuleEntity>): ContentModuleWorkspaceState =
            ContentModuleWorkspaceState(
                modules = modules.sortedWith(
                    compareBy<ContentModuleEntity> { it.position }
                        .thenBy { it.createdAt }
                        .thenBy { it.id },
                ).mapIndexed { index, module -> module.copy(position = index) },
                expandedModuleIds = emptySet(),
            )
    }
}
