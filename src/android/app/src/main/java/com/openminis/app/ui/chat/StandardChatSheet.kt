package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import novex.android.ui.ModalBottomSheet
import novex.android.ui.NovexIcons

/**
 * 聊天「⋯」菜单弹出的统一半屏面板壳：顶部细把手、居中标题 + 可选左槽
 * + 右侧关闭、细分隔线，下方是调用方给的 [content]。
 *
 * [heightFraction] 给要小档位的调用方（如 TokenUsageSheet 的 0.5），
 * 钳在 (0,1] 防止把面板压没。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StandardChatSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    heightFraction: Float = 0.9f,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ChatColors.background,
        dragHandle = { ChatSheetGrabber() },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(heightFraction.coerceIn(0.1f, 1f))) {
            // 标题条：标题居中，左槽与关闭按钮分居两侧（Box 叠层而非三段 Row）。
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).height(48.dp)) {
                Box(Modifier.align(Alignment.CenterStart)) { leadingAction?.invoke() }
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(
                        NovexIcons.Close,
                        contentDescription = stringResource(R.string.standard_sheet_close),
                        tint = ChatColors.secondaryText,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** 比 Material 默认把手更扁的指示条（上下间距收紧）。 */
@Composable
private fun ChatSheetGrabber() {
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .width(32.dp)
                .height(4.dp)
                .background(ChatColors.secondaryText.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
    }
}
