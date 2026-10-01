package com.openminis.app.ui.components

// [T-android-model-quick-test] 快捷测试执行侧：候选能力判定、并发跑测、
// 单测请求编排。UI 侧卡片见 QuickTestCards.kt。

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.openminis.app.R
import novex.android.data.model.LLMMessage
import novex.android.data.model.LLMMediaAttachment
import novex.android.data.model.ModelEntry
import novex.android.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "QuickTest"

/** Mutable per-run holder observed by its card. */
internal class QuickTestRun(val kind: QuickTestKind) {
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
    val outputs = (entry.model.outputModalities ?: emptyList()).map { it.normalizeModalityName() }
    val kinds = buildList {
        if ("image" in outputs) add(QuickTestKind.IMAGE_GEN)
        // [P3.3 裁军] audio 输入/输出两档（语音试听/听写）随语音全家退役。
        // outputModalities null/empty ⇒ text-out (documented convention), so
        // text is applicable whenever the list is empty OR contains text.
        if (outputs.isEmpty() || "text" in outputs) add(QuickTestKind.TEXT)
        if (isEmpty()) add(QuickTestKind.TEXT)
    }
    return kinds.take(3)
}

/** Reset every run to Running and fire each test concurrently. */
internal fun runAll(
    scope: CoroutineScope,
    context: Context,
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
    context: Context,
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
