package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
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
                .take(12)
        }.getOrDefault("" to emptyList())
    }
    if (parsed.second.size < 2) return

    Column(
        modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
    ) {
        if (parsed.first.isNotEmpty()) {
            Text(
                text = parsed.first,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            parsed.second.forEach { choice ->
                OutlinedButton(
                    onClick = { onChoice(choice) },
                    modifier = Modifier.heightIn(min = 36.dp),
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = choice,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
