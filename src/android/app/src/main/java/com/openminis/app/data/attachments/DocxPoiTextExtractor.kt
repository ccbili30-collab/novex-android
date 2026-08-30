package com.openminis.app.data.attachments

import java.io.File
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument

/** Primary DOCX reader backed by the Android-shaded Apache POI build. */
internal object DocxPoiTextExtractor {
    data class Extraction(
        val text: String,
        val hasPictures: Boolean,
    )

    private val xmlParserConfigured: Unit by lazy {
        System.setProperty(
            "org.apache.poi.javax.xml.stream.XMLInputFactory",
            "com.fasterxml.aalto.stax.InputFactoryImpl",
        )
        System.setProperty(
            "org.apache.poi.javax.xml.stream.XMLOutputFactory",
            "com.fasterxml.aalto.stax.OutputFactoryImpl",
        )
        System.setProperty(
            "org.apache.poi.javax.xml.stream.XMLEventFactory",
            "com.fasterxml.aalto.stax.EventFactoryImpl",
        )
        Unit
    }

    fun extract(file: File): Extraction {
        xmlParserConfigured
        return file.inputStream().buffered().use { input ->
            XWPFDocument(input).use { document ->
                val extractor = XWPFWordExtractor(document).apply {
                    setFetchHyperlinks(true)
                }
                val primaryText = extractor.text

                // XWPFWordExtractor includes referenced comments, notes,
                // headers, footers, tables and hyperlink targets. Append all
                // comment/note bodies as a safety net for documents whose
                // reference markers are unusual or absent; appendUnique keeps
                // ordinary documents from duplicating them.
                val augmented = StringBuilder(primaryText)
                document.comments.orEmpty().forEach { comment ->
                    appendUnique(augmented, comment.paragraphs.joinToString("\n") { it.text })
                }
                document.footnotes.forEach { note ->
                    appendUnique(augmented, note.paragraphs.joinToString("\n") { it.text })
                }
                document.endnotes.forEach { note ->
                    appendUnique(augmented, note.paragraphs.joinToString("\n") { it.text })
                }

                Extraction(
                    text = augmented.toString(),
                    hasPictures = document.allPackagePictures.isNotEmpty(),
                )
            }
        }
    }

    private fun appendUnique(target: StringBuilder, candidate: String) {
        val normalized = candidate.trim()
        if (normalized.isEmpty() || target.contains(normalized)) return
        if (target.isNotEmpty() && !target.endsWith('\n')) target.append('\n')
        target.append(normalized).append('\n')
    }
}
