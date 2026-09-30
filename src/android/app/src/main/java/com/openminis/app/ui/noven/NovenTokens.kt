package com.openminis.app.ui.noven

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * home-v2 design tokens — scoped to the five root tabs and the bottom bar.
 * Dark/light follows the app's own MaterialTheme rather than the system flag.
 */
internal object NovenColors {
    private val dark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** 页面底 */
    val Canvas: Color
        @Composable
        @ReadOnlyComposable
        get() = if (dark) Color(0xFF0F1112) else Color(0xFFF4F5F4)

    /** 卡片 / 底栏 / 搜索框 */
    val Surface: Color
        @Composable
        @ReadOnlyComposable
        get() = if (dark) Color(0xFF1A1D1F) else Color(0xFFFFFFFF)

    /** 未选中胶囊 / 搜索框底 */
    val Muted: Color
        @Composable
        @ReadOnlyComposable
        get() = if (dark) Color(0xFF26292B) else Color(0xFFECEEEF)

    val Text: Color
        @Composable
        @ReadOnlyComposable
        get() = if (dark) Color(0xFFF2F3F3) else Color(0xFF16181A)

    val Secondary = Color(0xFF8A8F94)

    val Divider: Color
        @Composable
        @ReadOnlyComposable
        get() = Muted

    /** 品牌薄荷绿 = 主题 accent（默认预设为薄荷系），只用于选中态与关键动作 */
    val Mint: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.primary

    /** 薄荷绿上的文字 */
    val OnMint: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onPrimary

    val ChipSelectedBg: Color
        @Composable
        @ReadOnlyComposable
        get() = if (dark) Color(0xFFF2F3F3) else Color(0xFF16181A)

    val ChipSelectedText: Color
        @Composable
        @ReadOnlyComposable
        get() = if (dark) Color(0xFF16181A) else Color(0xFFFFFFFF)

    /** 底栏「我的」未读角标 */
    val Badge = Color(0xFFFF3B30)
}

internal object NovenDimens {
    val CardRadius = 14.dp
    val Hairline = 0.5.dp
    val PageHorizontal = 12.dp
}
