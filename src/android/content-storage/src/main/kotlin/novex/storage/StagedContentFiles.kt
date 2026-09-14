package novex.storage

import novex.content.ContentRef
import novex.content.ContentTransfer
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

/** 文件事务实验，不是正式包格式。只有整个批次发布后，内容引用才可读。 */
class StagedContentFiles(private val root: Path) {
    private val idPattern = Regex("[a-zA-Z0-9-]+")
    init { Files.createDirectories(root.resolve("batches")); Files.createDirectories(root.resolve("pending")) }

    fun allocator(): () -> ContentRef {
        val batch = UUID.randomUUID().toString()
        return { ContentRef("$batch/${UUID.randomUUID()}") }
    }

    private fun address(ref: ContentRef): Pair<String, String> {
        val parts = ref.value.split('/')
        require(parts.size == 2 && parts.all { idPattern.matches(it) }) { "实验内容引用无效" }
        return parts[0] to parts[1]
    }

    /** 提供者返回空流引用或途中失败时，整个批次不可见，不生成成功回执。 */
    fun receive(transfers: List<ContentTransfer>, source: (ContentRef) -> InputStream?) {
        require(transfers.isNotEmpty()) { "没有待转存内容" }
        val addresses = transfers.map { address(it.destination) }
        require(addresses.map { it.first }.distinct().size == 1) { "一次转存只能发布一个批次" }
        require(addresses.distinct().size == addresses.size) { "目标内容引用重复" }
        val batch = addresses.first().first
        val published = root.resolve("batches").resolve(batch)
        require(!Files.exists(published)) { "批次已存在，不能覆盖" }
        val staging = root.resolve("pending").resolve(batch)
        Files.createDirectory(staging)
        try {
            val entries = mutableListOf<String>()
            transfers.zip(addresses).forEach { (transfer, address) ->
                val file = staging.resolve(address.second)
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val input = source(transfer.source) ?: throw IOException("来源内容无法读取")
                input.use { reader ->
                    Files.newOutputStream(file).use { writer ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("来源输入流未取得进展")
                            writer.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size += count
                        }
                    }
                }
                FileChannel.open(file, WRITE).use { it.force(true) }
                entries += "${address.second}\t$size\t${digest.digest().hex()}"
            }
            val manifest = staging.resolve("manifest")
            Utf8Files.write(manifest, entries.joinToString("\n"))
            FileChannel.open(manifest, WRITE).use { it.force(true) }
            // 无法提供原子目录移动的平台应明确失败，不退化为逐文件发布。
            Files.move(staging, published, ATOMIC_MOVE)
        } catch (failure: Throwable) {
            if (Files.exists(staging)) Files.walk(staging).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
            throw failure
        }
    }

    /** 从已知字节边界局部读取；非零偏移只校验登记与文件长度，不声称整文件摘要已验证。 */
    fun openAt(ref:ContentRef,offset:Long):InputStream {
        require(offset>=0){"内容位置无效"}
        if(offset==0L)return open(ref)
        val (batch,id)=address(ref)
        val directory=root.resolve("batches").resolve(batch)
        val row=Files.readAllLines(directory.resolve("manifest")).map {it.split('\t')}
            .singleOrNull {it.firstOrNull()==id}?:throw IOException("内容未登记")
        require(row.size==3){"实验清单损坏"}
        val size=row[1].toLong()
        val channel=FileChannel.open(directory.resolve(id),java.nio.file.StandardOpenOption.READ)
        try {
            if(channel.size()!=size)throw IOException("内容长度校验失败")
            require(offset<=size){"内容位置超过末尾"}
            channel.position(offset)
            return java.nio.channels.Channels.newInputStream(channel)
        } catch(failure:Throwable){channel.close();throw failure}
    }

    /** 完整读至末尾才完成摘要校验；调用方不能将未读完的流称为已验证。 */
    fun open(ref: ContentRef): InputStream {
        val (batch, id) = address(ref)
        val directory = root.resolve("batches").resolve(batch)
        val row = Files.readAllLines(directory.resolve("manifest")).map { it.split('\t') }
            .singleOrNull { it.firstOrNull() == id } ?: throw IOException("内容未登记")
        require(row.size == 3) { "实验清单损坏" }
        val expectedSize = row[1].toLong()
        val expectedHash = row[2]
        return object : FilterInputStream(Files.newInputStream(directory.resolve(id))) {
            private val digest = MessageDigest.getInstance("SHA-256")
            private var size = 0L
            private var verified = false
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = `in`.read(buffer, offset, length)
                if (count > 0) { digest.update(buffer, offset, count); size += count }
                if (count < 0 && !verified) {
                    if (size != expectedSize || digest.digest().hex() != expectedHash) throw IOException("内容校验失败")
                    verified = true
                }
                return count
            }
            override fun skip(n: Long): Long {
                val buffer = ByteArray(65536)
                var skipped = 0L
                while (skipped < n) {
                    val count = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                    if (count < 0) break
                    skipped += count
                }
                return skipped
            }
            override fun markSupported() = false
            override fun reset(): Unit = throw IOException("校验流不支持重置")
        }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
