package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import novex.android.ui.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import novex.android.ui.OutlinedTextField
import novex.android.ui.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import novex.android.data.model.ModelGroup
import com.openminis.app.data.ModelGroupBinding
import com.openminis.app.data.ModelGroupMove
import com.openminis.app.data.modelGroupRemovalImpact
import com.openminis.app.data.moveManagedModelGroup
import com.openminis.app.data.reorderManagedModelGroups
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.SectionCard
import com.openminis.app.ui.components.SectionDesign
import com.openminis.app.ui.components.SectionDivider
import com.openminis.app.ui.components.SectionFooter
import com.openminis.app.ui.components.SectionHeader
import java.util.UUID
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)

@Composable
fun ModelGroupsScreen(
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onGroupClick: (String) -> Unit,
    /** T185: navigate to the entries picker for the agent-loop set.
     *  Replaces T182's in-screen ModalBottomSheet so the picker shape
     *  matches AddModelsToGroupScreen (shared composable). */
    onAddAgentLoopModels: () -> Unit = {},
    /** T185: navigate to the groups picker for the agent-loop set. */
    onAddAgentLoopGroups: () -> Unit = {},
) {
    val config by providerRepository.config.collectAsState()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            providerRepository.ensureImageGenerationMigration()
        }
    }
    val groups = config.modelGroups.filterNot { it.id in config.imageGenerationGroupIds }
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var newGroupName by remember { mutableStateOf("") }
    var isManagingGroups by remember { mutableStateOf(false) }
    var pendingDeleteGroupId by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val reorderSavedMessage = stringResource(R.string.model_groups_order_saved)
    val undoLabel = stringResource(R.string.model_groups_undo)

    LaunchedEffect(groups.isEmpty()) {
        if (groups.isEmpty()) isManagingGroups = false
    }

    fun commitMenuMove(groupId: String, move: ModelGroupMove) {
        val current = providerRepository.config.value
        val before = current.modelGroups.map { it.id }
        val managed = current.modelGroups
            .filterNot { it.id in current.imageGenerationGroupIds }
            .map { it.id }
        val after = moveManagedModelGroup(before, managed, groupId, move)
        if (after == before) return
        providerRepository.reorderModelGroups(after)
        coroutineScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = reorderSavedMessage,
                actionLabel = undoLabel,
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                providerRepository.reorderModelGroups(before)
            }
        }
    }

    // T186: parent LazyListState shared with rememberReorderableLazyListState
    // so each agent-loop pinned row (rendered as its own LazyColumn item)
    // can carry a Modifier.draggableHandle and reorder live. The reorder
    // callback resolves the dragged row by its key (entry id / group id),
    // permutes our local copy, then commits via ProviderRepository.
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        // Two reorder zones share the same lazyListState — entries
        // (key prefix "agent_entry:") and groups ("agent_group:").
        // Refuse cross-zone drags so a user can't accidentally drop an
        // entry into the groups zone (the data shape would reject it
        // anyway, but a no-op is friendlier than a snap-back).
        when {
            fromKey.startsWith("agent_entry:") && toKey.startsWith("agent_entry:") -> {
                val fromId = fromKey.removePrefix("agent_entry:")
                val toId = toKey.removePrefix("agent_entry:")
                val cur = providerRepository.config.value.agentLoopModelEntryIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopEntries(newOrder)
            }
            fromKey.startsWith("agent_group:") && toKey.startsWith("agent_group:") -> {
                val fromId = fromKey.removePrefix("agent_group:")
                val toId = toKey.removePrefix("agent_group:")
                val cur = providerRepository.config.value.agentLoopGroupIds.toList()
                val fromIdx = cur.indexOf(fromId)
                val toIdx = cur.indexOf(toId)
                if (fromIdx < 0 || toIdx < 0) return@rememberReorderableLazyListState
                val newOrder = cur.toMutableList().apply { add(toIdx, removeAt(fromIdx)) }
                providerRepository.reorderAgentLoopGroups(newOrder)
            }
            // [T-android-modelgroup-reorder] Third zone: the user's Model
            // Groups themselves ("日常/编程/翻译"…). NOTE the prefix check
            // order is safe: "agent_group:" does not start with "group:", so
            // the zones cannot cross-match.
            fromKey.startsWith("group:") && toKey.startsWith("group:") -> {
                val fromId = fromKey.removePrefix("group:")
                val toId = toKey.removePrefix("group:")
                val current = providerRepository.config.value
                val cur = current.modelGroups.map { it.id }
                val managed = current.modelGroups
                    .filterNot { it.id in current.imageGenerationGroupIds }
                    .map { it.id }
                val newOrder = reorderManagedModelGroups(cur, managed, fromId, toId)
                providerRepository.reorderModelGroups(newOrder)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_groups_model_groups)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(novex.android.ui.NovexIcons.ArrowBack, contentDescription = stringResource(R.string.model_group_detail_back))
                    }
                },
                actions = {
                    if (groups.isNotEmpty()) {
                        MinisTextButton(onClick = { isManagingGroups = !isManagingGroups }) {
                            Text(
                                if (isManagingGroups) {
                                    stringResource(R.string.model_groups_done)
                                } else {
                                    stringResource(R.string.model_groups_manage)
                                },
                            )
                        }
                    }
                    if (!isManagingGroups) {
                        IconButton(onClick = { showNewGroupDialog = true }) {
                            Icon(novex.android.ui.NovexIcons.Add, contentDescription = stringResource(R.string.model_groups_new_group))
                        }
                    }
                },
            )
        },
    ) { padding ->
        // T313: page background uses SectionDesign.screenBackgroundColor()
        // (= surfaceContainerLow), so the section cards painted in `surface`
        // visibly stand out as iOS-style inset-grouped panels.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SectionDesign.screenBackgroundColor()),
        ) {
            item("top_gap") { Spacer(modifier = Modifier.height(SectionDesign.FirstSectionTopGap)) }

            if (groups.isEmpty()) {
                item("empty_state") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            novex.android.ui.NovexIcons.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.model_groups_no_model_groups),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.model_groups_groups_let_you_combine_models_for_fallba),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                // T313 — Section 1 (Groups).
                //
                // [T-android-modelgroup-reorder] The section used to be ONE
                // LazyColumn item wrapping a SectionCard with a forEach inside
                // — which is exactly why the groups could not be reordered:
                // ReorderableLazyListState only sees direct LazyColumn items.
                // Rows are now individual items keyed "group:<id>" with the
                // per-row cardRow(first/last) treatment, the same composition
                // the agent-loop section below has used since T186. Swipe-to-
                // delete and tap-to-open are unchanged; dragging lives on the
                // explicit leading handle (consistent with AgentLoopRow, and
                // it keeps clickable/swipe gestures conflict-free).
                item("groups_section_header") {
                    SectionHeader(text = stringResource(R.string.agent_loop_models_groups))
                }
                itemsIndexed(groups, key = { _, g -> "group:${g.id}" }) { index, group ->
                    val copySuffix = stringResource(R.string.model_groups_copy_suffix)
                    ReorderableItem(
                        state = reorderState,
                        key = "group:${group.id}",
                    ) { _ ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
                                pendingDeleteGroupId = group.id
                                dismissState.reset()
                            }
                        }
                        Column {
                            if (index != 0) SectionDividerInsetCard()
                            Box(
                                modifier = Modifier.cardRow(
                                    isFirst = index == 0,
                                    isLast = index == groups.lastIndex,
                                ),
                            ) {
                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.errorContainer)
                                                .padding(end = 20.dp),
                                            contentAlignment = Alignment.CenterEnd,
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                Icon(
                                                    novex.android.ui.NovexIcons.DeleteOutline,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                                )
                                                Text(
                                                    stringResource(R.string.common_delete),
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                )
                                            }
                                        }
                                    },
                                    enableDismissFromStartToEnd = false,
                                    gesturesEnabled = !isManagingGroups,
                                ) {
                                    GroupRow(
                                        group = group,
                                        config = config,
                                        onClick = { onGroupClick(group.id) },
                                        isManaging = isManagingGroups,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < groups.lastIndex,
                                        onEdit = { onGroupClick(group.id) },
                                        onDuplicate = {
                                            providerRepository.addGroup(
                                                group.copy(
                                                    id = UUID.randomUUID().toString(),
                                                    name = group.name + " " + copySuffix,
                                                    memberEntryIds = group.memberEntryIds.toMutableList(),
                                                ),
                                            )
                                        },
                                        onMove = { move -> commitMenuMove(group.id, move) },
                                        onDelete = { pendingDeleteGroupId = group.id },
                                        dragHandleModifier = if (isManagingGroups) {
                                            with(this@ReorderableItem) { Modifier.draggableHandle() }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // T313 — Section 2 (Defaults). Two dropdown rows in one card
                // with SectionDivider between them, footer caption outside.
                item("defaults_section_spacer") {
                    Spacer(modifier = Modifier.height(SectionDesign.SectionTopGap))
                }
                item("defaults_section_header") {
                    SectionHeader(text = "Defaults")
                }
                item("defaults_section_card") {
                    SectionCard {
                        GroupDropdown(
                            label = "Default Primary",
                            groups = groups,
                            selectedId = config.defaultPrimaryGroupId,
                            onSelect = { providerRepository.defaultPrimaryGroupId = it },
                        )
                        SectionDivider()
                        GroupDropdown(
                            label = "Default Sub",
                            groups = groups,
                            selectedId = config.defaultSubGroupId,
                            onSelect = { providerRepository.defaultSubGroupId = it },
                        )
                        // [P3.3 裁军] 语音输入/输出分组绑定两行（voiceInput/
                        // OutputGroupId 下拉）随语音全家退役删除。
                        // [T-android-vision-group / GH#182] Vision Group — the
                        // group whose vision-capable members read images for a
                        // main model that cannot natively see them.
                        SectionDivider()
                        GroupDropdown(
                            label = stringResource(R.string.model_groups_vision),
                            groups = groups,
                            selectedId = config.visionGroupId,
                            onSelect = { providerRepository.visionGroupId = it },
                        )
                    }
                }
                item("defaults_section_footer") {
                    SectionFooter(
                        text = stringResource(R.string.model_groups_primary_is_used_for_main_agent_tasks_sub),
                    )
                }
            }

            // T182 / T185 / T186: Agent Loop Models section. Mirrors iOS
            // ModelGroupsView.swift L77-79's inline AgentLoopModelsSection
            // — header + curated rows + add-row + footer all directly in
            // this LazyColumn so the rows can participate in the parent's
            // ReorderableLazyListState (T186 drag-to-reorder).
            agentLoopModelsSectionItems(
                providerRepository = providerRepository,
                config = config,
                reorderState = reorderState,
                onAddModelsTap = onAddAgentLoopModels,
                onAddGroupsTap = onAddAgentLoopGroups,
            )
        }
    }

    // T185: the in-screen ModalBottomSheet was replaced by full-screen
    // navigated picker screens (AddAgentLoopModelsScreen /
    // AddAgentLoopGroupsScreen). The "Add Models" / "Add Groups"
    // buttons inside AgentLoopModelsSection now invoke
    // onAddAgentLoopModels / onAddAgentLoopGroups passed from
    // AppNavigation, mirroring how AddModelsToGroupScreen is reached.

    if (showNewGroupDialog) {
        AlertDialog(
            onDismissRequest = {
                showNewGroupDialog = false
                newGroupName = ""
            },
            title = { Text(stringResource(R.string.model_groups_new_group)) },
            text = {
                Column {
                    Text(
                        "Enter a name for the new model group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newGroupName,
                        onValueChange = { newGroupName = it },
                        label = { Text(stringResource(R.string.model_groups_group_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            val newGroup = ModelGroup(name = newGroupName.trim())
                            providerRepository.addGroup(newGroup)
                            // Auto-set as primary default if it's the first group
                            if (config.modelGroups.size == 1 && config.defaultPrimaryGroupId == null) {
                                providerRepository.defaultPrimaryGroupId = newGroup.id
                            }
                            newGroupName = ""
                            showNewGroupDialog = false
                        }
                    },
                    enabled = newGroupName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.model_groups_create))
                }
            },
            dismissButton = {
                MinisTextButton(onClick = {
                    showNewGroupDialog = false
                    newGroupName = ""
                }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    val pendingDeleteGroup = pendingDeleteGroupId?.let { id ->
        config.modelGroups.find { it.id == id }
    }
    if (pendingDeleteGroup != null) {
        val impact = config.modelGroupRemovalImpact(pendingDeleteGroup.id)
        val bindingNames = impact.bindings.map { binding ->
            when (binding) {
                ModelGroupBinding.DEFAULT_PRIMARY -> stringResource(R.string.model_groups_binding_default_primary)
                ModelGroupBinding.DEFAULT_SUB -> stringResource(R.string.model_groups_binding_default_sub)
                ModelGroupBinding.VOICE_INPUT -> stringResource(R.string.model_groups_binding_voice_input)
                ModelGroupBinding.VOICE_OUTPUT -> stringResource(R.string.model_groups_binding_voice_output)
                ModelGroupBinding.VISION -> stringResource(R.string.model_groups_binding_vision)
                ModelGroupBinding.AGENT_LOOP -> stringResource(R.string.model_groups_binding_agent_loop)
                ModelGroupBinding.IMAGE_GENERATION -> stringResource(R.string.model_groups_binding_image_generation)
            }
        }
        val impactText = if (bindingNames.isEmpty()) {
            stringResource(R.string.model_groups_delete_keeps_models)
        } else {
            stringResource(
                R.string.model_groups_delete_clears_bindings,
                bindingNames.joinToString(stringResource(R.string.model_groups_binding_separator)),
            )
        }
        AlertDialog(
            onDismissRequest = { pendingDeleteGroupId = null },
            title = { Text(stringResource(R.string.model_group_detail_delete_group)) },
            text = {
                Text(
                    stringResource(
                        R.string.model_groups_delete_named_group_safe_confirm,
                        pendingDeleteGroup.name,
                        impactText,
                    ),
                )
            },
            confirmButton = {
                MinisTextButton(
                    onClick = {
                        providerRepository.removeGroup(pendingDeleteGroup.id)
                        pendingDeleteGroupId = null
                    },
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { pendingDeleteGroupId = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** Small colored badge label (e.g. "Primary", "Sub"). */
