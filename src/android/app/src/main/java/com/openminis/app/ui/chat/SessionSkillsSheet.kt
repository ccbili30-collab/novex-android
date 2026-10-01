package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.ui.settings.sourceIconAndColor
import novex.android.ui.GhostIconButton
import novex.android.ui.ModalBottomSheet
import novex.android.ui.NovexCheckToggle
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexIcons
import novex.android.ui.NovexSearchField
import novex.android.ui.NovexType

/**
 * 会话内技能开关面板（自有实现）。
 *
 * 列出全部技能，按会话维度启停：[SkillRepository.setSessionOverride]
 * 只写本会话覆盖，不改全局开关。支持搜索过滤；「全部启用/全部停用」只
 * 作用于当前过滤结果。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SessionSkillsSheet(
    sessionId: String,
    skillRepository: SkillRepository,
    onDismiss: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsState()

    // 覆盖表以 skills 为键重建：列表异步到达时重新播种，避免行卡在
    // 首次空列表的全局默认值上。
    val overrides = remember(skills) {
        mutableStateMapOf<String, Boolean>().apply {
            skills.forEach { put(it.id, skillRepository.isEnabledForSession(it.id, sessionId)) }
        }
    }

    var query by remember { mutableStateOf("") }
    val shown = remember(skills, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) skills
        else skills.filter { q in it.name.lowercase() || q in it.description.lowercase() }
    }

    fun toggleAll(enabled: Boolean) {
        shown.forEach {
            overrides[it.id] = enabled
            skillRepository.setSessionOverride(sessionId, it.id, enabled)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            // 顶栏：标题 + 关闭
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NovexDimensions.PageHorizontal)
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.session_skills_title),
                    style = NovexType.PageTitle,
                    color = NovexColors.Text,
                    modifier = Modifier.weight(1f),
                )
                GhostIconButton(
                    icon = NovexIcons.Close,
                    contentDescription = "关闭",
                    onClick = onDismiss,
                )
            }

            if (skills.isEmpty()) {
                SessionSkillsEmpty()
            } else {
                NovexSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.skills_search_placeholder),
                    onClear = { query = "" },
                )

                // 区段标签 + 批量开关（作用于过滤结果）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = NovexDimensions.PageHorizontal, end = 8.dp, top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.session_skills_header_installed),
                        style = NovexType.Metadata,
                        color = NovexColors.TertiaryText,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    BulkAction(stringResource(R.string.session_skills_enable_all)) { toggleAll(true) }
                    BulkAction(stringResource(R.string.session_skills_disable_all)) { toggleAll(false) }
                }

                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.skills_search_no_match, query),
                        style = NovexType.Body,
                        color = NovexColors.SecondaryText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 24.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .padding(horizontal = NovexDimensions.PageHorizontal, vertical = 8.dp)
                            .clip(RoundedCornerShape(NovexDimensions.SectionRadius))
                            .background(NovexColors.Surface)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        shown.forEachIndexed { i, skill ->
                            SessionSkillRow(
                                skill = skill,
                                enabled = overrides[skill.id] ?: skill.isEnabled,
                                showDivider = i < shown.lastIndex,
                                onToggle = { on ->
                                    overrides[skill.id] = on
                                    skillRepository.setSessionOverride(sessionId, skill.id, on)
                                },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.session_skills_footer),
                        style = NovexType.Metadata,
                        color = NovexColors.TertiaryText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = NovexDimensions.PageHorizontal)
                            .padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionSkillRow(
    skill: SkillRepository.Skill,
    enabled: Boolean,
    showDivider: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        skill.name,
                        style = NovexType.ItemTitle,
                        color = NovexColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val (badge, tint) = sourceIconAndColor(skill.importSource)
                    Spacer(Modifier.width(6.dp))
                    Icon(badge, null, tint = tint, modifier = Modifier.size(14.dp))
                }
                if (skill.description.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        skill.description,
                        style = NovexType.Metadata,
                        color = NovexColors.SecondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            NovexCheckToggle(checked = enabled, onCheckedChange = onToggle)
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(NovexDimensions.Hairline)
                    .background(NovexColors.Divider),
            )
        }
    }
}

@Composable
private fun BulkAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = NovexType.Metadata,
        color = NovexColors.Primary,
        modifier = Modifier
            .clip(RoundedCornerShape(NovexDimensions.SmallRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SessionSkillsEmpty() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            NovexIcons.Description,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = NovexColors.TertiaryText.copy(alpha = 0.4f),
        )
        Text(
            stringResource(R.string.session_skills_empty_title),
            style = NovexType.Body,
            color = NovexColors.SecondaryText,
        )
        Text(
            stringResource(R.string.session_skills_empty_subtitle),
            style = NovexType.Metadata,
            color = NovexColors.TertiaryText,
        )
    }
}
