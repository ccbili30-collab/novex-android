package com.openminis.app.ui.novex

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import com.openminis.app.R
import com.openminis.app.novex.domain.*

/** A browser of user-visible sources. Navigation never adopts content or changes global filters. */
@Composable
internal fun NovexLibraryPicker(
    title: String,
    entries: List<NovexLibraryEntry>,
    libraries: List<NovexWorkGroup>,
    selected: Set<NovexContentAddress> = emptySet(),
    multiple: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (Set<NovexContentAddress>) -> Unit,
) {
    var source by rememberSaveable { mutableStateOf("") }
    var libraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by rememberSaveable(stateSaver = listSaver<Set<NovexContentAddress>, String>(
        save = { values -> values.map { "${it.kind.name}:${it.id}" } },
        restore = { values -> values.map { NovexContentAddress(NovexContentKind.valueOf(it.substringBefore(':')), it.substringAfter(':')) }.toSet() },
    )) { mutableStateOf(selected) }
    val library = libraries.firstOrNull { it.id == libraryId }
    val available = entries.map { it.address }.toSet()
    fun back() {
        when {
            folderId != null -> folderId = library?.folders?.firstOrNull { it.id == folderId }?.parentId
            libraryId != null -> libraryId = null
            source == "files" -> source = "libraries"
            source.isNotEmpty() -> source = ""
            else -> onDismiss()
        }
    }
    val visible = when {
        library != null -> entries.filter { it.address in library.contents(folderId) }
        source == "files" -> entries.filter { it.address.kind == NovexContentKind.CREATIVE_ARTIFACT }
        else -> entries.filter { it.address.kind.name == source }
    }
    val caption = when {
        library != null -> library.folderPath(folderId)
        source == "libraries" -> "创作库"
        source == "files" -> "全部文件"
        source == NovexContentKind.WORLD.name -> "世界库"
        source == NovexContentKind.CHARACTER_VERSION.name -> "角色库"
        source == NovexContentKind.INTERACTIVE_FICTION.name -> "文游库"
        else -> title
    }
    val actions = buildList {
        if (source.isNotEmpty()) add(NovexSelectionAction("返回上一级", R.drawable.ic_phosphor_arrow_left) { back() })
        when {
            source.isEmpty() -> {
                listOf(NovexContentKind.WORLD to "世界库", NovexContentKind.CHARACTER_VERSION to "角色库",
                    NovexContentKind.INTERACTIVE_FICTION to "文游库").forEach { (kind, label) ->
                    val count = entries.count { it.address.kind == kind }
                    if (count > 0) add(NovexSelectionAction(label, description = "$count 项") { source = kind.name })
                }
                add(NovexSelectionAction("创作库", description = "按库或文件夹查找") { source = "libraries" })
            }
            source == "libraries" && library == null -> {
                libraries.forEach { group -> add(NovexSelectionAction(group.name,
                    description = "${group.members.count { it in available }} 项内容") { libraryId = group.id; folderId = null }) }
                if (entries.any { it.address.kind == NovexContentKind.CREATIVE_ARTIFACT }) {
                    add(NovexSelectionAction("全部文件", group = "文件") { source = "files" })
                }
            }
            else -> {
                library?.folders?.filter { it.parentId == folderId }?.forEach { folder ->
                    add(NovexSelectionAction(folder.name, description = "文件夹", group = "文件夹") { folderId = folder.id })
                }
                if (multiple && library != null) {
                    val contents = library.contents(folderId, recursive = true).intersect(available)
                    if (contents.isNotEmpty()) add(NovexSelectionAction("选择这里的全部内容", description = "当前共 ${contents.size} 项",
                        selected = selection.containsAll(contents)) {
                        selection = if (selection.containsAll(contents)) selection - contents else selection + contents
                    })
                }
                visible.forEach { entry -> add(NovexSelectionAction(entry.title, description = entry.type,
                    group = if (library != null) "内容" else "", selected = entry.address in selection) {
                    if (multiple) selection = if (entry.address in selection) selection - entry.address else selection + entry.address
                    else onConfirm(setOf(entry.address))
                }) }
            }
        }
    }
    // Re-key only the browser's search field on navigation; selection remains outside it.
    key(source, libraryId, folderId) {
        NovexSearchableSelectionSheet(caption, actions, "搜索名称", onDismissRequest = ::back,
            dismissOnSelection = false, onConfirmSelection = if (multiple) ({ onConfirm(selection.intersect(available)) }) else null,
            confirmLabel = "完成（已选 ${selection.intersect(available).size} 项）", onCancelSelection = onDismiss)
    }
}
