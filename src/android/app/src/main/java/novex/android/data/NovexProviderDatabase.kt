package novex.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import novex.android.data.provider.AgentLoopTargetRow
import novex.android.data.provider.ProviderGroupRow
import novex.android.data.provider.ProviderMetaRow
import novex.android.data.provider.ProviderModelRow
import novex.android.data.provider.ProviderRow
import novex.android.data.provider.ProviderStoreDao
import novex.android.data.provider.ThinkingRuleRow

/*
 * The provider settings database, kept in its own file ("provider.db",
 * schema version 5) so a downgrade to a build that predates these tables
 * cannot break the main database. Downgraded builds keep reading the legacy
 * JSON mirror — which every save here also refreshes — and the mirror's
 * digest is recorded in the meta table so a re-upgrade can detect and
 * re-import writes that happened behind our back. Destructive fallback is
 * deliberately off: this store is the only structured copy of provider
 * state.
 */
@Database(
    entities = [
        ProviderRow::class,
        ProviderModelRow::class,
        ProviderGroupRow::class,
        AgentLoopTargetRow::class,
        ProviderMetaRow::class,
        ThinkingRuleRow::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class NovexProviderDatabase : RoomDatabase() {
    abstract fun providerStoreDao(): ProviderStoreDao

    companion object {
        const val DB_NAME = "provider.db"

        /** Azure mode column; NOT NULL DEFAULT 0 backfills every row to off. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN azure_mode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Image-endpoint picker columns missed by the store's first cut. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_mode TEXT")
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN image_endpoint_resolved TEXT")
            }
        }

        /** Custom thinking rules table; additive, so resolution is unchanged for rule-less providers. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_thinking_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        provider_instance_id TEXT NOT NULL,
                        label TEXT NOT NULL,
                        scope_kind TEXT NOT NULL,
                        scope_pattern TEXT,
                        wire_format_json TEXT,
                        reasoning_echo_json TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_provider_thinking_rules_provider_instance_id " +
                        "ON provider_thinking_rules(provider_instance_id)",
                )
            }
        }

        /** Qianchen preset columns: key-acquisition link and responses-API auto-fallback. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN key_help_url TEXT")
                db.execSQL("ALTER TABLE provider_instances ADD COLUMN auto_responses_fallback INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: NovexProviderDatabase? = null

        fun getInstance(context: Context): NovexProviderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovexProviderDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
