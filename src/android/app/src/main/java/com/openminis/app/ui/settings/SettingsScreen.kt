package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.ModalBottomSheet
import com.openminis.app.ui.novex.Scaffold
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl
import com.openminis.app.ui.novex.NovexSettingsRow
import com.openminis.app.ui.novex.NovexSettingsScaffold
import com.openminis.app.ui.novex.NovexSettingsSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onProvidersClick: () -> Unit,
    onModelGroupsClick: () -> Unit,
    onImageGenerationClick: () -> Unit = {},
    onRootfsClick: () -> Unit = {},
    onEnvVarsClick: () -> Unit = {},
    onSkillsClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    // [T-mcp-integration-android] MCP Integrations page, listed directly below
    // Memory. Default no-op for callers that haven't wired the route yet.
    onMcpClick: () -> Unit = {},
    // [T-soul-md] Soul settings page lives between Skills and Memory in the
    // Agent Runtime section; default no-op for callers that haven't wired
    // the route yet.
    onSoulClick: () -> Unit = {},
    onPermissionsClick: () -> Unit = {},
    onUsageClick: () -> Unit = {},
    onAppearanceClick: () -> Unit = {},
    onLogsClick: () -> Unit = {},
    // T219-2: Mount External Folders entry. Default no-op for any caller
    // that hasn't wired the route yet.
    onMountedFoldersClick: () -> Unit = {},
    // T235: Shared Folders entry (Shared / Skills / Memory). Default no-op
    // for back-compat with callers wired before T235.
    onSharedFoldersClick: () -> Unit = {},
    // T50: Background & Notifications screen (battery optimisation +
    // OEM autostart guidance). Default no-op so older callers/tests
    // don't need to be retrofitted.
    onBackgroundClick: () -> Unit = {},
    // Hook accepted for forward-compat with AppNavigation's About route. The
    // About row below still has a TODO onClick in HEAD; future settings-bucket
    // work will wire this through.
    onAboutClick: () -> Unit = {},
    onFeedbackClick: () -> Unit = {},
    onCreativeLibraryClick: () -> Unit = {},
) {
    val context = LocalContext.current
    NovexSettingsScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        NovexSettingsSection(title = "反馈与交流") {
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_chats,
                title = "QQ 反馈与交流",
                subtitle = "加入 QQ 群反馈问题、建议新功能",
                showDivider = false,
                onClick = onFeedbackClick,
            )
        }
        NovexSettingsSection(title = "创作与内容") {
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_note_pencil,
                title = "创作库",
                subtitle = "集中查看对话生成的文档、图片、地图和卡片",
                showDivider = false,
                onClick = onCreativeLibraryClick,
            )
        }
        NovexSettingsSection(
            title = stringResource(R.string.settings_section_llm_providers),
            footer = stringResource(R.string.settings_section_llm_providers_footer),
        ) {
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_lock,
                title = stringResource(R.string.settings_manage_providers),
                subtitle = stringResource(R.string.settings_manage_providers_subtitle),
                onClick = onProvidersClick,
            )
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_image,
                title = "生图服务",
                subtitle = "独立接口、模型与自动降级分组",
                onClick = onImageGenerationClick,
            )
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_chart_bar,
                title = stringResource(R.string.settings_token_usage),
                subtitle = stringResource(R.string.settings_token_usage_subtitle),
                showDivider = false,
                onClick = onUsageClick,
            )
        }
        NovexSettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_palette,
                title = stringResource(R.string.settings_section_appearance),
                subtitle = stringResource(R.string.settings_appearance_subtitle),
                showDivider = false,
                onClick = onAppearanceClick,
            )
        }
        NovexSettingsSection(title = stringResource(R.string.settings_section_agent_runtime)) {
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_puzzle_piece,
                title = stringResource(R.string.settings_skills),
                subtitle = stringResource(R.string.settings_skills_subtitle),
                onClick = onSkillsClick,
            )
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_sparkle,
                title = stringResource(R.string.settings_soul),
                subtitle = stringResource(R.string.settings_soul_subtitle),
                onClick = onSoulClick,
            )
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_brain,
                title = stringResource(R.string.settings_memory),
                subtitle = stringResource(R.string.settings_memory_subtitle),
                showDivider = false,
                onClick = onMemoryClick,
            )
        }
        NovexSettingsSection(title = stringResource(R.string.settings_section_about)) {
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_info,
                title = stringResource(R.string.settings_about_minis),
                subtitle = stringResource(R.string.settings_about_subtitle),
                onClick = onAboutClick,
            )
            NovexSettingsRow(
                icon = R.drawable.ic_phosphor_shield,
                title = stringResource(R.string.settings_privacy_policy),
                showDivider = false,
                onClick = { openExternalUrl(context, "https://openminis.github.io/privacy-policy.html") },
            )
        }
    }
}
