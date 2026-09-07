package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun NovexWorldParallelSection(worldId: String, onReturnToLibrary: () -> Unit) {
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var open by remember(worldId) { mutableStateOf(false) }
    var plan by remember(worldId) { mutableStateOf<NovexWorldParallelPlan?>(null) }
    var selected by remember(worldId) { mutableStateOf<Set<String>>(emptySet()) }
    var name by remember(worldId) { mutableStateOf("") }
    var series by remember(worldId) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var revision by remember(worldId) { mutableIntStateOf(0) }
    var busy by remember(worldId) { mutableStateOf(false) }
    var error by remember(worldId) { mutableStateOf<String?>(null) }
    var result by remember(worldId) { mutableStateOf<NovexWorldParallelResult?>(null) }
    LaunchedEffect(worldId, revision) {
        try {
            fun seriesId(raw: String?) = runCatching { JSONObject(raw ?: "{}").optJSONObject("_novexWorldSeries")?.optString("id") }.getOrNull()
            val world = workspace.world(worldId)
            if(name.isBlank()) name = "${world?.world?.name.orEmpty()}·平行路线"
            val id = seriesId(world?.world?.legacySnapshotJson)
            series = if(id.isNullOrBlank()) emptyList() else workspace.worlds().filter { seriesId(it.world.legacySnapshotJson) == id }.map { it.world.id to it.world.name }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { error = failure.message ?: "读取世界系列失败" }
    }
    fun prepare() {
        busy = true; plan = null
        scope.launch {
            try { plan = withContext(Dispatchers.IO) { workspace.prepareWorldParallel(worldId) }; selected = emptySet(); open = true }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { error = failure.message ?: "无法准备平行世界" }
            finally { busy = false }
        }
    }
    NovexContentSection("平行世界系列", subtitle = "各路线独立保存；人物知识和记忆不会自动同步") {
        series.forEach { (id, title) -> NovexSummaryRow(title, if(id == worldId) "当前世界" else "同系列独立世界 · $id") }
        NovexTextActionRow(if(busy) "正在核对…" else "创建平行路线并选择人物分身", onClick = { if(!busy) prepare() })
    }
    if(open) NovexContentDialog("平行路线预览", onDismiss = { if(!busy) open = false }, confirmButton = {
        TextButton(enabled = !busy && plan != null && name.isNotBlank(), onClick = {
            val current = plan ?: return@TextButton
            busy = true
            scope.launch {
                try {
                    result = withContext(Dispatchers.IO) { (workspace.apply(NovexCommand.CreateParallelWorld(current, name, selected)) as NovexChange.WorldParallelCreated).result }
                    open = false; revision++
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(failure: Exception) { error = failure.message ?: "创建未完成"; plan = null }
                finally { busy = false }
            }
        }) { Text(if(busy) "正在保存" else "创建此路线") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("取消") } }) {
        NovexTextField(value = name, onValueChange = { if(!busy) name = it }, label = "新世界名称")
        Text("新世界复制原正文、模块和图片，沿用包外引用；原世界和新世界记入同一系列。选中人物生成同一人的平行分身，未选中人物继续引用原版本。", color = NovexColors.SecondaryText)
        plan?.let { current ->
            TextButton(enabled = !busy, onClick = { selected = if(selected.size == current.people.size) emptySet() else current.people.map { it.versionId }.toSet() }) { Text("生成分身 ${selected.size}/${current.people.size} · 全选/清空") }
            androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                current.people.forEach { person -> TextButton(enabled = !busy, onClick = {
                    selected = if(person.versionId in selected) selected - person.versionId else selected + person.versionId
                }) { Text("${if(person.versionId in selected) "生成分身" else "沿用原版"} · ${person.name} / ${person.label}") } }
                (current.worldCopy.missingTargets + current.missingPeople.map { "配套人物缺失：$it" }).forEach { Text(it) }
            }
            Text("列出本世界直接关联的人物版本；不自动扩大到全库或递归创建其他人物。当前对话的身份和采用快照保留。", color = NovexColors.SecondaryText)
        }
        if(plan == null && !busy) TextButton(onClick = { prepare() }) { Text("重新核对预览") }
    }
    result?.let { saved -> NovexContentDialog("平行世界已保存", onDismiss = { result = null }, confirmButton = {
        TextButton(onClick = { result = null; onReturnToLibrary() }) { Text("返回世界库查看") }
    }) { Text("新世界：$name\n已生成 ${saved.versions.size} 个分身\n${saved.worldId}") } }
    error?.let { NovexNoticeDialog("平行世界", it) { error = null } }
}
