package com.openminis.app.ui.novex

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

private enum class CardSection(val label: String) {
    CONTENT("内容"), RELATIONS("关系与使用"), MANAGEMENT("管理")
}

/** All native cards keep their existing primary renderer, also used by editor previews. */
@Composable
internal fun NovexCardDetailSections(
    cardId: String,
    content: @Composable () -> Unit,
    relations: @Composable () -> Unit,
    management: @Composable () -> Unit,
) {
    var selected by rememberSaveable(cardId) { mutableStateOf(CardSection.CONTENT) }
    NovexFilterTabs(CardSection.entries, selected, { it.label }, { selected = it })
    when (selected) {
        CardSection.CONTENT -> content()
        CardSection.RELATIONS -> relations()
        CardSection.MANAGEMENT -> management()
    }
}
