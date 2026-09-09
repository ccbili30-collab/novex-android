package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.novex.domain.NovexStoryIllustrations

/** Rules stay in the current module draft and are committed by its existing Save button. */
@Composable
internal fun NovexIllustrationConditionField(raw: String, type: ContentModuleType, key: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var keys by remember { mutableStateOf("") }
    var cooldown by remember { mutableStateOf("1") }
    var sample by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val rule = NovexStoryIllustrations.decode(NovexStoryIllustrations.read(raw, key))
    NovexSummaryRow("剧情展示", when { rule == null -> "条件待检查"; !rule.enabled -> "已关闭"; rule.keys.isEmpty() -> "按需选图"; else -> "按关键词展示" }, onClick = {
        enabled = rule?.enabled ?: false; keys = rule?.keys?.joinToString("\n").orEmpty()
        cooldown = (rule?.cooldown ?: 1).toString(); sample = ""; error = null; open = true
    })
    if (open) NovexContentDialog("剧情展示", onDismiss = { open = false }, confirmButton = {
        TextButton(onClick = {
            val count = cooldown.toIntOrNull()
            val updated = NovexStoryIllustrations.Rule(enabled, keys.lines().map(String::trim).filter(String::isNotBlank).distinct(), count ?: 0)
            if (NovexStoryIllustrations.decode(NovexStoryIllustrations.encode(updated)) == null) { error = "关键词最多 50 个，每个不超过 100 字；间隔为 1 到 20 轮。" }
            else { onChange(NovexStoryIllustrations.set(raw, type, key, updated)); open = false }
        }) { Text("完成") }
    }, dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } }) {
        NovexSettingsVectorToggleRow("允许剧情展示", "仅在本卡被采用时可用。", checked = enabled, onCheckedChange = { enabled = it })
        if (enabled) {
            NovexTextField("关键词，每行一个", keys, { keys = it }, modifier = Modifier.fillMaxWidth())
            Text("留空时由人工智能按需要选择；填写后，完成的剧情命中时自动展示。", style = NovexType.Metadata)
            NovexTextField("同图至少间隔几轮", cooldown, { cooldown = it }, modifier = Modifier.fillMaxWidth())
            NovexTextField("试一段剧情", sample, { sample = it }, modifier = Modifier.fillMaxWidth())
            if (sample.isNotBlank()) Text(if (keys.lines().any { it.trim().isNotEmpty() && sample.contains(it.trim(), true) }) "命中关键词；实际展示仍受本轮数量和间隔限制。" else "未命中自动展示条件。", style = NovexType.Metadata)
        }
        Text("完成后保存模块。已开始的游玩使用原来采用的版本。", style = NovexType.Metadata)
        error?.let { Text(it) }
    }
}
