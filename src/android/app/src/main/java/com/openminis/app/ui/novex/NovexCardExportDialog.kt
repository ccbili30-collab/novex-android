package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardPackagePreview
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.settings.shareNovexCardPackage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun NovexCardExportDialog(root: NovexCardCopyKey, onDismiss: () -> Unit) {
    val workspace = rememberNovexWorkspace()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var versions by remember(root) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember(root) { mutableStateOf<Set<String>>(emptySet()) }
    var dependencies by remember(root) { mutableStateOf(false) }
    var prepared by remember(root) { mutableStateOf<NovexCardPackagePreview?>(null) }
    var busy by remember(root) { mutableStateOf(false) }
    var loading by remember(root) { mutableStateOf(true) }
    var error by remember(root) { mutableStateOf<String?>(null) }
    LaunchedEffect(root) {
        try {
            if(root.kind == NovexCardKind.CHARACTER) {
                versions = requireNotNull(workspace.character(root.id)) { "角色不存在" }.character.allVersions.map { it.id to it.label }
                selected = versions.map { it.first }.toSet()
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { error = failure.message ?: "无法读取卡片" }
        finally { loading = false }
    }
    NovexContentDialog("导出卡片预览", onDismiss = { if(!busy) onDismiss() }, confirmButton = {
        TextButton(enabled = !loading && !busy && (root.kind != NovexCardKind.CHARACTER || selected.isNotEmpty()), onClick = {
            val snapshot = prepared
            if(snapshot != null) {
                try { shareNovexCardPackage(context, snapshot); onDismiss() }
                catch(failure: Exception) { error = failure.message ?: "无法分享导出文件" }
            } else {
                busy = true
                scope.launch {
                    try { prepared = withContext(Dispatchers.IO) { workspace.apply(NovexCommand.ExportNativeSelection(
                        root, selected.takeIf { root.kind == NovexCardKind.CHARACTER }, dependencies)).requireNativeCard() } }
                    catch(cancelled: CancellationException) { throw cancelled }
                    catch(failure: Exception) { error = failure.message ?: "无法准备导出" }
                    finally { busy = false }
                }
            }
        }) { Text(if(busy) "正在核对" else if(prepared == null) "生成预览" else "分享此预览包") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } }) {
        if(loading) Text("正在读取版本…")
        Text("包含所选卡片的完整模块、图片和专属扮演/玩家资料；请核对范围后分享。", color = NovexColors.SecondaryText)
        TextButton(enabled = !busy, onClick = { dependencies = !dependencies; prepared = null }) {
            Text(if(dependencies) "已选择：连带引用依赖" else "当前：仅选中卡片，包外引用保留为缺项")
        }
        if(root.kind == NovexCardKind.CHARACTER) {
            TextButton(enabled = !busy, onClick = { selected = if(selected.size == versions.size) emptySet() else versions.map { it.first }.toSet(); prepared = null }) {
                Text("角色版本 ${selected.size}/${versions.size} · 全选/清空")
            }
            androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                versions.forEach { (id, label) -> TextButton(enabled = !busy, onClick = {
                    selected = if(id in selected) selected - id else selected + id; prepared = null
                }) { Text("${if(id in selected) "已选择" else "未选择"} · $label") } }
            }
            Text("未选本体时，所选的首个版本作为导入后的默认版本；来源阶段与分身关系另行保留。", color = NovexColors.SecondaryText)
        }
        prepared?.let { card ->
            val bundle = JSONObject(card.documentJson).getJSONObject("referenceBundle")
            val rows = bundle.getJSONArray("cards")
            val addresses = (0 until rows.length()).flatMap { i -> rows.getJSONObject(i).getJSONArray("addresses").let { values ->
                (0 until values.length()).map { n -> values.getJSONObject(n).let { "${it.getString("kind")}:${it.getString("id")}" } }
            } }.toSet()
            val refs = bundle.getJSONArray("references")
            val unresolved = (0 until refs.length()).count { i ->
                val ref = NovexCardReferenceCodec.decode(refs.getJSONObject(i).toString())
                "${ref.target.subject.kind}:${ref.target.subject.id}" !in addresses || ref.unresolvedTarget != null
            }
            Text("${rows.length()} 张卡片 · ${addresses.count { it.startsWith("CHARACTER_VERSION:") }} 个角色版本 · ${card.media.size} 个图片条目")
            Text("带用途引用中有 $unresolved 条目标未纳入或已标为缺失；旧式引用、模块和条目缺项见下方说明。")
            val diagnostics = JSONObject(card.documentJson).optJSONArray("_novexExportDiagnostics")
            if(diagnostics != null && diagnostics.length() > 0) Text((0 until diagnostics.length()).joinToString("\n") { diagnostics.getString(it) },
                modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
            Text((0 until rows.length()).joinToString("\n") { i -> rows.getJSONObject(i).let { "${it.getString("name")} · ${it.getJSONArray("addresses").length()} 个对象" } },
                modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
            Text("这是已保存的导出内容快照，分享不会重新读取原卡。依赖角色会包含其全部版本；未知扩展原样保留。包外旧式引用保留来源信息，不按重名连接本地卡。", color = NovexColors.SecondaryText)
            TextButton(enabled = !busy, onClick = { prepared = null }) { Text("重新核对当前原卡") }
        }
        error?.let { Text(it, color = NovexColors.SecondaryText) }
    }
}
