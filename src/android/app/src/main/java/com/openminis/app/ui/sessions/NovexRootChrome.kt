package com.openminis.app.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.openminis.app.R
import com.openminis.app.ui.novex.NovexIconAction
import com.openminis.app.ui.novex.NovexRootHeader
import com.openminis.app.ui.settings.NovexUpdateAction

internal data class NovexCreateMenuItem(
    val label: String,
    val onClick: () -> Unit,
)

/** One root toolbar shared by conversation, world and character directories. */
@Composable
internal fun NovexRootPageHeader(
    space: NovexRootSpace,
    searching: Boolean,
    searchDescription: String,
    onSettings: () -> Unit,
    onSearchToggle: () -> Unit,
    createItems: List<NovexCreateMenuItem>,
) {
    var createMenuExpanded by remember { mutableStateOf(false) }
    val chrome = novexRootChrome(space)
    NovexRootHeader(
        title = chrome.title,
        leading = {
            NovexIconAction(
                icon = R.drawable.ic_phosphor_gear,
                contentDescription = "设置",
                onClick = onSettings,
            )
        },
        actions = {
            NovexUpdateAction()
            NovexIconAction(
                icon = R.drawable.ic_phosphor_search,
                contentDescription = if (searching) "关闭$searchDescription" else searchDescription,
                onClick = onSearchToggle,
            )
            Box {
                NovexIconAction(
                    icon = R.drawable.ic_phosphor_plus,
                    contentDescription = "新建",
                    onClick = { createMenuExpanded = true },
                )
                DropdownMenu(
                    expanded = createMenuExpanded,
                    onDismissRequest = { createMenuExpanded = false },
                ) {
                    createItems.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            onClick = {
                                createMenuExpanded = false
                                item.onClick()
                            },
                        )
                    }
                }
            }
        },
    )
}
