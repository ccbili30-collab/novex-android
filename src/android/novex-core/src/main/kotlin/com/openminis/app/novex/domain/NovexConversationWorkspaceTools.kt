package com.openminis.app.novex.domain

import java.util.Base64
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject

data class NovexWorkspaceInspectRequest(
    val area: NovexWorkspaceArea? = null,
    val maxEntries: Int = 200,
) {
    init {
        require(maxEntries in 1..500) { "工作区清单上限必须在一到五百之间" }
    }
}

data class NovexWorkspaceReadRequest(
    val workspaceRef: NovexWorkspaceFileRef,
    val cursor: String? = null,
    val maxChars: Int = 12_000,
) {
    init {
        require(maxChars in 1..48_000) { "单次读取字符预算必须在一到四万八千之间" }
    }
}

data class NovexWorkspaceWriteRequest(
    val area: NovexWorkspaceArea,
    val relativePath: String,
    val content: String,
    val mimeType: String = "text/markdown",
)

data class NovexWorkspaceEditRequest(
    val workspaceRef: NovexWorkspaceFileRef,
    val expectedSha256: String,
    val startChar: Int,
    val endChar: Int,
    val replacement: String,
) {
    init {
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "编辑前校验值必须是 SHA-256" }
        require(startChar >= 0 && endChar >= startChar) { "编辑字符范围无效" }
    }
}

enum class NovexWorkspaceComputeOperation(val wireName: String, val writesOutput: Boolean) {
    MERGE_TEXT("merge_text", true),
    TEXT_STATISTICS("text_statistics", false),
    JSON_VALIDATE("json_validate", false),
    JSON_FORMAT("json_format", true),
    ;

    companion object {
        val allowedValues = entries.map { it.wireName }

        fun fromWireName(value: String): NovexWorkspaceComputeOperation? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class NovexWorkspaceComputeRequest(
    val operation: NovexWorkspaceComputeOperation,
    val inputRefs: List<NovexWorkspaceFileRef>,
    val outputArea: NovexWorkspaceArea? = null,
    val outputPath: String? = null,
    val delimiter: String = "\n\n",
    val indent: Int = 2,
) {
    init {
        require(inputRefs.size in 1..8) { "受限计算需要一到八个输入文件" }
        require(inputRefs.distinct().size == inputRefs.size) { "受限计算输入文件不能重复" }
        require(delimiter.length <= 200) { "文本分隔符不能超过二百字符" }
        require(indent in 0..8) { "JSON 缩进必须在零到八之间" }
        if (operation.writesOutput) {
            require(outputArea?.modelWritable == true) { "产生文件的受限计算必须指定可写目录" }
            require(!outputPath.isNullOrBlank()) { "产生文件的受限计算必须指定输出路径" }
        }
    }
}

/**
 * Four bounded tools cover browsing and editing without teaching the model device paths.
 * Binary conversion and system export remain native application actions.
 */
class NovexConversationWorkspaceTools(
    private val scope: NovexConversationWorkspaceScope,
    private val store: NovexConversationWorkspaceStore,
    private val provenance: NovexWorkspaceProvenance = NovexWorkspaceProvenance(
        conversationId = scope.conversationId,
        branchId = scope.writeBranchId,
    ),
) {
    fun workspaceInspect(request: NovexWorkspaceInspectRequest): NovexToolResult {
        val allEntries = store.inspect(scope).entries
            .filter { request.area == null || it.workspaceRef.area == request.area }
        val returned = allEntries.take(request.maxEntries)
        return NovexToolResult.success(
            code = "workspace.ready",
            summary = "工作区共有 ${allEntries.size} 个可见文件",
            data = mapOf(
                "scope" to "current_conversation_branch",
                "areas" to NovexWorkspaceArea.entries.map { area ->
                    mapOf(
                        "name" to area.wireName,
                        "model_writable" to area.modelWritable,
                        "entry_count" to allEntries.count { it.workspaceRef.area == area },
                    )
                },
                "entries" to returned.map(::entryPayload),
                "truncated" to (returned.size < allEntries.size),
            ),
            affectedRefs = returned.map { it.workspaceRef.asResourceRef() },
        )
    }

    fun workspaceRead(request: NovexWorkspaceReadRequest): NovexToolResult {
        val entry = store.find(scope, request.workspaceRef) ?: return notFound(request.workspaceRef)
        if (!isTextMimeType(entry.mimeType)) {
            return NovexToolResult.failure(
                code = "workspace.binary_requires_artifact",
                summary = "二进制成果不能作为普通文本读取，请使用对应成果查看器",
                data = mapOf(
                    "workspace_ref" to entry.workspaceRef.value,
                    "artifact_ref" to entry.artifactRef?.value,
                    "mime_type" to entry.mimeType,
                    "byte_count" to entry.byteCount,
                ),
                affectedRefs = listOfNotNull(entry.workspaceRef.asResourceRef(), entry.artifactRef),
            )
        }
        val text = runCatching { store.readBytes(scope, entry.workspaceRef).toString(Charsets.UTF_8) }
            .getOrElse {
                return NovexToolResult.failure(
                    code = "workspace.content_unavailable",
                    summary = "工作区文件内容暂时不可用",
                    affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
                )
            }
        val offset = request.cursor?.let { decodeCursor(it, entry) }
            ?: if (request.cursor == null) 0 else return NovexToolResult.failure(
                code = "workspace.invalid_cursor",
                summary = "工作区读取游标已失效，请重新检查文件",
                affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
            )
        if (offset > text.length) {
            return NovexToolResult.failure(
                code = "workspace.invalid_cursor",
                summary = "工作区读取游标已失效，请重新检查文件",
                affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
            )
        }
        val content = text.substring(offset, minOf(text.length, offset + request.maxChars))
        val nextOffset = offset + content.length
        val truncated = nextOffset < text.length
        val data = linkedMapOf<String, Any?>(
            "workspace_ref" to entry.workspaceRef.value,
            "mime_type" to entry.mimeType,
            "sha256" to entry.sha256,
            "content" to content,
            "char_offset" to offset,
            "truncated" to truncated,
        )
        if (truncated) data["next_cursor"] = encodeCursor(entry, nextOffset)
        return NovexToolResult.success(
            code = "workspace.read",
            summary = "已读取 ${entry.workspaceRef.relativePath} 的 ${content.length} 个字符",
            data = data,
            affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
        )
    }

    fun workspaceWrite(request: NovexWorkspaceWriteRequest): NovexToolResult {
        if (!request.area.modelWritable) {
            return NovexToolResult.failure(
                code = "workspace.area_read_only",
                summary = "${request.area.wireName} 目录只能由应用写入",
                allowedValues = NovexWorkspaceArea.entries.filter { it.modelWritable }.map { it.wireName },
            )
        }
        if (!isTextMimeType(request.mimeType)) {
            return NovexToolResult.failure(
                code = "workspace.binary_write_unsupported",
                summary = "工作区文本工具不接受二进制内容，请使用专用成果工具",
            )
        }
        val existing = store.inspect(scope).entries.firstOrNull {
            it.workspaceRef.area == request.area &&
                it.workspaceRef.relativePath == request.relativePath
        }
        if (existing != null) {
            return NovexToolResult.failure(
                code = "workspace.already_exists",
                summary = "同名文件已经存在，请先读取并使用定点编辑",
                data = mapOf(
                    "workspace_ref" to existing.workspaceRef.value,
                    "sha256" to existing.sha256,
                ),
                affectedRefs = listOf(existing.workspaceRef.asResourceRef()),
            )
        }
        return runCatching {
            val entry = store.writeText(
                scope = scope,
                area = request.area,
                relativePath = request.relativePath,
                content = request.content,
                mimeType = request.mimeType,
                provenance = provenance,
            )
            NovexToolResult.success(
                code = "workspace.written",
                summary = "已写入 ${entry.workspaceRef.relativePath}",
                data = entryPayload(entry),
                affectedRefs = listOfNotNull(entry.workspaceRef.asResourceRef(), entry.artifactRef),
                sideEffect = NovexToolSideEffect.SESSION_REVERSIBLE,
            )
        }.getOrElse { failure -> writeFailure(failure) }
    }

    fun workspaceEdit(request: NovexWorkspaceEditRequest): NovexToolResult {
        val entry = store.find(scope, request.workspaceRef) ?: return notFound(request.workspaceRef)
        if (!entry.workspaceRef.area.modelWritable) {
            return NovexToolResult.failure(
                code = "workspace.area_read_only",
                summary = "${entry.workspaceRef.area.wireName} 目录只能由应用写入",
            )
        }
        if (!isTextMimeType(entry.mimeType)) {
            return NovexToolResult.failure(
                code = "workspace.binary_edit_unsupported",
                summary = "二进制成果必须使用专用编辑器",
                affectedRefs = listOfNotNull(entry.workspaceRef.asResourceRef(), entry.artifactRef),
            )
        }
        if (!entry.sha256.equals(request.expectedSha256, ignoreCase = true)) {
            return editConflict(entry)
        }
        return runCatching {
            val originalBytes = store.readBytes(scope, entry.workspaceRef)
            if (sha256(originalBytes) != entry.sha256) return editConflict(entry)
            val original = originalBytes.toString(Charsets.UTF_8)
            if (request.endChar > original.length) {
                return NovexToolResult.failure(
                    code = "workspace.invalid_edit_range",
                    summary = "编辑范围超过当前文件长度",
                    data = mapOf("char_count" to original.length),
                    affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
                )
            }
            val edited = original.replaceRange(request.startChar, request.endChar, request.replacement)
            val updated = store.writeText(
                scope = scope,
                area = entry.workspaceRef.area,
                relativePath = entry.workspaceRef.relativePath,
                content = edited,
                mimeType = entry.mimeType,
                provenance = provenance,
            )
            NovexToolResult.success(
                code = "workspace.edited",
                summary = "已编辑 ${updated.workspaceRef.relativePath}",
                data = entryPayload(updated) + mapOf("char_count" to edited.length),
                affectedRefs = listOfNotNull(updated.workspaceRef.asResourceRef(), updated.artifactRef),
                sideEffect = NovexToolSideEffect.SESSION_REVERSIBLE,
            )
        }.getOrElse { failure -> writeFailure(failure) }
    }

    fun workspaceCompute(request: NovexWorkspaceComputeRequest): NovexToolResult {
        val entries = request.inputRefs.map { ref ->
            store.find(scope, ref) ?: return notFound(ref)
        }
        if (entries.any { !isTextMimeType(it.mimeType) }) {
            return NovexToolResult.failure(
                code = "workspace.compute_requires_text",
                summary = "受限计算只接受工作区文本文件",
                affectedRefs = entries.map { it.workspaceRef.asResourceRef() },
            )
        }
        val bytes = entries.map { entry -> store.readBytes(scope, entry.workspaceRef) }
        val totalBytes = bytes.sumOf { it.size.toLong() }
        if (totalBytes > MAX_COMPUTE_INPUT_BYTES) {
            return NovexToolResult.failure(
                code = "workspace.compute_input_too_large",
                summary = "受限计算输入总量超过二 MiB 上限",
                data = mapOf("input_bytes" to totalBytes, "max_input_bytes" to MAX_COMPUTE_INPUT_BYTES),
                affectedRefs = entries.map { it.workspaceRef.asResourceRef() },
            )
        }
        val texts = bytes.map { it.toString(Charsets.UTF_8) }
        return when (request.operation) {
            NovexWorkspaceComputeOperation.TEXT_STATISTICS -> textStatistics(entries, texts)
            NovexWorkspaceComputeOperation.JSON_VALIDATE -> validateJson(entries, texts)
            NovexWorkspaceComputeOperation.MERGE_TEXT -> writeComputedText(
                request = request,
                entries = entries,
                content = texts.joinToString(request.delimiter),
                mimeType = "text/plain",
            )
            NovexWorkspaceComputeOperation.JSON_FORMAT -> {
                if (texts.size != 1) {
                    NovexToolResult.failure(
                        code = "workspace.compute_single_input_required",
                        summary = "JSON 格式化只接受一个输入文件",
                        affectedRefs = entries.map { it.workspaceRef.asResourceRef() },
                    )
                } else {
                    val formatted = runCatching { formatJson(texts.single(), request.indent) }
                        .getOrElse {
                            return NovexToolResult.failure(
                                code = "workspace.invalid_json",
                                summary = "输入文件不是有效 JSON",
                                affectedRefs = listOf(entries.single().workspaceRef.asResourceRef()),
                            )
                        }
                    writeComputedText(request, entries, formatted, "application/json")
                }
            }
        }
    }

    private fun textStatistics(
        entries: List<NovexWorkspaceEntry>,
        texts: List<String>,
    ): NovexToolResult = NovexToolResult.success(
        code = "workspace.computed",
        summary = "已统计 ${entries.size} 个文本文件",
        data = mapOf(
            "operation" to NovexWorkspaceComputeOperation.TEXT_STATISTICS.wireName,
            "files" to entries.zip(texts).map { (entry, text) ->
                mapOf(
                    "workspace_ref" to entry.workspaceRef.value,
                    "characters" to text.length,
                    "non_whitespace_characters" to text.count { !it.isWhitespace() },
                    "lines" to if (text.isEmpty()) 0 else text.lineSequence().count(),
                )
            },
        ),
        affectedRefs = entries.map { it.workspaceRef.asResourceRef() },
    )

    private fun validateJson(
        entries: List<NovexWorkspaceEntry>,
        texts: List<String>,
    ): NovexToolResult {
        if (texts.size != 1) {
            return NovexToolResult.failure(
                code = "workspace.compute_single_input_required",
                summary = "JSON 校验只接受一个输入文件",
                affectedRefs = entries.map { it.workspaceRef.asResourceRef() },
            )
        }
        val value = runCatching { JSONTokener(texts.single()).nextValue() }.getOrNull()
        return if (value is JSONObject || value is JSONArray) {
            NovexToolResult.success(
                code = "workspace.computed",
                summary = "JSON 结构有效",
                data = mapOf(
                    "operation" to NovexWorkspaceComputeOperation.JSON_VALIDATE.wireName,
                    "root_type" to if (value is JSONObject) "object" else "array",
                ),
                affectedRefs = listOf(entries.single().workspaceRef.asResourceRef()),
            )
        } else {
            NovexToolResult.failure(
                code = "workspace.invalid_json",
                summary = "输入文件不是有效 JSON 对象或数组",
                affectedRefs = listOf(entries.single().workspaceRef.asResourceRef()),
            )
        }
    }

    private fun writeComputedText(
        request: NovexWorkspaceComputeRequest,
        entries: List<NovexWorkspaceEntry>,
        content: String,
        mimeType: String,
    ): NovexToolResult {
        val area = requireNotNull(request.outputArea)
        val path = requireNotNull(request.outputPath)
        val existing = store.inspect(scope).entries.firstOrNull {
            it.workspaceRef.area == area && it.workspaceRef.relativePath == path
        }
        if (existing != null) {
            return NovexToolResult.failure(
                code = "workspace.already_exists",
                summary = "受限计算不会覆盖同名文件，请指定新路径",
                affectedRefs = listOf(existing.workspaceRef.asResourceRef()),
            )
        }
        return runCatching {
            val output = store.writeText(scope, area, path, content, mimeType, provenance)
            NovexToolResult.success(
                code = "workspace.computed",
                summary = "已完成 ${request.operation.wireName} 并写入 ${output.workspaceRef.relativePath}",
                data = entryPayload(output) + mapOf(
                    "operation" to request.operation.wireName,
                    "input_refs" to entries.map { it.workspaceRef.value },
                ),
                affectedRefs = entries.map { it.workspaceRef.asResourceRef() } +
                    listOfNotNull(output.workspaceRef.asResourceRef(), output.artifactRef),
                sideEffect = NovexToolSideEffect.SESSION_REVERSIBLE,
            )
        }.getOrElse(::writeFailure)
    }

    private fun formatJson(text: String, indent: Int): String = when (val value = JSONTokener(text).nextValue()) {
        is JSONObject -> value.toString(indent)
        is JSONArray -> value.toString(indent)
        else -> throw IllegalArgumentException("JSON 根必须是对象或数组")
    }

    private fun editConflict(entry: NovexWorkspaceEntry) = NovexToolResult.failure(
        code = "workspace.edit_conflict",
        summary = "文件已发生变化，请重新读取后再编辑",
        data = mapOf("current_sha256" to entry.sha256),
        affectedRefs = listOf(entry.workspaceRef.asResourceRef()),
    )

    private fun notFound(ref: NovexWorkspaceFileRef) = NovexToolResult.failure(
        code = "workspace.not_found",
        summary = "当前对话分支看不到指定工作区文件",
        affectedRefs = listOf(ref.asResourceRef()),
    )

    private fun writeFailure(failure: Throwable): NovexToolResult = NovexToolResult.failure(
        code = "workspace.write_failed",
        summary = failure.message?.takeIf { it.isNotBlank() } ?: "工作区写入失败",
    )

    private fun entryPayload(entry: NovexWorkspaceEntry): Map<String, Any?> = linkedMapOf(
        "workspace_ref" to entry.workspaceRef.value,
        "area" to entry.workspaceRef.area.wireName,
        "path" to entry.workspaceRef.relativePath,
        "mime_type" to entry.mimeType,
        "byte_count" to entry.byteCount,
        "sha256" to entry.sha256,
        "artifact_ref" to entry.artifactRef?.value,
        "created_at_millis" to entry.createdAtMillis,
        "updated_at_millis" to entry.updatedAtMillis,
        "source_branch" to entry.provenance.branchId,
        "source_message" to entry.provenance.messageId,
        "source_refs" to entry.provenance.sourceRefs.map { it.value },
    )

    private fun encodeCursor(entry: NovexWorkspaceEntry, offset: Int): String {
        val payload = JSONObject()
            .put("version", 1)
            .put("workspace_ref", entry.workspaceRef.value)
            .put("sha256", entry.sha256)
            .put("offset", offset)
            .toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    }

    private fun decodeCursor(value: String, entry: NovexWorkspaceEntry): Int? = runCatching {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8))
        if (json.getInt("version") != 1) return null
        if (json.getString("workspace_ref") != entry.workspaceRef.value) return null
        if (json.getString("sha256") != entry.sha256) return null
        json.getInt("offset").takeIf { it in 0..Int.MAX_VALUE }
    }.getOrNull()
}

/** Provider-independent JSON decoder for the four workspace tools. */
class NovexConversationWorkspaceToolRouter(
    private val tools: NovexConversationWorkspaceTools,
) {
    fun execute(name: String, argumentsJson: String): NovexToolResult {
        if (name !in TOOL_NAMES) {
            return NovexToolResult.failure(
                code = "tool.unknown",
                summary = "当前工作区工具不存在",
                allowedValues = TOOL_NAMES,
            )
        }
        return runCatching {
            val arguments = JSONObject(argumentsJson.ifBlank { "{}" })
            when (name) {
                WORKSPACE_INSPECT -> tools.workspaceInspect(
                    NovexWorkspaceInspectRequest(
                        area = arguments.optionalString("area")?.let(NovexWorkspaceArea::fromWireName),
                        maxEntries = arguments.optInt("max_entries", 200),
                    ),
                )
                WORKSPACE_READ -> tools.workspaceRead(
                    NovexWorkspaceReadRequest(
                        workspaceRef = arguments.workspaceRef(),
                        cursor = arguments.optionalString("cursor"),
                        maxChars = arguments.optInt("max_chars", 12_000),
                    ),
                )
                WORKSPACE_WRITE -> tools.workspaceWrite(
                    NovexWorkspaceWriteRequest(
                        area = NovexWorkspaceArea.fromWireName(arguments.requiredString("area")),
                        relativePath = arguments.requiredString("path"),
                        content = arguments.requiredString("content", allowEmpty = true),
                        mimeType = arguments.optionalString("mime_type") ?: "text/markdown",
                    ),
                )
                WORKSPACE_EDIT -> tools.workspaceEdit(
                    NovexWorkspaceEditRequest(
                        workspaceRef = arguments.workspaceRef(),
                        expectedSha256 = arguments.requiredString("expected_sha256"),
                        startChar = arguments.getInt("start_char"),
                        endChar = arguments.getInt("end_char"),
                        replacement = arguments.requiredString("replacement", allowEmpty = true),
                    ),
                )
                WORKSPACE_COMPUTE -> {
                    val operation = NovexWorkspaceComputeOperation.fromWireName(
                        arguments.requiredString("operation"),
                    ) ?: return NovexToolResult.failure(
                        code = "workspace.unsupported_operation",
                        summary = "不支持的工作区受限计算操作",
                        allowedValues = NovexWorkspaceComputeOperation.allowedValues,
                    )
                    tools.workspaceCompute(
                        NovexWorkspaceComputeRequest(
                            operation = operation,
                            inputRefs = arguments.workspaceRefs(),
                            outputArea = arguments.optionalString("output_area")?.let(NovexWorkspaceArea::fromWireName),
                            outputPath = arguments.optionalString("output_path"),
                            delimiter = arguments.optionalString("delimiter") ?: "\n\n",
                            indent = arguments.optInt("indent", 2),
                        ),
                    )
                }
                else -> error("unreachable")
            }
        }.getOrElse { failure ->
            NovexToolResult.failure(
                code = "tool.invalid_arguments",
                summary = failure.message?.takeIf(String::isNotBlank) ?: "工作区工具参数无效",
                allowedValues = TOOL_NAMES,
            )
        }
    }

    private fun JSONObject.workspaceRef(): NovexWorkspaceFileRef =
        NovexWorkspaceFileRef.parse(requiredString("workspace_ref"))

    private fun JSONObject.workspaceRefs(): List<NovexWorkspaceFileRef> {
        require(has("input_refs") && !isNull("input_refs")) { "缺少 input_refs" }
        val values = getJSONArray("input_refs")
        return (0 until values.length()).map { index ->
            NovexWorkspaceFileRef.parse(values.getString(index))
        }
    }

    private fun JSONObject.requiredString(name: String, allowEmpty: Boolean = false): String {
        require(has(name) && !isNull(name)) { "缺少 $name" }
        val value = getString(name)
        require(allowEmpty || value.isNotBlank()) { "$name 不能为空" }
        return value
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name).takeIf(String::isNotBlank) else null

    companion object {
        const val WORKSPACE_INSPECT = "workspace_inspect"
        const val WORKSPACE_READ = "workspace_read"
        const val WORKSPACE_WRITE = "workspace_write"
        const val WORKSPACE_EDIT = "workspace_edit"
        const val WORKSPACE_COMPUTE = "workspace_compute"
        val TOOL_NAMES = listOf(
            WORKSPACE_INSPECT,
            WORKSPACE_READ,
            WORKSPACE_WRITE,
            WORKSPACE_EDIT,
            WORKSPACE_COMPUTE,
        )
    }
}

private const val MAX_COMPUTE_INPUT_BYTES = 2L * 1024L * 1024L
