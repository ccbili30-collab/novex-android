package novex.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.openminis.app.data.character.CatalogMigrationStateEntity
import com.openminis.app.data.character.CharacterCatalogConverters
import com.openminis.app.data.character.CharacterCatalogDao
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.ContentModuleConverters
import com.openminis.app.data.character.ContentModuleDao
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleReferenceEntity
import com.openminis.app.data.character.MediaAssetConverters
import com.openminis.app.data.character.MediaAssetDao
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetReferenceEntity
import com.openminis.app.data.character.WorldCharacterVersionEntity
import com.openminis.app.data.character.WorldEntity
import novex.android.data.chat.ChatDao
import novex.android.data.chat.CompactMarkerRow
import novex.android.data.chat.ConversationDraftDao
import novex.android.data.chat.ConversationDraftRow
import novex.android.data.chat.ContextUsageRow
import novex.android.data.chat.MessageRow
import novex.android.data.chat.SessionFolderRow
import novex.android.data.chat.SessionRow
import novex.android.data.cards.CardDirectoryDao
import novex.android.data.cards.CardDirectoryRow
import novex.android.data.cards.CardLinkDao
import novex.android.data.cards.CardLinkRow
import novex.android.data.cards.CardRevisionDao
import novex.android.data.cards.GameRevisionRow
import novex.android.data.cards.RoleRevisionDao
import novex.android.data.cards.RoleRevisionRow
import novex.android.data.cards.RoleVersionLinkDao
import novex.android.data.cards.RoleVersionLinkRow
import novex.android.data.cards.WorkGroupDao
import novex.android.data.cards.WorkGroupMemberRow
import novex.android.data.cards.WorkGroupPickRow
import novex.android.data.cards.WorkGroupRow
import novex.android.data.cards.WorldRevisionRow
import com.openminis.app.data.creative.CreativeArtifactAttachmentEntity
import com.openminis.app.data.creative.CreativeArtifactDao
import com.openminis.app.data.creative.CreativeArtifactEntity
import com.openminis.app.data.creative.CreativeArtifactRevisionEntity
import com.openminis.app.data.interactivefiction.InteractiveFictionConverters
import com.openminis.app.data.interactivefiction.InteractiveFictionDao
import com.openminis.app.data.interactivefiction.InteractiveFictionProjectEntity

/*
 * The main Novex database: conversations, message trees, session groups,
 * the character/world catalog, creative artifacts, and the Novex card
 * library, all in one file ("minis.db") at schema version 39.
 *
 * The entity list below is ordered exactly as the schema expects; row types
 * are grouped by the package that owns them. Schema evolution lives one
 * file over (MainDatabaseMigrations.kt) and every step is registered here,
 * so an upgraded install always lands on the current shape without any
 * destructive fallback.
 */
@Database(
    entities = [
        SessionRow::class,
        MessageRow::class,
        CompactMarkerRow::class,
        WebShortcutRow::class,
        SessionFolderRow::class,
        WorldEntity::class,
        CharacterEntity::class,
        CharacterVersionEntity::class,
        WorldCharacterVersionEntity::class,
        CatalogMigrationStateEntity::class,
        ContentModuleEntity::class,
        ContentModuleReferenceEntity::class,
        MediaAssetEntity::class,
        MediaAssetReferenceEntity::class,
        InteractiveFictionProjectEntity::class,
        ContextUsageRow::class,
        CreativeArtifactEntity::class,
        CreativeArtifactRevisionEntity::class,
        CreativeArtifactAttachmentEntity::class,
        ConversationDraftRow::class,
        CardLinkRow::class,
        RoleVersionLinkRow::class,
        RoleRevisionRow::class,
        WorkGroupRow::class,
        WorkGroupMemberRow::class,
        WorkGroupPickRow::class,
        WorldRevisionRow::class,
        GameRevisionRow::class,
        CardDirectoryRow::class,
    ],
    version = 39,
    exportSchema = false,
)
@TypeConverters(
    CharacterCatalogConverters::class,
    ContentModuleConverters::class,
    MediaAssetConverters::class,
    InteractiveFictionConverters::class,
)
abstract class NovexMainDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webShortcutDao(): WebShortcutDao
    abstract fun characterCatalogDao(): CharacterCatalogDao
    abstract fun contentModuleDao(): ContentModuleDao
    abstract fun mediaAssetDao(): MediaAssetDao
    abstract fun interactiveFictionDao(): InteractiveFictionDao
    abstract fun creativeArtifactDao(): CreativeArtifactDao
    abstract fun conversationDraftDao(): ConversationDraftDao
    abstract fun cardLinkDao(): CardLinkDao
    abstract fun roleVersionLinkDao(): RoleVersionLinkDao
    abstract fun roleRevisionDao(): RoleRevisionDao
    abstract fun workGroupDao(): WorkGroupDao
    abstract fun cardRevisionDao(): CardRevisionDao
    abstract fun cardDirectoryDao(): CardDirectoryDao

    companion object {
        /** On-disk file name; part of the persisted contract. */
        const val DB_NAME = "minis.db"

        @Volatile
        private var instance: NovexMainDatabase? = null

        fun getInstance(context: Context): NovexMainDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovexMainDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(*MAIN_SCHEMA_STEPS)
                    .build()
                    .also { instance = it }
            }
    }
}
