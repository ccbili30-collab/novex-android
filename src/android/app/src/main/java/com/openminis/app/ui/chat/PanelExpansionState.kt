package com.openminis.app.ui.chat

import androidx.compose.runtime.mutableStateMapOf

/** Expansion overrides live above LazyColumn rows, so recycling a row cannot reset it. */
internal class PanelExpansionState {
    private val overrides = mutableStateMapOf<String, Boolean>()

    fun value(key: String, defaultExpanded: Boolean): Boolean =
        overrides[key] ?: defaultExpanded

    fun set(key: String, expanded: Boolean) {
        overrides[key] = expanded
    }

    fun clear() = overrides.clear()
}
