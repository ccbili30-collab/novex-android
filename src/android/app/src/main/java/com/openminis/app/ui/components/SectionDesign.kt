package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexType

/**
 * Settings 分组卡视觉节奏的单一事实源。所有设置屏（ProviderDetail /
 * MountedFolders / ModelGroups …）从这里取值，不在行内硬编码——
 * 改一处全部屏同步。具体数值桥接到 novex 尺寸令牌。
 */
object SectionDesign {
    /** 页边到卡片的水平留白。 */
    val ScreenHorizontalPadding = NovexDimensions.PageHorizontal

    /** 卡片圆角。 */
    val CardShape = RoundedCornerShape(NovexDimensions.SectionRadius)

    /** 屏顶 → 第一段标题。 */
    val FirstSectionTopGap = 16.dp

    /** 上一段 footer → 下一段标题。 */
    val SectionTopGap = 24.dp

    /** 段标题 → 卡片。 */
    val HeaderToCardGap = 8.dp

    /** 卡片 → 脚注。 */
    val CardToFooterGap = 8.dp

    /** 同段内相邻行/卡的视觉间隔。 */
    val InterRowGap = 8.dp

    /** 卡内标准行高。 */
    val RowMinHeight = NovexDimensions.SettingsRowMinHeight

    /** 单行输入框的最小高度（与行高同档，避免与旁边开关行错位）。 */
    val TextFieldMinHeight = NovexDimensions.SettingsRowMinHeight

    /** 卡内左右留白。 */
    val RowHorizontalPadding = 16.dp

    /** 行内上下留白。 */
    val RowVerticalPadding = 10.dp

    /** SectionCard 内部 Column 的上下缓冲，首尾行不贴卡边。 */
    val CardInnerVerticalPadding = 6.dp

    /** 卡内行分隔线粗细。 */
    val DividerThickness = 0.5.dp

    /** 分隔线左缩进（从内容文字下方起线，不顶到卡边）。 */
    val DividerStartInset = 16.dp

    /** 卡片前景色。 */
    @Composable
    @ReadOnlyComposable
    fun cardColor(): Color = NovexColors.Surface

    /** 页底背景色（卡片在它上面浮出）。 */
    @Composable
    @ReadOnlyComposable
    fun screenBackgroundColor(): Color = NovexColors.Background

    /** 卡内分隔线色。 */
    @Composable
    @ReadOnlyComposable
    fun dividerColor(): Color = NovexColors.Divider

    /** 脚注文字色。 */
    @Composable
    @ReadOnlyComposable
    fun footerColor(): Color = NovexColors.SecondaryText
}

/** 卡片上方的段标题。 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = NovexType.Metadata,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        color = NovexColors.SecondaryText,
        modifier = modifier.padding(
            horizontal = SectionDesign.ScreenHorizontalPadding,
        ).padding(bottom = SectionDesign.HeaderToCardGap),
    )
}

/** 卡片下方的脚注说明。 */
@Composable
fun SectionFooter(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = NovexType.Metadata,
        color = SectionDesign.footerColor(),
        modifier = modifier.padding(
            horizontal = SectionDesign.ScreenHorizontalPadding,
        ).padding(top = SectionDesign.CardToFooterGap),
    )
}

/** 一节的卡片容器：子行自带 [SectionDivider] 分隔。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    novex.android.ui.NovexSectionSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SectionDesign.ScreenHorizontalPadding),
    ) {
        Column(
            modifier = Modifier.padding(vertical = SectionDesign.CardInnerVerticalPadding),
            content = content,
        )
    }
}

/** [SectionCard] 内的行分隔线。 */
@Composable
fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SectionDesign.DividerStartInset),
        thickness = SectionDesign.DividerThickness,
        color = SectionDesign.dividerColor(),
    )
}

/**
 * 输入框上方的行内小标签。M3 的 floating label 在 10dp 行距里会压住输入
 * 文字，所以标签一律外置——调用方放在 SettingsCardBlock / 手动 padding
 * 的 Column 里，这里自身不加水平缩进避免双重缩进。
 */
@Composable
fun RowLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = NovexType.Metadata,
        color = NovexColors.SecondaryText,
        modifier = modifier.padding(bottom = 6.dp),
    )
}
