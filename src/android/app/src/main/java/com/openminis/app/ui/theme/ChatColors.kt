package com.openminis.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// 会话页语义调色板。字段按用途分组：底板 → 文字 → 输入区 → 气泡/工具块 →
// Markdown 元素 → 杂项。MinisTheme 通过 LocalChatPalette 提供；
// 消费侧统一走 ChatColors.xxx 短访问器。
@Immutable
data class ChatPalette(
    val isDark: Boolean,

    // 底板层
    val background: Color,
    val secondaryBg: Color,
    val sheetHeaderBg: Color,
    val sheetHeaderBorder: Color,
    val toastBg: Color,

    // 文字层级
    val primaryText: Color,
    val secondaryText: Color,
    val tertiaryText: Color,
    val disabledText: Color,

    // 输入区
    val inputBg: Color,
    val inputIconBg: Color,
    val inputIconBorder: Color,
    val inputBorder: Color,
    val inputShadow: Color,
    val sendButton: Color,
    val sendButtonDisabled: Color,

    // 气泡与工具块
    val userBubble: Color,
    val toolBg: Color,
    val toolBorder: Color,
    val toolCapsuleBg: Color,
    val thumbnailBorder: Color,

    // Markdown 元素
    val codeBlockBg: Color,
    val codeBlockText: Color,
    val inlineCodeBg: Color,
    val inlineCodeText: Color,
    val link: Color,
    val blockquoteBar: Color,
    val tableBorder: Color,

    // 提示与状态
    val thinking: Color,
    val warningBg: Color,
    val warningText: Color,
    val separator: Color,
    val fabAccent: Color,
)

/**
 * 调色规格：apply 块里按语义位赋值，build() 按字段顺序展开成
 * [ChatPalette]——色表只剩「语义名 = 值」两列，不再重复参数名。
 */
private class ChatPaletteSpec(val isDark: Boolean) {
    var background = Color.Unspecified; var secondaryBg = Color.Unspecified
    var sheetHeaderBg = Color.Unspecified; var sheetHeaderBorder = Color.Unspecified
    var toastBg = Color.Unspecified
    var primaryText = Color.Unspecified; var secondaryText = Color.Unspecified
    var tertiaryText = Color.Unspecified; var disabledText = Color.Unspecified
    var inputBg = Color.Unspecified; var inputIconBg = Color.Unspecified
    var inputIconBorder = Color.Unspecified; var inputBorder = Color.Unspecified
    var inputShadow = Color.Unspecified; var sendButton = Color.Unspecified
    var sendButtonDisabled = Color.Unspecified
    var userBubble = Color.Unspecified; var toolBg = Color.Unspecified
    var toolBorder = Color.Unspecified; var toolCapsuleBg = Color.Unspecified
    var thumbnailBorder = Color.Unspecified
    var codeBlockBg = Color.Unspecified; var codeBlockText = Color.Unspecified
    var inlineCodeBg = Color.Unspecified; var inlineCodeText = Color.Unspecified
    var link = Color.Unspecified; var blockquoteBar = Color.Unspecified
    var tableBorder = Color.Unspecified
    var thinking = Color.Unspecified; var warningBg = Color.Unspecified
    var warningText = Color.Unspecified; var separator = Color.Unspecified
    var fabAccent = Color.Unspecified

    fun build() = ChatPalette(
        isDark = isDark,
        background = background, secondaryBg = secondaryBg,
        sheetHeaderBg = sheetHeaderBg, sheetHeaderBorder = sheetHeaderBorder,
        toastBg = toastBg,
        primaryText = primaryText, secondaryText = secondaryText,
        tertiaryText = tertiaryText, disabledText = disabledText,
        inputBg = inputBg, inputIconBg = inputIconBg,
        inputIconBorder = inputIconBorder, inputBorder = inputBorder,
        inputShadow = inputShadow, sendButton = sendButton,
        sendButtonDisabled = sendButtonDisabled,
        userBubble = userBubble, toolBg = toolBg, toolBorder = toolBorder,
        toolCapsuleBg = toolCapsuleBg, thumbnailBorder = thumbnailBorder,
        codeBlockBg = codeBlockBg, codeBlockText = codeBlockText,
        inlineCodeBg = inlineCodeBg, inlineCodeText = inlineCodeText,
        link = link, blockquoteBar = blockquoteBar, tableBorder = tableBorder,
        thinking = thinking, warningBg = warningBg, warningText = warningText,
        separator = separator, fabAccent = fabAccent,
    )
}

val LightChatPalette: ChatPalette = ChatPaletteSpec(isDark = false).apply {
    background = Color.White
    secondaryBg = Color(0xFFF2F2F7)
    sheetHeaderBg = Color(0xFFFFFFFF)
    sheetHeaderBorder = Color(0x1A000000)
    toastBg = Color(0x2E0E9F6E)

    primaryText = Color(0xFF000000)
    secondaryText = Color(0x993C3C43)
    tertiaryText = Color(0x4D3C3C43)
    disabledText = Color(0x2E3C3C43)

    inputBg = Color.White
    inputIconBg = Color(0xFFF2F2F7)
    inputIconBorder = Color.Transparent
    inputBorder = Color(0x4D3C3C43)
    inputShadow = Color.Transparent
    sendButton = Color(0xFF000000)
    sendButtonDisabled = Color(0x2E3C3C43)

    userBubble = Color(0x1E787880)
    toolBg = Color(0xFFF2F2F7)
    toolBorder = Color(0x14000000)
    toolCapsuleBg = Color(0xFFF2F2F7)
    thumbnailBorder = Color(0x33808080)

    codeBlockBg = Color(0xFF000000)
    codeBlockText = Color(0xFF34C759)
    inlineCodeBg = Color(0xFFF2F2F7)
    inlineCodeText = Color(0xFFFF9500)
    link = Color(0xFF0E9F6E)
    blockquoteBar = Color(0x80FF9500)
    tableBorder = Color(0x1F000000)

    thinking = Color(0xFF0E9F6E)
    warningBg = Color(0x14FF9500)
    warningText = Color(0x73000000)
    separator = Color(0x4D3C3C43)
    fabAccent = Color(0xFFB7AF96)
}.build()

// 暗色板注意：Android 中端机亮度远低于 iOS 千元尼特屏，非背景层整体
// 抬 ~6-10%，否则 #1C1C1E 式分层在 #000 底上糊成一团。背景本身留纯黑。
// userBubble 用不透明冷灰蓝而不是 14% 透明蓝灰——半透明在纯黑底上几乎看不见。
// inlineCodeBg 抬到 #34343A，高于 codeBlockBg(#262626) 低于 toolBg(#3A3A3F)，
// 行内小胶囊在两种底上都读得出来。
val DarkChatPalette: ChatPalette = ChatPaletteSpec(isDark = true).apply {
    background = Color(0xFF000000)
    secondaryBg = Color(0xFF26262A)
    sheetHeaderBg = Color(0xFF2C2C2E)
    sheetHeaderBorder = Color(0x33FFFFFF)
    toastBg = Color(0x2E34D399)

    primaryText = Color(0xFFFFFFFF)
    secondaryText = Color(0x99EBEBF5)
    tertiaryText = Color(0x4DEBEBF5)
    disabledText = Color(0x2EEBEBF5)

    inputBg = Color(0xFF2C2C30)
    inputIconBg = Color(0xFF1C1C1E)
    inputIconBorder = Color(0xFF595959)
    inputBorder = Color(0x40545458)
    inputShadow = Color(0x80000000)
    sendButton = Color(0xFFFFFFFF)
    sendButtonDisabled = Color(0x2EEBEBF5)

    userBubble = Color(0xFF2F3A5C)
    toolBg = Color(0xFF3A3A3F)
    toolBorder = Color(0x40545458)
    toolCapsuleBg = Color(0xFF28282C)
    thumbnailBorder = Color(0x20545458)

    codeBlockBg = Color(0xFF262626)
    codeBlockText = Color(0xFF8CF38C)
    inlineCodeBg = Color(0xFF34343A)
    inlineCodeText = Color(0xFFFF9F0A)
    link = Color(0xFF34D399)
    blockquoteBar = Color(0x80FF9F0A)
    tableBorder = Color(0xFF38383A)

    thinking = Color(0xFF34D399)
    warningBg = Color(0x14FF9F0A)
    warningText = Color(0x73FFFFFF)
    separator = Color(0x99545458)
    fabAccent = Color(0xFF504C42)
}.build()

val LocalChatPalette = compositionLocalOf { LightChatPalette }

// 短访问器：ChatColors.primaryText
val ChatColors: ChatPalette
    @Composable
    @ReadOnlyComposable
    get() = LocalChatPalette.current
