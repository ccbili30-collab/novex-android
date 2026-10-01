package com.openminis.app.ui.components

// [T-android-model-quick-test] 轻量半屏面板：对单个模型并发跑至多 3 个
// 模态匹配的冒烟测试，让用户不离开供应商页就能确认"模型+凭据真的能用"。
// 对齐 iOS ModelQuickTestSheet。执行管线见 QuickTestRunner.kt，卡片见
// QuickTestCards.kt。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository

/** One testable capability. Priority order (most distinctive first) is
 *  imageGen > text. */
enum class QuickTestKind {
    IMAGE_GEN,
    TEXT;

    // [P3.3 裁军] SPEECH_OUT/TRANSCRIPTION 两档随语音全家退役删除。

    val titleRes: Int
        get() = when (this) {
            TEXT -> R.string.quicktest_kind_text
            IMAGE_GEN -> R.string.quicktest_kind_image
        }

    val icon: ImageVector
        get() = when (this) {
            TEXT -> novex.android.ui.NovexIcons.TextFields
            IMAGE_GEN -> novex.android.ui.NovexIcons.Image
        }
}

/** Result state of a single test run. */
sealed class QuickTestState {
    object Running : QuickTestState()
    data class TextReply(val text: String) : QuickTestState()
    data class ImageReply(val data: ByteArray) : QuickTestState()
    // [P3.3 裁军] AudioReply（TTS 试听音频）随语音测试退役删除。
    data class Failure(val message: String) : QuickTestState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTestSheet(
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runs = remember(entry.id) {
        mutableStateListOf<QuickTestRun>().apply {
            applicableKinds(entry).forEach { add(QuickTestRun(it)) }
        }
    }
    val isRunning by remember { derivedStateOf { runs.any { it.state is QuickTestState.Running } } }

    // Kick off all tests on first show (once per entry).
    LaunchedEffect(entry.id) {
        runAll(scope, context, entry, providerRepository, runs)
    }

    novex.android.ui.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            QuickTestToolbar(
                running = isRunning,
                onRerun = { runAll(scope, context, entry, providerRepository, runs) },
                onDone = onDismiss,
            )
            Spacer(Modifier.height(8.dp))
            QuickTestEntryHeader(entry)
            Spacer(Modifier.height(14.dp))
            runs.forEach { run ->
                QuickTestCard(run)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 工具行：标题 + 重跑 + 完成。 */
@Composable
private fun QuickTestToolbar(running: Boolean, onRerun: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.quicktest_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRerun, enabled = !running) {
            Icon(
                novex.android.ui.NovexIcons.Refresh,
                contentDescription = stringResource(R.string.quicktest_run_again),
            )
        }
        MinisTextButton(onClick = onDone) {
            Text(stringResource(R.string.quicktest_done))
        }
    }
}

/** 头部：闪电图标 + 模型名 + id。 */
@Composable
private fun QuickTestEntryHeader(entry: ModelEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                novex.android.ui.NovexIcons.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(entry.model.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                entry.model.id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
