package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import com.openminis.app.data.repository.ProviderRepository

/**
 * Novex 的提供商详情统一使用精简连接页。
 *
 * Minis 原有的协议、Azure（微软云）、图像、语音和调试配置仍由底层默认值兼容，
 * 但不再暴露给 Novex 用户，避免把普通文游用户带进开发工具配置。
 */
@Composable
fun ProviderDetailScreen(
    instanceId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
    onModelEntryClick: (String) -> Unit = {},
    onAddCustomModel: () -> Unit = {},
    onVoiceServiceClick: (String) -> Unit = {},
) {
    NovexProviderSetupScreen(
        providerRepository = providerRepository,
        onBack = onBack,
        onSaved = onBack,
        instanceId = instanceId,
    )
}
