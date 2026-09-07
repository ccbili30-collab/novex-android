package com.openminis.app.share

import android.content.Context
import android.database.Cursor
import androidx.room.withTransaction
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardPackageCodec
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.novex.domain.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Native user export only: raw stored conversation, selected environment, immutable manifest. No provider credentials. */
data class NovexConversationExportState(val busy: Boolean = false, val result: NovexConversationBundleResult? = null, val error: String? = null)
private class BundleLimitExceeded : IllegalStateException("对话包超过一吉字节或两万文件；未生成截断包")

data class NovexConversationBundleResult(val file: File, val messageCount: Int, val fileCount: Int,
    val missing: List<String>, val traceCount: Int)

class NovexConversationBundleExporter(private val context: Context, private val database: AppDatabase,
    private val workspace: NovexWorkspace) {
    suspend fun export(conversationId: String, runtime: JSONObject): NovexConversationBundleResult = withContext(Dispatchers.IO) {
        require(conversationId.isNotBlank()) { "尚无可导出的对话" }
        require(runtime.getString("conversationId") == conversationId) { "当前环境不属于此对话" }
        val exportContext = currentCoroutineContext()
        val id = UUID.randomUUID().toString()
        val stage = File(context.cacheDir, "conversation-bundle-staging/$id").apply { check(mkdirs()) }
        val partial = File(context.cacheDir, "shared/conversation-$id.zip.partial")
        val destination = File(context.cacheDir, "shared/conversation-$id.zip")
        val missing = linkedSetOf<String>()
        val manifestEntries = JSONArray()
        val localFiles = linkedMapOf<String, Pair<File, String?>>()
        val collectionRefs = linkedSetOf<String>()
        val documentRefs = linkedSetOf<String>()
        var totalBytes = 0L
        var messageCount = 0
        var traceCount = 0
        val userTextMessageIds = linkedSetOf<String>()
        val tracedRequestIds = linkedSetOf<String>()
        fun target(path: String): File {
            exportContext.ensureActive()
            require(!path.startsWith("/") && '\\' !in path && path.split('/').none { it == ".." || it == "." }) { "导出条目路径不安全" }
            val file = File(stage, path).canonicalFile
            require(file.path.startsWith(stage.canonicalPath + File.separator)) { "导出路径越界" }
            check(!file.exists()) { "导出条目重复：$path" }
            file.parentFile.mkdirs()
            return file
        }
        fun register(path: String, file: File, source: String? = null) {
            totalBytes += file.length()
            if(totalBytes > 1024L * 1024 * 1024 || manifestEntries.length() >= 20_000) throw BundleLimitExceeded()
            manifestEntries.put(JSONObject().put("path", path).put("bytes", file.length()).put("sha256", digest(file)).put("source", source))
        }
        fun write(path: String, bytes: ByteArray, source: String? = null) {
            val file = target(path)
            try { file.writeBytes(bytes); register(path, file, source) }
            catch(failure: Exception) { file.delete(); throw failure }
        }
        fun json(path: String, value: Any) = write(path, value.toString().toByteArray(Charsets.UTF_8))
        fun copy(path: String, source: File, expected: String? = null): Boolean {
            try {
                require(source.isFile) { "文件已不存在" }
                require(source.length() <= 1024L * 1024 * 1024 - totalBytes) { "文件超过剩余导出容量" }
                val before = digest(source)
                val file = target(path)
                source.inputStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                val saved = digest(file)
                if(saved != before || saved != digest(source)) missing += "$path：导出期间原件变化，保存的是复制时读到的内容"
                if(expected != null && saved != expected) missing += "$path：文件与记录的修订不符，未当作原修订"
                register(path, file, source.path)
                return true
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) {
                if(failure is BundleLimitExceeded) throw failure
                File(stage, path).delete(); missing += "$path：${failure.message}"
                return false
            }
        }
        fun media(path: String, expected: String? = null) {
            if(path.isBlank() || path == "null") return
            val file = File(path.removePrefix("file://")).canonicalFile
            val roots = listOf("media", "novex-media", "novex/adopted-media", "novex-artifacts", "generated-images",
                "minis-sessions/$conversationId/attachments", "minis-sessions/$conversationId/workspace").map { File(context.filesDir, it).canonicalFile }
            if(roots.none { file.path.startsWith(it.path + File.separator) }) { missing += "未收录应用媒体目录之外的引用：$path"; return }
            localFiles.putIfAbsent(path, file to expected)
        }
        fun scan(value: Any?) {
            when(value) {
                is JSONObject -> {
                    if(value.has("sourceAssetId") && value.has("sha256") && value.has("path")) media(value.optString("path"), value.optString("sha256"))
                    if(value.optString("type") == "mediaRef") value.optJSONObject("value")?.optString("relativePath")?.let { media(File(context.filesDir, "media/$it").path) }
                    if(value.optString("type") in setOf("toolUse", "uiToolUse")) value.optJSONObject("value")?.optString("imageFilePath")?.let { media(it) }
                    value.keys().asSequence().toList().forEach { key ->
                        val child = value.opt(key)
                        if(child is String && key in setOf("parts_json", "payload_json", "configurationJson", "novex_configuration_json",
                            "character_snapshot_json", "world_snapshot_json", "persona_snapshot_json", "documentJson")) {
                            runCatching { org.json.JSONTokener(child).nextValue() }.getOrNull()?.takeUnless { it is String }?.let(::scan)
                        }
                        scan(child)
                    }
                }
                is JSONArray -> repeat(value.length()) { scan(value.opt(it)) }
                is String -> {
                    collectionRefs += NovexSourceCollectionPromptReceipt.refsIn(value).map { it.value }
                    Regex("novex://documents/[0-9a-fA-F]{64}").findAll(value).forEach { documentRefs += it.value.lowercase() }
                }
            }
        }
        suspend fun table(path: String, sql: String, arguments: Array<Any?> = arrayOf(conversationId), onRow: (JSONObject) -> Unit = {}): Int {
            val file = target(path)
            var count = 0
            file.bufferedWriter(Charsets.UTF_8).use { output ->
                database.openHelper.readableDatabase.query(sql, arguments).use { cursor ->
                    while(cursor.moveToNext()) {
                        val row = cursor.row()
                        onRow(row)
                        output.write(row.toString()); output.newLine(); count++
                    }
                }
            }
            register(path, file)
            return count
        }
        try {
            val begun = System.currentTimeMillis()
            var savedSession: JSONObject? = null
            val cardSubjects = linkedSetOf<NovexContentAddress>()
            database.withTransaction {
                table("database/session.jsonl", "SELECT * FROM sessions WHERE id = ?") { row ->
                    // Binding holds routing identifiers. Never add provider repository or credential tables to this export.
                    row.optString("model_binding").takeIf { !row.isNull("model_binding") && it.isNotBlank() }?.let { binding ->
                        val raw = runCatching { JSONObject(binding) }.getOrNull()
                        val safe = JSONObject()
                        listOf("type", "groupId", "lastEntryId", "entryId").forEach { key -> raw?.optString(key)?.takeIf { it.isNotBlank() }?.let { safe.put(key, it) } }
                        if(raw == null || raw.keys().asSequence().any { it !in setOf("type", "groupId", "lastEntryId", "entryId") }) {
                            row.put("model_binding", safe.toString())
                            missing += "模型路由含未知字段，导出仅保留已知路由编号；未收录未知配置值"
                        }
                    }
                    savedSession = row; scan(row)
                    listOf("chat_background_path", "assistant_avatar_path", "player_avatar_path").forEach { media(row.optString(it)) }
                }
                require(savedSession != null) { "对话已不存在，未导出其他对话" }
                messageCount = table("database/messages.jsonl", "SELECT * FROM messages WHERE session_id = ? ORDER BY sort_order, created_at, id") { row ->
                    scan(row)
                    if(row.optString("role") == "user") runCatching {
                        val parts = JSONArray(row.getString("parts_json"))
                        if((0 until parts.length()).any { parts.optJSONObject(it)?.optString("type") == "text" }) userTextMessageIds += row.getString("id")
                    }.onFailure { missing += "消息 ${row.getString("id")} 的部分结构无法解析，原始字段仍保留" }
                }
                table("database/context-usage.jsonl", "SELECT * FROM novex_context_usage_records WHERE session_id = ? ORDER BY created_at, id", onRow = ::scan)
                table("database/drafts-and-writes.jsonl", "SELECT * FROM novex_conversation_drafts WHERE conversation_id = ?", onRow = ::scan)
                val configuration = NovexConversationConfigurationCodec.decode(runtime.getString("configurationJson"), conversationId)
                cardSubjects += configuration.backgroundSettings.map { it.subject }
                cardSubjects += configuration.managedSubjects.map { it.subject }
                (configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.let { cardSubjects += NovexContentAddress.characterVersion(it.versionId) }
                configuration.activeInteractiveFiction?.let { cardSubjects += NovexContentAddress.interactiveFiction(it.projectId) }
                cardSubjects += workspace.conversationDrafts(conversationId)?.cards.orEmpty().map { it.subject }
                val mappings = JSONArray()
                for(subject in cardSubjects.filter { it.kind != NovexContentKind.CREATIVE_ARTIFACT }) {
                    try {
                        val root = when(subject.kind) {
                            NovexContentKind.WORLD -> NovexCardCopyKey(NovexCardKind.WORLD, subject.id)
                            NovexContentKind.INTERACTIVE_FICTION -> NovexCardCopyKey(NovexCardKind.GAME, subject.id)
                            NovexContentKind.CHARACTER_VERSION -> NovexCardCopyKey(NovexCardKind.CHARACTER, requireNotNull(workspace.characterForVersion(subject.id)) { "角色版本已不存在" }.character.character.id)
                            else -> error("不支持的卡片类型")
                        }
                        val rawPrefix = "library-at-export/raw/${NovexFrozenContextCodec.digest(subject.toString())}"
                        val subjectTable = when(subject.kind) {
                            NovexContentKind.WORLD -> "worlds"
                            NovexContentKind.CHARACTER_VERSION -> "character_versions"
                            NovexContentKind.INTERACTIVE_FICTION -> "interactive_fiction_projects"
                            else -> error("不支持的卡片类型")
                        }
                        table("$rawPrefix/subject.jsonl", "SELECT * FROM $subjectTable WHERE id = ?", arrayOf(subject.id))
                        table("$rawPrefix/modules.jsonl", "SELECT * FROM content_modules WHERE owner_type = ? AND owner_id = ? ORDER BY position, id", arrayOf(subject.kind.name, subject.id))
                        table("$rawPrefix/module-references.jsonl", "SELECT * FROM content_module_references WHERE source_module_id IN (SELECT id FROM content_modules WHERE owner_type = ? AND owner_id = ?)", arrayOf(subject.kind.name, subject.id))
                        json("$rawPrefix/references.json", JSONArray(workspace.referencesFrom(subject).map { JSONObject(NovexCardReferenceCodec.encode(it)) }))
                        if(subject.kind == NovexContentKind.CHARACTER_VERSION) {
                            table("$rawPrefix/person.jsonl", "SELECT * FROM characters WHERE id = ?", arrayOf(root.id))
                            json("$rawPrefix/version-relations.json", JSONArray(workspace.versionRelations(subject.id).map { JSONObject(NovexCharacterVersionRelationCodec.encode(it)) }))
                        }
                        val card = workspace.apply(NovexCommand.ExportNativeSelection(root, setOf(subject.id).takeIf { subject.kind == NovexContentKind.CHARACTER_VERSION })).requireNativeCard()
                        val path = "library-at-export/${NovexFrozenContextCodec.digest(subject.toString())}.${card.kind.extension}"
                        write(path, NovexCardPackageCodec.encode(card), subject.toString())
                        mappings.put(JSONObject().put("kind", subject.kind.name).put("id", subject.id).put("path", path))
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(failure: Exception) { if(failure is BundleLimitExceeded) throw failure; missing += "当前原卡 ${subject.kind}:${subject.id}：${failure.message}" }
                }
                json("library-at-export/index.json", mappings)
                // Capture each owned creative artifact's full revision metadata; files are read separately by hash.
                val artifactIds = cardSubjects.filter { it.kind == NovexContentKind.CREATIVE_ARTIFACT }.map { it.id }
                val clause = if(artifactIds.isEmpty()) "origin_conversation_id = ?" else "origin_conversation_id = ? OR id IN (${artifactIds.joinToString(",") { "?" }})"
                val args: Array<Any?> = (listOf(conversationId) + artifactIds).toTypedArray()
                table("database/creative-artifacts.jsonl", "SELECT * FROM creative_artifacts WHERE $clause", args)
                table("database/creative-revisions.jsonl", "SELECT * FROM creative_artifact_revisions WHERE artifact_id IN (SELECT id FROM creative_artifacts WHERE $clause)", args) { row ->
                    val key = row.getString("storage_key")
                    require(File(key).name == key) { "成果存储编号越界" }
                    localFiles["artifact:$key"] = File(context.filesDir, "novex-artifacts/$key") to row.getString("content_hash")
                }
                table("database/creative-attachments.jsonl", "SELECT * FROM creative_artifact_attachments WHERE artifact_id IN (SELECT id FROM creative_artifacts WHERE $clause)", args)
            }
            val databaseCaptured = System.currentTimeMillis()
            json("environment/current-runtime.json", runtime); scan(runtime)
            val store = FileNovexConversationWorkspaceStore(File(context.filesDir, "novex/conversation-workspaces"))
            val workspaceEntries = JSONArray()
            try {
                store.inspectConversation(conversationId).forEach { snapshot -> snapshot.entries.forEach { entry ->
                    val path = "workspace/${NovexFrozenContextCodec.digest(entry.workspaceRef.value)}"
                    val item = JSONObject().put("reference", entry.workspaceRef.value).put("path", path).put("sha256", entry.sha256)
                        .put("mimeType", entry.mimeType).put("byteCount", entry.byteCount).put("createdAt", entry.createdAtMillis).put("updatedAt", entry.updatedAtMillis)
                        .put("artifactRef", entry.artifactRef?.value).put("provenance", JSONObject().put("conversationId", entry.provenance.conversationId)
                            .put("branchId", entry.provenance.branchId).put("messageId", entry.provenance.messageId).put("toolCallId", entry.provenance.toolCallId)
                            .put("sourceRefs", JSONArray(entry.provenance.sourceRefs.map { it.value })))
                    workspaceEntries.put(item)
                    try {
                        val bytes = store.readBytes(snapshot.scope, entry.workspaceRef)
                        if(hash(bytes) != entry.sha256) missing += "$path：工作区原件与索引修订不符"
                        write(path, bytes, entry.workspaceRef.value)
                        if(entry.mimeType.contains("json")) scan(runCatching { org.json.JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue() }.getOrNull())
                        else if(entry.mimeType.startsWith("text/")) scan(bytes.toString(Charsets.UTF_8))
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(failure: Exception) { missing += "$path：${failure.message}" }
                } }
            } catch(failure: Exception) { if(failure is CancellationException || failure is BundleLimitExceeded) throw failure; missing += "工作区清单：${failure.message}" }
            json("workspace/index.json", workspaceEntries)
            for(area in listOf("workspace", "attachments")) {
                val sourceRoot = File(context.filesDir, "minis-sessions/$conversationId/$area").canonicalFile
                if(sourceRoot.isDirectory) sourceRoot.walkTopDown().onEnter { it.canonicalPath == sourceRoot.path || it.canonicalPath.startsWith(sourceRoot.path + File.separator) }.filter { it.isFile }.forEach { source ->
                    if(!source.canonicalPath.startsWith(sourceRoot.path + File.separator)) missing += "旧工作区引用越界：${source.path}"
                    else copy("session-files/$area/${source.relativeTo(sourceRoot).invariantSeparatorsPath}", source)
                }
            }
            val traceRoot = File(context.filesDir, "novex/teaching-traces/${NovexFrozenContextCodec.digest(conversationId)}")
            traceRoot.listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }.forEach { source ->
                try {
                    val value = JSONObject(source.readText(Charsets.UTF_8))
                    require(value.getString("conversationId") == conversationId) { "装配记录归属不符" }
                    runCatching { FileNovexTeachingTraceStore(File(context.filesDir, "novex/teaching-traces"))
                        .read("${NovexFrozenContextCodec.digest(conversationId)}/${source.name}") }.onFailure { missing += "装配记录 ${source.name}：${it.message}，保留原始文件" }
                    if(copy("environment/teaching-traces/${source.name}", source)) {
                        scan(value); traceCount++
                        value.optString("requestMessageId").takeIf { it.isNotBlank() }?.let(tracedRequestIds::add)
                    }
                } catch(failure: Exception) { if(failure is CancellationException || failure is BundleLimitExceeded) throw failure; missing += "装配记录 ${source.name}：${failure.message}" }
            }
            val withoutTrace = userTextMessageIds - tracedRequestIds
            if(withoutTrace.isNotEmpty()) missing += "${withoutTrace.size} 条用户文字消息未找到配套装配记录；可能尚未发起请求或旧版未记录，未用当前环境补造"
            json("environment/request-index.json", JSONObject().put("recordedRequestIds", JSONArray(tracedRequestIds.toList()))
                .put("userMessagesWithoutTrace", JSONArray(withoutTrace.toList())))
            for(ref in collectionRefs.toList()) {
                val stem = NovexFrozenContextCodec.digest(ref)
                val source = File(context.filesDir, "novex/learning/$stem.json")
                copy("learning/$stem.json", source)
                if(source.isFile) scan(JSONObject(source.readText(Charsets.UTF_8)))
                File(context.filesDir, "novex/learning/$stem-responses").listFiles().orEmpty().filter { it.extension == "json" }.forEach {
                    copy("learning/$stem-responses/${it.name}", it)
                }
            }
            for(ref in documentRefs) {
                val stem = ref.substringAfterLast('/')
                val root = File(context.filesDir, "novex/derived/document-snapshots")
                copy("documents/$stem.json", File(root, "$stem.json"))
                File(root, "revisions").listFiles().orEmpty().filter { it.name.startsWith("$stem-") && it.extension == "json" }.forEach {
                    copy("documents/revisions/${it.name}", it)
                }
            }
            localFiles.forEach { (reference, source) -> copy("media/${NovexFrozenContextCodec.digest(reference)}", source.first, source.second) }
            json("media/index.json", JSONArray(localFiles.map { (reference, source) -> JSONObject().put("reference", reference)
                .put("sourcePath", source.first.path).put("path", "media/${NovexFrozenContextCodec.digest(reference)}").put("expectedRevision", source.second) }))
            val limits = listOf("逐字原话来自全部已保存消息，包含保留分支、工具部分和原始元数据；未落库的生成过程不在包内。",
                "数据库与当前原卡在同一事务内捕获；文件逐项读取并核对修订，不能宣称文件系统与数据库全局同一时刻。",
                "采用快照在会话配置中，导出时共享原卡另存；后者不能冒充历史采用内容。",
                "历史请求环境只保存实际存在的应用装配记录；旧版本未记录内容、提供商转换后报文及工具循环变化不能补造。",
                "包内不包含模型账户密钥、登录令牌、请求头和提供商凭据配置；原始消息或用户文件中的原话不作替换。",
                "这是预览测试导出包，当前没有一键导入恢复功能。未登记到原生工作区的旧外部执行环境不保证收录。")
            write("阅读说明.txt", ("对话原话与已保存环境导出包（预览测试）\n\n" + limits.joinToString("\n") + "\n\n缺项：\n" + missing.joinToString("\n")).toByteArray(Charsets.UTF_8))
            val manifest = JSONObject().put("format", "novex.conversation-bundle").put("version", 1).put("previewOnly", true)
                .put("conversationId", conversationId).put("startedAt", begun).put("databaseCapturedAt", databaseCaptured).put("completedAt", System.currentTimeMillis())
                .put("messageCount", messageCount).put("teachingTraceCount", traceCount).put("missing", JSONArray(missing.toList()))
                .put("limitations", JSONArray(limits)).put("entries", manifestEntries)
            // Manifest deliberately does not contain its own digest.
            target("manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
            partial.parentFile.mkdirs()
            ZipOutputStream(partial.outputStream().buffered()).use { zip -> stage.walkTopDown().filter { it.isFile }.forEach { file ->
                exportContext.ensureActive()
                zip.putNextEntry(ZipEntry(file.relativeTo(stage).invariantSeparatorsPath)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            } }
            check(partial.renameTo(destination)) { "导出包未完成写入" }
            NovexConversationBundleResult(destination, messageCount, manifestEntries.length(), missing.toList(), traceCount)
        } finally { stage.deleteRecursively(); partial.delete() }
    }

    private fun Cursor.row() = JSONObject().apply {
        columnNames.forEachIndexed { i, name -> put(name, when(getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(getBlob(i), android.util.Base64.NO_WRAP)
            else -> getString(i)
        }) }
    }
    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while(true) { val count = input.read(buffer); if(count < 0) break; digest.update(buffer, 0, count) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
