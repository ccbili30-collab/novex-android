package com.openminis.app.novex.domain

/**
 * Compact persisted capability receipt for one branch-owned source collection.
 * It intentionally carries no source body and no device path.
 */
object NovexSourceCollectionPromptReceipt {
    private val receiptPattern = Regex(
        "<novex-source-collection\\s+[^>]*ref=\\\"(novex://source-collections/[0-9a-fA-F]{64})\\\"[^>]*/>",
    )

    fun build(collectionRef: NovexResourceRef, sourceCount: Int): String {
        require(collectionRef.value.matches(COLLECTION_REF_PATTERN)) { "资料集引用无效" }
        require(sourceCount > 0) { "资料集回执至少需要一项来源" }
        return "<novex-source-collection ref=\"${collectionRef.value.lowercase()}\" sources=\"$sourceCount\" />"
    }

    fun refsIn(prompt: String): Set<NovexResourceRef> = receiptPattern.findAll(prompt)
        .map { match -> NovexResourceRef(match.groupValues[1].lowercase()) }
        .toSet()

    fun contains(text: String): Boolean = receiptPattern.containsMatchIn(text)

    fun stripFrom(text: String): String = text.replace(receiptPattern, "").trim()

    private val COLLECTION_REF_PATTERN = Regex(
        "novex://source-collections/[0-9a-fA-F]{64}",
    )
}
