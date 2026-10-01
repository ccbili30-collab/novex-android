package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.tools.isImageGenerationEntry
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.modelEntryPickerItems
import novex.android.ui.NovexColors
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.Scaffold
import novex.android.ui.TopAppBar

/**
 * Agent 循环可用集的两个添加页，共用一张多选骨架：
 * 顶栏 = 返回 + 取消 + 「添加 N」，主体留给各页自己填。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentLoopPickerScaffold(
    titleRes: Int,
    confirmCount: Int,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    body: @Composable (padding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, stringResource(R.string.model_group_detail_back))
                    }
                },
                actions = {
                    MinisTextButton(onClick = onBack) { Text(stringResource(R.string.common_cancel)) }
                    MinisButton(
                        onClick = onConfirm,
                        enabled = confirmCount > 0,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(stringResource(R.string.add_models_to_group_add_count, confirmCount))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NovexColors.Background),
            )
        },
        containerColor = NovexColors.Background,
    ) { padding -> body(padding) }
}

/** 往可用集加模型：多选 + 顶栏确认（不再点一个弹一次）。 */
@Composable
fun AddAgentLoopModelsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()

    val pinned = config.agentLoopModelEntryIds.toSet()
    // 已被置顶组覆盖的条目不再直钉——对 resolved 集是空操作，只会弄脏界面。
    val groupBacked: Set<String> = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .flatMap { it.memberEntryIds }
        .toSet()
    val available = config.modelEntries.filter {
        !it.isHidden && !isImageGenerationEntry(it) && it.id !in pinned && it.id !in groupBacked
    }

    val searchQuery = remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val collapsedInstanceIds = remember(config) {
        mutableStateOf(config.instances.map { it.id }.toSet())
    }

    AgentLoopPickerScaffold(
        titleRes = R.string.agent_loop_section_add_models_title,
        confirmCount = selectedIds.size,
        onBack = onBack,
        onConfirm = {
            // 按列表顺序加，置顶区呈现的顺序就是用户在 picker 里看到的顺序。
            available.map { it.id }.filter { it in selectedIds }
                .forEach(providerRepository::addAgentLoopEntry)
            onBack()
        },
    ) { padding ->
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
                emptyTextRes = R.string.agent_loop_section_no_available_models,
                emptySearchTextRes = R.string.add_models_to_group_no_match,
                searchPlaceholderRes = R.string.add_models_to_group_search_models,
                clearContentDescriptionRes = R.string.add_models_to_group_clear,
            )
        }
    }
}

/** 往可用集加模型组：平铺卡片式多选，没有按 Provider 分组的维度。 */
@Composable
fun AddAgentLoopGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val pinned = config.agentLoopGroupIds.toSet()
    val available = remember(config, pinned) {
        config.modelGroups.filter { it.id !in pinned && it.id !in config.imageGenerationGroupIds }
    }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    AgentLoopPickerScaffold(
        titleRes = R.string.agent_loop_section_add_groups_title,
        confirmCount = selectedIds.size,
        onBack = onBack,
        onConfirm = {
            available.map { it.id }.filter { it in selectedIds }
                .forEach(providerRepository::addAgentLoopGroup)
            onBack()
        },
    ) { padding ->
        if (available.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    stringResource(R.string.agent_loop_section_no_available_groups),
                    style = NovexType.Body,
                    color = NovexColors.SecondaryText,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Column(
                        Modifier
                            .padding(16.dp)
                            .background(NovexColors.Surface, RoundedCornerShape(12.dp)),
                    ) {
                        available.forEachIndexed { i, group ->
                            AgentLoopGroupRow(
                                name = group.name,
                                preview = groupPreview(config, group.memberEntryIds),
                                selected = group.id in selectedIds,
                                position = RowPosition.of(i, available.size),
                                onToggle = {
                                    selectedIds =
                                        if (group.id in selectedIds) selectedIds - group.id
                                        else selectedIds + group.id
                                },
                            )
                            if (i < available.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 46.dp, end = 16.dp),
                                    color = NovexColors.Divider,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 组成员名预览：前 3 个名字，多出记 "+N"；空组给占位文案。 */
@Composable
private fun groupPreview(
    config: novex.android.data.model.ProviderConfig,
    memberIds: List<String>,
): String {
    val names = memberIds.mapNotNull { id ->
        config.modelEntries.find { it.id == id }?.model?.displayName
    }
    return when {
        names.isEmpty() -> stringResource(R.string.agent_loop_section_empty_group_subtitle)
        names.size <= 3 -> names.joinToString(", ")
        else -> names.take(3).joinToString(", ") + " +${names.size - 3}"
    }
}

private enum class RowPosition {
    Only, First, Middle, Last;

    companion object {
        fun of(index: Int, count: Int) = when {
            count == 1 -> Only
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

@Composable
private fun AgentLoopGroupRow(
    name: String,
    preview: String,
    selected: Boolean,
    position: RowPosition,
    onToggle: () -> Unit,
) {
    val shape = when (position) {
        RowPosition.Only -> RoundedCornerShape(12.dp)
        RowPosition.First -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        RowPosition.Last -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        RowPosition.Middle -> RoundedCornerShape(0.dp)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) NovexIcons.CheckCircle else NovexIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) NovexColors.Primary else NovexColors.TertiaryText,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = NovexType.ItemTitle, fontWeight = FontWeight.Medium)
            Text(preview, style = NovexType.Metadata, color = NovexColors.TertiaryText)
        }
    }
}
