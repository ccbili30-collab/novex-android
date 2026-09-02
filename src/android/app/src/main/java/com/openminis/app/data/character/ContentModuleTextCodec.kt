package com.openminis.app.data.character

/** Compatibility adapter for the remaining text-only editor surfaces. */
object ContentModuleTextCodec {
    fun encode(text: String): String = ContentModuleDocumentCodec.encode(
        ContentModuleDocument.Article(text),
    )

    fun decode(contentJson: String): String = ContentModuleDocumentCodec.decode(contentJson).toPlainText()
}
