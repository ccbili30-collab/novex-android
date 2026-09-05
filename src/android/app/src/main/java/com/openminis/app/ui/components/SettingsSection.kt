package com.openminis.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openminis.app.ui.novex.NovexSettingsSection
import com.openminis.app.ui.novex.NovexDivider
import com.openminis.app.ui.novex.NovexDimensions

/** Kept for existing callers; no independent styling remains. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    NovexSettingsSection(title = title, modifier = modifier, content = content)
}

@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    NovexDivider(modifier.padding(horizontal = NovexDimensions.PageHorizontal))
}
