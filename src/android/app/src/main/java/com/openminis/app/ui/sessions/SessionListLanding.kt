package com.openminis.app.ui.sessions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

// ─── 轻量启动面空态 ────────────────────────────────────────────────────────

/**
 * providerRuntimeAvailable == false（NovexHomeSurface 冷启动路径）时的普通
 * 空态：不渲染 onboarding 三步引导——那三步全都依赖 provider 运行时。
 * 「新建对话」由宿主接到 openLegacy，点按时才走 ensureRuntime 拉起运行时。
 * 视觉等价于已删除的 NovexConversationRoot 空态。
 */
@Composable
internal fun SessionListEmptyState(onNewChat: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 80.dp),
    ) {
        Text(
            "还没有对话",
            color = com.openminis.app.ui.noven.NovenColors.Text,
            fontSize = novex.android.ui.novexScaledSp(18),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "从一个新的想法开始",
            color = com.openminis.app.ui.noven.NovenColors.Secondary,
            fontSize = novex.android.ui.novexScaledSp(14),
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(
            "新建对话",
            color = novex.android.ui.NovexColors.Primary,
            fontSize = novex.android.ui.novexScaledSp(15),
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .padding(top = 18.dp)
                .clickable(onClick = onNewChat)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

// ─── Onboarding Landing (iOS-style 3-step setup) ───────────────────────────

@Composable
internal fun OnboardingLanding(
    hasProviders: Boolean,
    hasGroups: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.novex_logo_transparent),
                contentDescription = stringResource(R.string.novex_logo_description),
                modifier = Modifier.size(68.dp),
            )
            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.sessionlist_welcome_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.sessionlist_welcome_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SetupStepCard(
                    number = 1,
                    title = stringResource(R.string.sessionlist_welcome_step1_title),
                    subtitle = if (hasProviders) {
                        stringResource(R.string.sessionlist_welcome_step_done)
                    } else {
                        stringResource(R.string.sessionlist_welcome_step1_subtitle)
                    },
                    isDone = hasProviders,
                    isLocked = false,
                    onClick = { if (!hasProviders) onAddProvider() },
                )

                SetupStepCard(
                    number = 2,
                    title = stringResource(R.string.sessionlist_welcome_step2_title),
                    subtitle = when {
                        hasGroups -> stringResource(R.string.sessionlist_welcome_step_done)
                        hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                        else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                    },
                    isDone = hasGroups,
                    isLocked = !hasProviders,
                    onClick = { if (hasProviders && !hasGroups) onSelectModels() },
                )

                SetupStepCard(
                    number = 3,
                    title = stringResource(R.string.sessionlist_welcome_step3_title),
                    subtitle = if (hasGroups) {
                        stringResource(R.string.sessionlist_welcome_step3_subtitle)
                    } else {
                        stringResource(R.string.sessionlist_welcome_draft_hint)
                    },
                    isDone = false,
                    isLocked = false,
                    onClick = onStartConversation,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.novex_openminis_thanks),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                MinisTextButton(
                    onClick = { uriHandler.openUri("https://github.com/ccbili30-collab/novex-android") },
                ) {
                    Text(stringResource(R.string.novex_star_openminis))
                }
                MinisTextButton(
                    onClick = { uriHandler.openUri("https://github.com/ccbili30-collab/novex-android") },
                ) {
                    Text(stringResource(R.string.novex_star_novex))
                }
            }
        }
    }
}

@Composable
internal fun SetupStepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isDone) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = novex.android.ui.NovexIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).height(56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isDone && isEnabled) {
            Icon(
                imageVector = novex.android.ui.NovexIcons.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

