package novex.storage

import java.util.stream.Collectors

import novex.content.*
import org.json.JSONObject
import org.json.JSONArray
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ChangeSource { HUMAN, AI, IMPORT }
data class SavedCard(val revision: String, val parentRevision: String?, val source: ChangeSource, val content: ContentDocument)
data class CardSummary(val id: String, val name: String, val kind: CardKind, val revision: String)
class RevisionConflict : IllegalStateException("卡片已更新，本次没有覆盖新内容")

/** 新内容的共同保存入口。结构与正文分开；列表只读正式摘要，不加载模块正文。 */
class CardStore(root: Path) {
    internal val directory = root.toAbsolutePath().normalize()
    val contents = StagedContentFiles(directory.resolve("contents"))
    internal val editAccess = CardEditAccess(directory)
    fun acquireEdit(cardId: String, targetId: String, owner: String): CardEditLease {
        val card = CardDrafts(this).read(cardId)?.content ?: requireNotNull(open(cardId)) { "卡片不存在" }.content
        return editAccess.acquire(card, targetId, owner)
    }
    private val heads = directory.resolve("heads")
    private val revisions = directory.resolve("revisions")
    private val mutex: Any

    init {
        Files.createDirectories(heads); Files.createDirectories(revisions)
        mutex = locks.computeIfAbsent(directory.toRealPath()) { Any() }
    }

    /** 调用方复用同一提交编号可核对中断后的结果，不能复用编号提交另一份修改。 */
    fun save(candidate: ContentDocument, expectedRevision: String?, source: ChangeSource, commitId: String, lease: CardEditLease? = null): SavedCard =
        save(candidate, expectedRevision, source, commitId, lease) { }

    internal fun save(candidate: ContentDocument, expectedRevision: String?, source: ChangeSource,
                      commitId: String, lease: CardEditLease? = null, checkpoint: (String) -> Unit): SavedCard = synchronized(mutex) {
        require(commitId.isNotBlank()) { "提交编号不能为空" }
        // 防止调用方持有的可变列表在校验后被修改；只复制结构，不复制正文。
        val card = CardStructureCodec.decode(CardStructureCodec.encode(candidate.validate())).validate()
        val record = JSONObject().put("version", 1).put("commit", commitId)
            .put("parent", expectedRevision ?: JSONObject.NULL).put("source", source.name)
            .put("card", CardStructureCodec.encode(card))
        val serialized = canonical(record)
        FileChannel.open(directory.resolve("write.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                check(!isDeleted(card.id)){"卡片已删除，本次未恢复或覆盖内容"}
                val headPath = heads.resolve(key(card.id))
                val current = if (Files.exists(headPath)) readHead(headPath) else null
                // 只有出现在正式祖先链的提交才算已保存，遗留的孤立修订不是成功回执。
                var ancestor = current?.revision
                val visited = mutableSetOf<String>()
                while (ancestor != null) {
                    check(visited.add(ancestor)) { "修订链损坏" }
                    val existing = readRevision(card.id, ancestor)
                    if (ancestor == commitId) {
                        require(Utf8Files.read(revisionPath(card.id, commitId)) == serialized) { "提交编号已用于其他修改" }
                        verifyContents(existing.content)
                        return@synchronized existing
                    }
                    ancestor = existing.parentRevision
                }
                if (current?.revision != expectedRevision) throw RevisionConflict()
                if (current != null) require(current.kind == card.kind) { "不能通过保存改变卡片种类" }
                editAccess.changes(current?.let { readRevision(card.id, it.revision).content }, card, lease) {
                verifyContents(card)
                val revisionPath = revisionPath(card.id, commitId)
                if (Files.exists(revisionPath)) {
                    require(Utf8Files.read(revisionPath) == serialized) { "提交编号已有不同候选内容" }
                } else durableWrite(revisionPath, serialized)
                checkpoint("revision-written")
                val summary = JSONObject().put("id", card.id).put("name", card.name)
                    .put("kind", card.kind.name).put("revision", commitId)
                replaceHead(headPath, canonical(summary))
                checkpoint("head-published")
                readRevision(card.id, commitId)
                }
            }
        }
    }

    fun isDeleted(cardId:String):Boolean = Files.exists(directory.resolve("deleted").resolve(key(cardId)))

    fun deletedCard(cardId:String):SavedCard? {
        val path=directory.resolve("deleted").resolve(key(cardId))
        if(!Files.exists(path))return null
        val head=readHead(path);return readRevision(cardId,head.revision)
    }

    /** 从当前库移除；保留历史、正文、图片和草稿，禁止旧迁移或迟到写入复活。 */
    fun delete(cardId:String,expectedRevision:String) = synchronized(mutex) {
        FileChannel.open(directory.resolve("write.lock"),CREATE,WRITE).use { channel -> channel.lock().use {
            val head=heads.resolve(key(cardId))
            if(!Files.exists(head)) { require(isDeleted(cardId)){"卡片不存在"};return@synchronized }
            val current=readHead(head)
            if(current.revision!=expectedRevision)throw RevisionConflict()
            val card=readRevision(cardId,current.revision).content
            val draft=CardDrafts(this).read(cardId)?.content
            val ids=setOf(cardId)+card.internalCharacters.map {it.id}+draft?.internalCharacters.orEmpty().map {it.id}
            editAccess.guarded(ids,null) {
                val trash=directory.resolve("deleted");Files.createDirectories(trash)
                Files.move(head,trash.resolve(key(cardId)),ATOMIC_MOVE)
            }
        }}
    }

    fun open(cardId: String): SavedCard? {
        val path = heads.resolve(key(cardId))
        if (!Files.exists(path)) return null
        val head = readHead(path)
        check(head.id == cardId) { "摘要对象不一致" }
        return readRevision(cardId, head.revision)
    }

    /** 仅允许读取正式修订链中的历史，不能打开半提交候选冒充存档。 */
    fun history(cardId: String, revision: String): SavedCard? {
        var item = open(cardId)
        val visited = mutableSetOf<String>()
        while (item != null) {
            check(visited.add(item.revision)) { "修订链损坏" }
            if (item.revision == revision) return item
            item = item.parentRevision?.let { readRevision(cardId, it) }
        }
        return null
    }

    fun list(): List<CardSummary> = Files.list(heads).use { paths ->
        paths.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}")) }
            .map(::readHead).collect(Collectors.toList()).sortedBy { it.name }
    }

    /** 读取已发布修订并完整校验其正文、图片和扩展；不会改变当前修订或发布候选。 */
    fun verify(cardId:String,revision:String):SavedCard {
        val saved=requireNotNull(history(cardId,revision)){"作品修订不存在"}
        verifyContents(saved.content)
        return saved
    }

    internal fun verifyContents(card: ContentDocument) {
        val buffer = ByteArray(65536)
        ExchangeLab.references(card).forEach { ref -> contents.open(ref).use { stream ->
            while (stream.read(buffer) >= 0) Unit
        } }
    }

    private fun readHead(path: Path): CardSummary {
        val json = JSONObject(Utf8Files.read(path))
        return CardSummary(json.getString("id"), json.getString("name"), CardKind.valueOf(json.getString("kind")), json.getString("revision"))
    }

    private fun readRevision(cardId: String, revision: String): SavedCard {
        val json = JSONObject(Utf8Files.read(revisionPath(cardId, revision)))
        require(json.getInt("version") == 1 && json.getString("commit") == revision) { "修订格式不匹配" }
        val card = CardStructureCodec.decode(json.getJSONObject("card")).validate()
        require(card.id == cardId) { "修订对象不匹配" }
        return SavedCard(revision, if (json.isNull("parent")) null else json.getString("parent"),
            ChangeSource.valueOf(json.getString("source")), card)
    }

    private fun revisionPath(cardId: String, revision: String) = revisions.resolve(key(cardId) + "-" + key(revision))
    private fun durableWrite(path: Path, text: String) {
        val pending = path.resolveSibling("pending-${UUID.randomUUID()}")
        try {
            FileChannel.open(pending, CREATE_NEW, WRITE).use { output ->
                val buffer = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(pending, path, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(pending) }
    }

    private fun replaceHead(path: Path, text: String) {
        val staging = heads.resolve("pending-${UUID.randomUUID()}")
        try {
            durableWrite(staging, text)
            Files.move(staging, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(staging) }
    }

    companion object { private val locks = ConcurrentHashMap<Path, Any>() }
}

private fun key(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** 键排序使同一提交重试不受映射遍历顺序影响，数组保留内容顺序。 */
private fun canonical(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toSet().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    // 数组的值编码在桌面与安卓都有公开接口，不调用安卓缺少的 valueToString。
    else -> JSONArray().put(value).toString().let { it.substring(1, it.length - 1) }
}
