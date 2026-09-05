package com.openminis.app.novex.domain

data class NovexStructuredDocumentBlock(
    val kind: NovexDocumentBlockKind,
    val text: String,
    val headingPath: List<String> = emptyList(),
    val headingLevel: Int? = null,
    val source: NovexDocumentSourceAnchor,
    val mediaRef: NovexResourceRef? = null,
)

data class NovexStructuredDocument(
    val status: NovexDocumentStatus,
    val blocks: List<NovexStructuredDocumentBlock>,
    val warnings: List<NovexDocumentWarning> = emptyList(),
)
