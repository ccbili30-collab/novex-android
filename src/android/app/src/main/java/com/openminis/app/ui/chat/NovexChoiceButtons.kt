package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun NovexChoiceButtons(
    argsJson: String,
    onChoice: (String) -> Unit,
) {
    val parsed = remember(argsJson) {
        runCatching {
            val args = JSONObject(argsJson)
            val title = args.optString("title").trim()
            val values = JSONArray(args.getString("choices"))
            title to (0 until values.length())
                .mapNotNull { values.optString(it).trim().takeIf(String::isNotEmpty) }
                .take(6)
        }.getOrDefault("" to emptyList())
    }
    if (parsed.second.size < 2) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (parsed.first.isNotEmpty()) {
            Text(
                text = parsed.first,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        parsed.second.forEach { choice ->
            OutlinedButton(
                onClick = { onChoice(choice) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(choice)
            }
        }
    }
}
