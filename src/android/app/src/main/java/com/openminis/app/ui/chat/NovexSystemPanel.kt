package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun NovexPanel(argsJson: String, onButton: (String) -> Unit) {
    val args = remember(argsJson) { runCatching { JSONObject(argsJson) }.getOrNull() }
    val title = args?.optString("title")?.trim().orEmpty().ifEmpty { "资料" }
    val content = args?.optString("content")?.trim().orEmpty()
    val images = remember(argsJson) { parseStrings(args?.optString("images").orEmpty()) }
    val items = remember(argsJson) { parseStrings(args?.optString("items").orEmpty()) }
    val buttons = remember(argsJson) { parseButtons(args?.optString("buttons").orEmpty()) }
    if (content.isEmpty() && images.isEmpty() && items.isEmpty() && buttons.isEmpty()) return
    var expanded by remember(argsJson) { mutableStateOf(args?.optBoolean("expanded", false) == true) }

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
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (content.isNotEmpty()) {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    images.forEach { source ->
                        AsyncImage(
                            model = source,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
                    }
                    items.forEach { item ->
                        Text(
                            text = "• $item",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    buttons.forEach { button ->
                        OutlinedButton(
                            onClick = { onButton(button.value) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(button.label)
                        }
                    }
                }
            }
        }
    }
}

private data class PanelButton(val label: String, val value: String)

private fun parseStrings(raw: String): List<String> = runCatching {
    val values = JSONArray(raw)
    (0 until values.length()).mapNotNull { index ->
        values.optString(index).trim().takeIf(String::isNotEmpty)
    }
}.getOrDefault(emptyList())

private fun parseButtons(raw: String): List<PanelButton> = runCatching {
    val values = JSONArray(raw)
    (0 until values.length()).mapNotNull { index ->
        val item = values.optJSONObject(index) ?: return@mapNotNull null
        val label = item.optString("label").trim()
        val value = item.optString("value", label).trim()
        if (label.isEmpty() || value.isEmpty()) null else PanelButton(label, value)
    }
}.getOrDefault(emptyList())
