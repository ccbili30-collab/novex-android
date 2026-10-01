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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 设置 → 共享目录。/var/minis/{shared,skills,memory} 三个一等目录入口。
 * skills/memory 由 agent 工具维护，这里只读，防止用户手动改乱。
 */
@Composable
fun SharedFoldersScreen(
    onBack: () -> Unit,
    onFolderClick: (folderId: String) -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.shared_folders_title), onBack = onBack) {
        Text(
            stringResource(R.string.shared_folders_info_banner),
            style = NovexType.Metadata,
            color = NovexColors.SecondaryText,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 8.dp)
                .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
                .background(NovexColors.Surface)
                .padding(14.dp),
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SharedFolderRegistry.entries, key = { it.id }) { folder ->
                SharedFolderRow(
                    folder = folder,
                    onClick = { onFolderClick(folder.id) },
                    modifier = Modifier.padding(horizontal = NovexDimensions.PageHorizontal),
                )
            }
        }
    }
}

@Composable
private fun SharedFolderRow(
    folder: SharedFolderEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
            .background(NovexColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            folder.icon,
            contentDescription = null,
            tint = folder.iconColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(folder.nameRes),
                    style = NovexType.ItemTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                MountAccessBadge(writable = folder.writable)
            }
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
