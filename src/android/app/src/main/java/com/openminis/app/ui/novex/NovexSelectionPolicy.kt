package com.openminis.app.ui.novex

internal data class NovexSelectionEntry(
    val id: String,
    val label: String,
    val description: String = "",
    val group: String = "",
    val enabled: Boolean = true,
)

internal fun filterNovexSelections(entries: List<NovexSelectionEntry>, query: String): List<NovexSelectionEntry> {
    val needle = query.trim()
    return if (needle.isEmpty()) entries else entries.filter {
        it.label.contains(needle, true) || it.description.contains(needle, true) || it.group.contains(needle, true)
    }
}

internal fun toggleNovexSelection(selected: Set<String>, entry: NovexSelectionEntry): Set<String> = when {
    !entry.enabled -> selected
    entry.id in selected -> selected - entry.id
    else -> selected + entry.id
}
