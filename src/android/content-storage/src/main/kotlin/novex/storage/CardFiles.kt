package novex.storage

import novex.content.*
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 文件接收、导入草稿及正式版本导出共用入口；不依赖页面或模型。 */
class CardFiles(private val store: CardStore) {
    fun prepare(input: InputStream, name: String, fallbackKind: CardKind): CardDraft = input.use { source ->
        PushbackInputStream(source, 4).use { buffered ->
            val prefix = ByteArray(4)
            var count = 0
            while (count < prefix.size) {
                val read = buffered.read(prefix, count, prefix.size - count)
                if (read < 0) break
                require(read > 0) { "文件读取没有取得进展" }
                count += read
            }
            val signature = prefix.copyOf(count)
            require(signature.isNotEmpty()) { "文件为空，未创建卡片" }
            buffered.unread(signature)
            val archive = signature.size >= 4 && signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte() &&
                ((signature[2] == 3.toByte() && signature[3] == 4.toByte()) ||
                    (signature[2] == 5.toByte() && signature[3] == 6.toByte()))
            val card = if (archive) {
                val incoming = store.directory.resolve("incoming")
                Files.createDirectories(incoming)
                val pending = incoming.resolve(UUID.randomUUID().toString())
                try {
                    Files.newOutputStream(pending).use { buffered.copyTo(it, 65536) }
                    ExchangeLab.read(pending, store.contents)
                } finally { Files.deleteIfExists(pending) }
            } else {
                val destination = store.contents.allocator()()
                store.contents.receive(listOf(ContentTransfer(ContentRef("import"), destination))) { buffered }
                try {
                    if(signature.contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47)))
                        PngCardImport.prepare(destination,store.contents,fallbackKind,name)
                    else RawTextImport.prepareUtf8(destination, store.contents, fallbackKind, name)
                }
                catch (failure: java.nio.charset.CharacterCodingException) {
                    throw IllegalArgumentException("目前此入口支持 UTF-8（万国码变长编码）原文、含角色原文的 PNG（便携式网络图像）卡和本次新实现导出的交换包；该文件尚不能解析，未创建卡片", failure)
                }
            }
            CardDrafts(store).create(card, ChangeSource.IMPORT)
        }
    }

    /** 只导出已保存版本，草稿必须通过共同保存入口提交后才能替代它。 */
    fun export(cardId: String, destination: Path, targetId:String=cardId): SavedCard {
        val saved = requireNotNull(store.open(cardId)) { "卡片尚未保存，无法导出正式版本" }
        ExchangeLab.write(destination, ContentTargets.find(saved.content,targetId), store.contents,readable=true)
        return saved
    }
}
