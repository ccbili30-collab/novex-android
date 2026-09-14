package novex.storage

import java.util.stream.Collectors

import novex.content.*
import org.json.JSONObject
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class EditorPosition(val moduleId: String? = null, val blockId: String? = null,
                          val selectionStart: Int = 0, val selectionEnd: Int = 0, val scrollOffset: Double = 0.0, val textOffset: Long = 0, val textLength: Int = 4096)
data class CardDraft(val version: String, val baseRevision: String?, val content: ContentDocument,
                     val position: EditorPosition = EditorPosition(), val source: ChangeSource = ChangeSource.HUMAN, val scopedPublication: Boolean = false,
                     val remainderSource: ChangeSource = ChangeSource.HUMAN)
class DraftConflict : IllegalStateException("草稿已更新，当前编辑结果未覆盖新草稿")

/** 持久草稿与正式内容分开；预览和编辑读取同一草稿，提交调用唯一 CardStore（卡片存储）入口。 */
class CardDrafts(private val store: CardStore) {
    private val root = store.directory.resolve("drafts")
    private val mutex: Any
    init { Files.createDirectories(root); mutex = locks.computeIfAbsent(root.toRealPath()) { Any() } }

    fun create(content: ContentDocument, source: ChangeSource = ChangeSource.HUMAN): CardDraft = locked {
        require(store.open(content.id) == null && raw(content.id) == null) { "卡片或草稿已经存在" }
        write(CardDraft(newId(), null, content.validate(), source = source))
    }

    fun begin(cardId: String): CardDraft = locked {
        recover(cardId) ?: run {
            val saved = requireNotNull(store.open(cardId)) { "卡片不存在" }
            write(CardDraft(newId(), saved.revision, saved.content))
        }
    }

    fun read(cardId: String): CardDraft? = locked { recover(cardId) }

    /** 始终检查草稿版本，防止后台延迟写入把较新的文字或位置覆盖。 */
    fun update(cardId: String, expectedVersion: String, content: ContentDocument, position: EditorPosition, source: ChangeSource = ChangeSource.HUMAN, lease: CardEditLease? = null): CardDraft = locked {
        val current = requireVersion(cardId, expectedVersion)
        require(content.id == cardId && content.kind == current.content.kind) { "草稿对象不能改变" }
        validatePosition(content.validate(), position)
        val positionTarget = if (position == current.position) emptySet() else {
            val id = position.moduleId
            setOf(content.internalCharacters.find { role -> role.modules.flattenModules().any { it.id == id } }?.id ?: content.id)
        }
        store.editAccess.changes(current.content, content, lease, positionTarget) {
            write(CardDraft(newId(), current.baseRevision, content, position,
                if (content == current.content) current.source else source))
        }
    }

    fun commit(cardId: String, expectedVersion: String, lease: CardEditLease? = null): SavedCard = commit(cardId, expectedVersion, lease) { }

    internal fun commit(cardId: String, expectedVersion: String, lease: CardEditLease? = null, afterSave: () -> Unit): SavedCard = locked {
        val draft = raw(cardId)
        if (draft == null) {
            // 已保存并完成清理的同一次请求重试，查真实历史，不重复创建修订。
            return@locked store.history(cardId, expectedVersion) ?: throw DraftConflict()
        }
        if (draft.version != expectedVersion) throw DraftConflict()
        val saved = store.save(draft.content, draft.baseRevision, draft.source, draft.version, lease)
        afterSave()
        Files.delete(path(cardId))
        saved
    }

    /** 发布当前目标；世界内部其他角色的草稿继续保留。发布标记先落盘，便于中断后重建剩余草稿。 */
    fun commitTarget(cardId: String, expectedVersion: String, targetId: String,
                     beforeEdit: CardDraft, lease: CardEditLease? = null): SavedCard = locked {
        store.history(cardId, expectedVersion)?.let { recover(cardId); return@locked it }
        val draft = requireVersion(cardId, expectedVersion)
        val saved = store.open(cardId)
        require(saved != null || targetId == cardId) { "请先保存世界，再管理其中的角色" }
        if (saved?.revision != draft.baseRevision || beforeEdit.baseRevision != draft.baseRevision) throw DraftConflict()
        val target = ContentTargets.find(draft.content, targetId)
        val candidate = if (targetId == cardId) {
            // 世界权限不等于所有内部角色的编辑权限；只带本次命令改变的成员关系。
            val beforeIds = beforeEdit.content.internalCharacters.map { it.id }.toSet()
            val afterIds = draft.content.internalCharacters.map { it.id }.toSet()
            val removed = beforeIds - afterIds
            val added = draft.content.internalCharacters.filter { it.id !in beforeIds }
            target.copy(internalCharacters = (saved?.content?.internalCharacters?:emptyList()).filter { it.id !in removed } + added)
        } else ContentTargets.replace(requireNotNull(saved).content, target)
        val retainedPosition = try { validatePosition(draft.content, beforeEdit.position); beforeEdit.position }
            catch(_: IllegalArgumentException) { draft.position }
        write(draft.copy(position = retainedPosition, scopedPublication = true, remainderSource = beforeEdit.source))
        val result = store.save(candidate.validate(), draft.baseRevision, draft.source, draft.version, lease)
        recover(cardId)
        result
    }

    /** 只删除明确指定版本的草稿；正式内容和资源均不删除。 */
    fun discard(cardId: String, expectedVersion: String) = locked {
        val current = requireVersion(cardId, expectedVersion)
        store.editAccess.guarded((listOf(current.content.id) + current.content.internalCharacters.map { it.id }).toSet(), null) { Files.delete(path(cardId)) }
    }

    fun list(): List<CardDraft> = locked {
        val ids = Files.list(root).use { paths -> paths.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}")) }
            .map { decode(JSONObject(Utf8Files.read(it))).content.id }.collect(Collectors.toList()) }
        ids.mapNotNull(::recover)
    }

    private fun recover(cardId: String): CardDraft? {
        val draft = raw(cardId) ?: return null
        // 保存完成后进程退出、来不及清草稿：以正式修订链为准。
        val published = store.history(cardId, draft.version)
        if (published != null) {
            if (draft.scopedPublication && draft.content != published.content) {
                return write(draft.copy(version = newId(), baseRevision = published.revision,
                    source = draft.remainderSource, scopedPublication = false))
            }
            Files.delete(path(cardId)); return null
        }
        return draft
    }

    private fun requireVersion(cardId: String, version: String): CardDraft {
        val draft = recover(cardId) ?: throw DraftConflict()
        if (draft.version != version) throw DraftConflict()
        return draft
    }

    private fun raw(cardId: String): CardDraft? {
        val file = path(cardId)
        if (!Files.exists(file)) return null
        return decode(JSONObject(Utf8Files.read(file))).also { require(it.content.id == cardId) { "草稿对象不匹配" } }
    }

    private fun write(draft: CardDraft): CardDraft {
        val json = encode(draft)
        val detached = decode(json)
        store.verifyContents(detached.content)
        val pending = root.resolve("pending-${newId()}")
        try {
            FileChannel.open(pending, CREATE_NEW, WRITE).use { output ->
                val buffer = java.nio.ByteBuffer.wrap(json.toString().toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) output.write(buffer)
                output.force(true)
            }
            Files.move(pending, path(draft.content.id), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending) }
        return detached
    }

    private fun encode(draft: CardDraft) = JSONObject().put("schema", 1).put("version", draft.version)
        .put("base", draft.baseRevision ?: JSONObject.NULL).put("source", draft.source.name).put("card", CardStructureCodec.encode(draft.content))
        .put("scopedPublication", draft.scopedPublication).put("remainderSource", draft.remainderSource.name)
        .put("position", JSONObject().put("module", draft.position.moduleId ?: JSONObject.NULL)
            .put("block", draft.position.blockId ?: JSONObject.NULL).put("start", draft.position.selectionStart)
            .put("end", draft.position.selectionEnd).put("scroll", draft.position.scrollOffset).put("textOffset", draft.position.textOffset).put("textLength", draft.position.textLength))

    private fun decode(json: JSONObject): CardDraft {
        require(json.getInt("schema") == 1) { "草稿格式不支持" }
        fun optional(objectValue: JSONObject, key: String) = if (objectValue.isNull(key)) null else objectValue.getString(key)
        val pos = json.getJSONObject("position")
        val content = CardStructureCodec.decode(json.getJSONObject("card")).validate()
        val position = EditorPosition(optional(pos, "module"), optional(pos, "block"), pos.getInt("start"), pos.getInt("end"), pos.getDouble("scroll"), pos.optLong("textOffset", 0), pos.optInt("textLength", 4096))
        validatePosition(content, position)
        return CardDraft(json.getString("version"), optional(json, "base"), content, position,
            ChangeSource.valueOf(json.optString("source", ChangeSource.HUMAN.name)),
            json.optBoolean("scopedPublication", false), ChangeSource.valueOf(json.optString("remainderSource", ChangeSource.HUMAN.name)))
    }

    private fun validatePosition(card: ContentDocument, pos: EditorPosition) {
        require(pos.textLength > 0 && pos.textOffset >= 0 && pos.selectionStart >= 0 && pos.selectionEnd >= pos.selectionStart && pos.scrollOffset.isFinite() && pos.scrollOffset >= 0) { "编辑位置无效" }
        val modules = card.modules.flattenModules() + card.internalCharacters.flatMap { it.modules.flattenModules() }
        val module = pos.moduleId?.let { id -> requireNotNull(modules.find { it.id == id }) { "编辑模块不存在" } }
        if (pos.blockId != null) require(module != null && module.blocks.any { it.id == pos.blockId }) { "编辑内容块不存在" }
    }

    private fun path(id: String) = root.resolve(MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
    private fun <T> locked(block: () -> T): T = synchronized(mutex) {
        FileChannel.open(root.resolve("write.lock"), CREATE, WRITE).use { it.lock().use { block() } }
    }
    companion object {
        private val locks = ConcurrentHashMap<Path, Any>()
        private fun newId() = UUID.randomUUID().toString()
    }
}
