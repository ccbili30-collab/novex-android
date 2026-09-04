package com.openminis.app.data.interactivefiction

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.toPlainText

enum class InteractiveFictionLaunchMode(val displayName: String) {
    FIXED_IDENTITY("固定玩家身份"),
    USER_CREATED_IDENTITY("玩家自建身份"),
    CO_CREATE_WORLD("先共创世界"),
    FREE_SANDBOX("自由沙盒"),
}

@Entity(tableName = "interactive_fiction_projects")
data class InteractiveFictionProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val summary: String,
    @ColumnInfo(name = "launch_mode") val launchMode: InteractiveFictionLaunchMode,
    @ColumnInfo(name = "player_identity") val playerIdentity: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "source_id") val sourceId: String? = null,
    @ColumnInfo(name = "source_document_json") val sourceDocumentJson: String? = null,
)

class InteractiveFictionConverters {
    @TypeConverter
    fun launchModeToString(value: InteractiveFictionLaunchMode): String = value.name

    @TypeConverter
    fun stringToLaunchMode(value: String): InteractiveFictionLaunchMode =
        InteractiveFictionLaunchMode.valueOf(value)
}

object InteractiveFictionDocumentComposer {
    fun fullText(
        project: InteractiveFictionProjectEntity,
        modules: List<ContentModuleEntity>,
    ): String = buildString {
        appendLine("# ${project.name}")
        if (project.summary.isNotBlank()) appendLine().appendLine(project.summary)
        appendLine().appendLine("启动方式：${project.launchMode.displayName}")
        if (project.playerIdentity.isNotBlank()) appendLine("玩家身份：${project.playerIdentity}")
        modules.sortedBy(ContentModuleEntity::position).forEach { module ->
            appendLine().appendLine("## ${module.name}")
            val text = ContentModuleDocumentCodec.decode(module.type, module.contentJson).toPlainText()
            if (text.isNotBlank()) appendLine(text)
        }
    }.trimEnd()
}
