package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.openminis.app.ui.novex.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardPackageCodec
import com.openminis.app.data.character.NovexCardPackagePreview
import com.openminis.app.data.character.NovexCharacterImportDocument
import com.openminis.app.data.character.NovexInteractiveFictionImportDocument
import com.openminis.app.data.character.NovexValidatedCardImport
import com.openminis.app.data.character.NovexWorldImportDocument
import com.openminis.app.ui.novex.NovexOutlineButton
import com.openminis.app.ui.novex.NovexPrimaryButton
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import java.io.File

@Composable
internal fun NovexCardImportPreviewDialog(
    preview: NovexValidatedCardImport,
    importing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val summary = when (val document = preview.document) {
        is NovexWorldImportDocument -> listOf(
            "${document.modules.size} 个内容模块",
            "${document.characterVersionLinks.size} 个角色版本引用",
            "${preview.media.size} 张图片",
        )
        is NovexCharacterImportDocument -> listOf(
            "${document.versions.size} 个角色版本",
            "${document.versions.sumOf { it.modules.size }} 个内容模块",
            "${preview.media.size} 张图片",
        )
        is NovexInteractiveFictionImportDocument -> listOf(
            "${document.modules.size} 个内容模块",
            document.launchMode.displayName,
            "${preview.media.size} 张图片",
        )
    }
    val bundle = org.json.JSONObject(preview.document.originalJson).optJSONObject("referenceBundle")
    val dependencyNames = bundle?.optJSONArray("cards")?.let { cards ->
        (0 until cards.length()).mapNotNull { index -> cards.optJSONObject(index)?.takeIf {
            it.optString("key") != bundle.optString("root")
        }?.optString("name") }
    }.orEmpty()
    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        shape = RoundedCornerShape(20.dp),
        title = { Text("导入预览：${preview.displayName}") },
        text = {
            Column {
                Text(
                    when (preview.document) {
                        is NovexWorldImportDocument -> "世界卡"
                        is NovexCharacterImportDocument -> "角色卡"
                        is NovexInteractiveFictionImportDocument -> "文游卡"
                    },
                    color = MaterialTheme.colorScheme.primary,
                )
                summary.forEach { line -> Text(line, modifier = Modifier.padding(top = 8.dp)) }
                (preview.document as? NovexCharacterImportDocument)?.versions?.firstOrNull()?.let { version ->
                    com.openminis.app.novex.domain.NovexTavernExchange.sourceSummary(version.profileJson)?.let { message ->
                        Text(message, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (bundle != null) {
                    Text("同时导入 ${dependencyNames.size} 张依赖卡片、${bundle.optJSONArray("references")?.length() ?: 0} 条带用途引用。",
                        modifier = Modifier.padding(top = 8.dp))
                    if (dependencyNames.isNotEmpty()) Text(dependencyNames.take(12).joinToString("、") +
                        if (dependencyNames.size > 12) "等" else "", modifier = Modifier.padding(top = 4.dp))
                    Text("包内依赖使用新编号相互连接；缺失目标保留为待修复引用，不按重名匹配。",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
                Text(
                    "确认前不会写入数据库；重名默认创建独立副本。导入不会执行模型、工具或其他外部操作。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        },
        confirmButton = {
            NovexPrimaryButton(
                label = if (importing) "导入中" else "确认导入",
                enabled = !importing,
                onClick = onConfirm,
            )
        },
        dismissButton = {
            NovexOutlineButton(label = "取消", enabled = !importing, onClick = onDismiss)
        },
    )
}

internal fun shareNovexCardPackage(context: Context, card: NovexCardPackagePreview) {
    val safeName = card.displayName.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fff._-]+"), "-")
        .trim('-')
        .ifBlank {
            when (card.kind) {
                NovexCardKind.WORLD -> "world"
                NovexCardKind.CHARACTER -> "character"
                NovexCardKind.GAME -> "game"
            }
        }
    val directory = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(directory, "$safeName.${card.kind.extension}")
    file.writeBytes(NovexCardPackageCodec.encode(card))
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "导出 ${card.displayName}",
        ),
    )
}
