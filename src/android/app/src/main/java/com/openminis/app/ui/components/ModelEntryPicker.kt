package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import novex.android.ui.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import novex.android.data.model.SystemVoiceEntries
import novex.android.data.model.hasImageInput
import novex.android.data.model.normalizeModalities
import novex.android.ui.NovexIcons

/**
 * [T-android-vision-group] 视觉场景：只要图像输入条目。无 System 虚拟
 * 条目——端侧没有视觉引擎。
 * [P3.3 裁军] 原 AUDIO_INPUT/AUDIO_OUTPUT（语音 ASR/TTS 模态过滤 +
 * System 虚拟条目注入）随语音全家退役删除。
 */
enum class PickerModalityFilter {
    IMAGE_INPUT;

    fun matches(model: LLMModel): Boolean = when (this) {
        IMAGE_INPUT -> model.hasImageInput
    }
}

/**
 * T185 — 模型条目多选挑选器（LazyListScope 扩展）。
 *
 * T185 之前 agent-loop 加件 Sheet 和 AddModelsToGroupScreen 用两套不同
 * 视觉渲同一份列表；iOS 两侧是同一个分节多选样式，所以把布局抽到这里
 * 共用。挂进调用方的 LazyColumn 而不是自封 Composable——外围 chrome
 * （Scaffold/顶栏/确认键）和 LazyListState 归调用方管。
 */
fun LazyListScope.modelEntryPickerItems(
    instances: List<ProviderInstance>,
    availableEntries: List<ModelEntry>,
    selectedIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    searchQuery: MutableState<String>,
    collapsedInstanceIds: MutableState<Set<String>>,
    @StringRes emptyTextRes: Int,
    @StringRes emptySearchTextRes: Int,
    @StringRes searchPlaceholderRes: Int,
    @StringRes clearContentDescriptionRes: Int,
    // [T-android-model-quick-test] 可选的行内快速测试钮：给了就在每行加
    // 闪电图标开 QuickTestSheet（sheet 本体和仓库归调用方）。System 虚拟
    // 条目没有云端端点可测，不出钮。
    onQuickTest: ((ModelEntry) -> Unit)? = null,
    // [T-android-provider-voice] 模态过滤（见 PickerModalityFilter）：
    // null = 不过滤、不注入 System——保留历史调用方的默认行为。
    modalityFilter: PickerModalityFilter? = null,
    // 注入的 System 条目要排除的 id（如已在目标组里的）。常规条目由
    // 调用方通过 [availableEntries] 排除；System 条目在这里构造，故单列。
    excludeIds: Set<String> = emptySet(),
    // System 供应商分节的本地化显示名——本扩展不是 @Composable，由调用
    // 方先 stringResource 好再传。对齐 iOS String(localized:"System")。
    systemProviderLabel: String = "System",
) {
    val query = searchQuery.value.lowercase()
    val searching = searchQuery.value.isNotBlank()

    fun ModelEntry.hitsQuery() =
        !searching ||
            model.displayName.lowercase().contains(query) ||
            model.id.lowercase().contains(query)

    // 按供应商分节：启用的实例 × 其下过过滤+命中的条目。
    val sections = instances
        .filter { it.isEnabled && !SystemVoiceEntries.isSystemEntryId(it.id) }
        .mapNotNull { instance ->
            val entries = availableEntries.filter { entry ->
                entry.providerInstanceId == instance.id &&
                    (modalityFilter == null || modalityFilter.matches(entry.model))
            }.filter { it.hitsQuery() }
            if (entries.isEmpty()) null else PickerSection(instance, entries)
        }

    // 搜索时强制展开全部分节，否则折叠态会藏住命中结果。
    val collapsedIds =
        if (searching) emptySet() else collapsedInstanceIds.value

    item("__search__") {
        PickerSearchField(
            query = searchQuery,
            placeholderRes = searchPlaceholderRes,
            clearDescriptionRes = clearContentDescriptionRes,
        )
    }

    for (section in sections) {
        val collapsed = section.instance.id in collapsedIds
        item(key = "header_${section.instance.id}") {
            PickerSectionHeader(
                section = section,
                collapsed = collapsed,
                onToggleCollapse = {
                    collapsedInstanceIds.value =
                        if (collapsed) collapsedInstanceIds.value - section.instance.id
                        else collapsedInstanceIds.value + section.instance.id
                },
            )
        }
        if (collapsed) {
            item(key = "collapsed_${section.instance.id}") {
                // 折叠态预览：第一行 + 「N models」计数提醒节内数量。
                CollapsedSectionPreview(
                    section = section,
                    selectedIds = selectedIds,
                    onToggleSelection = onToggleSelection,
                )
            }
        } else {
            item(key = "entries_${section.instance.id}") {
                ExpandedSectionBody(
                    section = section,
                    selectedIds = selectedIds,
                    onToggleSelection = onToggleSelection,
                    onQuickTest = onQuickTest,
                )
            }
        }
    }

    if (sections.isEmpty()) {
        item("__empty__") {
            Text(
                stringResource(if (searching) emptySearchTextRes else emptyTextRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

private class PickerSection(
    val instance: ProviderInstance,
    val entries: List<ModelEntry>,
)

// ── 搜索框 ──────────────────────────────────────────────────────────────────

@Composable
private fun PickerSearchField(
    query: MutableState<String>,
    @StringRes placeholderRes: Int,
    @StringRes clearDescriptionRes: Int,
) {
    OutlinedTextField(
        value = query.value,
        onValueChange = { query.value = it },
        placeholder = { Text(stringResource(placeholderRes)) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        leadingIcon = {
            Icon(
                NovexIcons.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.value.isNotEmpty()) {
                IconButton(onClick = { query.value = "" }) {
                    Icon(
                        NovexIcons.Close,
                        contentDescription = stringResource(clearDescriptionRes),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
    )
}

// ── 分节头 ──────────────────────────────────────────────────────────────────

@Composable
private fun PickerSectionHeader(
    section: PickerSection,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.instance.label.ifEmpty { section.instance.providerType.displayName },
                // [T-android-model-picker-polish] 分节头要压过行。
                // titleMedium+SemiBold 对行内 bodyMedium，层级才立得住；
                // 和会话模型挑选器同款。
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    // 中性披露控件 24dp——带色或更大的圆会和供应商名抢戏。
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        CircleShape,
                    )
                    .clickable(onClick = onToggleCollapse),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (collapsed) NovexIcons.KeyboardArrowDown else NovexIcons.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 细线把头名和模型行分开，否则头名读成列表第一行。
        HorizontalDivider(
            modifier = Modifier.padding(start = 20.dp, end = 16.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        )
    }
}

// ── 行 ─────────────────────────────────────────────────────────────────────

@Composable
private fun CollapsedSectionPreview(
    section: PickerSection,
    selectedIds: Set<String>,
    onToggleSelection: (String) -> Unit,
) {
    val first = section.entries.firstOrNull() ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .pickerCardShape(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onToggleSelection(first.id) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionDot(first.id in selectedIds)
        Spacer(Modifier.width(10.dp))
        ProviderDot(section.instance.providerType)
        Spacer(Modifier.width(10.dp))
        Text(
            first.model.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            // 曾是硬编码英文 "N models"——挑选器里唯一非资源文案，中文
            // 用户看到 "413 models"。
            androidx.compose.ui.res.pluralStringResource(
                R.plurals.model_picker_models_count,
                section.entries.size,
                section.entries.size,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun ExpandedSectionBody(
    section: PickerSection,
    selectedIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onQuickTest: ((ModelEntry) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(12.dp),
            ),
    ) {
        section.entries.forEachIndexed { index, entry ->
            PickerEntryRow(
                entry = entry,
                providerType = section.instance.providerType,
                selected = entry.id in selectedIds,
                onToggle = { onToggleSelection(entry.id) },
                rowShape = edgeShape(index, section.entries.size),
                onQuickTest = onQuickTest,
            )
            if (index < section.entries.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

/** 首/尾行圆角吃卡片的 12dp，中间行直角。 */
private fun edgeShape(index: Int, count: Int): Shape = when {
    count == 1 -> RoundedCornerShape(12.dp)
    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    index == count - 1 -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    else -> RoundedCornerShape(0.dp)
}

private fun Modifier.pickerCardShape(fill: Color): Modifier =
    this.clip(RoundedCornerShape(12.dp))
        .background(fill, RoundedCornerShape(12.dp))

@Composable
private fun PickerEntryRow(
    entry: ModelEntry,
    providerType: ProviderType?,
    selected: Boolean,
    onToggle: () -> Unit,
    rowShape: Shape,
    onQuickTest: ((ModelEntry) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(rowShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionDot(selected)
        Spacer(Modifier.width(10.dp))
        ProviderDot(providerType)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.model.displayName, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.model.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                // [T-android-provider-voice] 模态徽章（iOS entryRow
                // modalityBadges：img/audio/video/pdf 及 -out 变体）。
                for (badge in modalityBadges(entry.model)) {
                    Spacer(Modifier.width(4.dp))
                    ModalityBadge(badge)
                }
            }
        }
        if (onQuickTest != null && !SystemVoiceEntries.isSystemEntryId(entry.id)) {
            IconButton(onClick = { onQuickTest(entry) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    NovexIcons.Bolt,
                    contentDescription = stringResource(R.string.quicktest_button),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ProviderDot(providerType: ProviderType?) {
    Box(Modifier.size(6.dp).background(providerDotColor(providerType), CircleShape))
}

@Composable
private fun SelectionDot(isSelected: Boolean) {
    Icon(
        if (isSelected) NovexIcons.CheckCircle else NovexIcons.RadioButtonUnchecked,
        contentDescription = null,
        tint = if (isSelected) Color(0xFF007AFF)
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp),
    )
}

// ── 模态徽章 ────────────────────────────────────────────────────────────────

/**
 * [T-android-provider-voice] 模型行的非文本模态徽章——对齐 iOS
 * UnifiedModelPicker.modalityBadges（同名同序：先输入后输出，text 不标）。
 */
fun modalityBadges(model: LLMModel): List<String> {
    val ins = model.inputModalities.normalizeModalities().orEmpty()
    val outs = model.outputModalities.normalizeModalities().orEmpty()
    return buildList {
        if ("image" in ins) add("img")
        if ("audio" in ins) add("audio")
        if ("video" in ins) add("video")
        if ("pdf" in ins) add("pdf")
        if ("image" in outs) add("img-out")
        if ("audio" in outs) add("audio-out")
        if ("video" in outs) add("video-out")
    }
}

/**
 * [T-android-modality-chip] 单个模态徽章（iOS entryRow badge 配方：
 * 9pt medium、tertiarySystemFill 圆角 3）。两条 Android 侧修正：
 *  - 填充用 onSurface@8% 而非 surfaceContainerHighest——徽章坐在
 *    surfaceContainerHigh 卡片上，同色调会让胶囊整个消失；
 *  - maxLines=1+softWrap=false，徽章内部永不折行（Compose 溢出会逐字
 *    断行，"audio-out" 会竖排）；宿主用 FlowRow 让溢出整颗换行。
 */
@Composable
fun ModalityBadge(badge: String) {
    Text(
        badge,
        fontSize = 9.sp,
        lineHeight = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

/** 供应商圆点色——ChatScreen/AddModelsToGroup/agent-loop sheet 全用同一
 *  组色，视觉线索在各处一致。 */
fun providerDotColor(providerType: ProviderType?): Color = when (providerType) {
    ProviderType.anthropic -> Color(0xFFAB47BC)
    ProviderType.gemini -> Color(0xFF42A5F5)
    ProviderType.openAI -> Color(0xFF4CAF50)
    ProviderType.openRouter -> Color(0xFF00BCD4)
    ProviderType.xAI -> Color(0xFFFF7043)
    ProviderType.kimiCode -> Color(0xFF5C6BC0) // indigo——Kimi 品牌色
    null -> Color(0xFF8E8E93)
}
