package com.openminis.app.ui.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType

/**
 * 单个共享目录的详情页：头部信息卡 + 「浏览文件」入口（跳到以对应宿主路径
 * 为根的应用内文件浏览器）。iOS 的「在 Files 中显示」没有 Android 对应物，
 * 不移植。
 */
@Composable
fun SharedFolderDetailScreen(
    folderId: String,
    onBack: () -> Unit,
    onBrowseFiles: () -> Unit,
) {
    val folder = SharedFolderRegistry.find(folderId)
    if (folder == null) {
        // 未知 id——直接弹回，不渲染空详情。
        LaunchedEffect(Unit) { onBack() }
        return
    }

    SettingsScaffold(
        title = stringResource(R.string.shared_folder_detail_title),
        onBack = onBack,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NovexDimensions.PageHorizontal),
        ) {
            Spacer(Modifier.height(16.dp))
            SharedFolderHeaderCard(folder)

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
                    .background(NovexColors.Surface)
                    .clickable(onClick = onBrowseFiles)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(NovexIcons.Folder, contentDescription = null, tint = NovexColors.Primary)
                Text(
                    stringResource(R.string.shared_folder_browse_files),
                    style = NovexType.ItemTitle,
                    fontWeight = FontWeight.Medium,
                    color = NovexColors.Text,
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SharedFolderHeaderCard(folder: SharedFolderEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
            .background(NovexColors.Surface)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(folder.iconColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                folder.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(folder.nameRes),
                    style = NovexType.PageTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                MountAccessBadge(writable = folder.writable)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                folder.linuxPath,
                style = NovexType.Metadata.copy(fontFamily = FontFamily.Monospace),
                color = NovexColors.SecondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
