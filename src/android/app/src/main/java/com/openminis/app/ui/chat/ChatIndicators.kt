package com.openminis.app.ui.chat

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

// 聊天里的等待动效：一枚 transition 驱动多枚点，每个调用方只描述点的长相。

private class DotMotion(
    val target: Float,
    val stepDelay: Int,
    val duration: Int,
    val easing: Easing,
)

/** 驱动 [count] 枚点的错相位往复升降；[dot] 收到当前偏移量自绘。 */
@Composable
private fun AnimatedDots(
    transition: InfiniteTransition,
    count: Int,
    motion: DotMotion,
    dot: @Composable RowScope.(index: Int, lift: Float) -> Unit,
) {
    Row {
        repeat(count) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = motion.target,
                animationSpec = infiniteRepeatable(
                    animation = tween(motion.duration, delayMillis = i * motion.stepDelay, easing = motion.easing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_lift_$i",
            )
            dot(i, lift)
        }
    }
}

/** 三枚圆点依次起伏（工具卡上的「执行中」标志）。 */
@Composable
internal fun BouncingDots(color: Color) {
    AnimatedDots(
        transition = rememberInfiniteTransition(label = "dots"),
        count = 3,
        motion = DotMotion(target = -2f, stepDelay = 120, duration = 350, easing = LinearEasing),
    ) { _, lift ->
        Box(
            Modifier
                .size(4.dp)
                .padding(top = (-lift).dp.coerceAtLeast(0.dp))
                .background(color, CircleShape),
        )
    }
}

/** 跟在文字后面的行内跳动 "."（流式输出中）。 */
@Composable
internal fun StreamingDotsText() {
    AnimatedDots(
        transition = rememberInfiniteTransition(label = "stream"),
        count = 3,
        motion = DotMotion(target = -3f, stepDelay = 120, duration = 350, easing = FastOutSlowInEasing),
    ) { _, lift ->
        DotGlyph(lift, fontSize = 13.sp, weight = FontWeight.Medium, color = ChatColors.primaryText, useOffset = true)
    }
}

// ── 等待提示 ────────────────────────────────────────────────────────────────

/**
 * 首个流式分片尚未到达的请求的开始时间（epoch ms）；null = 没有在等。
 * 由 ChatScreen 从 ChatViewModel.streamAwaitingSince 喂进来，让
 * "正在想" 不会在连接静默时一句反馈都没有。
 */
internal val LocalStreamAwaitingSince = compositionLocalOf<Long?> { null }

/**
 * 等待时长提示文案：3 秒内算正常延迟不显示；一分钟内只报秒数；满一分钟
 * 追加"卡住将自动重连"，告诉用户看门狗已在看着。
 */
internal fun streamAwaitingHintText(seconds: Long): String? = when {
    seconds < 3 -> null
    seconds < 60 -> "已等待 $seconds 秒"
    else -> "已等待 ${seconds / 60} 分 ${seconds % 60} 秒 · 卡住将自动重连"
}

@Composable
private fun DotGlyph(lift: Float, fontSize: TextUnit, weight: FontWeight, color: Color, useOffset: Boolean) {
    Text(
        ".",
        fontSize = fontSize,
        fontWeight = weight,
        color = color,
        modifier = if (useOffset) Modifier.offset(y = lift.dp)
        else Modifier.graphicsLayer { translationY = lift },
    )
}

@Composable
internal fun TypingIndicator() {
    // Soul 改名后读 cachedMetadata（保存即更新），指示器立刻换名。
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Nova" }

    // 等待秒数每秒重计一次；无等待时不启动 ticker。
    val awaitingSince = LocalStreamAwaitingSince.current
    var tick by remember(awaitingSince) { mutableLongStateOf(0L) }
    LaunchedEffect(awaitingSince) {
        while (awaitingSince != null) {
            delay(1_000)
            tick++
        }
    }
    val waitedSeconds = awaitingSince?.let { (System.currentTimeMillis() - it) / 1000 }

    Row(
        Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        AnimatedDots(
            transition = rememberInfiniteTransition(label = "typing"),
            count = 3,
            motion = DotMotion(target = -6f, stepDelay = 150, duration = 400, easing = LinearEasing),
        ) { _, lift ->
            DotGlyph(lift, fontSize = 15.sp, weight = FontWeight.Bold, color = ChatColors.tertiaryText, useOffset = false)
        }
        streamAwaitingHintText(waitedSeconds ?: -1)?.let { hint ->
            Text(
                hint,
                fontSize = 13.sp,
                color = ChatColors.tertiaryText,
                modifier = Modifier.padding(start = 8.dp, bottom = 1.dp),
            )
        }
    }
}
