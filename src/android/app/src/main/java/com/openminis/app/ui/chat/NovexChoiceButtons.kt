package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** A choice has a visible label and an optional payload sent when selected. */
internal data class NovexChoiceOption(val label: String, val payload: String = label)
internal data class NovexChoiceEvent(
    val title: String,
    val options: List<NovexChoiceOption>,
    val allowMultiple: Boolean = false,
    val expired: Boolean = false,
)

/**
 * Reads the choice tool's wire shape without requiring providers to agree on
 * whether `choices` is a JSON array or a JSON-encoded string.  Older models
 * commonly emit the latter while OpenAI-compatible providers usually emit the
 * former.  The UI must accept both and never silently drop the choice row.
 */
internal fun parseNovexChoiceEvent(argsJson: String): NovexChoiceEvent = runCatching {
    val args = JSONObject(argsJson)
    val raw = args.opt("choices")
    val values = when (raw) {
        is JSONArray -> raw
        is String -> JSONArray(raw)
        else -> JSONArray()
    }
    val options = buildList {
        repeat(values.length()) {
            when (val value = values.opt(it)) {
                is JSONObject -> {
                    val label = value.optString("label").trim()
                        .ifBlank { value.optString("text").trim() }
                        .ifBlank { value.optString("title").trim() }
                    if (label.isNotBlank()) {
                        add(NovexChoiceOption(label, value.optString("value").trim().ifBlank {
                            value.optString("prompt").trim().ifBlank { label }
                        }))
                    }
                }
                else -> value
                    ?.takeUnless { it == JSONObject.NULL }
                    ?.toString()
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { add(NovexChoiceOption(it)) }
            }
        }
    }.distinctBy { it.label }.take(12)
    NovexChoiceEvent(
        title = args.optString("title").trim(),
        options = options,
        allowMultiple = args.optBoolean("allow_multiple", args.optBoolean("multi_select", false)),
        expired = args.optBoolean("expired", false) || args.optString("status").equals("expired", ignoreCase = true),
    )
}.getOrDefault(NovexChoiceEvent("", emptyList()))

internal fun parseNovexChoiceOptions(argsJson: String): Pair<String, List<NovexChoiceOption>> {
    val event = parseNovexChoiceEvent(argsJson)
    return event.title to event.options
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovexChoiceButtons(
    argsJson: String,
    onChoice: (String) -> Unit,
) {
    val event = remember(argsJson) { parseNovexChoiceEvent(argsJson) }
    if (event.options.size < 2) return
    if (event.expired) {
        Text("选项已失效", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    val selected = remember(argsJson) { mutableStateListOf<String>() }

    Column(
        modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
    ) {
        if (event.title.isNotEmpty()) {
            Text(
                text = event.title,
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
            event.options.forEach { choice ->
                val isSelected = choice.payload in selected
                OutlinedButton(
                    onClick = {
                        if (!event.allowMultiple) onChoice(choice.payload)
                        else if (choice.payload in selected) selected.remove(choice.payload) else selected.add(choice.payload)
                    },
                    modifier = Modifier.heightIn(min = 32.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (event.allowMultiple && selected.isNotEmpty()) {
            OutlinedButton(
                onClick = { onChoice(selected.joinToString("\n")); selected.clear() },
                modifier = Modifier.padding(top = 6.dp),
            ) { Text("确认选择（${selected.size}）") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NovexChoiceButtons(
    choices: List<String>,
    onChoice: (String) -> Unit,
) {
    val normalized = remember(choices) {
        choices.map(String::trim).filter(String::isNotEmpty).distinct().take(12)
    }
    if (normalized.size < 2) return
    FlowRow(
        modifier = Modifier.padding(top = 3.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        normalized.forEach { choice ->
            OutlinedButton(
                onClick = { onChoice(choice) },
                modifier = Modifier.heightIn(min = 32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
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
