package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.MinisApp
import com.openminis.app.ui.creative.CreativeLibraryScreen
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.FilePreviewScreen

/** Open existing file browser/preview in a child surface, keeping the unsaved settings draft alive. */
@Composable
internal fun ConversationWorkspaceFiles(sessionId: String) {
    val app = LocalContext.current.applicationContext as MinisApp
    var browsing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    NovexTextActionRow("打开对话仓库", onClick = { browsing = true })
    if (browsing) Dialog(onDismissRequest = { browsing = false }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CreativeLibraryScreen(app.creativeArtifactRepository, app.creativeArtifactDeviceDirectory, app.novexWorkspace,
            sessionId, onBack = { browsing = false }, onOpenArtifact = { record, file ->
                preview = FileItem(file, com.openminis.app.novex.domain.NovexDisplayName.file(record.artifact.title), false, false, file.length(), record.artifact.updatedAt)
            })
    }
    preview?.let { item -> Dialog(onDismissRequest = { preview = null }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FilePreviewScreen(item, onBack = { preview = null })
    } }
}
