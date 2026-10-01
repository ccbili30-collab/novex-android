package com.openminis.app.ui.chat

// 输入栏周边小组件：附件片、Novex 光效点按、图形符号钮、圆形钮、思考档
// 位选择器。工具缩略预览/悬浮状态条在 ToolPreviewBar.kt。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.launch
import novex.android.data.model.ThinkingLevel
import novex.android.ui.NovexIcons

// ─── 附件片 ─────────────────────────────────────────────────────────────────

/**
 * iOS UserAttachmentList 对齐：64dp 片 + 右上角 xmark.circle.fill 删除徽，
 * 徽标一半压片一半悬空（20dp 圆偏移 -6/-6）。外层 Box 给 72×70 让徽标
 * 溢出不被裁；片体在 TopStart 保持正好 64dp。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AttachmentChip(
    attachment: InputAttachment,
    onRemove: () -> Unit,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val chipShape = RoundedCornerShape(8.dp)
    Box(modifier = Modifier.size(width = 72.dp, height = 70.dp)) {
        AttachmentThumb(attachment, chipShape, onClick, onLongClick)
        RemoveBadge(onRemove)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun androidx.compose.foundation.layout.BoxScope.AttachmentThumb(
    attachment: InputAttachment,
    chipShape: RoundedCornerShape,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .size(64.dp)
            // 点片体（不含删除徽——徽在外层 Box）预览附件，对齐 iOS
            // InputAttachmentTile.onTapGesture。
            .clip(chipShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (attachment.isImage) {
            AsyncImage(
                model = attachment.uri,
                contentDescription = attachment.fileName,
                modifier = Modifier
                    .matchParentSize()
                    .clip(chipShape)
                    .border(1.dp, ChatColors.thumbnailBorder, chipShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Column(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, chipShape)
                    .border(1.dp, ChatColors.thumbnailBorder, chipShape),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    NovexIcons.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = attachment.fileName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.RemoveBadge(onRemove: () -> Unit) {
    // 删除徽：压片右上角、半压半悬。细描边保证在图片内容上可读。
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .size(20.dp)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .border(0.5.dp, ChatColors.thumbnailBorder, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onRemove),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            NovexIcons.Close,
            contentDescription = "Remove",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(13.dp),
        )
    }
}

// ─── Novex 点击光效（自有 indication，替代默认灰涟漪）─────────────────────────
// 按下点向外扩散一圈薄荷光辉并消退——比灰色圆涟漪轻，也脱离 Material 语言。

private val NovexGlowColor = Color(0xFF2FBF8F)

private class NovexGlowNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f)
    private var origin = Offset.Unspecified

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Press) {
                    origin = interaction.pressPosition
                    progress.snapTo(0f)
                    launch {
                        progress.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val p = progress.value
        if (p > 0f && p < 1f && origin != Offset.Unspecified) {
            val r = size.maxDimension * p
            drawCircle(
                brush = Brush.radialGradient(
                    0f to NovexGlowColor.copy(alpha = 0.20f * (1f - p)),
                    1f to NovexGlowColor.copy(alpha = 0f),
                    center = origin,
                    radius = r.coerceAtLeast(1f),
                ),
                radius = r,
                center = origin,
            )
        }
    }
}

private object NovexGlowIndicationFactory : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource) =
        NovexGlowNode(interactionSource)
    override fun hashCode() = 31
    override fun equals(other: Any?) = other is NovexGlowIndicationFactory
}

/**
 * 带 Novex 薄荷点击光效的 clickable/combinedClickable。
 * clip 要在它之前调用，光效才会被裁进形状内。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.novexClickable(
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = interactionSource,
        indication = NovexGlowIndicationFactory,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

// ─── 图形符号 ────────────────────────────────────────────────────────────────

/** 扇形张开的三张卡（指令卡入口图形）：左右两张各向两侧张开，中卡压顶。 */
@Composable
internal fun NovexCardStackGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fillColor: Color = ChatColors.inputBg,
) {
    Canvas(modifier.size(20.dp)) {
        val strokeWidth = 1.6.dp.toPx()
        val w = size.width
        val h = size.height
        val cardW = w * 0.52f
        val cardH = h * 0.66f
        val corner = CornerRadius(2.2.dp.toPx())
        val stroke = Stroke(strokeWidth)
        val pivot = Offset(w / 2f, h * 0.90f)
        val cardTop = pivot.y - cardH
        val cardLeft = w / 2f - cardW / 2f

        // 后两张卡绕底部中点各向两侧倾斜，实底填充防线条互透。
        for ((deg, alpha) in listOf(-18f to 0.45f, 18f to 0.45f)) {
            withTransform({ rotate(degrees = deg, pivot = pivot) }) {
                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(cardLeft, cardTop),
                    size = Size(cardW, cardH),
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = tint.copy(alpha = alpha),
                    topLeft = Offset(cardLeft, cardTop),
                    size = Size(cardW, cardH),
                    cornerRadius = corner,
                    style = stroke,
                )
            }
        }
        // 中卡正立压顶：实底 + 全色描边。
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(cardLeft, cardTop + h * 0.02f),
            size = Size(cardW, cardH),
            cornerRadius = corner,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(cardLeft, cardTop + h * 0.02f),
            size = Size(cardW, cardH),
            cornerRadius = corner,
            style = stroke,
        )
    }
}

/** 小卡片标（卡行行首）：一张中空描边的竖立矩形框，描边即卡片。 */
@Composable
internal fun NovexMiniCardGlyph(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(20.dp)) {
        val cardW = size.width * 0.62f
        val cardH = size.height * 0.80f
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - cardW) / 2f, (size.height - cardH) / 2f),
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(2.4.dp.toPx()),
            style = Stroke(1.6.dp.toPx()),
        )
    }
}

// ─── 输入栏按钮 ──────────────────────────────────────────────────────────────

/**
 * [A2c-glyphs] 输入栏第二行裸符号钮：无圆底无边框，图标是符号本身；
 * 40dp 触控区保可达性，按下走 NovexGlow 自有光效而非灰涟漪。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerGlyphButton(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = NovexGlowIndicationFactory,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
internal fun InputCircleButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .background(ChatColors.inputIconBg, CircleShape)
            .border(0.5.dp, ChatColors.inputIconBorder, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// [P3.3 裁军] MicButton（语音/键盘切换按钮）随语音输入退役删除。

// ─── 思考档位选择器 ──────────────────────────────────────────────────────────

private val ClampOrange = Color(0xFFFF9500)

/**
 * `/thinking` 行右侧的内联分段选择器。对齐 iOS thinkingLevelPicker：
 * 当前档画实色胶囊；存储档超出当前模型上限时钳位高亮最高可用档（橙底
 * + 上箭头），否则按蓝底高亮。
 *
 * [T-android-thinking-level-arch] `availableLevels` 是当前模型实际可达的
 * 档集（OFF + effectiveMaxThinkingLevel 以内），行可横滑让 GPT-5.6 的
 * MAX/ULTRA 不挤爆窄输入栏。
 */
@Composable
internal fun ThinkingLevelPicker(
    current: ThinkingLevel,
    availableLevels: List<ThinkingLevel>,
    onSelect: (ThinkingLevel) -> Unit,
) {
    val context = LocalContext.current
    // 钳位态：持久化的档比当前模型能到的还高（如存了 ULTRA 后切到封顶
    // XHIGH 的 DeepSeek）。此时 current 不在 availableLevels，若无补偿
    // 整行无选中态像"思考已关"。对齐 iOS：最高可用档橙色高亮+上箭头，
    // 表达"你的设置更高，这个模型到这里封顶"。
    val maxAvailable = availableLevels.lastOrNull { it != ThinkingLevel.OFF }
    val isClamped = current.isEnabled && maxAvailable != null && current.rank > maxAvailable.rank

    Row(
        modifier = Modifier
            .background(ChatColors.secondaryText.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        availableLevels.forEach { level ->
            val clamped = isClamped && level == maxAvailable
            ThinkingCapsule(
                label = level.localizedName(context),
                selected = level == current,
                clamped = clamped,
                // [T-android-thinking-level-arch] 点已高亮的胶囊（选中或钳位
                // 高亮）= 关思考；否则选所点档。
                onTap = { onSelect(if (level == current || clamped) ThinkingLevel.OFF else level) },
            )
        }
    }
}

@Composable
private fun ThinkingCapsule(
    label: String,
    selected: Boolean,
    clamped: Boolean,
    onTap: () -> Unit,
) {
    val highlighted = selected || clamped
    // [T-android-thinking-picker-ui] 钳位档橙、正常选中蓝（ChatColors.thinking
    // 主题自适应的 system blue，对齐 iOS Color.blue）。旧实现用
    // ChatColors.sendButton（浅色黑/深色白），选中态读作未选中。
    val bg = when {
        clamped -> ClampOrange.copy(alpha = 0.75f)
        highlighted -> ChatColors.thinking
        else -> Color.Transparent
    }
    val fg = if (highlighted) Color.White else ChatColors.secondaryText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(bg)
            .clickable(onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            color = fg,
        )
        if (clamped) {
            Icon(
                imageVector = NovexIcons.KeyboardArrowUp,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
