package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.service.DynamicIslandSupport
import novex.android.ui.NovexCheckToggle
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.novexScaledSp

/**
 * 后台保活设置页。App 翻不动的两项系统开关（电池白名单、厂商自启动）
 * 只负责把用户带到对应的系统页；状态在 ON_RESUME 重新探测。
 * 上面三个开关（任务通知/悬浮层/实时活动）是本应用内的偏好，直接写库。
 */
@Composable
fun BackgroundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repo = (context.applicationContext as MinisApp).backgroundSettingsRepository

    val taskNotificationsEnabled by repo.taskNotificationsEnabled.collectAsState()
    val backgroundOverlayEnabled by repo.backgroundOverlayEnabled.collectAsState()
    val dynamicIslandEnabled by repo.dynamicIslandEnabled.collectAsState()

    var ignoringOptimizations by remember {
        mutableStateOf(PowerOptimizationManager.isIgnoringBatteryOptimizations(context))
    }
    var canDrawOverlays by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context),
        )
    }
    var dynamicIslandCapable by remember {
        mutableStateOf(DynamicIslandSupport.isDynamicIslandCapable(context))
    }

    // 从系统页回来（ON_RESUME）时重探三项系统态。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringOptimizations =
                    PowerOptimizationManager.isIgnoringBatteryOptimizations(context)
                canDrawOverlays =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
                dynamicIslandCapable = DynamicIslandSupport.isDynamicIslandCapable(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val vendor = remember { PowerOptimizationManager.Vendor.current() }
    val needsOemGuidance = remember { PowerOptimizationManager.needsOemAutostartGuidance() }

    SettingsScaffold(title = stringResource(R.string.bg_section_header), onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NovexDimensions.PageHorizontal),
        ) {
            BgSectionLabel(stringResource(R.string.settings_section_notifications))

            BgToggleLine(
                icon = NovexIcons.NotificationsActive,
                iconColor = Color(0xFF007AFF),
                title = stringResource(R.string.settings_task_notifications),
                checked = taskNotificationsEnabled,
                onCheckedChange = repo::setTaskNotificationsEnabled,
            )
            BgFootnote(stringResource(R.string.settings_task_notifications_footer))

            // 悬浮层：没拿到 SYSTEM_ALERT_WINDOW 就深链到系统授权页；用户意愿
            // 先落库，回来后 canDrawOverlays 重探到 true 即生效。
            BgToggleLine(
                icon = NovexIcons.Layers,
                iconColor = Color(0xFF5856D6),
                title = stringResource(R.string.settings_bg_overlay),
                checked = backgroundOverlayEnabled && canDrawOverlays,
                onCheckedChange = { wanted ->
                    if (wanted && !canDrawOverlays) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + context.packageName),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        repo.setBackgroundOverlayEnabled(true)
                    } else {
                        repo.setBackgroundOverlayEnabled(wanted)
                    }
                },
            )
            BgFootnote(
                stringResource(
                    if (!canDrawOverlays && backgroundOverlayEnabled) {
                        R.string.settings_bg_overlay_permission_needed
                    } else {
                        R.string.settings_bg_overlay_footer
                    },
                ),
            )

            // 实时活动仅 Android 16+ 且有 per-app 授权才可点；开时替代悬浮层
            // （互斥逻辑在 AgentForegroundService.applyOverlayState）。
            BgToggleLine(
                icon = NovexIcons.Bolt,
                iconColor = Color(0xFF34C759),
                title = stringResource(R.string.settings_dynamic_island),
                checked = dynamicIslandEnabled && dynamicIslandCapable,
                enabled = dynamicIslandCapable,
                onCheckedChange = repo::setDynamicIslandEnabled,
            )
            BgFootnote(
                stringResource(
                    if (dynamicIslandCapable) {
                        R.string.settings_dynamic_island_footer
                    } else {
                        R.string.settings_dynamic_island_unsupported
                    },
                ),
            )

            Spacer(Modifier.size(16.dp))
            BgSectionLabel(stringResource(R.string.battery_opt_section_title))
            BgActionLine(
                icon = NovexIcons.BatteryFull,
                iconColor = if (ignoringOptimizations) Color(0xFF34C759) else Color(0xFFFF9500),
                title = stringResource(R.string.battery_opt_row_title),
                subtitle = stringResource(
                    if (ignoringOptimizations) {
                        R.string.battery_opt_already_exempt
                    } else {
                        R.string.battery_opt_request_subtitle
                    },
                ),
                onClick = {
                    if (!ignoringOptimizations) {
                        activity?.let(PowerOptimizationManager::requestBatteryOptimizationExemption)
                    }
                },
            )
            BgFootnote(stringResource(R.string.battery_opt_section_footer))

            if (needsOemGuidance) {
                Spacer(Modifier.size(16.dp))
                BgSectionLabel(stringResource(R.string.rom_autostart_section_title))
                BgActionLine(
                    icon = NovexIcons.PhoneAndroid,
                    iconColor = Color(0xFFFF9500),
                    title = stringResource(R.string.rom_autostart_row_title),
                    subtitle = stringResource(R.string.rom_autostart_row_subtitle, vendor.displayName),
                    onClick = {
                        activity?.let { act ->
                            if (!PowerOptimizationManager.openOemAutostartSettings(act)) {
                                PowerOptimizationManager.openAppDetailsSettings(act)
                            }
                        }
                    },
                )
                BgFootnote(stringResource(R.string.rom_autostart_section_footer, vendor.displayName))
            }

            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BgSectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = novexScaledSp(12),
        color = NovexColors.SecondaryText,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

@Composable
private fun BgFootnote(text: String) {
    Text(
        text,
        fontSize = novexScaledSp(12),
        color = NovexColors.SecondaryText,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 8.dp),
    )
}

@Composable
private fun BgIconChip(icon: ImageVector, color: Color, alpha: Float = 1f) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f * alpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color.copy(alpha = alpha), modifier = Modifier.size(18.dp))
    }
}

private val bgRowShape = RoundedCornerShape(12.dp)

@Composable
private fun BgToggleLine(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(bgRowShape)
            .background(NovexColors.Surface, bgRowShape)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BgIconChip(icon, iconColor, alpha)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            fontSize = novexScaledSp(15),
            fontWeight = FontWeight.Medium,
            color = NovexColors.Text.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        NovexCheckToggle(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun BgActionLine(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(bgRowShape)
            .background(NovexColors.Surface, bgRowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BgIconChip(icon, iconColor)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(end = 6.dp)) {
            Text(
                title,
                fontSize = novexScaledSp(15),
                fontWeight = FontWeight.Medium,
                color = NovexColors.Text,
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, fontSize = novexScaledSp(12), color = NovexColors.SecondaryText)
            }
        }
    }
}
