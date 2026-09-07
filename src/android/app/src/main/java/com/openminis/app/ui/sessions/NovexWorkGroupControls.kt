package com.openminis.app.ui.sessions

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class WorkMemberOption(val address: NovexContentAddress, val title: String, val category: String)
private fun NovexContentAddress.selectionKey() = "${kind.name}:$id"
private fun String.memberAddress() = NovexContentAddress(NovexContentKind.valueOf(substringBefore(':')), substringAfter(':'))

/** One persistent selection shared by all three existing libraries; no content mutation here. */
@Composable
internal fun NovexWorkGroupControls(snapshot: NovexWorkGroupSnapshot?, openMembersRequest: Int = 0,
    onConfigureConversation: (String) -> Unit) {
    val groups = rememberNovexWorkGroups()
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var expectedName by rememberSaveable { mutableStateOf("") }
    var nameReturn by rememberSaveable { mutableStateOf("select") }
    var draft by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var expected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var options by remember { mutableStateOf<List<WorkMemberOption>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val group = snapshot?.groups?.firstOrNull { it.id == groupId }
    val conversations by groups.conversations.collectAsState(initial = emptyList())
    LaunchedEffect(openMembersRequest) {
        if (openMembersRequest > 0) {
            val selected = snapshot?.selectedGroup
            if (selected == null) page = "select" else {
                groupId = selected.id; expected = selected.members.map { it.selectionKey() }; draft = expected; page = "members"
            }
        }
    }
    fun execute(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作未完成，请重试" }
            finally { busy = false }
        }
    }
    LaunchedEffect(page) {
        if (page == "detail" || page == "members") {
            try {
                options = workspace.worlds().map { WorkMemberOption(NovexContentAddress.world(it.world.id), it.world.name, "世界") } +
                    workspace.characters().flatMap { card -> card.character.allVersions.map { version ->
                        WorkMemberOption(NovexContentAddress.characterVersion(version.id),
                            "${card.character.character.name} · ${version.label}", "角色版本")
                    } } + workspace.interactiveFictions().map {
                        WorkMemberOption(NovexContentAddress.interactiveFiction(it.project.id), it.project.name, "文游")
                    }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "卡片列表暂不可读" }
        }
    }
    NovexSummaryRow("作品 · ${snapshot?.label ?: "正在读取"}", "跨世界、角色与文游筛选 · 选择和管理作品",
        onClick = { if (snapshot != null) page = "select" })
    if (error != null) {
        NovexContentDialog("操作未完成", onDismiss = { error = null },
            confirmButton = { TextButton(onClick = { error = null }) { Text("返回") } }) { Text(error.orEmpty()) }
        return
    }
    when (page) {
        "select" -> NovexSearchableSelectionSheet("选择作品", buildList {
            add(NovexSelectionAction("全部作品", selected = snapshot?.selection == NovexWorkGroupSnapshot.ALL) {
                execute { groups.select(NovexWorkGroupSnapshot.ALL) }
            })
            add(NovexSelectionAction("未归类", selected = snapshot?.selection == NovexWorkGroupSnapshot.UNCLASSIFIED) {
                execute { groups.select(NovexWorkGroupSnapshot.UNCLASSIFIED) }
            })
            snapshot?.groups.orEmpty().forEach { item -> add(NovexSelectionAction(item.name,
                description = "收录 ${item.members.size} 个世界、角色版本或文游", selected = snapshot?.selection == item.id) {
                execute { groups.select(item.id) }
            }) }
            add(NovexSelectionAction("新建作品", group = "管理") {
                groupId = ""; name = ""; nameReturn = "select"; page = "name"
            })
            add(NovexSelectionAction("管理作品", group = "管理") { page = "manage" })
        }, "搜索作品", onDismissRequest = { page = "" })
        "manage" -> NovexSearchableSelectionSheet("管理作品", snapshot?.groups.orEmpty().map { item ->
            NovexSelectionAction(item.name, description = "收录 ${item.members.size} 项 · 更名、批量归类、解散分组") {
                groupId = item.id; options = null; page = "detail"
            }
        }, "搜索作品", onDismissRequest = { page = "select" })
        "detail" -> NovexContentDialog(group?.name ?: "作品已不存在", onDismiss = { page = "manage" },
            confirmButton = { TextButton(onClick = { page = "manage" }) { Text("返回作品管理") } }) {
            if (group != null) {
                val available = options?.map { it.address }?.toSet()
                Text(if (available == null) "正在核对收录卡片" else
                    "收录 ${group.members.size} 项，其中 ${group.members.count { it in available }} 项当前可用。角色按具体版本收录。")
                NovexSummaryRow("批量加入或移出", "勾选世界、具体角色版本和文游；保存后只改变本作品归类", onClick = {
                    expected = group.members.map { it.selectionKey() }; draft = expected; page = "members"
                })
                NovexSummaryRow("更名", group.name, onClick = {
                    name = group.name; expectedName = group.name; nameReturn = "detail"; page = "name"
                })
                NovexSummaryRow("用于已有对话", "先选择对话，再分别选择身份、背景、文游或管理对象；不自动启用整组卡片", onClick = { page = "conversations" })
                NovexSummaryRow("相关对话", "按实际使用和创作管理关系查找，对话可关联多个作品", onClick = { page = "related" })
                NovexSummaryRow("解散作品分组", "仅移除这个分组，不删除卡片或其他作品中的收录", onClick = { page = "delete" })
            }
        }
        "name" -> NovexContentDialog(if (groupId.isEmpty()) "新建作品" else "更名作品", onDismiss = { page = nameReturn },
            confirmButton = { TextButton(onClick = { execute {
                if (groupId.isEmpty()) groupId = groups.create(name) else groups.rename(groupId, expectedName, name)
                page = "detail"
            } }, enabled = !busy && name.isNotBlank()) { Text(if (busy) "正在保存" else "保存") } }) {
            NovexTextField(label = "作品名称", value = name, onValueChange = { name = it }, placeholder = "作品名称")
            Text("分组用于收纳卡片；选择作品不会自动启用设定或授予编辑权限。")
        }
        "members" -> {
            val available = options
            if (available == null) NovexContentDialog("读取卡片列表", onDismiss = { page = "detail" },
                confirmButton = { TextButton(onClick = { page = "detail" }) { Text("返回") } }) { Text("正在读取") }
            else NovexSearchableSelectionSheet("${group?.name ?: "作品"} · 批量归类", buildList {
                available.forEach { option ->
                    val key = option.address.selectionKey()
                    add(NovexSelectionAction(option.title, group = option.category, selected = key in draft, enabled = !busy) {
                        draft = if (key in draft) draft - key else draft + key
                    })
                }
                expected.filter { key -> available.none { it.address.selectionKey() == key } }.forEach { key ->
                    add(NovexSelectionAction("原收录卡片目前不可用", description = key, group = "缺失来源", selected = key in draft, enabled = !busy) {
                        draft = if (key in draft) draft - key else draft + key
                    })
                }
            }, "搜索世界、角色版本或文游", onDismissRequest = { if (!busy) page = "detail" }, dismissOnSelection = false,
                onConfirmSelection = { execute {
                    groups.replaceMembers(groupId, expected.map { it.memberAddress() }.toSet(), draft.map { it.memberAddress() }.toSet())
                    page = "detail"
                } }, confirmLabel = if (busy) "正在保存" else "保存 ${draft.size} 项收录")
        }
        "delete" -> NovexContentDialog("解散作品分组", onDismiss = { page = "detail" },
            confirmButton = { TextButton(onClick = { execute { groups.dissolve(groupId); page = "manage" } }, enabled = !busy) { Text("解散分组") } }) {
            Text("解散“${group?.name.orEmpty()}”会移除本分组的 ${group?.members?.size ?: 0} 项收录关系。卡片原件和其他分组中的收录保留。")
        }
        "conversations" -> NovexSearchableSelectionSheet("选择用于配置的对话", conversations.map { conversation ->
            NovexSelectionAction(conversation.title, description = "打开对话配置，逐项选择 ${group?.name.orEmpty()} 中的卡片") {
                execute { groups.select(groupId); page = ""; onConfigureConversation(conversation.id) }
            }
        }.ifEmpty { listOf(NovexSelectionAction("尚无对话", description = "先在对话页新建，再回此处选择", enabled = false) {}) },
            "按对话名称查找", onDismissRequest = { page = "detail" })
        "related" -> NovexSearchableSelectionSheet("${group?.name.orEmpty()} · 相关对话", buildList {
            for ((category, related) in listOf("使用设定或身份" to conversations.filter { row -> group?.members.orEmpty().any { it in row.used } },
                    "创作管理" to conversations.filter { row -> group?.members.orEmpty().any { it in row.managed } })) {
                related.forEach { row -> add(NovexSelectionAction(row.title, group = category,
                    description = "查看该对话的实际配置；对话不属于唯一作品目录") { onConfigureConversation(row.id) }) }
            }
        }.ifEmpty { listOf(NovexSelectionAction("尚无关联对话", description = "可先通过“用于已有对话”选择具体卡片", enabled = false) {}) },
            "搜索关联对话", onDismissRequest = { page = "detail" })
    }
}
