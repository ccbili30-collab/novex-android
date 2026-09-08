package com.openminis.app.ui.creative

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.*

@Composable
internal fun NovexLibraryContents(group: NovexWorkGroup, entries: List<NovexLibraryEntry>, onOpen: (NovexContentAddress) -> Unit) {
    var folderId by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(group.folders) { if (folderId != null && group.folders.none { it.id == folderId }) folderId = null }
    val folders = group.folders.filter { it.parentId == folderId }
    val contents = entries.filter { it.address in group.contents(folderId) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (folderId != null) item {
            NovexTextActionRow("返回上一级", com.openminis.app.R.drawable.ic_phosphor_arrow_left, onClick = {
                folderId = group.folders.firstOrNull { it.id == folderId }?.parentId
            })
            Text(group.folderPath(folderId), style = NovexType.Metadata, color = NovexColors.SecondaryText, modifier = Modifier.padding(16.dp))
        }
        items(folders, key = { "folder:${it.id}" }) { folder ->
            NovexSummaryRow(folder.name, "文件夹", onClick = { folderId = folder.id })
            NovexDivider(Modifier.padding(horizontal = 16.dp))
        }
        items(contents, key = { "${it.address.kind}:${it.address.id}" }) { entry ->
            NovexSummaryRow(entry.title, entry.type, onClick = { onOpen(entry.address) })
            NovexDivider(Modifier.padding(horizontal = 16.dp))
        }
        if (folders.isEmpty() && contents.isEmpty()) item {
            Text("这里还没有内容", style = NovexType.Body, color = NovexColors.SecondaryText, modifier = Modifier.padding(24.dp))
        }
    }
}
