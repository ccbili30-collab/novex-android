package com.openminis.app.ui.novex

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

internal data class NovexSelectionAction(
    val label: String,
    @DrawableRes val icon: Int = R.drawable.ic_phosphor_plus,
    val description: String = "",
    val group: String = "",
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val disabledReason: String = "",
    val onClick: () -> Unit,
)

@Composable
internal fun NovexSelectionSheet(
    title: String,
    actions: List<NovexSelectionAction>,
    onDismissRequest: () -> Unit,
) = NovexSelectionSurface(title, actions, null, true, onDismissRequest)

@Composable
internal fun NovexSearchableSelectionSheet(
    title: String,
    actions: List<NovexSelectionAction>,
    searchPlaceholder: String,
    onDismissRequest: () -> Unit,
    dismissOnSelection: Boolean = true,
) = NovexSelectionSurface(title, actions, searchPlaceholder, dismissOnSelection, onDismissRequest)

/** Short actions, grouped choices and searchable multi-select share one bounded lazy list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovexSelectionSurface(
    title: String,
    actions: List<NovexSelectionAction>,
    searchPlaceholder: String?,
    dismissOnSelection: Boolean,
    onDismissRequest: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val entries = remember(actions) { actions.mapIndexed { index, action ->
        NovexSelectionEntry(index.toString(), action.label, action.description, action.group, action.enabled)
    } }
    val visible = remember(entries, query) { filterNovexSelections(entries, query) }
    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(Modifier.fillMaxWidth().imePadding().padding(bottom = 12.dp)) {
            Text(title, style = NovexType.SectionTitle, color = NovexColors.Text,
                modifier = Modifier.padding(horizontal = NovexDimensions.PageHorizontal, vertical = 12.dp))
            if (searchPlaceholder != null) NovexSearchField(query, { query = it }, searchPlaceholder)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (visible.isEmpty()) item {
                    Text("没有匹配的选项", style = NovexType.Body, color = NovexColors.SecondaryText, modifier = Modifier.padding(24.dp))
                }
                itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                    val action = actions[entry.id.toInt()]
                    if (entry.group.isNotEmpty() && (index == 0 || visible[index - 1].group != entry.group)) {
                        Text(entry.group, style = NovexType.Metadata, color = NovexColors.SecondaryText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = NovexDimensions.MinimumTouch)
                            .semantics { action.selected?.let { selected = it } }
                            .clickable(enabled = action.enabled) {
                                if (dismissOnSelection) onDismissRequest()
                                action.onClick()
                            }.alpha(if (action.enabled) 1f else .5f)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(action.icon), null, Modifier.size(22.dp), tint = NovexColors.Primary)
                        Column(Modifier.weight(1f)) {
                            Text(action.label, style = NovexType.Body, color = NovexColors.Text)
                            val detail = if (!action.enabled && action.disabledReason.isNotBlank()) action.disabledReason else action.description
                            if (detail.isNotBlank()) Text(detail, style = NovexType.Metadata, color = NovexColors.SecondaryText)
                        }
                        action.selected?.let { NovexCheckIndicator(it, action.enabled) }
                    }
                    if (index < visible.lastIndex) NovexDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            if (!dismissOnSelection) TextButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
