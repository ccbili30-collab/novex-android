package novex.storage

import novex.content.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 候选交换格式，仅供实验。载荷按流复制；结构文件不内嵌正文和图片字节。 */
object ExchangeLab {
    fun write(path: Path, card: ContentDocument, contents: StagedContentFiles) {
        card.validate()
        val references = references(card)
        val paths = references.mapIndexed { index, ref -> ref to "contents/$index" }.toMap()
        val entries = JSONObject()
        val metadata = JSONObject().put("experiment", "novex-content-exchange").put("version", 1)
            .put("card", CardStructureCodec.encode(card)).put("contents", entries)
        // 调用方提供独立输出；错误时移除半包，不能把截断输出说成导出完成。
        require(!Files.exists(path)) { "输出已存在" }
        var created = false
        try {
            ZipOutputStream(Files.newOutputStream(path, CREATE_NEW, WRITE).also { created = true }).use { zip ->
                paths.forEach { (reference, name) ->
                    zip.putNextEntry(ZipEntry(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    contents.open(reference).use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            zip.write(buffer, 0, count); digest.update(buffer, 0, count); size += count
                        }
                    }
                    entries.put(reference.value, JSONObject().put("path", name).put("size", size).put("sha256", hex(digest.digest())))
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("structure.json"))
                zip.write(metadata.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        } catch (failure: Throwable) {
            if (created) Files.deleteIfExists(path)
            throw failure
        }
    }

    /** 返回已转存资源的候选内容，不声称卡片已正式归库。 */
    fun read(path: Path, destination: StagedContentFiles): ContentDocument = read(path,destination,false)

    /** 存档还原候选：保留对象编号，正文与资源独立转存。调用方必须在隔离区域准备，不能据此覆盖现有作品。 */
    fun restoreSnapshot(path:Path,destination:StagedContentFiles):ContentDocument = read(path,destination,true)

    private fun read(path:Path,destination:StagedContentFiles,preserveIds:Boolean):ContentDocument = ZipFile(path.toFile()).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toList()
        require(names.size == names.distinct().size) { "包内存在重名条目" }
        val header = zip.getEntry("structure.json") ?: error("缺少内容结构")
        val metadata = JSONObject(zip.getInputStream(header).bufferedReader(Charsets.UTF_8).use { it.readText() })
        fields(metadata, setOf("experiment", "version", "card", "contents"))
        require(metadata.getString("experiment") == "novex-content-exchange" && metadata.getInt("version") == 1) { "不是支持的实验包版本" }
        val card = CardStructureCodec.decode(metadata.getJSONObject("card")).validate()
        val entries = metadata.getJSONObject("contents")
        val refs = references(card)
        require(entries.keys().asSequence().toSet() == refs.map { it.value }.toSet()) { "内容清单与实际引用不一致" }
        val paths = refs.associateWith {
            val entry = entries.getJSONObject(it.value); fields(entry, setOf("path", "size", "sha256"))
            require(entry.getLong("size") >= 0 && Regex("[0-9a-f]{64}").matches(entry.getString("sha256"))) { "内容校验元数据无效" }
            entry.getString("path")
        }
        require(paths.values.toSet().size == paths.size) { "多个内容引用映射到同一包条目" }
        require(paths.values.all { Regex("contents/[0-9]+").matches(it) }) { "实验包条目路径无效" }
        require(names.toSet() == paths.values.toSet() + "structure.json") { "包条目缺失或含无法处理的额外条目" }
        val plan = if(preserveIds)ContentCopies.relocate(card,destination.allocator()) else ContentCopies.plan(card, { UUID.randomUUID().toString() }, destination.allocator())
        if (plan.transfers.isNotEmpty()) destination.receive(plan.transfers) { source ->
            val entry = entries.getJSONObject(source.value)
            checked(zip.getInputStream(zip.getEntry(paths.getValue(source))), entry.getLong("size"), entry.getString("sha256"))
        }
        plan.candidate
    }

    private fun fields(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected) { "交换结构字段不完整或含未支持字段" }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun checked(source: InputStream, expectedSize: Long, expectedHash: String): InputStream = object : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L
        private var verified = false
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(buffer, offset, length)
            if (count > 0) { digest.update(buffer, offset, count); size += count }
            if (size > expectedSize) throw IOException("包内正文超过登记长度")
            if (count < 0 && !verified) {
                if (size != expectedSize || hex(digest.digest()) != expectedHash) throw IOException("包内内容校验失败")
                verified = true
            }
            return count
        }
        override fun close() = source.close()
    }

    internal fun references(card: ContentDocument): Set<ContentRef> = buildSet {
        addAll(card.resources.map { it.content }); addAll(card.extensions.values)
        card.modules.flattenModules().flatMap { it.blocks }.forEach {
            when (it) {
                is ContentBlock.Text -> add(it.content)
                is ContentBlock.Image -> it.caption?.let(::add)
            }
        }
        card.internalCharacters.forEach { addAll(references(it)) }
    }

}
