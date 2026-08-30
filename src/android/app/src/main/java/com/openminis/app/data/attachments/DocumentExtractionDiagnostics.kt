package com.openminis.app.data.attachments

internal data class DocumentExtractionDiagnostic(
    val userMessage: String,
    val logMessage: String,
    val exceptionType: String,
    val stage: String,
)

internal fun documentExtractionDiagnostic(
    fileName: String,
    fileSize: Long,
    failure: Throwable,
): DocumentExtractionDiagnostic {
    val extractionFailure = failure as? DocumentTextExtractor.ExtractionException
    val rootFailure = failure.cause ?: failure
    val stage = extractionFailure?.stage ?: "本地文档解析"
    val exceptionType = rootFailure.javaClass.name
    val simpleType = rootFailure.javaClass.simpleName.ifBlank { exceptionType.substringAfterLast('.') }
    return DocumentExtractionDiagnostic(
        userMessage = "文档解析失败：$simpleType（异常类型），阶段：$stage。",
        logMessage = "document_extraction_failed file=$fileName size=$fileSize " +
            "exception=$exceptionType stage=$stage message=${rootFailure.message.orEmpty()}",
        exceptionType = exceptionType,
        stage = stage,
    )
}
