package novex.storage

import novex.content.*
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.UUID

/** 图片字节转存，供卡片和对话分别建立独立资源归属。 */
class ImageFiles(private val contents:StagedContentFiles) {
    fun receive(source: InputStream): CardResource = PushbackInputStream(source, 12).use { input ->
        val buffer = ByteArray(12)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            require(read > 0) { "图片读取没有取得进展" }
            count += read
        }
        val header = buffer.copyOf(count)
        input.unread(header)
        fun starts(vararg values: Int) = header.size >= values.size && values.indices.all { header[it].toInt() and 255 == values[it] }
        val mediaType = when {
            starts(137,80,78,71,13,10,26,10) -> "image/png"
            starts(255,216,255) -> "image/jpeg"
            header.size >= 6 && header.copyOfRange(0,6).toString(Charsets.US_ASCII) in setOf("GIF87a","GIF89a") -> "image/gif"
            header.size >= 12 && header.copyOfRange(0,4).toString(Charsets.US_ASCII) == "RIFF" &&
                header.copyOfRange(8,12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> throw IllegalArgumentException("图片格式尚不支持或文件无效")
        }
        val ref = contents.allocator()()
        contents.receive(listOf(ContentTransfer(ContentRef("image-input"), ref))) { input }
        CardResource(UUID.randomUUID().toString(), ref, mediaType)
    }
}
