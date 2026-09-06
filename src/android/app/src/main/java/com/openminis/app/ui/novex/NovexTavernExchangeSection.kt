package com.openminis.app.ui.novex

import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.openminis.app.novex.domain.NovexTavernExchange
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NovexTavernExchangeSection(versionId: String, profileJson: String) {
    val workspace = rememberNovexWorkspace()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(versionId) { mutableStateOf(false) }
    var error by remember(versionId) { mutableStateOf<String?>(null) }
    fun export(original: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val text = if (original) requireNotNull(NovexTavernExchange.originalSource(profileJson)) { "没有保存酒馆原件" }
                        else NovexTavernExchange.exportCharacter(workspace, versionId)
                    File(File(context.cacheDir, "share").apply { mkdirs() }, "tavern-${UUID.randomUUID()}.json")
                        .apply { writeText(text) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, if (original) "导出酒馆原始数据" else "导出当前版本的酒馆角色卡"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "导出酒馆角色卡失败" }
            finally { busy = false }
        }
    }
    NovexContentSection("酒馆交换", subtitle = "按当前人物版本交换，不含私人对话和本局存档") {
        Text(NovexTavernExchange.sourceSummary(profileJson) ?: NovexTavernExchange.COMPATIBILITY, color = NovexColors.SecondaryText)
        Text("导出为 JSON（结构化数据），不打包头像。未映射的公开模块转为常驻世界书条目；配套玩家身份不自动导出。", color = NovexColors.SecondaryText)
        if (busy) Text("正在准备导出…", color = NovexColors.SecondaryText)
        NovexTextActionRow("导出当前版本的酒馆角色卡", onClick = { export(false) })
        if (NovexTavernExchange.originalSource(profileJson) != null)
            NovexTextActionRow("导出保留的酒馆原始数据", onClick = { export(true) })
    }
    error?.let { NovexNoticeDialog("酒馆交换", it) { error = null } }
}
