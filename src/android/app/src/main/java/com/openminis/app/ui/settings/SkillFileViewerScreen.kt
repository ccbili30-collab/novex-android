package com.openminis.app.ui.settings

import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.components.DialogTextField
import androidx.compose.material3.Text
import novex.android.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.repository.SkillRepository
import novex.android.ui.NovexIcons
import novex.android.ui.Scaffold

/**
 * 技能文件查看/编辑页（整页，对齐 iOS）。
 *
 * SKILL.md 从内存里的 skill 记录重建（frontmatter 编辑与 DB 元数据保持
 * 同步）；兄弟文件直读磁盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillFileViewerScreen(
    skillId: String,
    relativePath: String = "SKILL.md",
    skillRepository: SkillRepository,
    onBack: () -> Unit,
) {
    val skills by skillRepository.skills.collectAsState()
    val skill = skills.find { it.id == skillId }
    if (skill == null) { onBack(); return }

    val isSkillMd = relativePath.equals("SKILL.md", ignoreCase = true)
    val fileName = relativePath.substringAfterLast('/')

    val initialContent = remember(skillId, relativePath, skill.updatedAt) {
        if (isSkillMd) renderSkillMd(skill) else skillRepository.readSkillFile(skillId, relativePath) ?: ""
    }

    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember(skillId, relativePath) { mutableStateOf(initialContent) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(NovexIcons.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (isEditing) {
                        MinisTextButton(onClick = {
                            if (isSkillMd) {
                                // SKILL.md 走 importFromContent，YAML
                                // frontmatter 的改动才会回流 DB 元数据。
                                skillRepository.importFromContent(editContent, skill.importSource)
                            } else {
                                skillRepository.writeSkillFile(skillId, relativePath, editContent)
                            }
                            isEditing = false
                            onBack()
                        }) { Text(stringResource(R.string.skill_file_save)) }
                    } else {
                        MinisTextButton(onClick = {
                            editContent = initialContent
                            isEditing = true
                        }) { Text(stringResource(R.string.skill_file_edit)) }
                    }
                },
            )
        },
    ) { padding ->
        if (isEditing) {
            DialogTextField(
                value = editContent,
                onValueChange = { editContent = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 8.dp),
                singleLine = false,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                item {
                    Text(
                        initialContent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 用 skill 记录重建 SKILL.md 文本（frontmatter + body）。 */
private fun renderSkillMd(skill: SkillRepository.Skill): String = buildString {
    appendLine("---")
    appendLine("name: ${skill.name}")
    appendLine("version: ${skill.version}")
    if (skill.description.isNotEmpty()) {
        appendLine("description: ${skill.description}")
    }
    appendLine("---")
    appendLine()
    append(skill.body)
}
