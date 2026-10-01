package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.PickerModalityFilter
import com.openminis.app.ui.components.QuickTestSheet
import com.openminis.app.ui.components.modelEntryPickerItems
import novex.android.data.model.ModelEntry
import novex.android.data.model.SystemVoiceEntries

/** 往某个模型组里批量加条目：多选 + 顶栏确认。Vision 组只列图像模态模型。 */
@Composable
fun AddModelsToGroupScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val group = config.modelGroups.find { it.id == groupId } ?: run {
        onBack()
        return
    }

    val existingIds = group.memberEntryIds.toSet()
    val modalityFilter = when (groupId) {
        config.visionGroupId -> PickerModalityFilter.IMAGE_INPUT
        else -> null
    }
    val available = config.modelEntries.filter { !it.isHidden && it.id !in existingIds }

    val searchQuery = remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }
    val collapsedInstanceIds = remember(config) {
        // 默认折叠；搜索非空时共享 picker 会自动展开命中项。
        // 但模态过滤场景（如多 voice 的 provider）不折叠，否则只剩一行可见。
        mutableStateOf(
            if (modalityFilter != null) emptySet() else config.instances.map { it.id }.toSet(),
        )
    }

    AgentLoopPickerScaffold(
        titleRes = R.string.model_group_detail_add_models,
        confirmCount = selectedIds.size,
        onBack = onBack,
        onConfirm = {
            providerRepository.updateGroup(
                group.copy(memberEntryIds = (group.memberEntryIds + selectedIds).toMutableList()),
            )
            onBack()
        },
    ) { padding ->
        // LazyListScope 里调不了 stringResource，System 分区标签在 Composable 层先取。
        val systemProviderLabel = stringResource(R.string.voice_provider_system)
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            modelEntryPickerItems(
                instances = config.instances,
                availableEntries = available,
                selectedIds = selectedIds,
                onToggleSelection = { id ->
                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                },
                searchQuery = searchQuery,
                collapsedInstanceIds = collapsedInstanceIds,
                emptyTextRes = R.string.add_models_to_group_all_in_group,
                emptySearchTextRes = R.string.add_models_to_group_no_match,
                searchPlaceholderRes = R.string.add_models_to_group_search_models,
                clearContentDescriptionRes = R.string.add_models_to_group_clear,
                // System 虚拟条目没有云端端点可冒烟测试。
                onQuickTest = { if (!SystemVoiceEntries.isSystemEntryId(it.id)) quickTestEntry = it },
                modalityFilter = modalityFilter,
                excludeIds = existingIds,
                systemProviderLabel = systemProviderLabel,
            )
        }
    }

    quickTestEntry?.let {
        QuickTestSheet(
            entry = it,
            providerRepository = providerRepository,
            onDismiss = { quickTestEntry = null },
        )
    }
}
