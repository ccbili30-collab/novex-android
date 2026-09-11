package novex.storage

import novex.content.*
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.UUID

/** 已接收为自有文件的 UTF-8（万国码变长编码）原文导入；不猜格式、不调用模型。 */
object RawTextImport {
    const val ORIGINAL = "novex.import.original"

    /** 返回候选卡片，尚未正式归库。读完验证编码及底层摘要，不复制或截断正文。 */
    fun prepareUtf8(
        original: ContentRef,
        files: StagedContentFiles,
        kind: CardKind,
        name: String,
    ): ContentDocument {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        InputStreamReader(files.open(original), decoder).use { reader ->
            val buffer = CharArray(8192)
            while (reader.read(buffer) >= 0) Unit
        }
        return ContentDocument(
            id = UUID.randomUUID().toString(), kind = kind, name = name,
            modules = listOf(ContentModule(
                id = UUID.randomUUID().toString(), name = "",
                blocks = listOf(ContentBlock.Text(UUID.randomUUID().toString(), original)),
            )),
            extensions = mapOf(ORIGINAL to original),
        ).validate()
    }
}
