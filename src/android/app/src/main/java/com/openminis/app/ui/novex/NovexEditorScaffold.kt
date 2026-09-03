package com.openminis.app.ui.novex

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.navigation.NovexEditorBackAction
import com.openminis.app.ui.navigation.novexEditorBackAction

/** Shared edit-page chrome. Domain forms remain supplied by each page. */
@Composable
internal fun <Draft> NovexEditorScaffold(
    title: String,
    loaded: Boolean,
    canSave: Boolean,
    saving: Boolean,
    baselineDraft: Draft?,
    currentDraft: Draft,
    onBack: () -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    onDeleteRequest: (() -> Unit)? = null,
    saveContainerColor: Color = NovexColors.Primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    var showExitPrompt by rememberSaveable { mutableStateOf(false) }
    fun requestBack() {
        if (saving) return
        when (
            novexEditorBackAction(
                previewVisible = false,
                baselineDraft = baselineDraft,
                currentDraft = currentDraft,
            )
        ) {
            NovexEditorBackAction.PROMPT_SAVE -> showExitPrompt = true
            NovexEditorBackAction.LEAVE_EDITOR -> onBack()
            NovexEditorBackAction.CLOSE_PREVIEW -> Unit
        }
    }
    BackHandler(onBack = ::requestBack)

    NovexDetailScaffold(
        title = title,
        onBack = ::requestBack,
        actions = {
            onDeleteRequest?.let { delete ->
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_trash,
                    contentDescription = "删除",
                    onClick = delete,
                )
            }
            NovexTopAction(
                icon = R.drawable.ic_phosphor_eye,
                contentDescription = "预览草稿",
                onClick = { if (loaded && canSave) onPreview() },
            )
        },
        bottomBar = {
            NovexPrimaryButton(
                label = if (saving) "保存中" else "保存",
                onClick = onSave,
                enabled = loaded && canSave && !saving,
                containerColor = saveContainerColor,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        },
    ) {
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NovexColors.Primary, strokeWidth = 2.dp)
            }
        } else {
            content()
        }
    }

    if (showExitPrompt) {
        NovexUnsavedChangesDialog(
            saving = saving,
            onSaveAndExit = onSave,
            onDiscard = {
                showExitPrompt = false
                onBack()
            },
            onContinueEditing = { showExitPrompt = false },
        )
    }
}

/** Draft previews and saved pages share their domain renderer; only the chrome differs. */
@Composable
internal fun NovexDraftPreviewScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    NovexDetailScaffold(
        title = title,
        onBack = onBack,
        actions = {
            NovexTopAction(
                icon = R.drawable.ic_phosphor_eye,
                contentDescription = "返回编辑",
                onClick = onBack,
            )
        },
        content = content,
    )
}

@Composable
internal fun NovexEditorSection(
    header: String,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            header,
            color = NovexColors.Text,
            style = NovexType.SectionTitle,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
        )
        Column(content = content)
        footer?.let {
            Text(
                it,
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun NovexEditorFoldRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), color = NovexColors.Text)
        Text(if (expanded) "收起" else "展开", color = NovexColors.SecondaryText)
    }
}
