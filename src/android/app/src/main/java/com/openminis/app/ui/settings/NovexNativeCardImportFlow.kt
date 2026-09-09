package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardPackageCodec
import com.openminis.app.data.character.NovexCardTransferParser
import com.openminis.app.data.character.NovexValidatedCardImport
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.requireNativeImport
import com.openminis.app.ui.novex.NovexNoticeDialog
import com.openminis.app.ui.novex.rememberNovexWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class NovexNativeCardImportSpec(
    val kind: NovexCardKind,
    val label: String,
    val extensionLabel: String,
    val mimeTypes: List<String>,
)

internal fun novexNativeCardImportSpec(kind: NovexCardKind): NovexNativeCardImportSpec =
    NovexNativeCardImportSpec(
        kind = kind,
        label = when (kind) {
            NovexCardKind.WORLD -> "导入世界卡"
            NovexCardKind.CHARACTER -> "导入角色卡"
            NovexCardKind.GAME -> "导入文游卡"
        },
        extensionLabel = when (kind) {
            NovexCardKind.WORLD -> ".${kind.extension} 或任意可读设定文本"
            NovexCardKind.CHARACTER -> ".${kind.extension}、酒馆图片卡或任意可读角色文本"
            NovexCardKind.GAME -> ".${kind.extension}"
        },
        mimeTypes = listOf("application/zip", "application/octet-stream") +
            if (kind != NovexCardKind.GAME) listOf("*/*", "image/png", "application/json", "text/plain") else emptyList(),
    )

internal data class NovexNativeCardImporter(
    val importing: Boolean,
    val launch: () -> Unit,
)

/**
 * Shared native-card import flow used by both Novex library roots.
 *
 * Decoding and validation are read-only. The database is touched only after the user confirms
 * the preview, through the Novex domain command boundary.
 */
@Composable
internal fun rememberNovexNativeCardImporter(
    kind: NovexCardKind,
    onImported: (String) -> Unit,
): NovexNativeCardImporter {
    val context = LocalContext.current
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    val spec = remember(kind) { novexNativeCardImportSpec(kind) }
    val currentOnImported by rememberUpdatedState(onImported)
    var importing by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<NovexValidatedCardImport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size().toLong() + count <= 64L * 1024 * 1024) { "卡片文件超过 64 MiB（兆二进制字节）" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                        ?: error("无法读取${spec.label.removePrefix("导入")}")
                    val fileName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    com.openminis.app.novex.domain.NovexExternalCardImport.decode(kind, bytes, fileName.orEmpty())
                }
            }.onSuccess {
                preview = it
                importing = false
            }.onFailure {
                importing = false
                error = it.message ?: "${spec.label}预览失败"
            }
        }
    }

    preview?.let { validated ->
        NovexCardImportPreviewDialog(
            preview = validated,
            importing = importing,
            onDismiss = { preview = null },
            onConfirm = {
                scope.launch {
                    importing = true
                    runCatching {
                        workspace.apply(NovexCommand.ImportNativeCard(validated)).requireNativeImport()
                    }.onSuccess { imported ->
                        preview = null
                        importing = false
                        currentOnImported(imported.localId)
                    }.onFailure {
                        importing = false
                        error = it.message ?: "${spec.label}失败"
                    }
                }
            },
        )
    }
    error?.let { message ->
        NovexNoticeDialog("${spec.label}失败", message) { error = null }
    }

    return NovexNativeCardImporter(
        importing = importing,
        launch = { picker.launch(spec.mimeTypes.toTypedArray()) },
    )
}
