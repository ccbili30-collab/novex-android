package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.FileNovexTeachingTraceStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun NovexTeachingTraceSection(reference: String) {
    val context = LocalContext.current
    var open by remember(reference) { mutableStateOf(false) }
    var payload by remember(reference) { mutableStateOf<JSONObject?>(null) }
    var error by remember(reference) { mutableStateOf<String?>(null) }
    var selected by remember(reference) { mutableStateOf<Pair<String, String>?>(null) }
    TextButton(onClick = { open = true }) { Text("本轮教学装配与候选对照（预览测试）") }
    LaunchedEffect(reference, open) {
        if(!open) return@LaunchedEffect
        try { payload = withContext(Dispatchers.IO) { FileNovexTeachingTraceStore(File(context.filesDir, "novex/teaching-traces")).read(reference) } }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { error = failure.message ?: "装配记录读取失败"; open = false }
    }
    if(open) {
        val record = payload
        val actions = if(record == null) emptyList() else buildList {
            add(NovexSelectionAction("正式装配原文", description = "应用在调用前采用的文本；不是提供商网络报文") { selected = "正式装配原文" to record.getString("formalPrompt") })
            add(NovexSelectionAction("当时工具声明", description = "应用通用格式，不含连接密钥或请求头") { selected = "工具声明" to record.getJSONArray("toolDefinitions").toString(2) })
            val candidate = record.optJSONObject("candidate")
            if(candidate == null) add(NovexSelectionAction("候选未生成", description = record.optString("candidateUnavailable")) {})
            else {
                val sections = candidate.getJSONArray("sections")
                for(index in 0 until sections.length()) {
                    val section = sections.getJSONObject(index)
                    add(NovexSelectionAction("候选 · ${section.getString("label")}", description = "第六版未通过验收；未发送模型") {
                        selected = "候选未发送 · ${section.getString("label")}" to section.getString("text")
                    })
                }
            }
        }
        NovexSearchableSelectionSheet(if(record == null) "读取装配记录…" else "本轮装配 · 候选未启用", actions,
            "搜索装配部分", onDismissRequest = { open = false }, dismissOnSelection = false)
    }
    selected?.let { (title, text) ->
        var offset by remember(reference, title) { mutableIntStateOf(0) }
        var end = minOf(text.length, offset + 12_000)
        if(end < text.length && end > offset && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        NovexContentDialog(title, onDismiss = { selected = null }, confirmButton = {
            TextButton(onClick = { selected = null }) { Text("返回目录") }
        }) {
            Text("仅供核对。查看和比较不会调用模型，也不会启用候选。")
            Text("显示 ${offset + 1}—$end / ${text.length} 字符；保存文件保留全文")
            SelectionContainer { Text(text.substring(offset, end), Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) }
            if(end < text.length) TextButton(onClick = { offset = end }) { Text("下一段") }
            if(offset > 0) TextButton(onClick = { offset = 0 }) { Text("回到开头") }
        }
    }
    error?.let { NovexNoticeDialog("装配记录", it) { error = null } }
}
