package com.openminis.app.ui.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Existing work-group IDs become user libraries; all three catalogs keep the same filter. */
@Composable
internal fun NovexWorkGroupControls(snapshot: NovexWorkGroupSnapshot?, openMembersRequest: Int = 0,
    onConfigureConversation: (String) -> Unit = {}, onOpenContent: ((NovexContentAddress) -> Unit)? = null) {
    val groups = rememberNovexWorkGroups()
    val workspace = rememberNovexWorkspace()
    val artifacts = rememberNovexCreativeArtifacts()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf("") }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var expectedName by rememberSaveable { mutableStateOf("") }
    var naming by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<NovexLibraryEntry>>(emptyList()) }
    var expectedMembers by remember { mutableStateOf(emptySet<NovexContentAddress>()) }
    var subject by remember { mutableStateOf<NovexContentAddress?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val group = snapshot?.groups?.firstOrNull { it.id == groupId }
    val conversations by groups.conversations.collectAsState(initial = emptyList())
    val visible = entries.associateBy { it.address }
    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作未完成，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(openMembersRequest, snapshot != null) {
        if (openMembersRequest > 0 && snapshot != null) {
            val selected = snapshot?.selectedGroup
            if (selected == null) page = "select" else { groupId = selected.id; folderId = null; page = "browse" }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(page) {
        if (page.isNotEmpty()) {
            val database = (context.applicationContext as com.openminis.app.MinisApp).database
            com.openminis.app.novex.adapter.observeNovexLibraryChanges(database).collect {
                try { entries = workspace.libraryDirectory(artifacts) }
                catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "读取仓库失败：${failure.message}" }
            }
        }
    }
    TextButton(onClick = { if (snapshot != null) page = "select" }, modifier = Modifier.padding(horizontal = 8.dp)) {
        Text("${snapshot?.label ?: "正在读取"} ▾", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    if (error != null) {
        NovexContentDialog("操作未完成", onDismiss = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("返回") } }) { Text(error.orEmpty()) }
        return
    }
    when (page) {
        "select" -> NovexSearchableSelectionSheet("按创作库筛选", buildList {
            add(NovexSelectionAction("全部作品", selected = snapshot?.selection == NovexWorkGroupSnapshot.ALL) {
                execute { groups.select(NovexWorkGroupSnapshot.ALL); page = "" }
            })
            add(NovexSelectionAction("未归类", selected = snapshot?.selection == NovexWorkGroupSnapshot.UNCLASSIFIED) {
                execute { groups.select(NovexWorkGroupSnapshot.UNCLASSIFIED); page = "" }
            })
            snapshot?.groups.orEmpty().forEach { item -> add(NovexSelectionAction(item.name, selected = snapshot?.selection == item.id) {
                execute { groups.select(item.id); page = "" }
            }) }
            add(NovexSelectionAction("新建创作库", group = "管理") { groupId = ""; name = ""; naming = "create"; page = "name" })
            add(NovexSelectionAction("管理创作库", group = "管理") { page = "manage" })
        }, "搜索创作库", onDismissRequest = { page = "" }, dismissOnSelection = false)
        "manage" -> NovexSearchableSelectionSheet("管理创作库", snapshot?.groups.orEmpty().map { item ->
            NovexSelectionAction(item.name, description = "${item.members.count { it in visible }} 项内容") {
                groupId = item.id; folderId = null; page = "browse"
            }
        } + NovexSelectionAction("新建创作库") { groupId = ""; name = ""; naming = "create"; page = "name" },
            "搜索创作库", onDismissRequest = { page = "" }, dismissOnSelection = false)
        "browse" -> if (group != null) NovexSearchableSelectionSheet(group.folderPath(folderId), buildList {
            add(NovexSelectionAction("返回上一级", com.openminis.app.R.drawable.ic_phosphor_arrow_left) {
                if (folderId == null) page = "manage" else folderId = group.folders.firstOrNull { it.id == folderId }?.parentId
            })
            group.folders.filter { it.parentId == folderId }.forEach { folder ->
                add(NovexSelectionAction(folder.name, description = "文件夹", group = "文件夹") { folderId = folder.id })
            }
            group.contents(folderId).mapNotNull { visible[it] }.forEach { entry ->
                add(NovexSelectionAction(entry.title, description = entry.type, group = "内容") { subject = entry.address; page = "item" })
            }
            add(NovexSelectionAction("加入内容", group = "管理") { expectedMembers = group.members; page = "add" })
            add(NovexSelectionAction("新建文件夹", group = "管理") { name = ""; naming = "folder"; page = "name" })
            add(NovexSelectionAction("重命名", group = "管理") {
                name = if (folderId == null) group.name else group.folders.first { it.id == folderId }.name
                expectedName = name; naming = if (folderId == null) "rename" else "renameFolder"; page = "name"
            })
            if (folderId == null) {
                add(NovexSelectionAction("用于对话", group = "管理") { page = "conversations" })
                add(NovexSelectionAction("相关对话", group = "管理") { page = "related" })
                add(NovexSelectionAction("移除创作库", group = "管理") { page = "delete" })
            } else add(NovexSelectionAction("删除空文件夹", group = "管理") {
                execute { val parent = group.folders.first { it.id == folderId }.parentId; groups.removeFolder(groupId, folderId!!); folderId = parent }
            })
        }, "搜索当前文件夹", onDismissRequest = { page = "" }, dismissOnSelection = false)
        "add" -> NovexLibraryPicker("选择加入的内容", entries.filterNot { it.address in expectedMembers }, snapshot?.groups.orEmpty(),
            onDismiss = { page = "" }, onConfirm = { selected -> execute {
                groups.replaceMembers(groupId, expectedMembers, expectedMembers + selected, folderId)
                page = "browse"
            } })
        "item" -> NovexSelectionSheet(visible[subject]?.title ?: "内容已不可用", buildList {
            if (onOpenContent != null && subject in visible) add(NovexSelectionAction("打开") { page = ""; subject?.let(onOpenContent) })
            add(NovexSelectionAction("移动到文件夹") { page = "move" })
            add(NovexSelectionAction("从此库移出") { execute {
                val current = requireNotNull(group); groups.replaceMembers(groupId, current.members, current.members - requireNotNull(subject)); page = "browse"
            } })
        }, onDismissRequest = { page = "" })
        "move" -> NovexSearchableSelectionSheet("移动到", buildList {
            add(NovexSelectionAction(group?.name ?: "库根目录") { execute { groups.moveMembers(groupId, setOf(requireNotNull(subject)), folderId, null); page = "browse" } })
            group?.folders.orEmpty().forEach { target -> add(NovexSelectionAction(group!!.folderPath(target.id)) {
                execute { groups.moveMembers(groupId, setOf(requireNotNull(subject)), folderId, target.id); page = "browse" }
            }) }
        }, "搜索文件夹", onDismissRequest = { page = "" }, dismissOnSelection = false)
        "name" -> NovexContentDialog(when (naming) { "create" -> "新建创作库"; "folder" -> "新建文件夹"; else -> "重命名" },
            onDismiss = { page = if (groupId.isEmpty()) "manage" else "browse" }, confirmButton = {
                TextButton(enabled = !busy && name.isNotBlank(), onClick = { execute {
                    when (naming) {
                        "create" -> { groupId = groups.create(name); folderId = null }
                        "folder" -> groups.createFolder(groupId, folderId, name)
                        "rename" -> groups.rename(groupId, expectedName, name)
                        "renameFolder" -> groups.renameFolder(groupId, requireNotNull(folderId), expectedName, name)
                    }
                    page = "browse"
                } }) { Text(if (busy) "正在保存" else "保存") }
            }) { NovexTextField("名称", name, { name = it.take(120) }, placeholder = "简短、容易辨认的名称") }
        "delete" -> NovexContentDialog("移除创作库", onDismiss = { page = "browse" }, confirmButton = {
            TextButton(enabled = !busy, onClick = { execute { groups.dissolve(groupId); page = "manage" } }) { Text("移除") }
        }) { Text("移除“${group?.name.orEmpty()}”及其文件夹。卡片和文件原件保留，其他库中的收录不受影响。") }
        "related" -> NovexSearchableSelectionSheet("相关对话", buildList {
            conversations.forEach { conversation ->
                val used = group?.members.orEmpty().any { it in conversation.used }
                val managed = group?.members.orEmpty().any { it in conversation.managed }
                if (used || managed) add(NovexSelectionAction(conversation.title,
                    description = listOfNotNull(if (used) "使用设定" else null, if (managed) "创作管理" else null).joinToString(" · ")) {
                    page = ""; onConfigureConversation(conversation.id)
                })
            }
        }, "搜索对话", onDismissRequest = { page = "" }, dismissOnSelection = false)
        "conversations" -> NovexSearchableSelectionSheet("选择对话", conversations.map { conversation ->
            NovexSelectionAction(conversation.title) { page = ""; onConfigureConversation(conversation.id) }
        }, "搜索对话", onDismissRequest = { page = "" }, dismissOnSelection = false)
    }
}
