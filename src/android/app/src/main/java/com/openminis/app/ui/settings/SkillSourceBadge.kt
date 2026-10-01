package com.openminis.app.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.openminis.app.data.repository.SkillRepository
import novex.android.ui.NovexIcons

/**
 * 技能来源角标的图标/配色映射。设置页技能列表与会话内技能面板共用，
 * 保证两处角标一致。
 */
fun sourceIconAndColor(source: SkillRepository.ImportSource): Pair<ImageVector, Color> = when (source) {
    SkillRepository.ImportSource.URL -> NovexIcons.Link to Color(0xFF007AFF)
    SkillRepository.ImportSource.FILE -> NovexIcons.Description to Color(0xFFFF9500)
    SkillRepository.ImportSource.BUNDLED -> NovexIcons.Inventory2 to Color(0xFF34C759)
    SkillRepository.ImportSource.SESSION -> NovexIcons.ChatBubble to Color(0xFFAF52DE)
}
