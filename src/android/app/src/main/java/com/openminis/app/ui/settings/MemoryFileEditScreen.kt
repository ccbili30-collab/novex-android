package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
import novex.android.ui.NovexColors
import novex.android.ui.NovexDimensions
import novex.android.ui.NovexDraftExitBoundary
import novex.android.ui.NovexIcons
import novex.android.ui.NovexType
import novex.android.ui.Scaffold
import novex.android.ui.TopAppBar

/**
 * 全页记忆文件编辑器（等宽字体）。保存键常驻——不做 hasChanges 门：
 * 粘贴/IME 提交不一定走 onValueChange，门控曾导致粘贴后保存键不出现。
 * saveFile 幂等，unchanged 时点保存无副作用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryFileEditScreen(
    fileName: String,
    isGlobal: Boolean,
    memoryRepository: MemoryRepository,
    onBack: () -> Unit,
) {
    var content by rememberSaveable(fileName) { mutableStateOf("") }
    var baseline by rememberSaveable(fileName) { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val savedToast = stringResource(R.string.memory_save_toast)

    LaunchedEffect(fileName) {
        if (baseline == null) {
            content = memoryRepository.readFile(fileName)
            baseline = content
        }
    }

    fun commit(): Boolean = try {
        memoryRepository.saveFile(fileName, content)
        baseline = content
        saveError = null
        Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        saveError = e.message ?: "保存失败，请重试"
        false
    }

    NovexDraftExitBoundary(
        baselineDraft = baseline,
        currentDraft = content,
        saving = false,
        onBack = onBack,
        onSaveAndExit = { if (commit()) onBack() },
    ) { requestBack ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(fileName) },
                    navigationIcon = {
                        IconButton(onClick = requestBack) {
                            Icon(NovexIcons.ArrowBack, stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        MinisTextButton(onClick = { commit() }, enabled = baseline != null) {
                            Text("保存")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = NovexDimensions.PageHorizontal),
            ) {
                Spacer(Modifier.height(8.dp))
                DialogTextField(
                    value = content,
                    onValueChange = { content = it },
                    singleLine = false,
                    textStyle = NovexType.Metadata.copy(fontFamily = FontFamily.Monospace),
                    placeholder = stringResource(R.string.memory_editor_placeholder),
                    modifier = Modifier.weight(1f),
                )
                if (isGlobal) {
                    Text(
                        stringResource(R.string.memory_global_footer),
                        style = NovexType.Metadata,
                        color = NovexColors.SecondaryText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                saveError?.let {
                    Text(
                        it,
                        style = NovexType.Metadata,
                        color = NovexColors.Danger,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
