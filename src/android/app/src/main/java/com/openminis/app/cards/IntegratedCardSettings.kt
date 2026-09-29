package com.openminis.app.cards

import novex.android.ui.NovexPageTopBar
import novex.android.ui.NovexColors

import novex.android.ui.Scaffold

import novex.android.ui.RadioButton

import novex.android.ui.Checkbox

import novex.android.ui.TextButton

import novex.android.ui.Button

import novex.android.ui.AlertDialog

import novex.content.flattenModules
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import novex.runtime.*

/**
 * [T-card-usage-pages]（用户 2026-09-28 裁决）原"卡片采用与管理"三列
 * 矩阵页退役，拆为三个专属页（顶部页签切换，入口直达对应页）：
 * - 回答身份（单选）：Nova 默认 / 角色·扮演 / 世界·GM 叙述——世界与
 *   角色分组并列，不混列；
 * - 背景资料（多选）：资料卡；含模块携带（必带/不带/默认）；
 * - 管理（多选）：仅授予编辑权限，不改变身份也不注入资料。
 * 绑定语义与保存链路不变（同一 CardBinding 显式保存；主卡变更经
 * saveIntegratedCardBinding 触发既有激活流程）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun IntegratedCardSettings(chat: String, initialMode: String = "identity", onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.openminis.app.MinisApp
    val model: com.openminis.app.ui.chat.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        viewModelStoreOwner = com.openminis.app.ui.chat.ChatViewModelStore.ownerFor(chat),
        factory = com.openminis.app.ui.chat.ChatViewModel.factory(chat, app.chatRepository, app.providerRepository,
            appContext = app, memoryRepository = app.memoryRepository, skillRepository = app.skillRepository, mcpRepository = app.mcpRepository))
    val ready by model.conversationSettingsReady.collectAsState()
    val cards = remember { IntegratedCards(context) }
    val saver = remember { Saver<CardBinding?, String>(save = { it?.encode() ?: "null" }, restore = { if (it == "null") null else CardBinding.decode(it) }) }
    var baseline by rememberSaveable(chat, stateSaver = saver) { mutableStateOf<CardBinding?>(null) }
    var draft by rememberSaveable(chat, stateSaver = saver) { mutableStateOf<CardBinding?>(null) }
    var hydrated by rememberSaveable(chat) { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var mode by rememberSaveable(chat) { mutableStateOf(if (initialMode in setOf("identity", "background", "manage")) initialMode else "identity") }
    var choices by remember { mutableStateOf(emptyList<UsageChoice>()) }
    var modules by remember { mutableStateOf<Map<SourceSelection, List<Pair<String, String>>>>(emptyMap()) }
    var expanded by remember { mutableStateOf<SourceSelection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(chat, ready) {
        if (!ready) return@LaunchedEffect
        try {
            val loaded = withContext(Dispatchers.IO) {
                run { LegacyCards(app).migrate(); model.integratedCardBinding() } to cards.store.list().flatMap { summary ->
                    val root = requireNotNull(cards.store.open(summary.id)).content
                    val isWorld = root.kind == novex.content.CardKind.WORLD
                    listOf(UsageChoice(SourceSelection(root.id), root.name, if (isWorld) "世界" else "角色", isWorld, missing = false)) +
                        root.internalCharacters.map { UsageChoice(SourceSelection(root.id, it.id), "${root.name} · ${it.name}", "角色", false, false) }
                }
            }
            if (!hydrated) { baseline = loaded.first; draft = loaded.first ?: CardBinding(); hydrated = true }
            val known = loaded.second.map { it.target }.toSet()
            val all = listOfNotNull(loaded.first?.primary) + loaded.first?.backgrounds.orEmpty() + loaded.first?.managed.orEmpty().map { SourceSelection(it.rootId, it.targetId) }
            choices = loaded.second + (all.distinct() - known).map { UsageChoice(it, "作品未找到（可取消关联）", "", false, missing = true) }
            modules = withContext(Dispatchers.IO) { loaded.second.associate { choice ->
                val card = novex.content.ContentTargets.find(requireNotNull(cards.store.open(choice.target.rootId)).content, choice.target.targetId)
                choice.target to (listOf(card) + card.internalCharacters).flatMap { c -> c.modules.flattenModules().map { it.id to "${c.name} · ${it.name.ifBlank { "未命名模块" }}" } }
            } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message } finally { busy = false }
    }
    val back: () -> Unit = { if (!busy) { if (draft != null && draft != (baseline ?: CardBinding())) discard = true else onBack() } }
    androidx.activity.compose.BackHandler(enabled = !WindowInsets.isImeVisible) { back() }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的设置？") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("放弃更改") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续设置") } })

    fun toggleBackground(target: SourceSelection, yes: Boolean) {
        draft = draft?.let { d -> d.copy(backgrounds = if (yes) (d.backgrounds + target).distinct() else d.backgrounds - target) }
    }

    fun toggleManage(target: SourceSelection, yes: Boolean) {
        val managed = ManagementTarget(target.rootId, target.targetId)
        draft = draft?.let { d -> d.copy(managed = if (yes) d.managed + managed else d.managed - managed) }
    }

    fun setOverride(moduleId: String, rule: Boolean?) {
        draft = draft?.let { d -> d.copy(overrides = if (rule == null) d.overrides - moduleId else d.overrides + (moduleId to rule)) }
    }

    val characterChoices = choices.filter { !it.missing && !it.isWorldRoot }
    val worldChoices = choices.filter { !it.missing && it.isWorldRoot }
    val missingChoices = choices.filter { it.missing }
    Scaffold(containerColor = NovexColors.Canvas, topBar = {
        NovexPageTopBar(title = when (mode) { "identity" -> "回答身份"; "background" -> "背景资料"; else -> "管理内容" }, onBack = back)
    }, bottomBar = {
        Button(enabled = !busy && draft != null, onClick = { busy = true; scope.launch {
            try { model.saveIntegratedCardBinding(requireNotNull(draft), baseline); onBack() }
            catch (failure: Exception) { error = failure.message } finally { busy = false }
        } }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("保存") }
    }) { padding -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Row {
            listOf("identity" to "回答身份", "background" to "背景资料", "manage" to "管理").forEach { (value, title) ->
                TextButton(enabled = mode != value, onClick = { mode = value; expanded = null }) {
                    Text(if (mode == value) "● $title" else title,
                        color = if (mode == value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
        when (mode) {
            "identity" -> {
                item { Text("单选。Nova 为默认助手；「扮演」以该角色身份回答；「GM 叙述」讲述世界并扮演其中人物，不替你决定行动。") }
                item {
                    UsageRadioRow(name = "Nova（默认）", badge = null, subtitle = "不采用卡片身份，日常助手模式",
                        selected = draft?.primary == null, enabled = !busy) { draft = draft?.copy(primary = null) }
                    HorizontalDivider()
                }
                if (draft?.primary != null && missingChoices.any { it.target == draft?.primary }) {
                    item { Text("当前主卡作品未找到，可改选 Nova 或其他身份。", color = MaterialTheme.colorScheme.error) }
                }
                item { SectionHeader("角色 · 扮演") }
                items(characterChoices, key = { "c" + it.target.rootId + ":" + it.target.targetId }) { choice ->
                    UsageRadioRow(name = choice.name, badge = "扮演",
                        selected = draft?.primary == choice.target, enabled = !busy) { draft = draft?.copy(primary = choice.target) }
                    HorizontalDivider()
                }
                item { SectionHeader("世界 · GM 叙述") }
                items(worldChoices, key = { "w" + it.target.rootId }) { choice ->
                    UsageRadioRow(name = choice.name, badge = "GM 叙述",
                        selected = draft?.primary == choice.target, enabled = !busy) { draft = draft?.copy(primary = choice.target) }
                    HorizontalDivider()
                }
            }
            "background" -> {
                item { Text("多选。作为背景资料参与开局资料包与每轮材料流；世界卡的内部角色会一并纳入。已采用的卡可展开设置模块携带。") }
                item { SectionHeader("角色") }
                items(characterChoices, key = { "c" + it.target.rootId + ":" + it.target.targetId }) { choice ->
                    UsageCheckRow(choice = choice, checked = draft?.backgrounds?.contains(choice.target) == true, enabled = !busy,
                        onToggle = { yes -> toggleBackground(choice.target, yes) })
                    ModuleCarrySection(choice = choice, draft = draft, modules = modules, expanded = expanded, busy = busy,
                        onToggleExpand = { expanded = if (expanded == choice.target) null else choice.target },
                        onOverride = { moduleId, rule -> setOverride(moduleId, rule) })
                    HorizontalDivider()
                }
                item { SectionHeader("世界") }
                items(worldChoices, key = { "w" + it.target.rootId }) { choice ->
                    UsageCheckRow(choice = choice, checked = draft?.backgrounds?.contains(choice.target) == true, enabled = !busy,
                        onToggle = { yes -> toggleBackground(choice.target, yes) })
                    ModuleCarrySection(choice = choice, draft = draft, modules = modules, expanded = expanded, busy = busy,
                        onToggleExpand = { expanded = if (expanded == choice.target) null else choice.target },
                        onOverride = { moduleId, rule -> setOverride(moduleId, rule) })
                    HorizontalDivider()
                }
                if (missingChoices.isNotEmpty()) {
                    item { SectionHeader("失效关联") }
                    items(missingChoices, key = { "m" + it.target.rootId + ":" + it.target.targetId }) { choice ->
                        UsageCheckRow(choice = choice, checked = draft?.backgrounds?.contains(choice.target) == true, enabled = !busy,
                            onToggle = { yes -> toggleBackground(choice.target, yes) })
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                item { Text("多选。仅授予对应卡片的编辑权限（工具读写与管理目录）；管理不改变回答身份，也不注入资料。") }
                item { SectionHeader("角色") }
                items(characterChoices, key = { "c" + it.target.rootId + ":" + it.target.targetId }) { choice ->
                    UsageCheckRow(choice = choice, checked = draft?.managed?.contains(ManagementTarget(choice.target.rootId, choice.target.targetId)) == true, enabled = !busy,
                        onToggle = { yes -> toggleManage(choice.target, yes) })
                    HorizontalDivider()
                }
                item { SectionHeader("世界") }
                items(worldChoices, key = { "w" + it.target.rootId }) { choice ->
                    UsageCheckRow(choice = choice, checked = draft?.managed?.contains(ManagementTarget(choice.target.rootId, choice.target.targetId)) == true, enabled = !busy,
                        onToggle = { yes -> toggleManage(choice.target, yes) })
                    HorizontalDivider()
                }
                if (missingChoices.isNotEmpty()) {
                    item { SectionHeader("失效关联") }
                    items(missingChoices, key = { "m" + it.target.rootId + ":" + it.target.targetId }) { choice ->
                        UsageCheckRow(choice = choice, checked = draft?.managed?.contains(ManagementTarget(choice.target.rootId, choice.target.targetId)) == true, enabled = !busy,
                            onToggle = { yes -> toggleManage(choice.target, yes) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }}
}

/** 采用候选：目标 + 展示名 + 类型徽标（世界/角色，失效为空）。 */
internal data class UsageChoice(
    val target: SourceSelection,
    val name: String,
    val kindLabel: String,
    val isWorldRoot: Boolean,
    val missing: Boolean,
)

@Composable private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable private fun UsageRadioRow(name: String, badge: String?, subtitle: String? = null, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Column(Modifier.padding(start = 8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                badge?.let { Text("  $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable private fun UsageCheckRow(choice: UsageChoice, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onToggle)
        Column(Modifier.padding(start = 8.dp)) {
            Text(choice.name, style = MaterialTheme.typography.titleMedium)
            if (choice.kindLabel.isNotEmpty()) Text(choice.kindLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ModuleCarrySection(
    choice: UsageChoice,
    draft: CardBinding?,
    modules: Map<SourceSelection, List<Pair<String, String>>>,
    expanded: SourceSelection?,
    busy: Boolean,
    onToggleExpand: () -> Unit,
    onOverride: (moduleId: String, rule: Boolean?) -> Unit,
) {
    val carried = draft?.primary == choice.target || draft?.backgrounds?.contains(choice.target) == true
    if (!carried) return
    TextButton(onClick = onToggleExpand) { Text(if (expanded == choice.target) "收起模块携带" else "模块携带") }
    if (expanded == choice.target) {
        val list = modules[choice.target].orEmpty()
        if (list.isEmpty()) Text("这张卡没有可单独控制的模块。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { (id, label) ->
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Row {
                listOf(null to "默认", true to "必带", false to "不带").forEach { (rule, title) ->
                    TextButton(onClick = { onOverride(id, rule) }, enabled = !busy) {
                        Text((if (draft?.overrides?.get(id) == rule) "✓ " else "") + title)
                    }
                }
            }
        }
    }
}
