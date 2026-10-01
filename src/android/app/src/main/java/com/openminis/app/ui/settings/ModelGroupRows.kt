package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import novex.android.ui.DropdownMenu
import novex.android.ui.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import novex.android.ui.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import novex.android.data.model.ModelGroup
import novex.android.data.model.RoutingStrategy
import com.openminis.app.data.ModelGroupMove
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class)

@Composable
internal fun BadgeLabel(text: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = novex.android.ui.novexScaledSp(11),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupDropdown(
    label: String,
    groups: List<ModelGroup>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    val noneLabel = stringResource(R.string.model_groups_none)
    val selected = groups.find { it.id == selectedId }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = novex.android.ui.NovexType.Metadata)
        com.openminis.app.ui.components.SectionDropdown(
            selected = selected,
            items = listOf<ModelGroup?>(null) + groups,
            onSelect = { onSelect(it?.id) },
            itemLabel = { it?.name ?: noneLabel },
        )
    }
}

/**
 * T182 — inline section embedded at the bottom of ModelGroupsScreen.
 * Mirrors iOS `AgentLoopModelsSection` from
 * `src/ios/Views/Providers/AgentLoopModelsView.swift` L1-72. Renders
 * the user's curated agent-loop usable set: pinned groups (rendered
 * first, since picking a group implicitly pins all its members) then
 * pinned individual entries. Each row carries a × icon to remove the
 * pin; the section footer hosts two add-sheet trigger buttons.
 *
 * The picker layouts behind those triggers live in
 * `AgentLoopAddSheets.kt` and use `LazyColumn` so a user with a 369-
 * entry OpenRouter instance doesn't freeze Compose the way the pre-
 * T182 `AgentLoopModelsScreen` did when it materialised every entry
 * in a single `Column`.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.agentLoopModelsSectionItems(
    providerRepository: ProviderRepository,
    config: novex.android.data.model.ProviderConfig,
    reorderState: ReorderableLazyListState,
    onAddModelsTap: () -> Unit,
    onAddGroupsTap: () -> Unit,
) {
    // [T-android-agentloop-dup-key-crash] distinctBy id (belt-and-suspenders
    // alongside the data-layer sink dedup). A config that's ALREADY corrupted
    // with a duplicate id (persisted before the sink fix) would otherwise yield
    // two rows sharing the same LazyColumn/Reorderable key and crash on scroll
    // (IllegalArgumentException: Key "..." was already used). Deduping here
    // makes the screen render so the user can even reach a state where the
    // persisted list gets rewritten clean. totalRows / absIndex below derive
    // from these deduped lists, so the row math stays consistent.
    val pinnedGroups = config.agentLoopGroupIds
        .mapNotNull { gid -> config.modelGroups.find { it.id == gid } }
        .distinctBy { it.id }
    val pinnedEntries = config.agentLoopModelEntryIds
        .mapNotNull { eid -> config.modelEntries.find { it.id == eid } }
        .distinctBy { it.id }
    val isEmpty = pinnedGroups.isEmpty() && pinnedEntries.isEmpty()

    // T313 — agent-loop section. Reorder rows MUST stay as top-level
    // LazyColumn items (ReorderableLazyListState only sees direct child
    // items), so the card visual is built per-row via SectionDesign
    // tokens: every row is wrapped with cardRow(isFirst, isLast) which
    // applies horizontal insets, surface fill, and rounded corners only
    // on the first/last row. SectionDivider is emitted as its own item
    // between rows so the inner card reads as one continuous panel.
    item("agent_loop_section_spacer") {
        Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
    }
    item("agent_loop_section_header") {
        SectionHeader(text = stringResource(R.string.agent_loop_section_title))
    }

    if (isEmpty) {
        item("agent_loop_section_empty") {
            Box(modifier = Modifier.cardRow(isFirst = true, isLast = true)) {
                Text(
                    text = stringResource(R.string.agent_loop_section_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    } else {
        val totalRows = pinnedGroups.size + pinnedEntries.size
        itemsIndexed(pinnedGroups, key = { _, g -> "agent_group:${g.id}" }) { index, group ->
            ReorderableItem(
                state = reorderState,
                key = "agent_group:${group.id}",
            ) { _ ->
                val resolvedCount = group.memberEntryIds.count { mid ->
                    config.modelEntries.any { it.id == mid }
                }
                val subtitle = if (resolvedCount == 1) {
                    stringResource(R.string.agent_loop_models_model_count_singular)
                } else {
                    stringResource(R.string.agent_loop_models_model_count_plural, resolvedCount)
                }
                Column {
                    if (index != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = index == 0,
                            isLast = index == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = group.name,
                            subtitle = subtitle,
                            badge = stringResource(R.string.agent_loop_section_group_badge),
                            onRemove = { providerRepository.removeAgentLoopGroup(group.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }

        itemsIndexed(pinnedEntries, key = { _, e -> "agent_entry:${e.id}" }) { index, entry ->
            ReorderableItem(
                state = reorderState,
                key = "agent_entry:${entry.id}",
            ) { _ ->
                val instanceLabel = config.instances
                    .find { it.id == entry.providerInstanceId }
                    ?.label
                val absIndex = pinnedGroups.size + index
                Column {
                    if (absIndex != 0) SectionDividerInsetCard()
                    Box(
                        modifier = Modifier.cardRow(
                            isFirst = absIndex == 0,
                            isLast = absIndex == totalRows - 1,
                        ),
                    ) {
                        AgentLoopRow(
                            title = entry.model.displayName,
                            subtitle = instanceLabel,
                            badge = null,
                            onRemove = { providerRepository.removeAgentLoopEntry(entry.id) },
                            dragHandleModifier = Modifier.then(
                                with(this@ReorderableItem) {
                                    Modifier.draggableHandle()
                                }
                            ),
                        )
                    }
                }
            }
        }
    }

    item("agent_loop_section_add_buttons") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MinisOutlinedButton(
                onClick = onAddModelsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    novex.android.ui.NovexIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_models))
            }
            MinisOutlinedButton(
                onClick = onAddGroupsTap,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    novex.android.ui.NovexIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.agent_loop_section_add_groups))
            }
        }
    }

    item("agent_loop_section_footer") {
        SectionFooter(text = stringResource(R.string.agent_loop_section_footer))
    }

    item("bottom_gap") { Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap)) }
}

/** Single row inside the agent-loop section. Drag handle on the left
 *  (T186), title + optional subtitle + optional "Group" badge in the
 *  middle, × to unpin on the right. The drag-handle modifier is built
 *  by the parent (since ReorderableItemScope.draggableHandle() is
 *  scope-bound) and threaded in here to keep the row composable
 *  scope-agnostic. */
@Composable
internal fun AgentLoopRow(
    title: String,
    subtitle: String?,
    badge: String?,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // T198: drag handle must be wrapped in IconButton — Icon is raw
        // vector graphics with no pointer consumer, so reorderable v2.4.0's
        // draggableHandle() never sees ACTION_DOWN/MOVE on a bare Icon.
        // IconButton is Compose's clickable container and consumes pointer
        // events, letting the dragHandleModifier route gestures correctly.
        IconButton(
            onClick = {},
            modifier = dragHandleModifier.size(36.dp),
        ) {
            Icon(
                imageVector = novex.android.ui.NovexIcons.DragHandle,
                contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (badge != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    BadgeLabel(badge, MaterialTheme.colorScheme.primary)
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = novex.android.ui.NovexIcons.Close,
                contentDescription = stringResource(R.string.agent_loop_section_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// ── T313 LazyList card helpers ────────────────────────────────────────────
//
// SectionCard / SectionDivider from SectionDesign are designed for
// Column-scoped sections, but the agent-loop section needs each row to
// stay a top-level LazyColumn item so ReorderableLazyListState (T186) can
// see it. These helpers paint the SectionDesign card visual on a per-row
// basis: cardRow() applies horizontal insets + surface fill + per-row
// corner clipping; SectionDividerInsetCard() draws the inner divider
// between consecutive rows so the painted run reads as one panel.

@Composable
internal fun Modifier.cardRow(isFirst: Boolean, isLast: Boolean): Modifier {
    val shape = when {
        isFirst && isLast -> SectionDesign.CardShape
        isFirst -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        isLast -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
        else -> RectangleShape
    }
    // Match SectionCard's inner Column vertical padding so first/last rows
    // get the same breathing room from the card edge as Sections 1 & 2.
    return this
        .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
        .clip(shape)
        .background(SectionDesign.cardColor())
        .padding(
            top = if (isFirst) SectionDesign.CardInnerVerticalPadding else 0.dp,
            bottom = if (isLast) SectionDesign.CardInnerVerticalPadding else 0.dp,
        )
}

/** Divider between two cardRow rows. Painted on the same surface fill so
 *  it reads as an inset divider inside the card panel rather than a hard
 *  line floating on the page background. */
@Composable
internal fun SectionDividerInsetCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding)
            .background(SectionDesign.cardColor()),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
            thickness = SectionDesign.DividerThickness,
            color = SectionDesign.dividerColor(),
        )
    }
}

/**
 * [T-android-modelgroup-modality-icons] One modality marker shown after a Model
 * Group title. `kind` is the modality string ("image"/"audio"/"video"/"pdf"/
 * "text") and `isOutput` distinguishes a generation output from an accepted
 * input. Ports iOS ModelGroupsView.GroupRow.topModalities /
 * modalityIcon (commits 7caa580a + e0a7cd5f).
 */
internal data class GroupModalityMarker(val kind: String, val isOutput: Boolean)

/**
 * Distinctiveness ranking, most distinctive first — mirrors iOS
 * `modalityPriority`. `text` input is intentionally absent (universal noise);
 * `text` output ranks last (implied for every model, only surfaces when a group
 * has nothing more distinctive). Transcription (audio-in) outranks other inputs
 * so Whisper-style groups get flagged even though their output is plain text.
 */
internal val GROUP_MODALITY_PRIORITY: List<GroupModalityMarker> = listOf(
    GroupModalityMarker("video", isOutput = true),
    GroupModalityMarker("image", isOutput = true),
    GroupModalityMarker("audio", isOutput = true),
    GroupModalityMarker("audio", isOutput = false),
    GroupModalityMarker("video", isOutput = false),
    GroupModalityMarker("image", isOutput = false),
    GroupModalityMarker("pdf", isOutput = false),
    GroupModalityMarker("text", isOutput = true),
)

/**
 * The group's top-2 most distinctive modalities across every member model's
 * inputs AND outputs, in priority order. Aggregates raw model modality lists
 * (Android convention — no capability inference); a null/empty outputModalities
 * counts as text output (iOS treats text-out as universal). Mirrors iOS
 * `GroupRow.topModalities`.
 */
internal fun groupTopModalities(
    group: ModelGroup,
    config: novex.android.data.model.ProviderConfig,
): List<GroupModalityMarker> {
    val inputs = mutableSetOf<String>()
    val outputs = mutableSetOf<String>()
    for (entryId in group.memberEntryIds) {
        val model = config.modelEntries.find { it.id == entryId }?.model ?: continue
        model.inputModalities.orEmpty().forEach { inputs.add(it.lowercase()) }
        val out = model.outputModalities.orEmpty()
        if (out.isEmpty()) outputs.add("text") else out.forEach { outputs.add(it.lowercase()) }
    }
    return GROUP_MODALITY_PRIORITY.filter { marker ->
        if (marker.isOutput) marker.kind in outputs else marker.kind in inputs
    }.take(2)
}

/**
 * Icon + tint + a11y label for one [GroupModalityMarker], reusing the glyph
 * convention from ProviderDetailScreen.ModalityIconsRow: output/generation
 * modalities use the primary tint + "generate"-style glyph; input modalities
 * use the muted onSurfaceVariant tint. Renders nothing for unknown combos.
 */
@Composable
internal fun GroupModalityIcon(marker: GroupModalityMarker) {
    val outputTint = MaterialTheme.colorScheme.primary
    val inputTint = MaterialTheme.colorScheme.onSurfaceVariant
    val size = Modifier.size(14.dp)
    val (vector, labelRes, tint) = when {
        marker.isOutput && marker.kind == "video" -> Triple(novex.android.ui.NovexIcons.MovieCreation, R.string.modeldetail_video_output, outputTint)
        marker.isOutput && marker.kind == "image" -> Triple(novex.android.ui.NovexIcons.AddPhotoAlternate, R.string.modeldetail_image_output, outputTint)
        marker.isOutput && marker.kind == "audio" -> Triple(novex.android.ui.NovexIcons.VolumeUp, R.string.modelgroup_speech_output, outputTint)
        marker.isOutput && marker.kind == "text" -> Triple(novex.android.ui.NovexIcons.Article, R.string.modelgroup_text_generation, outputTint)
        marker.kind == "audio" -> Triple(novex.android.ui.NovexIcons.Mic, R.string.modelgroup_speech_transcription, inputTint)
        marker.kind == "video" -> Triple(novex.android.ui.NovexIcons.Videocam, R.string.modeldetail_video_input, inputTint)
        marker.kind == "image" -> Triple(novex.android.ui.NovexIcons.Image, R.string.modeldetail_image_input, inputTint)
        marker.kind == "pdf" -> Triple(novex.android.ui.NovexIcons.InsertDriveFile, R.string.modeldetail_pdf_input, inputTint)
        else -> return
    }
    Icon(imageVector = vector, contentDescription = stringResource(labelRes), modifier = size, tint = tint)
}

/** A single group row inside the Groups section card. Built as a plain
 *  Composable (not a ListItem) so it inherits the card's surface color
 *  cleanly without ListItem's container override. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupRow(
    group: ModelGroup,
    config: novex.android.data.model.ProviderConfig,
    onClick: () -> Unit,
    isManaging: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (ModelGroupMove) -> Unit,
    onDelete: () -> Unit,
    /**
     * [T-android-modelgroup-reorder] When non-null, a leading drag handle is
     * rendered carrying this modifier (ReorderableItem.draggableHandle).
     * Explicit handle rather than long-press-anywhere: the row already owns
     * tap (open detail) and horizontal swipe (delete), and this matches
     * AgentLoopRow's affordance in the section below.
     */
    dragHandleModifier: Modifier? = null,
) {
    var actionsExpanded by remember(group.id) { mutableStateOf(false) }
    val memberNames = group.memberEntryIds.mapNotNull { entryId ->
        config.modelEntries.find { it.id == entryId }?.model?.displayName
    }
    val preview = if (memberNames.size <= 3) {
        memberNames.joinToString(", ")
    } else {
        memberNames.take(3).joinToString(", ") + " +${memberNames.size - 3}"
    }
    val isPrimary = config.defaultPrimaryGroupId == group.id
    val isSub = config.defaultSubGroupId == group.id
    val strategyLabel = when (group.strategy) {
        RoutingStrategy.fallback -> stringResource(R.string.model_group_detail_fallback)
        RoutingStrategy.loadBalance -> stringResource(R.string.model_group_detail_load_balance)
    }
    // [T-disabled-provider-via-group-android] Count members whose provider
    // instance is currently enabled — that's what the runtime resolver
    // will actually consider. When every member sits behind a disabled
    // provider, surface a warning so the user understands why the group
    // appears empty in chat.
    val enabledInstanceIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
    val totalMembers = group.memberEntryIds.size
    val enabledMembers = group.memberEntryIds.count { entryId ->
        config.modelEntries.find { it.id == entryId }?.providerInstanceId in enabledInstanceIds
    }
    val allDisabled = totalMembers > 0 && enabledMembers == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // SwipeToDismissBox always composes its reveal layer. Keep the
            // row opaque at rest so the destructive background appears only
            // while the foreground is actually being swiped away.
            .background(SectionDesign.cardColor())
            .clickable(onClick = onClick)
            .padding(
                horizontal = SectionDesign.RowHorizontalPadding,
                vertical = SectionDesign.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isManaging) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = novex.android.ui.NovexIcons.DeleteOutline,
                    contentDescription = stringResource(R.string.model_group_detail_delete_group),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // [T-android-modelgroup-modality-icons] Mark the group's top-2
                // most distinctive modalities (inputs + outputs) right after the
                // title, in priority order, for at-a-glance "what is this group
                // for". Ports iOS ModelGroupsView (7caa580a + e0a7cd5f).
                groupTopModalities(group, config).forEach { marker ->
                    GroupModalityIcon(marker)
                }
                if (isPrimary) BadgeLabel("Primary", MaterialTheme.colorScheme.primary)
                if (isSub) BadgeLabel("Sub", MaterialTheme.colorScheme.tertiary)
                if (allDisabled) {
                    BadgeLabel(
                        stringResource(R.string.model_group_no_usable_models_badge),
                        MaterialTheme.colorScheme.error,
                    )
                }
            }
            // [T-disabled-provider-via-group-android] Surface "M of N
            // disabled" when any member's provider is off so the user
            // doesn't have to drill into the detail page to learn that
            // the group isn't fully usable. Total count stays prominent
            // because it's still the source of truth for what's in the
            // group; the parenthetical reports disabled count.
            val disabledCount = totalMembers - enabledMembers
            val subtitleText = if (disabledCount > 0) {
                "$strategyLabel · $totalMembers models ($disabledCount disabled)"
            } else {
                "$strategyLabel · $totalMembers models"
            }
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.bodySmall,
                color = if (allDisabled) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (preview.isNotEmpty()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
        if (isManaging && dragHandleModifier != null) {
            IconButton(
                onClick = {},
                modifier = dragHandleModifier.size(44.dp),
            ) {
                Icon(
                    imageVector = novex.android.ui.NovexIcons.DragHandle,
                    contentDescription = stringResource(R.string.model_group_detail_drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Box {
                IconButton(onClick = { actionsExpanded = true }) {
                    Icon(
                        novex.android.ui.NovexIcons.MoreVert,
                        contentDescription = stringResource(R.string.model_groups_more_actions),
                    )
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = { actionsExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.common_edit)) },
                        onClick = {
                            actionsExpanded = false
                            onEdit()
                        },
                        leadingIcon = { Icon(novex.android.ui.NovexIcons.Edit, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_duplicate_group)) },
                        onClick = {
                            actionsExpanded = false
                            onDuplicate()
                        },
                        leadingIcon = { Icon(novex.android.ui.NovexIcons.ContentCopy, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_move_to_top)) },
                        enabled = canMoveUp,
                        onClick = {
                            actionsExpanded = false
                            onMove(ModelGroupMove.TOP)
                        },
                        leadingIcon = { Icon(novex.android.ui.NovexIcons.VerticalAlignTop, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_move_up)) },
                        enabled = canMoveUp,
                        onClick = {
                            actionsExpanded = false
                            onMove(ModelGroupMove.UP)
                        },
                        leadingIcon = { Icon(novex.android.ui.NovexIcons.KeyboardArrowUp, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_move_down)) },
                        enabled = canMoveDown,
                        onClick = {
                            actionsExpanded = false
                            onMove(ModelGroupMove.DOWN)
                        },
                        leadingIcon = { Icon(novex.android.ui.NovexIcons.KeyboardArrowDown, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.model_groups_move_to_bottom)) },
                        enabled = canMoveDown,
                        onClick = {
                            actionsExpanded = false
                            onMove(ModelGroupMove.BOTTOM)
                        },
                        leadingIcon = { Icon(novex.android.ui.NovexIcons.VerticalAlignBottom, contentDescription = null) },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.common_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            actionsExpanded = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                novex.android.ui.NovexIcons.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}

