package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import novex.android.ui.AlertDialog
import androidx.compose.material3.ButtonDefaults
import novex.android.ui.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import novex.android.ui.ListItem
import androidx.compose.material3.MaterialTheme
import novex.android.ui.Scaffold
import novex.android.ui.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import novex.android.ui.SingleChoiceSegmentedButtonRow
import novex.android.ui.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import novex.android.data.model.FallbackStrategy
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.ThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionTextField
import novex.android.ui.NovexIcons
import kotlin.math.roundToInt

/**
 * 单个 ModelGroup 的详情/编辑屏：名称、路由策略（含可选 Fallback
 * Trigger 子段）、可排序成员列表、会话默认值、删除组。
 *
 * 成员需要 LazyColumn 拖排，所以用裸 Scaffold——SettingsScaffold 的
 * verticalScroll 会和懒列表的无限高度测量打架。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelGroupDetailScreen(
    groupId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onAddModels: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val group = config.modelGroups.find { it.id == groupId }
        ?: run {
            onBack()
            return
        }

    var name by remember { mutableStateOf(group.name) }
    var strategy by remember { mutableStateOf(group.strategy) }
    var fallbackStrategy by remember { mutableStateOf(group.fallbackStrategy) }
    // T312: 会话默认值以 group.id 为 remember 键——用 `group` 会在每次
    // config 流刷新时重置（group 每次重组都重新计算），id 键才稳定。
    var defaultThinkingLevel by remember(group.id) {
        mutableStateOf(group.defaultThinkingLevel)
    }
    var contextLimitTokens by remember(group.id) {
        mutableStateOf(group.contextLimitTokens)
    }

    // 成员 ID 的本地副本承载实时拖排。
    var memberIds by remember(group.memberEntryIds) {
        mutableStateOf(group.memberEntryIds.toList())
    }
    var entryToRemove by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun save(updated: ModelGroup) = providerRepository.updateGroup(updated)

    fun removeMember(entryId: String) {
        memberIds = memberIds - entryId
        save(group.copy(memberEntryIds = memberIds.toMutableList()))
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // from/to.index 是 LazyColumn 全局下标（含 header 项）——改用
        // item key（entryId 字符串）回查 memberIds 里的真实位置。
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val fromIdx = memberIds.indexOf(fromKey)
        val toIdx = memberIds.indexOf(toKey)
        if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
        memberIds = memberIds.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
        save(group.copy(memberEntryIds = memberIds.toMutableList()))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(group.name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            NovexIcons.ArrowBack,
                            contentDescription = stringResource(R.string.model_group_detail_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                NameSection(
                    name = name,
                    onNameChange = { name = it },
                    onCommit = {
                        if (name.isNotBlank() && name != group.name) {
                            save(group.copy(name = name))
                            coroutineScope.launch { snackbarHostState.showSnackbar("Name saved") }
                        }
                    },
                    onDone = { focusManager.clearFocus() },
                )
            }

            item {
                RoutingSection(
                    strategy = strategy,
                    onPick = { picked ->
                        strategy = picked
                        save(group.copy(strategy = picked))
                    },
                )
            }

            if (strategy == RoutingStrategy.fallback) {
                item {
                    FallbackTriggerSection(
                        fallbackStrategy = fallbackStrategy,
                        onPick = { picked ->
                            fallbackStrategy = picked
                            save(group.copy(fallbackStrategy = picked))
                        },
                    )
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(
                        R.string.model_group_detail_models_header_count, memberIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                )
            }

            if (memberIds.isEmpty()) {
                item {
                    Text(
                        "No models in this group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                itemsIndexed(memberIds, key = { _, id -> id }) { _, entryId ->
                    ReorderableItem(reorderState, key = entryId) { _ ->
                        MemberRow(
                            entryId = entryId,
                            config = config,
                            dragModifier = Modifier.draggableHandle(),
                            onRemove = { entryToRemove = entryId },
                            onRemoveStale = { removeMember(entryId) },
                        )
                    }
                }
            }

            // 「添加模型」紧跟它操作的成员列表——原来放在最底部 Session
            // Defaults 之下、删除键之上，增加性动作离目标两个段还挨着破坏性
            // 动作，容易误点也难找。
            item {
                Spacer(Modifier.height(12.dp))
                MinisOutlinedButton(
                    onClick = onAddModels,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.model_group_detail_add_models))
                }
            }

            item {
                SessionDefaultsSection(
                    group = group,
                    config = config,
                    thinkingLevel = defaultThinkingLevel,
                    contextLimitTokens = contextLimitTokens,
                    onThinkingChange = {
                        defaultThinkingLevel = it
                        save(group.copy(defaultThinkingLevel = it))
                    },
                    onContextChange = { newTokens, last ->
                        contextLimitTokens = newTokens
                        save(group.copy(
                            contextLimitTokens = newTokens,
                            lastContextLimitTokens = last,
                        ))
                    },
                )
            }

            // 底部只剩破坏性动作——「添加模型」上移到成员列表下面之后，
            // 这里单独放删除键，旁边没有可误点的邻居。
            item {
                Spacer(Modifier.height(20.dp))
                MinisButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.model_group_detail_delete_group))
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    entryToRemove?.let { removingId ->
        val displayName = config.modelEntries.find { it.id == removingId }
            ?.model?.displayName
            ?: stringResource(R.string.model_group_detail_remove_this_model_fallback)
        AlertDialog(
            onDismissRequest = { entryToRemove = null },
            title = { Text(stringResource(R.string.model_group_detail_remove_model)) },
            text = {
                Text(stringResource(R.string.model_group_detail_remove_named_model_confirm, displayName))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    removeMember(removingId)
                    entryToRemove = null
                }) {
                    Text(stringResource(R.string.common_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { entryToRemove = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.model_group_detail_delete_group)) },
            text = {
                Text(stringResource(R.string.model_group_detail_delete_named_group_confirm, group.name))
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    providerRepository.removeGroup(groupId)
                    showDeleteDialog = false
                    onBack()
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

// ── 名称段 ──────────────────────────────────────────────────────────────────

@Composable
private fun NameSection(
    name: String,
    onNameChange: (String) -> Unit,
    onCommit: () -> Unit,
    onDone: () -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.model_group_detail_name),
        footer = stringResource(R.string.model_group_detail_the_label_shown_in_the_model_picker_save),
    ) {
        SettingsCardBlock {
            SectionTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                fieldModifier = Modifier.onFocusChanged { f ->
                    if (!f.isFocused) onCommit()
                },
            )
        }
    }
}

// ── 路由策略段 ───────────────────────────────────────────────────────────────

@Composable
private fun RoutingSection(
    strategy: RoutingStrategy,
    onPick: (RoutingStrategy) -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.model_group_detail_routing_strategy),
        footer = when (strategy) {
            RoutingStrategy.fallback -> "Try models in order. If one fails, advance to the next."
            RoutingStrategy.loadBalance -> "Distribute sessions across models in the group."
        },
    ) {
        SettingsChoiceRow(
            title = stringResource(R.string.model_group_detail_fallback),
            selected = strategy == RoutingStrategy.fallback,
            onSelect = { onPick(RoutingStrategy.fallback) },
        )
        SettingsChoiceRow(
            title = stringResource(R.string.model_group_detail_load_balance),
            selected = strategy == RoutingStrategy.loadBalance,
            onSelect = { onPick(RoutingStrategy.loadBalance) },
            showDivider = false,
        )
    }
}

// ── Fallback Trigger 子段（仅 fallback 策略时出现）─────────────────────────

@Composable
private fun FallbackTriggerSection(
    fallbackStrategy: FallbackStrategy,
    onPick: (FallbackStrategy) -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.model_group_detail_fallback_trigger),
        footer = when (fallbackStrategy) {
            FallbackStrategy.default -> "Fall back on rate limits (429) and server errors (5xx) only."
            FallbackStrategy.always -> "Fall back on any error, including network and auth failures."
        },
    ) {
        SettingsChoiceRow(
            title = stringResource(R.string.common_default),
            selected = fallbackStrategy == FallbackStrategy.default,
            onSelect = { onPick(FallbackStrategy.default) },
        )
        SettingsChoiceRow(
            title = stringResource(R.string.model_group_detail_always),
            selected = fallbackStrategy == FallbackStrategy.always,
            onSelect = { onPick(FallbackStrategy.always) },
            showDivider = false,
        )
    }
}

// ── 成员行 ──────────────────────────────────────────────────────────────────

@Composable
private fun MemberRow(
    entryId: String,
    config: ProviderConfig,
    dragModifier: Modifier,
    onRemove: () -> Unit,
    onRemoveStale: () -> Unit,
) {
    // [T-android-provider-voice] 系统语音哨兵不落库——解析成虚拟条目，
    // 让默认的语音输入/输出成员正常渲染而不是变成陈旧行。
    val entry = config.modelEntries.find { it.id == entryId }
        ?: novex.android.data.model.SystemVoiceEntries.resolve(entryId)

    if (entry == null) {
        // 陈旧成员：UUID 解析不到任何 ModelEntry。
        ListItem(
            headlineContent = {
                Text(
                    "Model no longer available",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            supportingContent = {
                Text(
                    entryId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(
                    imageVector = NovexIcons.Warning,
                    contentDescription = stringResource(R.string.model_group_detail_unavailable),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            trailingContent = {
                IconButton(onClick = onRemoveStale) {
                    Icon(
                        imageVector = NovexIcons.Delete,
                        contentDescription = stringResource(R.string.common_remove),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
        return
    }

    val instance = config.instances.find { it.id == entry.providerInstanceId }
    val providerDisabled = instance?.isEnabled == false
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.alpha(if (providerDisabled) 0.4f else 1f),
        headlineContent = { Text(entry.model.displayName) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(instance?.label ?: "")
                if (providerDisabled) {
                    Text(
                        " · Provider disabled",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        leadingContent = {
            IconButton(modifier = dragModifier, onClick = {}) {
                Icon(
                    NovexIcons.DragHandle,
                    contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        NovexIcons.MoreVert,
                        contentDescription = stringResource(R.string.model_group_detail_more_options),
                    )
                }
                com.openminis.app.ui.components.MinisMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.model_group_detail_remove_from_group),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        },
    )
}

// ── 会话默认值段（T312，对齐 iOS ModelGroupDetailView）──────────────────────

@Composable
private fun SessionDefaultsSection(
    group: ModelGroup,
    config: ProviderConfig,
    thinkingLevel: ThinkingLevel?,
    contextLimitTokens: Int?,
    onThinkingChange: (ThinkingLevel?) -> Unit,
    onContextChange: (newTokens: Int?, lastTokens: Int?) -> Unit,
) {
    SettingsSection(
        header = stringResource(R.string.model_group_detail_session_defaults),
        footer = stringResource(R.string.model_group_detail_session_defaults_footer),
    ) {
        ReasoningRows(
            group = group,
            config = config,
            level = thinkingLevel,
            onChange = onThinkingChange,
        )
        ContextRows(
            group = group,
            value = contextLimitTokens,
            onChange = onContextChange,
        )
    }
}

@Composable
private fun ReasoningRows(
    group: ModelGroup,
    config: ProviderConfig,
    level: ThinkingLevel?,
    onChange: (ThinkingLevel?) -> Unit,
) {
    val enabled = level != null
    SettingsSwitchRow(
        title = stringResource(R.string.model_group_detail_enable_reasoning),
        checked = enabled,
        onCheckedChange = { on ->
            // iOS parity：开默认 MEDIUM，关清 null。
            onChange(if (on) ThinkingLevel.MEDIUM else null)
        },
        icon = NovexIcons.Psychology,
        iconColor = Color(0xFFAF52DE),
        showDivider = enabled,
    )
    if (!enabled) return

    // [T-android-thinking-level-arch] 组的思维上限是任一推理成员支持的
    // 最高级——max() 不是 min()（对齐 iOS e51fef5d/53388728）。min() 错
    // 两次：非推理成员会把天花板拖到 OFF 清空选择器；即便全是推理成员
    // 也会把组压到最弱模型。运行时按实际模型再钳制，这里给真天花板是
    // 安全的；排除 OFF，无推理成员时回落 XHIGH。
    val groupCeiling = remember(group.memberEntryIds, config.modelEntries) {
        group.memberEntryIds
            .mapNotNull { id -> config.modelEntries.find { it.id == id }?.effectiveMaxThinkingLevel }
            .filter { it != ThinkingLevel.OFF }
            .maxByOrNull { it.rank }
            ?: ThinkingLevel.XHIGH
    }
    // 档位排除 OFF（开关承载 OFF 语义）和超出组上限的档。
    val cases = ThinkingLevel.entries
        .filter { it != ThinkingLevel.OFF && it.rank <= groupCeiling.rank }

    SettingsCardBlock {
        Text(
            text = stringResource(R.string.model_group_detail_intensity),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            cases.forEachIndexed { idx, lv ->
                SegmentedButton(
                    selected = level == lv,
                    onClick = { onChange(lv) },
                    shape = SegmentedButtonDefaults.itemShape(index = idx, count = cases.size),
                ) { Text(thinkingLevelLabel(lv)) }
            }
        }
    }
}

@Composable
private fun thinkingLevelLabel(level: ThinkingLevel): String = stringResource(
    when (level) {
        ThinkingLevel.LOW -> R.string.model_group_detail_thinking_low
        ThinkingLevel.MEDIUM -> R.string.model_group_detail_thinking_medium
        ThinkingLevel.HIGH -> R.string.model_group_detail_thinking_high
        ThinkingLevel.XHIGH -> R.string.model_group_detail_thinking_xhigh
        ThinkingLevel.MAX -> R.string.model_group_detail_thinking_max
        ThinkingLevel.ULTRA -> R.string.model_group_detail_thinking_ultra
        ThinkingLevel.OFF -> R.string.model_group_detail_thinking_low
    }
)

@Composable
private fun ContextRows(
    group: ModelGroup,
    value: Int?,
    onChange: (newTokens: Int?, lastTokens: Int?) -> Unit,
) {
    val enabled = value != null
    // T-android-ctx-slider-cap（移植 iOS fa77f493）：滑杆是固定 7 档阶梯，
    // 与成员模型元数据解耦——contextWindow 字段不可靠（图像输出/路由模型
    // 不申报窗口，掉到 128K 启发默认）曾把选择器悄悄缩没。运行时消费方
    // 仍按 min(groupLimit, modelLimit) 逐请求钳制。
    SettingsSwitchRow(
        title = stringResource(R.string.model_group_detail_limit_context_window),
        checked = enabled,
        onCheckedChange = { on ->
            if (on) {
                // 逐字恢复上次用户选值，首次开启默认 128K。
                val restored = group.lastContextLimitTokens ?: 128_000
                onChange(restored, restored)
            } else {
                // 置空前把当前值存进 lastContextLimitTokens，重新开启可还原
                // （离开页面再回来也一样）。
                onChange(null, value ?: group.lastContextLimitTokens)
            }
        },
        icon = NovexIcons.Memory,
        iconColor = Color(0xFF5856D6),
        showDivider = enabled,
    )
    if (enabled) {
        ContextLimitSlider(
            value = value,
            unlimitedLabel = stringResource(R.string.model_group_detail_unlimited),
            maxContextLabel = stringResource(R.string.model_group_detail_max_context),
            onValueChange = { onChange(it, it) },
        )
    }
}

// ── 上下文上限滑杆 ───────────────────────────────────────────────────────────

/**
 * 离散档位滑杆，对齐 iOS ContextLimitSlider（ModelGroupDetailView.swift,
 * fa77f493）。档位：32K / 64K / 128K / 200K / 400K / 1M / Unlimited。
 * 「Unlimited」档映射 Int.MAX_VALUE，运行侧把 >= MAX_VALUE 视为「不覆
 * 盖，用模型原生窗口」。
 */
private const val CONTEXT_UNLIMITED_SENTINEL: Int = Int.MAX_VALUE

private val CONTEXT_STEPS_TOKENS = listOf(
    32_000, 64_000, 128_000, 200_000, 400_000, 1_000_000,
)

private class ContextStep(val tokens: Int, val label: String)

@Composable
private fun ContextLimitSlider(
    value: Int?,
    unlimitedLabel: String,
    maxContextLabel: String,
    onValueChange: (Int) -> Unit,
) {
    val steps = remember(unlimitedLabel) {
        CONTEXT_STEPS_TOKENS.map { ContextStep(it, contextPresetLabel(it)) } +
            ContextStep(CONTEXT_UNLIMITED_SENTINEL, unlimitedLabel)
    }
    // 按下标追踪选中档：<= value 的最近档，让历史保存值（如 350K）落在
    // 下一档（200K）。
    var selectedIndex by remember(steps, value) {
        mutableIntStateOf(
            when {
                value == null || value >= CONTEXT_UNLIMITED_SENTINEL -> steps.lastIndex
                else -> steps.indexOfLast { it.tokens <= value }.coerceAtLeast(0)
            }
        )
    }
    val currentLabel = when {
        value == null || value >= CONTEXT_UNLIMITED_SENTINEL -> unlimitedLabel
        else -> steps.firstOrNull { it.tokens == value }?.label ?: "${value / 1000}K"
    }
    SettingsCardBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = maxContextLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = selectedIndex.toFloat(),
            onValueChange = { newIndex ->
                val idx = newIndex.roundToInt().coerceIn(0, steps.lastIndex)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    onValueChange(steps[idx].tokens)
                }
            },
            valueRange = 0f..steps.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (steps.size - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Text(
                steps.first().label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                unlimitedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun contextPresetLabel(tokens: Int): String =
    if (tokens >= 1_000_000) "${tokens / 1_000_000}M" else "${tokens / 1_000}K"
