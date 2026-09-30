package novex.android.data.provider

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/*
 * The provider store is written whole-snapshot style: the repository loads
 * every table, edits the in-memory config, and writes the complete set
 * back atomically. Truncation goes through one shared statement executor,
 * and inserts need no conflict clause because the transaction empties the
 * target table first. Thinking rules are the one exception — per-instance
 * user data owned by the rules screen, replaced one instance at a time.
 */

@Dao
interface ProviderStoreDao {
    /** Connections, in the order the settings list shows them. */
    @Query(
        "SELECT pi.* FROM provider_instances AS pi ORDER BY pi.sort_order ASC, pi.created_at ASC",
    )
    suspend fun instanceRows(): List<ProviderRow>

    /** Models offered under each connection, grouped then ordered. */
    @Query(
        "SELECT me.* FROM provider_model_entries AS me ORDER BY me.provider_instance_id ASC, me.sort_order ASC",
    )
    suspend fun modelRows(): List<ProviderModelRow>

    @Query("SELECT mg.* FROM provider_model_groups AS mg ORDER BY mg.sort_order ASC")
    suspend fun groupRows(): List<ProviderGroupRow>

    @Query(
        "SELECT loop.* FROM provider_agent_loop_ids AS loop ORDER BY loop.kind ASC, loop.sort_order ASC",
    )
    suspend fun agentLoopRows(): List<AgentLoopTargetRow>

    @Query("SELECT meta.* FROM provider_config_meta AS meta")
    suspend fun metaRows(): List<ProviderMetaRow>

    @Query("SELECT COUNT(*) FROM provider_instances")
    suspend fun instanceCount(): Int

    /** One read of everything [overwriteConfigTables] can write back. */
    @Transaction
    suspend fun readSnapshot(): ProviderRowsSnapshot = ProviderRowsSnapshot(
        instanceRows = instanceRows(),
        modelRows = modelRows(),
        groupRows = groupRows(),
        loopRows = agentLoopRows(),
        metaRows = metaRows(),
    )

    /** Escape hatch shared by every truncation; returns affected-row count. */
    @RawQuery
    suspend fun runStatement(sql: SupportSQLiteQuery): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeInstanceRows(rows: List<ProviderRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeModelRows(rows: List<ProviderModelRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeGroupRows(rows: List<ProviderGroupRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeAgentLoopRows(rows: List<AgentLoopTargetRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun writeMetaRows(rows: List<ProviderMetaRow>)

    /**
     * 单事务换写五张配置表。写入统一 REPLACE：镜像导入若出现重复主键
     * （如手工编辑过的损坏配置），按「后者胜」落库而不是让整个事务中止
     * ——与被替换实现的容错口径一致（净眼复审核对项）。先截断子表再截断
     * 父表：即便外键本会级联，显式顺序让无级联的 schema 变体（测试夹具）
     * 行为一致。
     */
    @Transaction
    suspend fun overwriteConfigTables(
        instances: List<ProviderRow>,
        models: List<ProviderModelRow>,
        groups: List<ProviderGroupRow>,
        loopTargets: List<AgentLoopTargetRow>,
        meta: List<ProviderMetaRow>,
    ) {
        for (table in listOf(
            "provider_agent_loop_ids",
            "provider_model_groups",
            "provider_model_entries",
            "provider_instances",
            "provider_config_meta",
        )) {
            runStatement(SimpleSQLiteQuery("DELETE FROM $table"))
        }
        writeInstanceRows(instances)
        writeModelRows(models)
        writeGroupRows(groups)
        writeAgentLoopRows(loopTargets)
        writeMetaRows(meta)
    }

    // ---- thinking rules ------------------------------------------------------

    @Query(
        """
        SELECT tr.* FROM provider_thinking_rules AS tr
        WHERE tr.provider_instance_id = :instanceId
        ORDER BY tr.sort_order ASC
        """,
    )
    suspend fun ruleRowsFor(instanceId: String): List<ThinkingRuleRow>

    @Query(
        """
        SELECT tr.* FROM provider_thinking_rules AS tr
        ORDER BY tr.provider_instance_id ASC, tr.sort_order ASC
        """,
    )
    suspend fun allRuleRows(): List<ThinkingRuleRow>

    @Insert
    suspend fun writeRuleRows(rows: List<ThinkingRuleRow>)

    @Query("DELETE FROM provider_thinking_rules WHERE id = :ruleId")
    suspend fun dropRule(ruleId: String): Int

    @Query("DELETE FROM provider_thinking_rules WHERE provider_instance_id = :instanceId")
    suspend fun dropRulesFor(instanceId: String)

    /** Re-serializes one instance's rule list, keeping the given order. */
    @Transaction
    suspend fun reorderRules(instanceId: String, rows: List<ThinkingRuleRow>) {
        dropRulesFor(instanceId)
        writeRuleRows(rows)
    }
}
