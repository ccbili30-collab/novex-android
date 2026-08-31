package com.openminis.app.tools

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.openai.OpenAIProvider
import com.openminis.app.sandbox.PRootKernel
import java.io.File
import org.json.JSONObject

object GenerateImageTool {
    const val NAME = "generate_image"

    fun skillPrompt(): String = """
<skill name="image-generation" description="统一生图与图片编辑">
用户要求生成、绘制、重做或编辑图片时，调用 generate_image。只需提交完整视觉提示词和可选参考图路径；不要选择、猜测或向用户暴露具体生图模型。应用会按已启用生图分组的顺序自动选择，并在失败时继续尝试下一成员和下一分组。只有工具真正返回图片后才能声称完成，并在回复中展示返回的本地图片。
</skill>
""".trimIndent()

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Generate or edit a real image through the configured image-generation groups. " +
            "Use this whenever the user asks to draw, generate, create, remake, or edit an image. " +
            "The app selects an enabled group and falls back automatically; never invent a result.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "Short user-visible action title in the user's language."),
            "prompt" to AgentToolParam("string", "Complete visual prompt describing subject, composition, style, lighting, text, and constraints."),
            "reference_image_path" to AgentToolParam("string", "Optional /var/minis path or minis:// URL of an image to edit."),
            "size" to AgentToolParam("string", "Optional provider size such as 1024x1024, 1536x1024, or 1024x1536."),
            "quality" to AgentToolParam("string", "Optional provider quality such as standard, high, or hd."),
            "count" to AgentToolParam("integer", "Number of images requested, from 1 to 4."),
        ),
        required = listOf("tool_title", "prompt"),
        propertyOrdering = listOf("tool_title", "prompt", "reference_image_path", "size", "quality", "count"),
    )

    suspend fun execute(
        argsJson: String,
        sessionId: String,
        context: Context,
        repository: ProviderRepository,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return ToolExecutionResult("生图参数不是有效的 JSON：${it.message}", false, toolTitle = "生成图片")
        }
        val prompt = args.optString("prompt").trim()
        val title = args.optString("tool_title", "生成图片").ifBlank { "生成图片" }
        if (prompt.isEmpty()) return ToolExecutionResult("缺少生图提示词", false, toolTitle = title)

        val reference = resolveReferenceImage(
            rawPath = args.optString("reference_image_path").trim(),
            sessionId = sessionId,
            context = context,
        ).getOrElse { return ToolExecutionResult(it.message ?: "无法读取参考图", false, toolTitle = title) }
        val entries = repository.resolvedImageGenerationEntries()
        val generated = runImageGenerationFallback(entries) { entry ->
            runCatching {
                generateWithEntry(
                    entry = entry,
                    repository = repository,
                    context = context,
                    prompt = prompt,
                    reference = reference,
                    count = args.optInt("count", 1).coerceIn(1, 4),
                    size = args.optString("size").trim().takeIf(String::isNotEmpty),
                    quality = args.optString("quality").trim().takeIf(String::isNotEmpty),
                )
            }
        }.getOrElse { return ToolExecutionResult(it.message ?: "生图失败", false, toolTitle = title) }

        val images = generated.value.mediaAttachments.filter { it.type == LLMMediaAttachment.MediaType.IMAGE }
        if (images.isEmpty()) return ToolExecutionResult(
                "${generated.entry.model.id} 请求成功，但没有返回图片数据",
                false,
                toolTitle = title,
            )
        val linuxDir = "/var/minis/attachments/generated"
        val stamp = System.currentTimeMillis()
        val saved = images.mapIndexed { index, media ->
            val suffix = if (images.size == 1) "" else "-${index + 1}"
            val linuxPath = "$linuxDir/generated-$stamp$suffix.${extensionForMime(media.mimeType)}"
            val outputFile = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
                ?: return ToolExecutionResult("无法创建会话生图目录", false, toolTitle = title)
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(media.data)
            Triple(linuxPath, outputFile, media)
        }
        val first = saved.first()

        val fallbackNote = generated.failures.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "；已跳过：", separator = "；")
            .orEmpty()
        return ToolExecutionResult(
            output = "图片已生成：${saved.joinToString("、") { it.first }}（${generated.entry.model.id}）$fallbackNote",
            success = true,
            imageData = first.third.data,
            imageMimeType = first.third.mimeType,
            toolTitle = title,
            imageFilePath = first.second.absolutePath,
            imageLinuxPath = first.first,
        )
    }

    private suspend fun generateWithEntry(
        entry: ModelEntry,
        repository: ProviderRepository,
        context: Context,
        prompt: String,
        reference: LLMMessage.ImagePart?,
        count: Int,
        size: String?,
        quality: String?,
    ): LLMResponse {
        val instance = repository.instance(entry.providerInstanceId)
            ?: error("提供商不存在")
        val credential = repository.usableApiKey(instance)
            ?: error("密钥不可用")
        val provider = ProviderFactory.create(instance, credential, entry.model, context)
        val openAI = provider as? OpenAIProvider
        val effectiveEndpointMode = if (instance.imageEndpointMode == ImageEndpointMode.auto) {
            instance.imageEndpointResolved ?: ImageEndpointMode.auto
        } else instance.imageEndpointMode
        if (openAI != null && effectiveEndpointMode != ImageEndpointMode.chatCompletions) {
            try {
                val response = if (reference == null) {
                    openAI.generateImage(prompt, count, size, quality)
                } else {
                    openAI.editImage(prompt, listOf(reference), count, size, quality)
                }
                if (instance.imageEndpointMode == ImageEndpointMode.auto) {
                    repository.setImageEndpointResolved(instance.id, ImageEndpointMode.imagesGenerations)
                }
                return response
            } catch (failure: Throwable) {
                if (instance.imageEndpointMode != ImageEndpointMode.auto || !looksLikeMissingImageEndpoint(failure.message)) {
                    throw failure
                }
                Log.i("GenerateImageTool", "${entry.model.id} images endpoint unavailable; using chat route")
                repository.setImageEndpointResolved(instance.id, ImageEndpointMode.chatCompletions)
            }
        }
        return provider.sendMessage(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = prompt,
                    imageParts = listOfNotNull(reference),
                ),
            ),
            systemPrompt = "Generate the requested image. Return actual image data, not a textual description.",
            maxTokens = entry.model.maxOutputTokens ?: 4096,
            imageParts = listOfNotNull(reference),
        )
    }

    private fun resolveReferenceImage(
        rawPath: String,
        sessionId: String,
        context: Context,
    ): Result<LLMMessage.ImagePart?> = runCatching {
        if (rawPath.isEmpty()) return@runCatching null
        val linuxPath = if (rawPath.startsWith("minis://")) {
            "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
        } else rawPath
        val file = PRootKernel.resolveSessionHostPath(sessionId, linuxPath, context)
            ?: PRootKernel.resolveHostPath(linuxPath)
            ?: error("无法解析参考图路径：$linuxPath")
        require(file.exists() && file.isFile) { "参考图不存在：$linuxPath" }
        LLMMessage.ImagePart(file.readBytes(), mimeForFile(file), linuxPath)
    }

    private fun looksLikeMissingImageEndpoint(message: String?): Boolean {
        val value = message.orEmpty().lowercase()
        return listOf("404", "405", "not found", "no such endpoint", "method not allowed", "unknown endpoint")
            .any(value::contains)
    }

    private fun mimeForFile(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
}
