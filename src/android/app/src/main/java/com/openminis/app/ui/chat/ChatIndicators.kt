package com.openminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .padding(top = (-offset).dp.coerceAtLeast(0.dp))
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    val infiniteTransition = rememberInfiniteTransition(label = "streamDots")
    Row {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sdot_$i",
            )
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.offset(y = offset.dp),
            )
        }
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

/**
 * [T-stream-stall-watchdog] Epoch millis of the in-flight request whose FIRST
 * stream chunk has not arrived yet; null when not waiting. Provided by
 * ChatScreen from ChatViewModel.streamAwaitingSince and read by
 * [TypingIndicator] so the "thinking…" dots never sit silent for minutes
 * without telling the user how long the connection has been quiet
 * (conversation-f899bf05: 51-minute hole with zero feedback).
 */
internal val LocalStreamAwaitingSince = compositionLocalOf<Long?> { null }

/**
 * [T-stream-stall-watchdog] Wait-time hint text for [TypingIndicator].
 * null = don't render yet (first 3 s are normal latency, no hint); under a
 * minute = plain seconds; at/over a minute the copy adds the auto-reconnect
 * note so the user knows the 5-minute stall watchdog is armed.
 */
internal fun streamAwaitingHintText(seconds: Long): String? = when {
    seconds < 3 -> null
    seconds < 60 -> "已等待 $seconds 秒"
    else -> "已等待 ${seconds / 60} 分 ${seconds % 60} 秒 · 卡住将自动重连"
}

@Composable
internal fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (MinisApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Nova" }
    // [T-stream-stall-watchdog] Elapsed-seconds ticker while awaiting the
    // first chunk; recomposes at most once per second. elapsedTick only
    // drives recomposition — the age itself is computed from the wall clock
    // on each recomposition.
    val awaitingSince = LocalStreamAwaitingSince.current
    var elapsedTick by remember(awaitingSince) { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(awaitingSince) {
        while (awaitingSince != null) {
            kotlinx.coroutines.delay(1_000)
            elapsedTick++
        }
    }
    val awaitingSeconds = awaitingSince?.let { (System.currentTimeMillis() - it) / 1000 }

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        // Animated bouncing dots
        val dots = listOf(".", ".", ".")
        dots.forEachIndexed { index, dot ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = index * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_bounce_$index",
            )
            Text(
                text = dot,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.tertiaryText,
                modifier = Modifier.graphicsLayer { translationY = offsetY },
            )
        }
        // [T-stream-stall-watchdog] Wait-time hint next to the dots — copy
        // thresholds in [streamAwaitingHintText] (3 s grace, 60 s adds the
        // auto-reconnect note so a dead relay reads as "armed", not "frozen").
        streamAwaitingHintText(awaitingSeconds ?: -1)?.let { hint ->
            Text(
                text = hint,
                fontSize = 13.sp,
                color = ChatColors.tertiaryText,
                modifier = Modifier.padding(start = 8.dp, bottom = 1.dp),
            )
        }
    }
}
