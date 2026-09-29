package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import novex.core.NovexExternalCardImport
import novex.android.ui.ContentModuleDraftList
import novex.android.ui.NovexInlineField

/** Presentation only: the enclosing editor keeps its existing atomic save and dirty checks. */
internal val ContentModuleDraftList.isVerbatimCard: Boolean
    get() = modules.any { NovexExternalCardImport.isVerbatimModule(it.contentJson) }

@Composable
internal fun NovexVerbatimCardEditor(
    name: String,
    onNameChange: (String) -> Unit,
    state: ContentModuleDraftList,
    persistedModuleIds: Set<String>,
    onChange: (ContentModuleDraftList) -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    NovexInlineField(label = "名称", value = name, placeholder = "卡片名称", onValueChange = onNameChange)
    SharedContentModuleDraftEditor(state, persistedModuleIds, onChange, onOpenDetails, freeTextOnly = true)
}
