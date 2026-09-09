package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.MinisAlertDialog

/** Owns model selection confirmation; conversation mutation remains with the caller. */
@Composable
internal fun ChatModelSelectionSheet(
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    config: ProviderConfig,
    providerRepository: ProviderRepository,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    onEditGroups: () -> Unit,
) {
    // When the user picks a model whose output is image/audio/video, defer
    // the actual binding behind a confirmation dialog — those models can't
    // drive an Agent loop, so we steer the user toward a text-output model
    // (or, if they really want it, hint at adding it as a tool inside an
    // Agent loop instead).
    var pendingNonTextSelection by remember {
        mutableStateOf<PendingNonTextSelection?>(null)
    }
    val resolveImageLabel = stringResource(R.string.model_picker_modality_image)
    val resolveAudioLabel = stringResource(R.string.model_picker_modality_audio)
    val resolveVideoLabel = stringResource(R.string.model_picker_modality_video)
    fun nonTextLabelFor(model: LLMModel): String? {
        val mods = model.outputModalities?.map { it.lowercase() } ?: emptyList()
        return when {
            "image" in mods -> resolveImageLabel
            "audio" in mods -> resolveAudioLabel
            "video" in mods -> resolveVideoLabel
            else -> null
        }
    }
    fun entryById(entryId: String): ModelEntry? =
        config.modelEntries.firstOrNull { it.id == entryId }

    ModelPickerSheet(
        groups = groups,
        selectedGroupId = selectedGroupId,
        activeEntryId = activeEntryId,
        defaultPrimaryGroupId = config.defaultPrimaryGroupId,
        config = config,
        providerRepository = providerRepository,
        onSelectGroup = { groupId ->
            val group = groups.firstOrNull { it.id == groupId }
            val firstEntry = group?.memberEntryIds?.firstNotNullOfOrNull(::entryById)
            val label = firstEntry?.model?.let(::nonTextLabelFor)
            if (label != null) {
                pendingNonTextSelection = PendingNonTextSelection.Group(
                    groupId = groupId,
                    modelDisplayName = firstEntry.model.displayName,
                    modalityLabel = label,
                )
            } else {
                onSelectGroup(groupId)
                onDismiss()
            }
        },
        onSelectGroupEntry = { groupId, entryId ->
            val entry = entryById(entryId)
            val label = entry?.model?.let(::nonTextLabelFor)
            if (entry != null && label != null) {
                pendingNonTextSelection = PendingNonTextSelection.GroupEntry(
                    groupId = groupId,
                    entryId = entryId,
                    modelDisplayName = entry.model.displayName,
                    modalityLabel = label,
                )
            } else {
                onSelectGroupEntry(groupId, entryId)
                onDismiss()
            }
        },
        onSelectEntry = { entryId ->
            val entry = entryById(entryId)
            val label = entry?.model?.let(::nonTextLabelFor)
            if (entry != null && label != null) {
                pendingNonTextSelection = PendingNonTextSelection.Entry(
                    entryId = entryId,
                    modelDisplayName = entry.model.displayName,
                    modalityLabel = label,
                )
            } else {
                onSelectEntry(entryId)
                onDismiss()
            }
        },
        onDismiss = { onDismiss() },
        // [T-android-modelpicker-group-edit] Close the picker first, then
        // navigate — pushing the management screen on top of an open bottom
        // sheet leaves the sheet lingering behind it on back.
        onEditGroups = {
            onDismiss()
            onEditGroups()
        },
    )

    pendingNonTextSelection?.let { pending ->
        MinisAlertDialog(
            onDismissRequest = { pendingNonTextSelection = null },
            title = stringResource(R.string.model_picker_non_text_warning_title),
            text = stringResource(
                R.string.model_picker_non_text_warning_body,
                pending.modelDisplayName,
                pending.modalityLabel,
                pending.modalityLabel,
            ),
            confirmText = stringResource(R.string.model_picker_non_text_warning_use_anyway),
            dismissText = stringResource(R.string.model_picker_non_text_warning_choose_other),
            onConfirm = {
                when (val sel = pending) {
                    is PendingNonTextSelection.Group -> onSelectGroup(sel.groupId)
                    is PendingNonTextSelection.GroupEntry ->
                        onSelectGroupEntry(sel.groupId, sel.entryId)
                    is PendingNonTextSelection.Entry -> onSelectEntry(sel.entryId)
                }
                pendingNonTextSelection = null
                onDismiss()
            },
        )
    }
}

private sealed class PendingNonTextSelection {
    abstract val modelDisplayName: String
    abstract val modalityLabel: String

    data class Group(
        val groupId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class GroupEntry(
        val groupId: String,
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class Entry(
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()
}
