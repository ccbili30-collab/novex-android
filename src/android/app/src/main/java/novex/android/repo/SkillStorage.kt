package novex.android.repo

import com.openminis.app.data.repository.SkillRepository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File

/**
 * 技能存储层 —— 两处物理落点的唯一读写出口：
 *
 *  1. [SkillFileStore]：`filesDir/minis-global/skills/<id>/` 目录树（SKILL.md
 *     与 scripts/、references/ 等兄弟文件）。模型侧经 /var/minis/skills/<id>/
 *     读取的就是这里（ContentPaths 把全局挂载绑到同一目录）。
 *  2. [SkillRegistry]：`skills.db`（表 skills + session_skill_overrides）。
 *
 * 目录布局、库文件名、表与列名都是磁盘数据事实：已装机的用户数据靠它们
 * 找回，升级路径必须一直兼容，逐字保留。仓库门面（SkillRepository）不
 * 直接碰 SQL 与 File 拼接，存储细节集中在此便于审计与替换。
 */
internal class SkillFileStore(context: Context) {

    private val root: File = File(context.filesDir, "minis-global/skills")

    fun rootDir(): File = root

    fun dirOf(skillId: String): File = File(root, skillId)

    fun skillMdOf(skillId: String): File = File(dirOf(skillId), SkillMarkdown.FILE_NAME)

    /** 技能目录是否存在且非空（捆绑技能自愈安装的判据之一）。 */
    fun isPopulated(skillId: String): Boolean =
        dirOf(skillId).isDirectory && dirOf(skillId).listFiles()?.isNotEmpty() == true

    /** 重写 SKILL.md（必要时建目录）。frontmatter 由 [SkillMarkdown.serialize] 生成。 */
    fun writeSkillMd(skill: SkillRepository.Skill) {
        val dir = dirOf(skill.id)
        if (!dir.exists()) dir.mkdirs()
        skillMdOf(skill.id).writeText(
            SkillMarkdown.serialize(skill.name, skill.description, skill.version, skill.body),
        )
    }

    /** 读技能正文；文件缺失或坏格式一律回空串（注册表行的 body 只是缓存性质）。 */
    fun readBody(skillId: String): String {
        val file = skillMdOf(skillId)
        if (!file.exists()) return ""
        return SkillMarkdown.parse(file.readText())?.body.orEmpty()
    }

    /**
     * 递归枚举技能目录内的文件，返回相对路径。SKILL.md 排最前，其余按路径
     * 排序，供技能详情页列出捆绑脚本时有一份稳定顺序。隐藏文件（.DS_Store 等）跳过。
     */
    fun listRelativeFiles(skillId: String): List<String> {
        val dir = dirOf(skillId)
        if (!dir.isDirectory) return emptyList()
        val prefixLength = dir.absolutePath.length + 1
        val all = dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.absolutePath.substring(prefixLength) }
            .toList()
        val skillMdFirst = compareBy<String> { path -> if (path.equals(SkillMarkdown.FILE_NAME, true)) 0 else 1 }
            .thenBy { it }
        return all.sortedWith(skillMdFirst)
    }

    fun hostPath(skillId: String, relativePath: String): String =
        File(dirOf(skillId), relativePath).absolutePath

    /** 读技能目录内任意文件。含 `..` 的路径一律拒绝（目录逃逸防护）。 */
    fun readText(skillId: String, relativePath: String): String? {
        if (relativePath.contains("..")) return null
        val file = File(dirOf(skillId), relativePath)
        if (!file.isFile) return null
        return try {
            file.readText()
        } catch (_: Exception) {
            null
        }
    }

    /** 写技能目录内任意文件（自动建父目录）。写 SKILL.md 的回写注册表由调用方负责。 */
    fun writeText(skillId: String, relativePath: String, content: String): Boolean {
        if (relativePath.contains("..")) return false
        val file = File(dirOf(skillId), relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.writeText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun wipe(skillId: String) {
        dirOf(skillId).deleteRecursively()
    }

    /** 磁盘上有 SKILL.md 的目录名集合 —— 自动发现（SESSION 来源）的扫描源。 */
    fun installedDirIds(): List<String> =
        root.listFiles()?.filter { it.isDirectory && File(it, SkillMarkdown.FILE_NAME).exists() }?.map { it.name }
            ?: emptyList()
}

/**
 * skills.db 注册表。所有 SQL 集中在此；表结构（列名、默认值、主键）为
 * 冻结面 —— 老版本写入的行必须能被直接读出，v2/v3 的 ALTER 升级路径保留。
 */
internal class SkillRegistry(context: Context) {

    private val logTag = "NovexSkillStore"

    private val db: SQLiteDatabase = OpenHelper(context).writableDatabase

    /** 一行注册表记录的原始列值（未做业务修补，修补在仓库装载阶段进行）。 */
    class RawRow(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val importSource: SkillRepository.ImportSource,
        val isEnabled: Boolean,
        val installedAt: Long,
        val updatedAt: Long,
        val sourceUrl: String?,
        val useCount: Double,
    )

    /**
     * 全量读出，按 installed_at 倒序。逐行隔离：一条第三方导入写坏的行
     * 只跳过它自己（记日志），不拖垮其余技能，更不允许把异常抛进应用启动。
     */
    fun selectAll(): List<RawRow> {
        val rows = ArrayList<RawRow>()
        db.rawQuery("SELECT * FROM skills ORDER BY installed_at DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val row = try {
                    RawRow(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
                        version = cursor.getString(cursor.getColumnIndexOrThrow("version")),
                        importSource = SkillRepository.ImportSource.from(
                            cursor.getString(cursor.getColumnIndexOrThrow("import_source")),
                        ),
                        isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1,
                        installedAt = cursor.getLong(cursor.getColumnIndexOrThrow("installed_at")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                        sourceUrl = nullableString(cursor, "source_url"),
                        useCount = nullableDouble(cursor, "use_count"),
                    )
                } catch (t: Throwable) {
                    Log.e(logTag, "skill row unreadable, skipped: ${t.message}", t)
                    continue
                }
                rows.add(row)
            }
        }
        return rows
    }

    private fun nullableString(cursor: android.database.Cursor, column: String): String? {
        val idx = cursor.getColumnIndex(column)
        return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
    }

    private fun nullableDouble(cursor: android.database.Cursor, column: String): Double {
        val idx = cursor.getColumnIndex(column)
        return if (idx >= 0) cursor.getDouble(idx) else 0.0
    }

    fun insert(skill: SkillRepository.Skill) {
        db.execSQL(
            "INSERT OR REPLACE INTO skills " +
                "(id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(
                skill.id, skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL,
                if (skill.isEnabled) 1 else 0,
                skill.installedAt, skill.updatedAt,
            ),
        )
    }

    fun updateCoreFields(skill: SkillRepository.Skill) {
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(skill.name, skill.description, skill.version, skill.updatedAt, skill.id),
        )
    }

    /** URL 导入的「原位替换」：连来源与源地址一起改写。 */
    fun updateWithOrigin(skill: SkillRepository.Skill) {
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, import_source=?, source_url=?, updated_at=? WHERE id=?",
            arrayOf<Any?>(
                skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL, skill.updatedAt, skill.id,
            ),
        )
    }

    fun rename(skillId: String, name: String, updatedAt: Long) {
        db.execSQL(
            "UPDATE skills SET name=?, updated_at=? WHERE id=?",
            arrayOf<Any>(name, updatedAt, skillId),
        )
    }

    fun setEnabled(skillId: String, enabled: Boolean) {
        db.execSQL(
            "UPDATE skills SET is_enabled=? WHERE id=?",
            arrayOf<Any>(if (enabled) 1 else 0, skillId),
        )
    }

    fun delete(skillId: String) {
        db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(skillId))
        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(skillId))
    }

    fun touch(skillId: String, updatedAt: Long) {
        db.execSQL(
            "UPDATE skills SET updated_at=? WHERE id=?",
            arrayOf<Any>(updatedAt, skillId),
        )
    }

    fun bumpUseCount(skillId: String) {
        db.execSQL("UPDATE skills SET use_count = use_count + 1 WHERE id=?", arrayOf<Any>(skillId))
    }

    /** 使用计数整体缩放（超过阈值后归一到 0–100）。单事务保证中间态不可见。 */
    fun rewriteUseCounts(scaled: List<SkillRepository.Skill>) {
        db.beginTransaction()
        try {
            for (skill in scaled) {
                db.execSQL(
                    "UPDATE skills SET use_count=? WHERE id=?",
                    arrayOf<Any>(skill.useCount, skill.id),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 会话级开关：返回覆盖值；无覆盖行返回 null（回落到全局启用位）。 */
    fun sessionOverrideOf(sessionId: String, skillId: String): Boolean? {
        db.rawQuery(
            "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
            arrayOf(sessionId, skillId),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        }
    }

    fun upsertSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        db.execSQL(
            "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
            arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0),
        )
    }

    fun dropSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
    }

    /**
     * 草稿会话 id → 落库正式 id 的覆盖行改挂（ensureSession 建行后调用），
     * 否则首条消息前的技能开关会永远绑在草稿键上、再进聊天时「消失」。
     */
    fun retargetSessionOverrides(fromDraftId: String, toSessionId: String) {
        if (fromDraftId == toSessionId) return
        db.execSQL(
            "UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toSessionId, fromDraftId),
        )
    }

    /** DDL 为冻结面：与历史版本的行格式逐字兼容（含 v2/v3 两步 ALTER 升级）。 */
    private class OpenHelper(context: Context) :
        SQLiteOpenHelper(context, "skills.db", null, 3) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE skills (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    import_source TEXT NOT NULL DEFAULT 'file',
                    source_url TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    use_count REAL NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE session_skill_overrides (
                    session_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, skill_id)
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN source_url TEXT")
                } catch (_: Exception) {
                    // 列可能已存在（异常升级路径），忽略即可。
                }
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN use_count REAL NOT NULL DEFAULT 0")
                } catch (_: Exception) {
                    // 同上。
                }
            }
        }
    }
}
