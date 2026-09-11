package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.novex.NovexIcons
import com.openminis.app.novex.domain.NovexDeepSeekPeakClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant

internal data class ContextMeterGeometry(val enabled: Float, val used: Float, val percent: Int)
internal fun contextMeterGeometry(used: Int, maximum: Int, enabled: Int): ContextMeterGeometry {
    val max = maximum.coerceAtLeast(1).toFloat()
    return ContextMeterGeometry((enabled / max).coerceIn(0f, 1f), (used / max).coerceIn(0f, 1f),
        (used.toLong().coerceAtLeast(0) * 100 / enabled.coerceAtLeast(1)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
}

@Composable
internal fun NovexContextMeter(
    usedTokens: Int,
    windowTokens: Int?,
    maximumTokens: Int?,
    estimated: Boolean,
    ready: Boolean,
    mode: Int,
    onClick: () -> Unit,
) {
    val capacityKnown = windowTokens != null && maximumTokens != null && windowTokens > 0 && maximumTokens > 0
    val known = ready && capacityKnown
    val geometry = contextMeterGeometry(usedTokens, maximumTokens ?: 1, windowTokens ?: 1)
    val ink = ChatColors.primaryText // black in light mode, readable inverted ink in dark mode
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics {
        contentDescription = if (known) "${if (estimated) "预计" else "实际"}上下文占用 ${geometry.percent}%，已用 $usedTokens 词元，启用 $windowTokens，模型上限 $maximumTokens；点击切换百分比和用量"
            else "本轮用量尚未确定，点击切换显示"
    }) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val inset = 2.dp.toPx()
                val origin = androidx.compose.ui.geometry.Offset(inset, inset)
                val area = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                drawArc(Color.Gray.copy(alpha = 0.35f), -90f, 360f, false, origin, area,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                if (capacityKnown) {
                    drawArc(Color.Gray, -90f, geometry.enabled * 360f, false, origin, area,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    if (known) drawArc(ink, -90f, geometry.used * 360f, false, origin, area,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            Text(if (!known) "—" else (if (estimated) "≈" else "") +
                if (mode % 2 == 0) "${geometry.percent}%" else compactContextTokens(usedTokens),
                color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

internal fun compactContextTokens(value: Int): String {
    fun amount(divisor: Float, suffix: String): String {
        val number = value / divisor
        val format = if (number >= 100f) "%.0f" else "%.1f"
        return String.format(java.util.Locale.ROOT, format, number).removeSuffix(".0") + suffix
    }
    return when {
        value >= 1_000_000 -> amount(1_000_000f, "M")
        value >= 1_000 -> amount(1_000f, "K")
        else -> value.toString()
    }
}

/** A reference to official DeepSeek hours, never a claim about a relay's invoice. */
@Composable
internal fun NovexDeepSeekClock() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                now = Instant.now()
                delay(60_000L - (now.toEpochMilli() % 60_000L))
            }
        }
    }
    val phase = NovexDeepSeekPeakClock.phase(now)
    val description = NovexDeepSeekPeakClock.explanation(now)
    IconButton(onClick = {
        android.widget.Toast.makeText(context, description, android.widget.Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.size(48.dp)) {
        Icon(NovexIcons.Schedule, contentDescription = description, modifier = Modifier.size(23.dp),
            tint = if (phase == NovexDeepSeekPeakClock.Phase.PEAK) Color(0xFFD14B4B) else Color(0xFF39965A))
    }
}
