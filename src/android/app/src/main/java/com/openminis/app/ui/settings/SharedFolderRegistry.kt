package com.openminis.app.ui.settings

// 三个一等共享目录的静态注册表 + R/W 徽章。/var/minis/{shared,skills,memory}
// skills/memory 由 agent 工具维护，界面只读——防止用户手动改乱。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType


/** R/W 与只读挂载徽章；列表与详情页共用。 */
@Composable
internal fun MountAccessBadge(writable: Boolean) {
    val (textRes, color) = if (writable) {
        R.string.mount_badge_rw to Color(0xFF34C759)
    } else {
        R.string.mount_badge_readonly to Color(0xFFFF9500)
    }
    Text(
        stringResource(textRes),
        style = NovexType.Metadata,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

internal data class SharedFolderEntry(
    val id: String,
    val nameRes: Int,
    val linuxPath: String,
    val writable: Boolean,
    val icon: ImageVector,
    val iconColor: Color,
)

internal object SharedFolderRegistry {
    val entries: List<SharedFolderEntry> = listOf(
        SharedFolderEntry(
            id = "shared",
            nameRes = R.string.shared_folder_name_shared,
            linuxPath = "/var/minis/shared",
            writable = true,
            icon = NovexIcons.Folder,
            iconColor = Color(0xFF007AFF),
        ),
        SharedFolderEntry(
            id = "skills",
            nameRes = R.string.shared_folder_name_skills,
            linuxPath = "/var/minis/skills",
            writable = false,
            icon = NovexIcons.AutoAwesome,
            iconColor = Color(0xFFAF52DE),
        ),
        SharedFolderEntry(
            id = "memory",
            nameRes = R.string.shared_folder_name_memory,
            linuxPath = "/var/minis/memory",
            writable = false,
            icon = NovexIcons.Psychology,
            iconColor = Color(0xFFFF2D55),
        ),
    )

    fun find(id: String): SharedFolderEntry? = entries.firstOrNull { it.id == id }
}

