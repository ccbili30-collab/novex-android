package com.openminis.app.ui.novex

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import com.openminis.app.MinisApp
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** Worldbook choices are a concise projection of the existing reference graph, not another library. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun NovexGameWorldbookSection(projectId: String) {
    val app = LocalContext.current.applicationContext as MinisApp
    val workspace = rememberNovexWorkspace()
    val service = remember(workspace) { NovexGameWorldbooks(workspace) { block -> app.database.withTransaction { block() } } }
    val coroutine = rememberCoroutineScope()
    var saved by remember(projectId) { mutableStateOf<List<NovexCardReference>>(emptyList()) }
    var baseline by remember(projectId) { mutableStateOf<List<NovexCardReference>>(emptyList()) }
    var draft by remember(projectId) { mutableStateOf<List<NovexCardReference>>(emptyList()) }
    var pickerEntries by remember(projectId) { mutableStateOf<List<NovexLibraryEntry>>(emptyList()) }
    var libraries by remember { mutableStateOf<List<NovexWorkGroup>>(emptyList()) }
    val groups = rememberNovexWorkGroups()
    val groupState by groups.snapshots.collectAsState(initial = null)
    var page by remember(projectId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var rangeOptions by remember { mutableStateOf<List<Pair<NovexReferenceTarget, String>>>(emptyList()) }
    var rangeSelection by remember { mutableStateOf<Set<NovexReferenceTarget>>(emptySet()) }
    var rangeReference by remember { mutableStateOf<NovexCardReference?>(null) }
    var rangeTitle by remember { mutableStateOf("") }
    var request by remember { mutableIntStateOf(0) }
    fun close() { request++; page = "" }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        coroutine.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "世界书选择未保存" }
            finally { busy = false }
        }
    }
    LaunchedEffect(projectId) {
        com.openminis.app.novex.adapter.observeNovexLibraryChanges(app.database).collect {
            try { saved = service.load(projectId) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "世界书选择暂时无法读取" }
        }
    }
    fun title(reference: NovexCardReference) = reference.targetLabel.ifBlank {
        pickerEntries.firstOrNull { it.address == reference.target.subject }?.title ?: "未命名世界书"
    }
    fun open() {
        val ticket = ++request
        run {
            val current = service.load(projectId)
            val available = workspace.libraryDirectory(app.creativeArtifactRepository).filter { it.address.kind == NovexContentKind.WORLD }
            if (ticket == request) {
                baseline = current; draft = current; pickerEntries = available
                libraries = groupState?.groups.orEmpty(); error = null; page = "edit"
            }
        }
    }
    fun chooseRange(reference: NovexCardReference) {
        val ticket = ++request
        run {
            val world = requireNotNull(workspace.world(reference.target.subject.id)) { "这份世界书已不存在" }
            val modules = workspace.modules(ModuleOwner.world(world.world.id)).modules
                .filter { NovexModuleVisibility.allowsContext(it.type, acting = false) }
            if (ticket == request) {
                rangeTitle = world.world.name; rangeReference = reference
                rangeSelection = draft.filter { it.target.subject == reference.target.subject }.mapTo(linkedSetOf()) { it.target }
                rangeOptions = listOf(NovexReferenceTarget(reference.target.subject) to "整本世界书") + modules.flatMap { module ->
                    val collection = com.openminis.app.data.character.ContentModuleDocumentCodec.decode(module.type, module.contentJson)
                        as? com.openminis.app.data.character.ContentModuleDocument.Collection
                    listOf(NovexReferenceTarget(reference.target.subject, module.id) to module.name) + collection?.items.orEmpty()
                        .filter { it.id.isNotBlank() }.map { item -> NovexReferenceTarget(reference.target.subject, module.id, item.id) to "${module.name} · ${item.name.ifBlank { "未命名条目" }}" }
                }
                page = "range"
            }
        }
    }
    NovexContentSection("使用的世界书") {
        NovexSummaryRow(if (saved.isEmpty()) "选择世界书" else "已选 ${saved.size} 项", if (busy) "正在读取" else
            if (saved.isEmpty()) "从世界库添加" else "${saved.count { it.enabled }} 项启用", onClick = ::open)
        saved.take(3).forEach { item -> NovexSummaryRow(title(item), if (item.enabled) "已启用" else "已关闭", onClick = ::open) }
    }
    when (page) {
        "edit" -> ModalBottomSheet(onDismissRequest = ::close) {
            Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("使用的世界书", style = NovexType.SectionTitle, color = NovexColors.Text)
                Text("保存为这张文游的默认选择，正在进行的对话保持原有设置。", style = NovexType.Metadata, color = NovexColors.SecondaryText)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(draft, key = { it.id }) { item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { chooseRange(item) }.padding(vertical = 12.dp)) {
                                Text(title(item), color = NovexColors.Text, style = NovexType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (item.target.moduleId == null) "整本 · 点按选择范围" else "指定范围 · 点按调整", style = NovexType.Metadata, color = NovexColors.SecondaryText)
                            }
                            NovexCheckToggle(item.enabled, { enabled -> draft = draft.map { if (it.id == item.id) it.copy(enabled = enabled) else it } }, enabled = !busy)
                        }
                    }
                }
                NovexTextActionRow("选择世界书", onClick = { if (!busy) page = "choose" })
                error?.let { Text(it, color = NovexColors.SecondaryText) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::close) { Text("取消") }
                    TextButton(enabled = !busy, onClick = {
                        val ticket = request
                        val submitted = draft.toList()
                        run { saved = service.save(projectId, baseline, submitted); if (ticket == request) close() }
                    }) { Text(if (busy) "正在保存" else "保存选择") }
                }
            }
        }
        "choose" -> if (pickerEntries.isEmpty()) NovexNoticeDialog("世界库暂无内容", "先在世界库创建或导入世界书，再来选用。") { page = "edit" }
        else NovexLibraryPicker("选择世界书", pickerEntries, libraries,
            selected = draft.mapTo(linkedSetOf()) { it.target.subject }, onDismiss = ::close, onConfirm = { selected ->
                val previous = draft
                draft = previous.filter { it.target.subject in selected } + selected.filter { address -> previous.none { it.target.subject == address } }.map { address ->
                    NovexCardReference(UUID.randomUUID().toString(), NovexContentAddress.interactiveFiction(projectId),
                        NovexReferenceTarget(address), NovexReferencePurpose.RULES, position = previous.size,
                        targetLabel = pickerEntries.first { it.address == address }.title)
                }
                draft = draft.mapIndexed { index, reference -> reference.copy(position = index) }
                page = "edit"
            })
        "range" -> NovexSearchableSelectionSheet(rangeTitle, rangeOptions.map { (target, label) ->
            NovexSelectionAction(label, selected = target in rangeSelection) {
                rangeSelection = if (target in rangeSelection) rangeSelection - target else {
                    rangeSelection.filterNot { old -> target.moduleId == null || old.moduleId == null ||
                        (old.moduleId == target.moduleId && (old.entryId == null || target.entryId == null)) }.toSet() + target
                }
            }
        }, "搜索模块或条目", onDismissRequest = ::close, dismissOnSelection = false,
            onCancelSelection = { page = "edit" }, confirmLabel = "使用选中的范围", onConfirmSelection = {
                val reference = requireNotNull(rangeReference)
                val old = draft.filter { it.target.subject == reference.target.subject }
                val replacement = rangeOptions.filter { it.first in rangeSelection }.map { (target, label) ->
                    old.singleOrNull { it.target == target } ?: reference.copy(id = UUID.randomUUID().toString(), target = target,
                        targetLabel = if (target.moduleId == null) rangeTitle else "$rangeTitle · $label")
                }
                val firstIndex = draft.indexOfFirst { it.target.subject == reference.target.subject }.coerceAtLeast(0)
                draft = draft.filterNot { it.target.subject == reference.target.subject }.toMutableList().apply { addAll(firstIndex.coerceAtMost(size), replacement) }
                    .mapIndexed { index, value -> value.copy(position = index) }
                page = "edit"
            })
    }
    if (page.isEmpty()) error?.let { message -> NovexNoticeDialog("世界书选择未完成", message) { error = null } }
}
