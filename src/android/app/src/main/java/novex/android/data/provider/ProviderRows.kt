package novex.android.data.provider

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Row types of the provider settings database (file "provider.db", schema
 * version 5). This store is deliberately separate from the main database so
 * a downgrade to a build that predates these tables leaves the main file
 * untouched; a JSON mirror of the same config is kept current on every save
 * so the separate store can never become silently stale.
 *
 * JSON blob columns (base_model_json, overrides_json, member_entry_ids_json,
 * wire_format_json, reasoning_echo_json) are the storage format and are
 * decoded by exactly one codec — see ProviderRowsCodec.kt.
 */

/** One row of `provider_instances` — one configured connection to a vendor. */
@Entity(tableName = "provider_instances")
data class ProviderRow(
    @PrimaryKey val id: String,
    val label: String,
    @ColumnInfo(name = "provider_type") val providerType: String,
    @ColumnInfo(name = "credential_type") val credentialType: String,
    @ColumnInfo(name = "custom_base_url") val customBaseURL: String? = null,
    @ColumnInfo(name = "append_v1_suffix") val appendV1Suffix: Int = 1,
    @ColumnInfo(name = "use_responses_api") val useResponsesAPI: Int = 0,
    @ColumnInfo(name = "azure_mode") val azureMode: Int = 0,
    /** Kotlin enum name of the image-generation endpoint picker; null = auto. */
    @ColumnInfo(name = "image_endpoint_mode") val imageEndpointMode: String? = null,
    /** Cached probe outcome for auto mode; null = not probed yet. */
    @ColumnInfo(name = "image_endpoint_resolved") val imageEndpointResolved: String? = null,
    @ColumnInfo(name = "custom_user_agent") val customUserAgent: String? = null,
    /** Vendor key-acquisition link shown on the edit screen; null hides it. */
    @ColumnInfo(name = "key_help_url") val keyHelpUrl: String? = null,
    @ColumnInfo(name = "auto_responses_fallback") val autoResponsesFallback: Int = 0,
    @ColumnInfo(name = "is_enabled") val isEnabled: Int = 1,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One row of `provider_model_entries` — one selectable model under a provider. */
@Entity(
    tableName = "provider_model_entries",
    foreignKeys = [
        ForeignKey(
            entity = ProviderRow::class,
            parentColumns = ["id"],
            childColumns = ["provider_instance_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["provider_instance_id"], name = "index_provider_model_entries_provider_instance_id")],
)
data class ProviderModelRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String,
    @ColumnInfo(name = "base_model_json") val baseModelJson: String,
    @ColumnInfo(name = "overrides_json") val overridesJson: String? = null,
    @ColumnInfo(name = "is_custom") val isCustom: Int = 0,
    @ColumnInfo(name = "is_hidden") val isHidden: Int = 0,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "user_modified_at") val userModifiedAt: Long? = null,
)

/** One row of `provider_model_groups` — an ordered fallback routing group. */
@Entity(tableName = "provider_model_groups")
data class ProviderGroupRow(
    @PrimaryKey val id: String,
    val name: String,
    val strategy: String,
    @ColumnInfo(name = "fallback_strategy") val fallbackStrategy: String,
    @ColumnInfo(name = "default_thinking_level") val defaultThinkingLevel: String? = null,
    @ColumnInfo(name = "context_limit_tokens") val contextLimitTokens: Int? = null,
    @ColumnInfo(name = "last_context_limit_tokens") val lastContextLimitTokens: Int? = null,
    /** Member ids in routing order; order is load-bearing, so it lives in JSON. */
    @ColumnInfo(name = "member_entry_ids_json") val memberEntryIdsJson: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)

/** One row of `provider_agent_loop_ids` — one position in the agent-loop picker. */
@Entity(tableName = "provider_agent_loop_ids", primaryKeys = ["kind", "target_id"])
data class AgentLoopTargetRow(
    val kind: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/** One row of `provider_config_meta` — key/value slot for non-row config state. */
@Entity(tableName = "provider_config_meta")
data class ProviderMetaRow(
    @PrimaryKey val key: String,
    val value: String?,
)

/** One row of `provider_thinking_rules` — one user-authored thinking rule. */
@Entity(
    tableName = "provider_thinking_rules",
    indices = [Index(value = ["provider_instance_id"], name = "index_provider_thinking_rules_provider_instance_id")],
)
data class ThinkingRuleRow(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String,
    val label: String,
    /** "allModels" or "modelPattern". */
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    /** Glob for modelPattern scope; null under allModels. */
    @ColumnInfo(name = "scope_pattern") val scopePattern: String? = null,
    @ColumnInfo(name = "wire_format_json") val wireFormatJson: String? = null,
    @ColumnInfo(name = "reasoning_echo_json") val reasoningEchoJson: String? = null,
    /** 0 = highest priority; drag-reorder rewrites the sequence. */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
)
