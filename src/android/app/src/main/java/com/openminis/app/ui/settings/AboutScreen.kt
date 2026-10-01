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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    SettingsScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        AboutHero()
        SettingsSection(header = stringResource(R.string.about_links)) {
            SettingsRow(
                icon = NovexIcons.Code,
                // 链接图标用正文色，不吃品牌色。
                iconColor = NovexColors.Text,
                title = stringResource(R.string.about_github_repository),
                onClick = { openExternalUrl(context, "https://github.com/ccbili30-collab/novex-android") },
                trailing = {
                    Text("↗", style = NovexType.Body, color = NovexColors.SecondaryText)
                },
                showDivider = false,
            )
        }
        CheckUpdateSection()
        Spacer(Modifier.height(24.dp))
    }
}

/** 图标 + 应用名 + 版本 + 标语的头部区。 */
@Composable
private fun AboutHero() {
    val context = LocalContext.current
    // painterResource 读不了 adaptive-icon 的 mipmap XML，走 PackageManager 取 Drawable 转 Bitmap。
    val iconPainter = remember(context) {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        BitmapPainter(drawable.toBitmap(width = 192, height = 192).asImageBitmap())
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = iconPainter,
            contentDescription = null,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .border(NovexDimensions.Hairline, NovexColors.Divider, CircleShape)
                .background(NovexColors.Surface, CircleShape),
        )
        Text(
            stringResource(R.string.app_name),
            style = NovexType.PageTitle,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(
                R.string.about_version_format,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE.toString(),
            ),
            style = NovexType.Body,
            color = NovexColors.SecondaryText,
        )
        Text(
            stringResource(R.string.about_minis_tagline),
            style = NovexType.Body,
            color = NovexColors.SecondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/**
 * 旧调用点的兼容垫片：内置浏览器退役后统一走系统外跳。
 */
internal fun openUrl(context: android.content.Context, url: String) {
    openExternalUrl(context, url)
}
