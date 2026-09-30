package com.openminis.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.ui.components.openExternalUrl

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 中性图标规则：链接图标用正文色，不用品牌/强调色。
    val iconTint = MaterialTheme.colorScheme.onSurface

    SettingsScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconPainter = remember(context) {
                // painterResource() can't load adaptive-icon XML drawables (mipmap-anydpi-v26),
                // so fetch the launcher icon as a Drawable and convert to a Bitmap.
                val drawable = context.packageManager.getApplicationIcon(context.packageName)
                BitmapPainter(drawable.toBitmap(width = 192, height = 192).asImageBitmap())
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                )
            }
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    R.string.about_version_format,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toString(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.about_minis_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        SettingsSection(header = stringResource(R.string.about_links)) {
            SettingsRow(
                icon = novex.android.ui.NovexIcons.Code,
                iconColor = iconTint,
                title = stringResource(R.string.about_github_repository),
                // Settings → ABOUT siblings (Privacy Policy / Submit GitHub
                // Issues) all use openExternalUrl directly. [P3.3 裁军]
                // LocalInAppBrowserLauncher 氛围值已随内置浏览器退役删除。
                // nothing wraps Settings, so the row used to be a dead tap.
                onClick = { openExternalUrl(context, "https://github.com/ccbili30-collab/novex-android") },
                trailing = { ExternalLinkIcon() },
                showDivider = false,
            )
        }

        // T122: surface the existing UpdateChecker entry on the About screen.
        // The composable was already implemented but never wired anywhere, so
        // users had no way to trigger a check.
        CheckUpdateSection()

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ExternalLinkIcon() {
    Text(
        "↗",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Back-compat shim for legacy call sites that still call [openUrl] with a
 * Context. Dispatches as a system Intent — the in-app preview path is the new
 * [P3.3 裁军] 内置浏览器预览退役后唯一路径就是系统外跳。
 */
internal fun openUrl(context: android.content.Context, url: String) {
    openExternalUrl(context, url)
}
