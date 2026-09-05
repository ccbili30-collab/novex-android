package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ReadImageTool {
    const val NAME = "read_image"
    private const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    private const val MAX_SOURCE_EDGE = 20_000
    private const val MAX_OUTPUT_EDGE = 2_000

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "读取当前对话有权访问的一项 Novex 图片成果，用于查看生成图片、地图或已挂载内容图片。" +
            "只接受成果编号，不接受设备路径；没有原生视觉能力时会通过已配置的视觉模型组返回文字描述。",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "展示给用户的简短操作名称。"),
            "artifact_id" to AgentToolParam("string", "当前对话创建或已通过挂载内容授权的 Novex 图片成果编号。"),
            "prompt" to AgentToolParam("string", "可选的查看重点，例如转录图片文字、描述地图区域或检查画面问题。"),
        ),
        required = listOf("tool_title", "artifact_id"),
        propertyOrdering = listOf("tool_title", "artifact_id", "prompt"),
    )

    fun executeArtifact(
        argsJson: String,
        artifactTitle: String,
        bytes: ByteArray,
        mimeType: String,
        imageFilePath: String?,
    ): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val toolTitle = args.optString("tool_title", NAME)
            if (bytes.isEmpty()) {
                return ToolExecutionResult("图片成果没有可读取的内容", false, toolTitle = toolTitle)
            }
            if (bytes.size > MAX_SOURCE_BYTES) {
                return ToolExecutionResult("图片成果超过 32 MiB 读取上限", false, toolTitle = toolTitle)
            }
            if (!mimeType.lowercase().startsWith("image/")) {
                return ToolExecutionResult("指定成果的媒体类型不是图片", false, toolTitle = toolTitle)
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return ToolExecutionResult("图片成果格式无法解码", false, toolTitle = toolTitle)
            }
            if (bounds.outWidth > MAX_SOURCE_EDGE || bounds.outHeight > MAX_SOURCE_EDGE) {
                return ToolExecutionResult("图片成果尺寸超过 20000 像素读取上限", false, toolTitle = toolTitle)
            }
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return ToolExecutionResult("图片成果格式无法解码", false, toolTitle = toolTitle)
            val width = original.width
            val height = original.height
            val scaled = if (width > MAX_OUTPUT_EDGE || height > MAX_OUTPUT_EDGE) {
                val scale = MAX_OUTPUT_EDGE.toFloat() / maxOf(width, height)
                Bitmap.createScaledBitmap(
                    original,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                original
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val imageBytes = out.toByteArray()
            if (scaled !== original) scaled.recycle()
            original.recycle()
            ToolExecutionResult(
                output = "[成果：$artifactTitle | ${width}x${height} | ${bytes.size} 字节]",
                success = true,
                imageData = imageBytes,
                imageMimeType = "image/jpeg",
                toolTitle = toolTitle,
                imageFilePath = imageFilePath,
                imageLinuxPath = null,
            )
        } catch (error: OutOfMemoryError) {
            ToolExecutionResult("图片成果过大，无法在当前设备上安全读取", false, toolTitle = NAME)
        } catch (error: Exception) {
            ToolExecutionResult("读取图片成果失败：${error.message}", false, toolTitle = NAME)
        }
    }

    /**
     * T178: when the caller knows the owning session, prefer
     * [PRootKernel.resolveSessionHostPath] so per-session subdirs
     * (`/var/minis/{attachments,workspace,offloads,browser}/...`) resolve
     * directly against this session's host dir instead of consulting the
     * global, last-writer-wins `bindMounts` map. Without this, an agent
     * loop in session A that calls `read_image` after session B booted
     * its PRoot reads from session B's host dir — confirmed leak per
     * docs/parity/cross-session-isolation-audit.md.
     *
     * This path is preserved only for replaying tool calls already stored in
     * conversation history. New provider schemas expose artifact IDs instead.
     */
    fun executeLegacy(argsJson: String, sessionId: String? = null, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val rawPath = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)

            if (rawPath.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            val path = if (rawPath.startsWith("minis://")) {
                "/var/minis/" + java.net.URLDecoder.decode(rawPath.removePrefix("minis://"), "UTF-8")
            } else rawPath

            val file = (
                if (sessionId != null && context != null) {
                    PRootKernel.resolveSessionHostPath(sessionId, path, context)
                } else null
            ) ?: PRootKernel.resolveHostPath(path)
                ?: return ToolExecutionResult("Error: Cannot resolve path: $path", false, toolTitle = toolTitle)

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path", false, toolTitle = toolTitle)
            }

            val original = BitmapFactory.decodeFile(file.absolutePath)
                ?: return ToolExecutionResult("Error: Cannot decode image: $path", false, toolTitle = toolTitle)
            val width = original.width
            val height = original.height

            val maxEdge = 2000
            val scaled = if (original.width > maxEdge || original.height > maxEdge) {
                val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
                val w = (original.width * scale).toInt()
                val h = (original.height * scale).toInt()
                Bitmap.createScaledBitmap(original, w, h, true)
            } else {
                original
            }

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val imageBytes = out.toByteArray()

            if (scaled !== original) scaled.recycle()
            original.recycle()

            val metadata = "[$path | ${width}x${height} | ${file.length()} bytes]"
            ToolExecutionResult(
                output = metadata,
                success = true,
                imageData = imageBytes,
                imageMimeType = "image/jpeg",
                toolTitle = toolTitle,
                // Surface the source file for inline preview in the tool result UI
                // (mirrors iOS ToolLiveSheet.readImageTool case).
                imageFilePath = file.absolutePath,
            )
        } catch (e: Exception) {
            ToolExecutionResult("Error reading image: ${e.message}", false)
        }
    }
}
