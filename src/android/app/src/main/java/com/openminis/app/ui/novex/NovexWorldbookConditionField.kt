package com.openminis.app.ui.novex

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.novex.domain.NovexWorldbookConditions
import org.json.JSONArray
import org.json.JSONObject

/** Edits the existing module draft; only its normal Save action writes the card. */
@Composable
internal fun NovexWorldbookConditionField(contentJson: String, type: com.openminis.app.data.character.ContentModuleType, onChange: (String) -> Unit) {
    val current = NovexWorldbookConditions.read(contentJson)
    var showing by remember { mutableStateOf(false) }
    var conditional by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var constant by remember { mutableStateOf(false) }
    var sensitive by remember { mutableStateOf(false) }
    var keywords by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("2") }
    var unsupported by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun open() {
        conditional = current != null; error = null
        unsupported = runCatching {
            val root = current?.let(::JSONObject) ?: JSONObject()
            require(root.keys().asSequence().all { it in setOf("version", "enabled", "constant", "caseSensitive", "keys", "scanDepth") })
            require(!root.has("version") || root.get("version") == 1)
            listOf("enabled", "constant", "caseSensitive").forEach { key -> require(!root.has(key) || root.get(key) is Boolean) }
            if (root.has("scanDepth")) { val n = root.get("scanDepth") as Number; require(n.toDouble() == n.toInt().toDouble() && n.toInt() in 0..100) }
            require(!root.has("keys") || root.get("keys") is JSONArray)
            enabled = root.optBoolean("enabled", true); constant = root.optBoolean("constant", false)
            sensitive = root.optBoolean("caseSensitive", false); depth = root.optInt("scanDepth", 2).toString()
            val keys = root.optJSONArray("keys") ?: JSONArray()
            keywords = (0 until keys.length()).joinToString("\n") { keys.get(it) as String }
        }.isFailure
        showing = true
    }
    NovexSummaryRow("使用条件", if (current == null) "普通资料" else "已设置", onClick = ::open)
    if (showing) NovexContentDialog("使用条件", onDismiss = { showing = false }, confirmButton = {
        TextButton(onClick = {
            if (unsupported && conditional) { showing = false; return@TextButton }
            val scan = depth.toIntOrNull()
            if (conditional && (scan == null || scan !in 0..100)) { error = "最近消息数量须为 0 到 100"; return@TextButton }
            if (!conditional && current == null) { showing = false; return@TextButton }
            val updated = runCatching { JSONObject(contentJson) }.getOrElse {
                JSONObject(com.openminis.app.data.character.ContentModuleDocumentCodec.encode(
                    com.openminis.app.data.character.ContentModuleDocumentCodec.decode(type, contentJson)))
            }
            if (conditional) updated.put(NovexWorldbookConditions.FIELD, JSONObject().put("version", 1)
                .put("enabled", enabled).put("constant", constant).put("caseSensitive", sensitive)
                .put("scanDepth", scan).put("keys", JSONArray(keywords.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct())))
            else updated.remove(NovexWorldbookConditions.FIELD)
            onChange(updated.toString()); showing = false
        }) { Text("完成") }
    }) {
        Text("只影响作为背景使用时的资料选择；完成后仍需保存模块。", style = NovexType.Metadata)
        NovexSettingsVectorToggleRow("按条件使用", "关闭时作为普通资料，保留正文。", checked = conditional, onCheckedChange = { conditional = it })
        if (conditional && unsupported) Text("这份条件含暂不支持的配置，原件保留且暂停采用。关闭“按条件使用”可明确移除条件。")
        else if (conditional) {
            NovexSettingsVectorToggleRow("启用", "暂停后不采用本模块正文。", checked = enabled, onCheckedChange = { enabled = it })
            NovexSettingsVectorToggleRow("常驻", "每轮候选均包含本模块，仍受本轮总容量限制。", checked = constant, onCheckedChange = { constant = it })
            if (!constant) {
                NovexTextField("关键词，每行一个", keywords, { keywords = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                NovexTextField("扫描最近几条消息", depth, { depth = it }, modifier = Modifier.fillMaxWidth())
                NovexSettingsVectorToggleRow("区分字母大小写", "只按普通文本匹配。", checked = sensitive, onCheckedChange = { sensitive = it })
            }
        }
        error?.let { Text(it) }
    }
}
