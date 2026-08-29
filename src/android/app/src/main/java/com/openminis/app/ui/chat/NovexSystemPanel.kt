package com.openminis.app.ui.chat

import android.text.Html
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mikepenz.markdown.m3.Markdown
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun NovexPanel(argsJson: String, onButton: (String) -> Unit) {
    val args = remember(argsJson) { runCatching { JSONObject(argsJson) }.getOrNull() } ?: return
    val title = args.optString("title").trim().ifEmpty { "资料" }
    val summary = args.optString("summary").trim()
    val icon = semanticIcon(args.optString("icon"))
    val blocks = remember(argsJson) { parseArray(args.optString("blocks")) }
    val actions = remember(argsJson) {
        parseArray(args.optString("actions")).mapNotNull { item ->
            val label = item.optString("label").trim()
            val prompt = item.optString("prompt").trim()
            if (label.isEmpty() || prompt.isEmpty()) null else PanelAction(label, prompt)
        }.ifEmpty { parseLegacyActions(args) }
    }
    val legacyContent = args.optString("content").trim()
    if (blocks.isEmpty() && actions.isEmpty() && legacyContent.isEmpty()) return
    var expanded by remember(argsJson) {
        mutableStateOf(!args.optBoolean("collapsed", !args.optBoolean("expanded", false)))
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon.isNotEmpty()) {
                    Text(icon)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (summary.isNotEmpty()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expanded) 2 else 1,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (legacyContent.isNotEmpty()) Markdown(content = legacyContent)
                    blocks.forEach { block -> PanelBlock(block) }
                    actions.forEach { action ->
                        OutlinedButton(
                            onClick = { onButton(action.prompt) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(action.label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelBlock(block: JSONObject) {
    when (block.optString("type")) {
        "markdown" -> Markdown(content = block.optString("content"))
        "image" -> {
            AsyncImage(
                model = block.optString("src"),
                contentDescription = block.optString("alt").ifEmpty { null },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            )
            block.optString("caption").takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "gallery" -> Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val images = block.optJSONArray("images") ?: JSONArray()
            for (index in 0 until images.length()) {
                val value = images.opt(index)
                val src = if (value is JSONObject) value.optString("src") else value?.toString().orEmpty()
                if (src.isNotBlank()) AsyncImage(model = src, contentDescription = null, modifier = Modifier.size(180.dp))
            }
        }
        "table" -> {
            val columns = block.optJSONArray("columns") ?: JSONArray()
            val rows = block.optJSONArray("rows") ?: JSONArray()
            if (columns.length() > 0) TableRow(columns, header = true)
            for (index in 0 until rows.length()) TableRow(rows.optJSONArray(index) ?: JSONArray(), header = false)
        }
        "stats" -> {
            val items = block.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                Row(Modifier.fillMaxWidth()) {
                    Text(item.optString("label"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.38f))
                    Text(item.optString("value"), fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.62f))
                }
            }
        }
        "timeline" -> {
            val items = block.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                Text("${item.optString("time")}　${item.optString("title")}", fontWeight = FontWeight.SemiBold)
                Text(item.optString("description"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "details" -> PanelDetails(block)
        "divider" -> HorizontalDivider()
        "html" -> Text(
            Html.fromHtml(block.optString("content"), Html.FROM_HTML_MODE_COMPACT).toString(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PanelDetails(block: JSONObject) {
    var open by remember(block.toString()) { mutableStateOf(!block.optBoolean("collapsed", true)) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(block.optString("title", "详细内容"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(open) { Markdown(content = block.optString("content")) }
    }
}

@Composable
private fun TableRow(values: JSONArray, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        val count = values.length().coerceAtLeast(1)
        for (index in 0 until count) {
            Text(
                values.optString(index),
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
    }
}

private data class PanelAction(val label: String, val prompt: String)

private fun parseArray(raw: String): List<JSONObject> = runCatching {
    val values = JSONArray(raw.ifBlank { "[]" })
    (0 until values.length()).mapNotNull(values::optJSONObject)
}.getOrDefault(emptyList())

private fun parseLegacyActions(args: JSONObject): List<PanelAction> = runCatching {
    val raw = args.optString("buttons").ifBlank { "[]" }
    val values = JSONArray(raw)
    (0 until values.length()).mapNotNull { index ->
        val item = values.optJSONObject(index) ?: return@mapNotNull null
        val label = item.optString("label").trim()
        val prompt = item.optString("value", label).trim()
        if (label.isEmpty() || prompt.isEmpty()) null else PanelAction(label, prompt)
    }
}.getOrDefault(emptyList())

private fun semanticIcon(value: String): String = when (value) {
    "character" -> "人"
    "save" -> "存"
    "world" -> "界"
    "document" -> "文"
    "timeline" -> "时"
    "map" -> "图"
    "system" -> "设"
    else -> ""
}
