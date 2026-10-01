package com.openminis.app.ui.chat

// ModelPickerSheet 的行件：模型组卡、供应商卡、选中点、胶囊徽、披露钮、
// 快测钮、供应商色点。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.SystemVoiceEntries
import novex.android.ui.NovexIcons

// ── 模型组区 ────────────────────────────────────────────────────────────────

/** 段头+行同卡：视觉单元不被拆散。 */
@Composable
internal fun ModelGroupsCard(
    groups: List<ModelGroup>,
    selectedGroupId: String?,
    activeEntryId: String?,
    defaultPrimaryGroupId: String?,
    config: ProviderConfig,
    expandedGroupIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    onSelectGroup: (String) -> Unit,
    onSelectGroupEntry: (String, String) -> Unit,
    onEditGroups: (() -> Unit)?,
    onQuickTest: (ModelEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.model_picker_groups_section),
                // [T-android-model-picker-polish] 段头权重必须压过行文字：
                // titleSmall=14sp Medium 与组名/模型名 bodyMedium SemiBold
                // 同号更轻，层级会倒挂。titleMedium 16sp + SemiBold 高一阶。
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
            )
            if (onEditGroups != null) {
                MinisTextButton(
                    onClick = onEditGroups,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text(stringResource(R.string.model_picker_groups_edit))
                }
            }
        }
        groups.forEachIndexed { index, group ->
            GroupEntry(
                group = group,
                isSelected = group.id == selectedGroupId,
                isDefault = group.id == defaultPrimaryGroupId,
                isExpanded = group.id in expandedGroupIds,
                isLast = index == groups.size - 1,
                config = config,
                activeEntryId = activeEntryId,
                onSelect = { onSelectGroup(group.id) },
                onToggleExpand = { onToggleExpand(group.id) },
                onSelectMember = { entryId -> onSelectGroupEntry(group.id, entryId) },
                onQuickTest = onQuickTest,
            )
            // 组间细分隔线，节奏同 MinisMenuDivider。
            if (index < groups.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 48.dp, end = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
            }
        }
    }
}

@Composable
internal fun GroupEntry(
    group: ModelGroup,
    isSelected: Boolean,
    isDefault: Boolean,
    isExpanded: Boolean,
    isLast: Boolean,
    config: ProviderConfig,
    activeEntryId: String?,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onSelectMember: (String) -> Unit,
    onQuickTest: (ModelEntry) -> Unit,
) {
    val strategyLabel = when (group.strategy) {
        RoutingStrategy.fallback -> "FB"
        RoutingStrategy.loadBalance -> "LB"
    }
    // 成员解析：memberEntryIds 优先，只有本组被选中才回退 activeEntryId。
    // SystemVoiceEntries 回退：语音组成员是按需合成、不进
    // config.modelEntries 的 "__builtin_system_speech__/…"，只查
    // modelEntries 会把它们数成 0。
    val resolvedEntry = group.memberEntryIds.firstNotNullOfOrNull { entryId ->
        config.modelEntries.find { it.id == entryId }
            ?: SystemVoiceEntries.resolve(entryId)
    } ?: if (isSelected && activeEntryId != null) {
        config.modelEntries.find { it.id == activeEntryId }
    } else null
    val resolvedCount = group.memberEntryIds.count { entryId ->
        config.modelEntries.any { it.id == entryId } ||
            SystemVoiceEntries.resolve(entryId) != null
    }

    // 段头压在首行上方，所以首行不再带圆角；只有末组的末行收底圆角。
    val rowShape = if (isLast) {
        RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(selected = isSelected, selectedTint = Color(0xFF34C759), size = 22.dp)
        Spacer(Modifier.width(10.dp))
        // [T-android-model-picker-polish] 组图形：iOS 蓝色 layers 标。没有它
        // 组行和供应商行只差一行副标题，滚动中容易看错——两者语义差很远
        // （组能故障转移/负载均衡，模型不能）。
        Icon(
            NovexIcons.Layers,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    group.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                // iOS ⊕ FB / ⊕ LB 策略徽
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Icon(
                        if (group.strategy == RoutingStrategy.fallback)
                            NovexIcons.ArrowCircleDown else NovexIcons.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(9.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        strategyLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            // iOS: "→ ModelName" 已解析条目行
            when {
                resolvedEntry != null -> Text(
                    "→ ${resolvedEntry.model.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
                resolvedCount > 0 -> Text(
                    pluralStringResource(R.plurals.model_picker_models_count, resolvedCount, resolvedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                else -> Text(
                    stringResource(R.string.model_picker_models_count_unlinked, group.memberEntryIds.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        // [T-android-model-picker-polish] "默认"胶囊对齐 iOS
        // （UnifiedModelPicker.swift:669-674）：9sp/11 行高 + 5/1 内边距，
        // 之前的 6/2 把胶囊撑得比组名还高抢视线。
        if (isDefault) {
            MiniCapsule(
                text = stringResource(R.string.model_picker_default_badge),
                fg = MaterialTheme.colorScheme.onSurfaceVariant,
                bg = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
            )
            Spacer(Modifier.width(8.dp))
        }

        DisclosureDot(expanded = isExpanded, onToggle = onToggleExpand)
    }

    if (isExpanded) {
        GroupMembers(
            group = group,
            isSelected = isSelected,
            config = config,
            activeEntryId = activeEntryId,
            onSelectMember = onSelectMember,
            onQuickTest = onQuickTest,
        )
    }
}

@Composable
internal fun GroupMembers(
    group: ModelGroup,
    isSelected: Boolean,
    config: ProviderConfig,
    activeEntryId: String?,
    onSelectMember: (String) -> Unit,
    onQuickTest: (ModelEntry) -> Unit,
) {
    val resolvedMembers = group.memberEntryIds.mapNotNull { entryId ->
        config.modelEntries.find { it.id == entryId }
            ?: SystemVoiceEntries.resolve(entryId)
    }
    // 兜底：只在本组选中时展示 activeEntryId。
    val displayMembers = resolvedMembers.ifEmpty {
        if (isSelected && activeEntryId != null) {
            listOfNotNull(config.modelEntries.find { it.id == activeEntryId })
        } else emptyList()
    }
    if (displayMembers.isEmpty()) {
        Text(
            stringResource(R.string.model_picker_no_linked_models),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 8.dp),
        )
    }
    displayMembers.forEachIndexed { memberIndex, entry ->
        // [T-android-model-picker-polish] 成员间细线：组卡和供应商行都有，
        // 展开的成员行没有时一组多模型会糊成一团——恰恰是最需要分开的地方。
        // 画在每行之前（首行除外），不与组卡后的分隔线重叠。
        if (memberIndex > 0) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
        }
        val isActive = activeEntryId == entry.id
        val instance = config.instances.find { it.id == entry.providerInstanceId }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelectMember(entry.id) }
                .padding(start = 48.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectDot(selected = isActive, selectedTint = MaterialTheme.colorScheme.primary, size = 17.dp, idleAlpha = 0.2f)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(providerDotColor(instance?.providerType), CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
                Row {
                    if (instance != null) {
                        Text(
                            instance.label.ifEmpty { instance.providerType.displayName },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                    Text(
                        entry.model.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            if (isActive) {
                MiniCapsule(
                    text = stringResource(R.string.model_picker_active_badge),
                    fg = Color(0xFF34C759),
                    bg = Color(0xFF34C759).copy(alpha = 0.1f),
                )
            }
            QuickTestButton(onClick = { onQuickTest(entry) })
        }
    }
}

// ── 供应商卡 ────────────────────────────────────────────────────────────────

/** 每个供应商一张卡：内嵌段头（带折叠箭头）+ 折叠摘要行或展开条目列表。
 *  卡间 12dp 间距、落在更高调表面上，深色背景下供应商分界不含糊。 */
@Composable
internal fun ProviderCard(
    instance: ProviderInstance,
    entries: List<ModelEntry>,
    collapsed: Boolean,
    activeEntryId: String?,
    groupSelected: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
    onSelectEntry: (String) -> Unit,
    onQuickTest: (ModelEntry) -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            ),
    ) {
        Row(
            // T236：段头 padding 收紧（top=10/bottom=8），首模型行距读作
            // ~8dp 而不是旧的 12dp+行内边距堆叠。
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                instance.label.ifEmpty { instance.providerType.displayName },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            DisclosureDot(expanded = !collapsed, onToggle = onToggle)
        }

        // [T-android-model-picker-polish] 供应商名下的细线——下面的行是
        // 模型不是供应商装帧，没线时头行读作首个列表项。
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )

        if (collapsed) {
            CollapsedProviderBody(
                instance = instance,
                entries = entries,
                activeEntryId = activeEntryId,
                groupSelected = groupSelected,
                onSelectEntry = onSelectEntry,
                onExpand = onExpand,
                onQuickTest = onQuickTest,
            )
        } else {
            entries.forEachIndexed { index, entry ->
                ProviderEntryRow(
                    entry = entry,
                    dotColor = providerDotColor(instance.providerType),
                    isSelected = activeEntryId == entry.id && !groupSelected,
                    // 组被选中时，活动条目行仍带绿色"在用"徽。
                    showActiveBadge = groupSelected && activeEntryId == entry.id,
                    isLast = index == entries.size - 1,
                    onSelect = { onSelectEntry(entry.id) },
                    onQuickTest = { onQuickTest(entry) },
                )
                // 条目间细线。
                if (index < entries.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CollapsedProviderBody(
    instance: ProviderInstance,
    entries: List<ModelEntry>,
    activeEntryId: String?,
    groupSelected: Boolean,
    onSelectEntry: (String) -> Unit,
    onExpand: () -> Unit,
    onQuickTest: (ModelEntry) -> Unit,
) {
    // 折叠摘要：优先选中项，否则首条 + 数量。
    val selectedEntry = entries.firstOrNull { it.id == activeEntryId && !groupSelected }
    val displayEntry = selectedEntry ?: entries.firstOrNull() ?: return
    Row(
        // T236：折叠摘要行 纵向 14→8 + heightIn(min=48dp) 保触控面积。
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .clickable { onSelectEntry(displayEntry.id) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(
            selected = selectedEntry != null,
            selectedTint = MaterialTheme.colorScheme.primary,
            size = 20.dp,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(providerDotColor(instance.providerType), CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        // [T-android-provider-voice] 模态徽（iOS entryRow badges）——缺了它
        // 语音种子充当折叠代表时与聊天模型无从区分。FlowRow 让溢出整枚换行
        // 而不是把每枚挤碎。
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                displayEntry.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            com.openminis.app.ui.components.modalityBadges(displayEntry.model).forEach { badge ->
                com.openminis.app.ui.components.ModalityBadge(badge)
            }
        }
        // [T-android-model-picker-polish] 尾槽放快测不放模型数——数量在
        // 下面"显示 N 个模型"行已说，重复放是浪费；其他模型行尾槽都是
        // 闪电钮，这里也一致（iOS 同理）。
        QuickTestButton(onClick = { onQuickTest(displayEntry) })
    }
    // 单模型供应商的折叠预览就是整个列表，"显示 1 个模型"点开会得到
    // 屏幕上已有的一行——只有多模型时才给展开行。
    if (entries.size > 1) {
        // 展开行前细线：它是控件不是模型行，贴着摘要行会读成两行的同一条目。
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
        // [T-android-model-picker-polish] 明示"显示 N 个模型"入口。段头
        // 箭头虽小但角落目标太小像装饰；点摘要行本身是选中该模型，展开必须
        // 有自己的控件而不能复用它。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 箭头领前贴卡边——它才是"可展开"的信号，旧的 30dp 缩进让它悬
            // 在上面模型名下方而不是对齐卡片。
            Icon(
                NovexIcons.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                pluralStringResource(
                    R.plurals.model_picker_show_models,
                    entries.size,
                    entries.size,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ProviderEntryRow(
    entry: ModelEntry,
    dotColor: Color,
    isSelected: Boolean,
    showActiveBadge: Boolean,
    isLast: Boolean,
    onSelect: () -> Unit,
    onQuickTest: () -> Unit,
) {
    // 末行自裁圆角，涟漪才不会溢出卡片底角。
    val rowShape = if (isLast) {
        RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(0.dp)
    }
    Row(
        // T236：展开条目行 纵向 14→8 + heightIn(min=48dp) 保触控面积。
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(rowShape)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(selected = isSelected, selectedTint = MaterialTheme.colorScheme.primary, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(dotColor, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.model.displayName,
                style = MaterialTheme.typography.bodyMedium,
            )
            // [T-android-provider-voice] 模态徽同行排布；FlowRow 让溢出整枚
            // 换行而不是把每个 Text 挤成竖排字母列。
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    entry.model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                com.openminis.app.ui.components.modalityBadges(entry.model).forEach { badge ->
                    com.openminis.app.ui.components.ModalityBadge(badge)
                }
            }
        }
        if (showActiveBadge) {
            MiniCapsule(
                text = stringResource(R.string.model_picker_active_badge),
                fg = Color(0xFF34C759),
                bg = Color(0xFF34C759).copy(alpha = 0.1f),
            )
        }
        QuickTestButton(onClick = onQuickTest)
    }
}

// ── 空态 ────────────────────────────────────────────────────────────────────

@Composable
internal fun PickerEmptyState(hasQuery: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (hasQuery) NovexIcons.Search else NovexIcons.Memory,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (hasQuery) R.string.model_picker_no_results else R.string.model_picker_no_models),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                if (hasQuery) R.string.model_picker_try_different_search
                else R.string.model_picker_configure_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (hasQuery) 0.5f else 0.6f,
            ),
            textAlign = TextAlign.Center,
        )
    }
}

// ── 小件 ────────────────────────────────────────────────────────────────────

/** 选中圆点：CheckCircle/RadioButtonUnchecked 互换。 */
@Composable
internal fun SelectDot(
    selected: Boolean,
    selectedTint: Color,
    size: androidx.compose.ui.unit.Dp,
    idleAlpha: Float = 0.3f,
) {
    Icon(
        if (selected) NovexIcons.CheckCircle else NovexIcons.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (selected) selectedTint
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = idleAlpha),
        modifier = Modifier.size(size),
    )
}

/** 9sp 胶囊徽（默认/在用同款视觉）。 */
@Composable
internal fun MiniCapsule(text: String, fg: Color, bg: Color) {
    Text(
        text,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * [T-android-model-picker-polish] 24dp 中性披露钮——tertiarySystemFill
 * 圆 + secondary 箭头（对齐 iOS）。T295 曾用 secondaryContainer 想逃离
 * surfaceContainerHigh（浅色下 #F7F7FA 会消失在白卡上），但有色容器读
 * 作强调动作与组名争眼球，而它只是个披露控件。onSurface 低透明够区分
 * 又不抢戏。28→24dp：28 的圆比行内文字还高最先抢视线。
 */
@Composable
internal fun DisclosureDot(expanded: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                CircleShape,
            )
            .clip(CircleShape)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (expanded) NovexIcons.KeyboardArrowUp else NovexIcons.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * [T-android-model-picker-polish] 行内快测钮，对齐 iOS
 * bolt.badge.checkmark。32dp 触控面、独立点击面——点行选中模型，点
 * 闪电只测试不改选中。
 */
@Composable
internal fun QuickTestButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            NovexIcons.Bolt,
            contentDescription = stringResource(R.string.model_picker_quick_test),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
    }
}

// iOS: 供应商色点
internal fun providerDotColor(providerType: ProviderType?): Color = when (providerType) {
    ProviderType.anthropic -> Color(0xFFAB47BC) // purple
    ProviderType.gemini -> Color(0xFF42A5F5)    // blue
    ProviderType.openAI -> Color(0xFF4CAF50)    // green
    ProviderType.openRouter -> Color(0xFF00BCD4) // cyan
    ProviderType.xAI -> Color(0xFFFF7043)        // orange — Grok brand
    ProviderType.kimiCode -> Color(0xFF5C6BC0)   // indigo — Kimi accent
    null -> Color(0xFF8E8E93)                    // gray
}
