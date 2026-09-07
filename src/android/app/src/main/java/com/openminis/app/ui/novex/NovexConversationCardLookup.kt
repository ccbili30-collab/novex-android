package com.openminis.app.ui.novex

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.openminis.app.novex.domain.*

/** Card lookup on the peer conversation page; it does not assign a conversation to a work group. */
@Composable
internal fun NovexConversationCardLookup(onOpenSession: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf<String?>(null) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetLabel by rememberSaveable { mutableStateOf("") }
    NovexTextActionRow("按卡片查找对话", onClick = { open = true })
    if (!open) return
    val directory = rememberNovexWorkGroups()
    val workspace = rememberNovexWorkspace()
    val conversations by directory.conversations.collectAsState(initial = null)
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val labels by produceState<Map<NovexContentAddress, String>?>(null, workspace, attempt) {
        error = null
        try {
            value = buildMap {
                workspace.worlds().forEach { put(NovexContentAddress.world(it.world.id), "世界 · ${it.world.name}") }
                workspace.characters().forEach { card -> card.character.allVersions.forEach { version ->
                    put(NovexContentAddress.characterVersion(version.id), "角色 · ${card.character.character.name} · ${version.label}")
                } }
                workspace.interactiveFictions().forEach { put(NovexContentAddress.interactiveFiction(it.project.id), "文游 · ${it.project.name}") }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "卡片目录暂不可用" }
    }
    fun subjects(row: NovexWorkConversation) = if (mode == "used") row.used else row.managed
    val target = kind?.let { name -> targetId?.let { NovexContentAddress(NovexContentKind.valueOf(name), it) } }
    if (mode == null) NovexSearchableSelectionSheet("按哪种关系查找", listOf(
        NovexSelectionAction("使用它", description = "回答身份、正在使用的背景或活动文游") { mode = "used" },
        NovexSelectionAction("创作管理它", description = "管理、编辑或创建此卡；不代表已作为背景") { mode = "managed" },
    ), "选择查找用途", onDismissRequest = { open = false }, dismissOnSelection = false)
    else if (target == null) {
        val addresses = (labels.orEmpty().keys + conversations.orEmpty().flatMap { subjects(it) }).distinct()
        val choices = addresses.map { address ->
            val label = labels?.get(address) ?: "原卡已不可用 · ${address.id}"
            val count = conversations.orEmpty().count { address in subjects(it) }
            NovexSelectionAction(label, description = "$count 个对话 · 编号 ${address.id}") {
                kind = address.kind.name; targetId = address.id; targetLabel = label
            }
        }.ifEmpty { listOf(NovexSelectionAction(if (conversations == null || labels == null) "正在读取卡片与对话" else "当前没有可查找的卡片", enabled = false) {}) }
        NovexSearchableSelectionSheet(if (mode == "used") "选择使用的卡片" else "选择创作管理的卡片",
            when {
                error != null -> listOf(NovexSelectionAction("重新读取卡片目录", description = error.orEmpty()) { attempt++ })
                labels == null || conversations == null -> listOf(NovexSelectionAction("正在读取卡片与对话", enabled = false) {})
                else -> choices
            },
            "搜索卡片名称或编号", onDismissRequest = { mode = null }, dismissOnSelection = false)
    } else {
        val rows = conversations.orEmpty().filter { target in subjects(it) }
        NovexSearchableSelectionSheet("${if (mode == "used") "使用" else "创作管理"} · $targetLabel",
            rows.map { row -> NovexSelectionAction(row.title, description = "打开原对话，保留其身份和工作区") {
                open = false; kind = null; targetId = null; onOpenSession(row.id)
            } }.ifEmpty { listOf(NovexSelectionAction(if (conversations == null) "正在读取关联" else "没有这类关联对话", enabled = false) {}) },
            "搜索对话名称", onDismissRequest = { kind = null; targetId = null }, dismissOnSelection = false)
    }
}
