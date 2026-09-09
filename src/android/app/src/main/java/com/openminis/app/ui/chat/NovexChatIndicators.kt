package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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

@Composable
internal fun NovexContextMeter(
    usedTokens: Int,
    windowTokens: Int?,
    mode: Int,
    onClick: () -> Unit,
) {
    val known = windowTokens != null && windowTokens > 0 && usedTokens > 0
    val window = windowTokens?.takeIf { it > 0 } ?: 1
    val progress = (usedTokens.toFloat() / window.toFloat()).coerceIn(0f, 1f)
    val percent = (progress * 100).toInt()
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics {
        contentDescription = if (known) "上轮请求上下文占用 $percent%，点击切换显示" else "上下文占用尚无记录，点击切换显示"
    }) {
        when (mode) {
            0 -> Box(
                Modifier
                    .size(9.dp)
                    .background(ChatColors.secondaryText, CircleShape),
            )
            1 -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .background(ChatColors.inputBg, CircleShape)
                    .border(1.dp, ChatColors.toolBorder, CircleShape),
            ) {
                Text(
                    if (known) "$percent%" else "—",
                    color = ChatColors.primaryText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            else -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxSize(),
                    color = ChatColors.sendButton,
                    trackColor = ChatColors.toolBorder,
                    strokeWidth = 2.5.dp,
                )
                Text(
                    if (known) compactContextTokens(usedTokens) else "—",
                    color = ChatColors.primaryText,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun compactContextTokens(value: Int): String = when {
    value >= 1_000_000 -> "${value / 100_000 / 10f}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
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
