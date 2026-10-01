package com.openminis.app.ui.chat

// 模型选择面板：模型组区 + 按供应商分卡的模型列表 + 搜索/快测。
// 行件在 ModelPickerRows.kt。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import novex.android.ui.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.SystemVoiceEntries
import com.openminis.app.data.repository.ProviderRepository
import novex.android.ui.NovexIcons

/** 模糊匹配：先子串，再按序全字符。对齐 iOS SessionModelPicker.fuzzyMatch。 */
private fun fuzzyMatch(text: String, query: String): Boolean {
    if (query.isEmpty()) return true
    val q = query.lowercase()
    val t = text.lowercase()
    if (t.contains(q)) return true
    var idx = 0
    for (ch in q) {
        val found = t.indexOf(ch, idx)
        if (found < 0) return false
        idx = found + 1
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    defaultPrimaryGroupId: String?,
    config: ProviderConfig,
    providerRepository: ProviderRepository,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onSelectEntry: (String) -> Unit,
    onDismiss: () -> Unit,
    /** "编辑"入口挂在模型组段头——关 sheet 后跳模型组管理屏。无路由时为
     *  null 隐藏按钮。 */
    onEditGroups: (() -> Unit)? = null,
) {
    val openTime = remember { System.nanoTime() }

    LaunchedEffect(Unit) {
        AppLogger.info("ModelPicker", "[ModelPicker] open triggered")
        withFrameNanos { frameTime ->
            val ms = (frameTime - openTime) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] first frame rendered: ${"%.1f".format(ms)}ms (total since trigger)")
        }
    }

    var searchText by remember { mutableStateOf("") }
    var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
    // 注：非文本输出模型"可能不适任 Agent"的确认在 ChatModelSelectionSheet；
    // 本列表只管呈现，选择策略归调用方。
    val allInstanceIds = remember(config) {
        config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    }
    var collapsedInstanceIds by remember(allInstanceIds) {
        mutableStateOf(allInstanceIds)
    }

    // 快测目标条目：复用设置屏同款 QuickTestSheet（对齐 iOS 复用
    // ModelQuickTestSheet）——同一模型在两个入口表现不一致比没有按钮更糟。
    var quickTestEntry by remember { mutableStateOf<ModelEntry?>(null) }

    val filteredGroups = remember(groups, searchText) {
        if (searchText.isEmpty()) groups
        else {
            val t0 = System.nanoTime()
            val result = groups.filter { fuzzyMatch(it.name, searchText) }
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            AppLogger.info("ModelPicker", "[ModelPicker] filter groups: ${result.size}/${groups.size}, ${"%.1f".format(ms)}ms")
            result
        }
    }

    val providersWithEntries = remember(config, searchText) {
        val t0 = System.nanoTime()
        var totalCount = 0
        val result = config.instances
            .filter { it.isEnabled }
            .map { instance ->
                val pt = System.nanoTime()
                val entries = config.modelEntries.filter {
                    it.providerInstanceId == instance.id && !it.isHidden
                }
                val filtered = if (searchText.isEmpty()) entries
                else entries.filter {
                    fuzzyMatch(it.model.displayName, searchText) || fuzzyMatch(it.model.id, searchText)
                }
                val pms = (System.nanoTime() - pt) / 1_000_000.0
                if (filtered.isNotEmpty()) {
                    totalCount += filtered.size
                    AppLogger.info("ModelPicker", "[ModelPicker] provider \"${instance.label.ifEmpty { instance.providerType.displayName }}\" models loaded: ${filtered.size} items, ${"%.1f".format(pms)}ms")
                }
                instance to filtered
            }
            .filter { it.second.isNotEmpty() }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        AppLogger.info("ModelPicker", "[ModelPicker] all providers loaded: total $totalCount items, ${"%.1f".format(ms)}ms")
        result
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 与 StandardChatSheet 同款细拖柄（6dp 顶 / 4dp 底），标题贴着指示
        // 条而不是被 Material 默认 ~44dp 空白顶开。
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            color = ChatColors.secondaryText.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                // [T-android-model-picker-polish] 只要 navigationBarsPadding。
                // 之前还叠了固定 padding(bottom=32.dp)，手势条被算了两次，
                // sheet 下方多出一条深死区、悬得比输入栏还高。
                .navigationBarsPadding(),
        ) {
            PickerTitleBar(onDismiss)
            novex.android.ui.NovexSearchField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = stringResource(R.string.model_picker_search_placeholder),
                onClear = { searchText = "" },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                if (filteredGroups.isNotEmpty()) {
                    item {
                        ModelGroupsCard(
                            groups = filteredGroups,
                            selectedGroupId = selectedGroupId,
                            activeEntryId = activeEntryId,
                            defaultPrimaryGroupId = defaultPrimaryGroupId,
                            config = config,
                            expandedGroupIds = expandedGroupIds,
                            onToggleExpand = { id ->
                                expandedGroupIds = if (id in expandedGroupIds) {
                                    expandedGroupIds - id
                                } else {
                                    expandedGroupIds + id
                                }
                            },
                            onSelectGroup = onSelectGroup,
                            onSelectGroupEntry = onSelectGroupEntry,
                            onEditGroups = onEditGroups,
                            onQuickTest = { quickTestEntry = it },
                        )
                    }
                    if (searchText.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.model_picker_groups_footer),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                // T236：提示夹在组卡和首个供应商卡之间——上
                                // 8 贴组卡，下 12 与下一张卡拉开。
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
                            )
                        }
                    }
                }

                providersWithEntries.forEach { (instance, entries) ->
                    item(key = "section_${instance.id}") {
                        ProviderCard(
                            instance = instance,
                            entries = entries,
                            collapsed = instance.id in collapsedInstanceIds,
                            activeEntryId = activeEntryId,
                            groupSelected = selectedGroupId != null,
                            onToggle = {
                                collapsedInstanceIds = if (instance.id in collapsedInstanceIds) {
                                    collapsedInstanceIds - instance.id
                                } else {
                                    collapsedInstanceIds + instance.id
                                }
                            },
                            onExpand = { collapsedInstanceIds = collapsedInstanceIds - instance.id },
                            onSelectEntry = onSelectEntry,
                            onQuickTest = { quickTestEntry = it },
                        )
                    }
                }

                if (filteredGroups.isEmpty() && providersWithEntries.isEmpty()) {
                    item { PickerEmptyState(hasQuery = searchText.isNotEmpty()) }
                }
            }
        }
    }

    // [T-android-model-picker-polish] 快测宿主。以条目 id 为 key，换模型时
    // 重建 sheet 状态而不是复用上一个模型的测试遗留——iOS 在
    // UnifiedModelPicker.swift:524 记过同一个身份坑。
    quickTestEntry?.let { entry ->
        key(entry.id) {
            com.openminis.app.ui.components.QuickTestSheet(
                entry = entry,
                providerRepository = providerRepository,
                onDismiss = { quickTestEntry = null },
            )
        }
    }
}

// ── 标题栏 ──────────────────────────────────────────────────────────────────

@Composable
private fun PickerTitleBar(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.model_picker_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.model_picker_done))
            }
        }
    }
}
