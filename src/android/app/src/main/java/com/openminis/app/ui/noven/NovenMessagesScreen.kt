package com.openminis.app.ui.noven

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/** 消息 tab：当前只有空态，后续真实通知进来再长内容。 */
@Composable
internal fun NovenMessagesScreen() {
    Column(Modifier.fillMaxSize().padding(horizontal = NovenDimens.PageHorizontal)) {
        Text(
            "消息",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = NovenColors.Text,
            modifier = Modifier.padding(top = 8.dp),
        )
        NovenEmptyState(
            icon = R.drawable.ic_phosphor_bell,
            title = "还没有消息",
            subtitle = "有人关注你、珍藏或进入你的世界时，会在这里告诉你",
            modifier = Modifier.fillMaxSize(),
        )
    }
}
