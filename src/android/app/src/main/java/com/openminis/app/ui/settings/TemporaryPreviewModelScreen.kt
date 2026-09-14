package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import com.openminis.app.data.model.TemporaryPreviewModel

@Composable
internal fun TemporaryPreviewModelScreen(onBack: () -> Unit) {
    SettingsScaffold(title = TemporaryPreviewModel.DISPLAY_NAME, onBack = onBack) {
        Text("测试版免费模型，可在对话中直接选择使用。")
        Text("此配置由测试版提供，不可修改。")
    }
}
