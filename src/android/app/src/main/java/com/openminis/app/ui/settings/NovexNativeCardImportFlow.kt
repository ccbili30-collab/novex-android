package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
        label = if (kind == NovexCardKind.WORLD) "导入世界卡" else "导入角色卡",
        extensionLabel = ".${kind.extension}",
        mimeTypes = listOf("application/zip", "application/octet-stream"),
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
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取${spec.label.removePrefix("导入")}")
                    val packagePreview = NovexCardPackageCodec.decode(bytes)
                    require(packagePreview.kind == spec.kind) {
                        "请选择 ${spec.extensionLabel} ${spec.label.removePrefix("导入")}"
                    }
                    NovexCardTransferParser.parse(packagePreview)
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
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("${spec.label}失败") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("知道了") }
            },
        )
    }

    return NovexNativeCardImporter(
        importing = importing,
        launch = { picker.launch(spec.mimeTypes.toTypedArray()) },
    )
}
