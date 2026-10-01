package com.openminis.app.ui.chat

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.rememberScrollableState

/**
 * 「内容不满屏也有拉伸回弹」的盒子（血统清剿 P3.7 就地真重写；组合结构
 * 为行为冻结面）。
 *
 * LazyColumn 在 canScrollForward/Backward 双双为 false 后就不再跑拖拽处
 * 理器，列表内容塞得进视口时平台 stretch overscroll 永远不触发。用本盒
 * 包住列表把它找回来：外层 scrollable 永远接手势、消耗 0、把增量灌进共
 * 享的拉伸效果；内层 LazyColumn 拿同一个效果，自己的溢出也一并转发。
 *
 * 用法：
 *   AlwaysStretchOverscrollBox { effect ->
 *     LazyColumn(overscrollEffect = effect, ...) { ... }
 *   }
 */
@Composable
fun AlwaysStretchOverscrollBox(
    modifier: Modifier = Modifier,
    content: @Composable (OverscrollEffect?) -> Unit,
) {
    val stretchEffect = rememberOverscrollEffect()
    val absorbEverythingState = rememberScrollableState { 0f }
    val shell = if (stretchEffect != null) {
        modifier
            .fillMaxSize()
            .scrollable(
                state = absorbEverythingState,
                orientation = Orientation.Vertical,
                overscrollEffect = stretchEffect,
            )
            .overscroll(stretchEffect)
    } else {
        modifier.fillMaxSize()
    }
    Box(modifier = shell) {
        content(stretchEffect)
    }
}
