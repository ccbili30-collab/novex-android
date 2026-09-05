package com.openminis.app.ui.components

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import com.openminis.app.ui.novex.*

/** All settings dropdowns share the searchable, selected-state Novex picker. */
@Composable
fun <T> SectionDropdown(
    selected: T,
    items: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    itemLabel: (T) -> String = { it.toString() },
    textStyle: TextStyle = NovexType.Body,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        NovexSettingsCustomRow(
            title = itemLabel(selected),
            onClick = if (enabled) ({ expanded = true }) else null,
            showDivider = false,
        )
    }
    if (expanded) NovexSearchableSelectionSheet(
        title = "选择",
        searchPlaceholder = "搜索选项",
        actions = items.map { item ->
            NovexSelectionAction(label = itemLabel(item), selected = item == selected) { onSelect(item) }
        },
        onDismissRequest = { expanded = false },
    )
}
