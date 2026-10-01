package com.openminis.app.ui.chat

// 聊天页浮层提示件：中断恢复的 Resume 横幅、上滑发送手势的跟随提示。
// 由 ChatMiscViews 拆出，与系统行组件分家。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import novex.android.ui.NovexIcons

private val ResumeOrange = Color(0xFFFF9500)

/**
 * 消息列表底部的恢复横幅：agent 循环被中断（手动停/工具取消/冷启动捡到
 * 未完成历史）且可续跑时展示。对齐 iOS resumeBanner：橙色低透明底圆角条，
 * 左图标+说明，右橙色胶囊 Resume 按钮。
 *
 * [T-android-c3a-resume-one-tap] 崩溃感知：上一周期以 crash_or_stall 结束
 * 时横幅换一行警告文案作上下文提示，但警告只是信息——点 Resume 本身就是
 * 显式用户动作，一键恢复（旧版第一下只亮警告要再点一次，多余摩擦）。
 */
@Composable
internal fun ResumeBanner(onResume: () -> Unit) {
    val showCrashWarning =
        com.openminis.app.diagnostics.LaunchCycleBeacon.lastCycleWasCrash

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ResumeOrange.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NovexIcons.PlayArrow,
            contentDescription = null,
            tint = ResumeOrange,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (showCrashWarning) R.string.chat_resume_crash_warning
                else R.string.resume_banner_title
            ),
            fontSize = 11.sp,
            color = if (showCrashWarning) {
                MaterialTheme.colorScheme.error
            } else {
                ChatColors.secondaryText
            },
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(ResumeOrange)
                .clickable(onClick = onResume)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = NovexIcons.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(10.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.resume_action),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}

/**
 * 上滑发送手势的跟随提示：发送箭头圆点 + "松手发送"胶囊，悬在指尖上方
 * `hoverAbovePx`。对齐 iOS SwipeToSendHint。
 *
 * - `progress` 0..1：拖动距离占触发阈值的比例。箭头透明度 = progress*1.4。
 * - 胶囊透明度在 `[armFraction - 0.4, armFraction]` 区间爬升，到触发点恰好
 *   满值，兼作"快到了"的确认信号。
 * - `location`：指尖在外层 Box 内的实时坐标（px）。
 * - `arrowHalfPx`：箭头渲染尺寸的一半，用于 X 向对齐指尖。
 * - `isEnqueue`：流式中手势走路径 `enqueuePrompt()`，胶囊文案换成
 *   "松手排队"，让用户知道手势仍然有效。
 */
@Composable
internal fun SwipeToSendHint(
    progress: Float,
    armFraction: Float,
    location: Offset,
    hoverAbovePx: Float,
    arrowHalfPx: Float,
    isEnqueue: Boolean = false,
) {
    if (progress <= 0f) return

    val chipBg = ChatColors.sendButton
    val chipFg = ChatColors.background
    val capsuleStart = (armFraction - 0.4f).coerceAtLeast(0f)
    val capsuleSpan = (armFraction - capsuleStart).coerceAtLeast(0.0001f)
    val capsuleAlpha = ((progress - capsuleStart) / capsuleSpan).coerceIn(0f, 1f)
    val rootAlpha = (progress * 1.4f).coerceIn(0f, 1f)
    val scale = 0.9f + 0.18f * progress
    val density = LocalDensity.current
    val xDp = with(density) { (location.x - arrowHalfPx).toDp() }
    val yDp = with(density) { (location.y - hoverAbovePx - arrowHalfPx).toDp() }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .offset(x = xDp, y = yDp)
            .graphicsLayer {
                alpha = rootAlpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
    ) {
        // 发送箭头圆盘——与真发送按钮同色底+反色箭头，读作"把消息拖上去发"。
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(chipBg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NovexIcons.ArrowUpward,
                contentDescription = null,
                tint = chipFg,
                modifier = Modifier.size(18.dp),
            )
        }
        // "松手发送"胶囊——同高，第二半程渐入。
        Box(
            modifier = Modifier
                .height(34.dp)
                .background(chipBg, RoundedCornerShape(50))
                .padding(horizontal = 14.dp)
                .graphicsLayer { alpha = capsuleAlpha },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (isEnqueue) R.string.composer_swipe_release_to_queue
                    else R.string.composer_swipe_release_to_send,
                ),
                color = chipFg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
