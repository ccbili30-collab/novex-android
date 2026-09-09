package com.openminis.app.ui.novex

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.MinisApp
import com.openminis.app.novex.adapter.NovexCardImageSource
import com.openminis.app.novex.domain.CreativeArtifactKind
import kotlinx.coroutines.launch

/** One temporary flow, one preview, one explicit save; dismissing never leaves another sheet open. */
@Composable
internal fun NovexModuleImagePicker(onDismiss: () -> Unit, onSave: suspend (NovexCardImageSource.Image) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val scope = rememberCoroutineScope()
    val source = remember { NovexCardImageSource() }
    var page by remember { mutableStateOf("source") }
    var link by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<NovexCardImageSource.Image?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alive by remember { mutableStateOf(true) }
    var choices by remember { mutableStateOf<List<com.openminis.app.novex.domain.NovexCreativeArtifactSummary>>(emptyList()) }
    DisposableEffect(Unit) { onDispose { alive = false } }
    fun close() { if (!busy || page != "saving") { alive = false; onDismiss() } }
    fun load(action: suspend () -> NovexCardImageSource.Image) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            runCatching { action() }.onSuccess { if (alive) { candidate = it; page = "preview" } }
                .onFailure { if (alive) { error = it.message ?: "图片读取失败，请重试"; if (page == "library") page = "source" } }
            busy = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load { source.fromUri(context, uri) }
    }
    when (page) {
        "source" -> NovexContentDialog("添加图片", onDismiss = ::close, confirmButton = {}, dismissButton = { TextButton(onClick = ::close) { Text("取消") } }) {
            NovexTextActionRow("从设备选择", onClick = { if (!busy) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            NovexTextActionRow("从创作库选择", onClick = {
                if (!busy) {
                    busy = true; error = null
                    scope.launch {
                        runCatching { app.creativeArtifactRepository.availableArtifacts().filter { it.kind == CreativeArtifactKind.IMAGE || it.kind == CreativeArtifactKind.MAP } }
                            .onSuccess { if (alive) { choices = it; page = "library" } }
                            .onFailure { if (alive) error = "暂时无法读取创作库，请重试" }
                        busy = false
                    }
                }
            })
            NovexTextActionRow("粘贴图片直链", onClick = { if (!busy) page = "link" })
            if (busy) Text("正在读取图片")
            error?.let { Text(it) }
        }
        "library" -> NovexSearchableSelectionSheet("创作库图片", choices.map { choice ->
            NovexSelectionAction(choice.title) { load { source.fromFile(app.creativeArtifactRepository.file(choice.address.id)) } }
        }, "搜索图片名称", onDismissRequest = ::close, dismissOnSelection = false,
            onCancelSelection = ::close, confirmLabel = if (busy) "正在读取" else "返回", onConfirmSelection = { if (!busy) page = "source" })
        "link" -> NovexContentDialog("图片直链", onDismiss = ::close, dismissButton = { TextButton(onClick = ::close) { Text("取消") } }, confirmButton = {
            TextButton(enabled = !busy && link.isNotBlank(), onClick = { load { source.fromLink(link) } }) { Text(if (busy) "正在读取" else "预览图片") }
        }) {
            NovexTextField("图片地址", link, { link = it }, modifier = Modifier.fillMaxWidth())
            Text("保存时复制到这张卡片中。", style = NovexType.Metadata)
            error?.let { Text(it) }
        }
        "preview", "saving" -> NovexContentDialog("图片预览", onDismiss = ::close, dismissButton = { TextButton(enabled = page != "saving", onClick = ::close) { Text("取消") } }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!busy) { busy = true; page = "saving"; error = null
                    scope.launch {
                        runCatching { onSave(requireNotNull(candidate)) }.onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "图片保存失败，请重试"; page = "preview" }
                        busy = false
                    }
                }
            }) { Text(if (busy) "正在保存" else "保存图片") }
        }) {
            AsyncImage(model = candidate?.bytes, contentDescription = "待保存的图片", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp))
            Text("图片属于当前卡片；原来源删除后仍可使用。", style = NovexType.Metadata)
            error?.let { Text(it) }
        }
    }
}
