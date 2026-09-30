package com.openminis.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import novex.android.ui.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMMediaAttachment
import novex.android.data.model.ModelEntry
import novex.android.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope

private const val TAG = "QuickTest"

/**
 * [T-android-model-quick-test] A lightweight half-sheet that fires
 * modality-matched smoke tests against one model so the user can confirm
 * "this model + credential actually works" without leaving the provider
 * screen. Mirrors iOS `ModelQuickTestSheet`.
 *
 * Up to 3 applicable tests run CONCURRENTLY, each with its own
 * running / success / failure state laid out as a stacked card.
 */

/** One testable capability. Priority order (most distinctive first) is
 *  imageGen > speechOut > transcription > text. */
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

/** Mutable per-run holder observed by its card. */
private class QuickTestRun(val kind: QuickTestKind) {
    var state by mutableStateOf<QuickTestState>(QuickTestState.Running)
    var elapsedMs by mutableStateOf(0L)
}

/**
 * Top-3 most distinctive applicable test kinds for [entry]. A model is
 * testable for a kind when its effective modality exposes that capability.
 * Text is always included as a baseline if the model has text output;
 * if nothing matches we fall back to text so there is always one test.
 */
internal fun applicableKinds(entry: ModelEntry): List<QuickTestKind> {
    val model = entry.model
    val outputs = (model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val inputs = (model.inputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val kinds = mutableListOf<QuickTestKind>()
    if ("image" in outputs) kinds.add(QuickTestKind.IMAGE_GEN)
    // [P3.3 裁军] audio 输入/输出两档（语音试听/听写）随语音全家退役。
    // outputModalities null/empty ⇒ text-out (documented convention), so text
    // is applicable whenever the list is empty OR explicitly contains text.
    if (outputs.isEmpty() || "text" in outputs) kinds.add(QuickTestKind.TEXT)
    if (kinds.isEmpty()) kinds.add(QuickTestKind.TEXT)
    return kinds.take(3)
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
    androidx.compose.runtime.LaunchedEffect(entry.id) {
        runAll(scope, context, entry, providerRepository, runs)
    }

    ModalBottomSheet(
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
            // Toolbar row: title + Run again / Done.
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
                IconButton(
                    onClick = { runAll(scope, context, entry, providerRepository, runs) },
                    enabled = !isRunning,
                ) {
                    Icon(
                        novex.android.ui.NovexIcons.Refresh,
                        contentDescription = stringResource(R.string.quicktest_run_again),
                    )
                }
                MinisTextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.quicktest_done))
                }
            }

            Spacer(Modifier.height(8.dp))

            // Header: icon + name + id.
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
                    Text(
                        entry.model.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        entry.model.id,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            runs.forEach { run ->
                TestCard(run)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun TestCard(run: QuickTestRun) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                run.kind.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(run.kind.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(run)
        }
        Spacer(Modifier.height(8.dp))
        TestContent(run)
    }
}

@Composable
private fun StatusBadge(run: QuickTestRun) {
    when (val s = run.state) {
        is QuickTestState.Running -> CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
        )
        is QuickTestState.Failure -> Icon(
            novex.android.ui.NovexIcons.Cancel,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        else -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                novex.android.ui.NovexIcons.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            if (run.elapsedMs > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    String.format("%.1fs", run.elapsedMs / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TestContent(run: QuickTestRun) {
    when (val s = run.state) {
        is QuickTestState.Running -> Text(
            stringResource(R.string.quicktest_testing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is QuickTestState.TextReply -> Text(
            s.text,
            style = MaterialTheme.typography.bodyMedium,
        )
        is QuickTestState.ImageReply -> {
            val bmp = remember(s.data) {
                runCatching { BitmapFactory.decodeByteArray(s.data, 0, s.data.size) }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainer,
                            RoundedCornerShape(8.dp),
                        ),
                )
            } else {
                Text(
                    "Received ${s.data.size} bytes (couldn't decode preview)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is QuickTestState.Failure -> Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// [P3.3 裁军] AudioReplyContent（TTS 试听播放器）随语音测试退役删除。

/** Reset every run to Running and fire each test concurrently. */
private fun runAll(
    scope: CoroutineScope,
    context: android.content.Context,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    runs: SnapshotStateList<QuickTestRun>,
) {
    runs.forEach { it.state = QuickTestState.Running; it.elapsedMs = 0 }
    runs.forEach { run ->
        AppLogger.info(TAG, "[QuickTest] start model=${entry.model.id} kind=${run.kind}")
        scope.launch {
            val start = System.currentTimeMillis()
            val result = performTest(run.kind, entry, providerRepository, context)
            run.elapsedMs = System.currentTimeMillis() - start
            run.state = result
            when (result) {
                is QuickTestState.Failure ->
                    AppLogger.warning(TAG, "[QuickTest] FAIL model=${entry.model.id} kind=${run.kind}: ${result.message}")
                else ->
                    AppLogger.info(TAG, "[QuickTest] OK model=${entry.model.id} kind=${run.kind} in ${run.elapsedMs}ms")
            }
        }
    }
}

/** Run one smoke test against a real provider (no mocks). */
internal suspend fun performTest(
    kind: QuickTestKind,
    entry: ModelEntry,
    providerRepository: ProviderRepository,
    context: android.content.Context,
): QuickTestState = withContext(Dispatchers.IO) {
    fun failure(msg: String) = QuickTestState.Failure(msg)

    val instance = providerRepository.instance(entry.providerInstanceId)
        ?: return@withContext failure("Provider instance not found.")
    val apiKey = providerRepository.loadApiKey(instance.id)
        ?: return@withContext failure("No API key configured for this provider.")

    // [P3.3 裁军] 语音试听/听写测试分支（VoiceClientFactory + TTS/ASR 请求）
    // 随语音全家退役删除；快捷测试只保留文本与生图两线。
    val provider = runCatching {
        ProviderFactory.create(instance, apiKey, entry.model, context)
    }.getOrElse { return@withContext failure(it.message ?: "Couldn't create provider.") }

    when (kind) {
        QuickTestKind.TEXT -> {
            runCatching {
                val resp = provider.sendMessage(
                    messages = listOf(
                        LLMMessage(
                            role = LLMMessage.Role.USER,
                            content = "Hi! I'm setting you up in Minis. Say hello back in one short, friendly sentence.",
                        ),
                    ),
                    systemPrompt = null,
                    maxTokens = 128,
                    temperature = null,
                )
                val text = resp.text.trim()
                QuickTestState.TextReply(
                    text.ifEmpty { context.getString(R.string.quicktest_empty_reply) },
                )
            }.getOrElse { failure(it.message ?: "Request failed.") }
        }

        QuickTestKind.IMAGE_GEN -> {
            // [P3.1d] 生图接口面：适配器走自有 novex.model ImagesClient；上游
            // 九类线路均已换管适配器（上游 openai 包已随 P3.1e 删除），行为不变。
            val images = (provider as? novex.android.transport.NovexTransportProvider)?.imageDelegate
                ?: provider as? com.openminis.app.provider.ImagesCapableProvider
                ?: return@withContext failure(context.getString(R.string.quicktest_image_unsupported))
            runCatching {
                val resp = images.generateImage(
                    prompt = "A friendly cute mascot logo for an app called Minis, minimalist, centered, soft colors",
                    n = 1,
                    size = "1024x1024",
                    quality = null,
                )
                val img = resp.mediaAttachments.firstOrNull {
                    it.type == LLMMediaAttachment.MediaType.IMAGE
                } ?: return@runCatching failure(context.getString(R.string.quicktest_no_image))
                QuickTestState.ImageReply(img.data)
            }.getOrElse { failure(it.message ?: "Image request failed.") }
        }

    }
}

// [P3.3 裁军] synthesizeTestClip（系统 TTS 合成测试音频）随语音测试退役删除。
