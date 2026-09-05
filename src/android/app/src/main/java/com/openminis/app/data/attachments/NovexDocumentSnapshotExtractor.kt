package com.openminis.app.data.attachments

import android.content.Context
import com.openminis.app.novex.domain.NovexCompatibilityDocument
import com.openminis.app.novex.domain.NovexDocumentDescriptor
import com.openminis.app.novex.domain.NovexDocumentFormat
import com.openminis.app.novex.domain.NovexDocumentSnapshot
import com.openminis.app.novex.domain.NovexDocumentSnapshotCache
import com.openminis.app.novex.domain.NovexDocumentSnapshotPipeline
import com.openminis.app.novex.domain.NovexDocumentStatus
import com.openminis.app.novex.domain.NovexDocumentWarning
import com.openminis.app.novex.domain.NovexResourceRef
import java.io.File
import java.security.MessageDigest

fun interface NovexLegacyDocumentExtractor {
    fun extract(
        context: Context?,
        file: File,
        mimeType: String?,
        originalName: String,
    ): DocumentTextExtractor.Result?
}

/**
 * Compatibility adapter around the existing Android extractors.
 *
 * It is intentionally thin: file identity, cache invalidation and semantic block conversion live
 * in novex-core, while Android-only PDF and Apache POI parsing remain behind this boundary.
 */
class NovexDocumentSnapshotExtractor(
    cache: NovexDocumentSnapshotCache,
    private val legacyExtractor: NovexLegacyDocumentExtractor = NovexLegacyDocumentExtractor(
        DocumentTextExtractor::extract,
    ),
    private val parserVersion: String = PARSER_VERSION,
) {
    private val pipeline = NovexDocumentSnapshotPipeline(cache)

    fun extract(
        context: Context?,
        file: File,
        mimeType: String?,
        originalName: String,
    ): NovexDocumentSnapshot? {
        val format = formatOf(originalName, mimeType) ?: return null
        val sha256 = file.sha256()
        val descriptor = NovexDocumentDescriptor(
            ref = NovexResourceRef("novex://documents/$sha256"),
            sha256 = sha256,
            title = originalName,
            format = format,
            parserVersion = parserVersion,
        )
        if (format == NovexDocumentFormat.DOC) {
            return pipeline.resolve(descriptor) {
                NovexCompatibilityDocument(
                    text = "",
                    status = NovexDocumentStatus.UNSUPPORTED,
                    warnings = listOf(
                        NovexDocumentWarning(
                            code = "document.legacy_doc_unsupported",
                            message = "旧版 Word 文档尚未支持，请另存为 .docx 后重试",
                        ),
                    ),
                )
            }
        }

        return pipeline.resolve(descriptor) {
            try {
                val result = legacyExtractor.extract(context, file, mimeType, originalName)
                    ?: return@resolve NovexCompatibilityDocument(
                        text = "",
                        status = NovexDocumentStatus.UNSUPPORTED,
                        warnings = listOf(
                            NovexDocumentWarning("document.unsupported", "当前文档格式不受支持"),
                        ),
                    )
                result.toCompatibilityDocument()
            } catch (failure: Exception) {
                val passwordProtected = failure.causeChain().any { cause ->
                    val detail = "${cause.javaClass.simpleName} ${cause.message.orEmpty()}".lowercase()
                    "password" in detail || "encrypted" in detail || "加密" in detail || "密码" in detail
                }
                NovexCompatibilityDocument(
                    text = "",
                    status = if (passwordProtected) {
                        NovexDocumentStatus.PASSWORD_REQUIRED
                    } else {
                        NovexDocumentStatus.DAMAGED
                    },
                    warnings = listOf(
                        NovexDocumentWarning(
                            code = if (passwordProtected) {
                                "document.password_required"
                            } else {
                                "document.parse_failed"
                            },
                            message = if (passwordProtected) {
                                "文档受到密码保护，需要用户提供可读取版本"
                            } else {
                                "文档解析失败，原文件未被修改，可以更换文件后重试"
                            },
                        ),
                    ),
                )
            }
        }
    }

    private fun DocumentTextExtractor.Result.toCompatibilityDocument(): NovexCompatibilityDocument {
        val warnings = buildList {
            if (requiresOcr) add(
                NovexDocumentWarning(
                    code = "document.ocr_required",
                    message = "文档没有可提取文字，需要光学字符识别或视觉模型",
                ),
            )
            if (emptyDocument && !requiresOcr) add(
                NovexDocumentWarning(
                    code = "document.empty",
                    message = "文档没有可提取的可见正文",
                ),
            )
            if (truncated) add(
                NovexDocumentWarning(
                    code = "document.compatibility_extract_truncated",
                    message = "兼容解析结果过长，已保留截断状态",
                ),
            )
            if (primaryFailureType != null) add(
                NovexDocumentWarning(
                    code = "document.compatibility_fallback_used",
                    message = "主解析器失败，已使用兼容降级解析器",
                ),
            )
        }
        return NovexCompatibilityDocument(
            text = contentText,
            status = when {
                requiresOcr -> NovexDocumentStatus.OCR_REQUIRED
                emptyDocument -> NovexDocumentStatus.EMPTY
                truncated -> NovexDocumentStatus.TRUNCATED
                else -> NovexDocumentStatus.READY
            },
            warnings = warnings,
        )
    }

    private fun formatOf(fileName: String, mimeType: String?): NovexDocumentFormat? {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "doc" -> NovexDocumentFormat.DOC
            "docx" -> NovexDocumentFormat.DOCX
            "pdf" -> NovexDocumentFormat.PDF
            "xlsx" -> NovexDocumentFormat.XLSX
            "pptx" -> NovexDocumentFormat.PPTX
            "epub" -> NovexDocumentFormat.EPUB
            "rtf" -> NovexDocumentFormat.RTF
            "txt" -> NovexDocumentFormat.TEXT
            "md", "markdown" -> NovexDocumentFormat.MARKDOWN
            "html", "htm" -> NovexDocumentFormat.HTML
            "csv" -> NovexDocumentFormat.CSV
            "json" -> NovexDocumentFormat.JSON
            "xml" -> NovexDocumentFormat.XML
            "yaml", "yml" -> NovexDocumentFormat.YAML
            else -> when {
                mimeType.orEmpty().contains("wordprocessingml", ignoreCase = true) -> NovexDocumentFormat.DOCX
                mimeType.orEmpty().contains("application/msword", ignoreCase = true) -> NovexDocumentFormat.DOC
                mimeType.orEmpty().contains("application/pdf", ignoreCase = true) -> NovexDocumentFormat.PDF
                mimeType.orEmpty().startsWith("text/", ignoreCase = true) -> NovexDocumentFormat.TEXT
                else -> null
            }
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

    companion object {
        const val PARSER_VERSION = "android-compatibility-v1"
    }
}
