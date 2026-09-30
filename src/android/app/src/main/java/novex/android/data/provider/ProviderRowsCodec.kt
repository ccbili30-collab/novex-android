package novex.android.data.provider

import novex.android.data.model.FallbackStrategy
import novex.android.data.model.ImageEndpointMode
import novex.android.data.model.LLMModel
import novex.android.data.model.ModelEntry
import novex.android.data.model.ModelGroup
import novex.android.data.model.ModelOverrides
import novex.android.data.model.ProviderConfig
import novex.android.data.model.ProviderCredential
import novex.android.data.model.ProviderInstance
import novex.android.data.model.ProviderType
import novex.android.data.model.RoutingStrategy
import novex.android.data.model.ThinkingLevel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * Codec between the [ProviderConfig] object graph and the flat rows of the
 * provider store. The JSON blobs inside the rows are the persisted format
 * and are written only here, always through the caller-supplied Json
 * instance so encodeDefaults/ignoreUnknownKeys stay consistent with the
 * legacy JSON mirror of the same config.
 *
 * One id convention runs through everything: a model row's primary key is
 * "<instanceId>/<modelId>". Legacy random-UUID ids are translated on the
 * way in; group members and agent-loop pins follow the same translation so
 * no reference dangles after the first round trip.
 */

/** The five tables of one config save, written together in one transaction. */
data class ProviderRowsSnapshot(
    val instanceRows: List<ProviderRow>,
    val modelRows: List<ProviderModelRow>,
    val groupRows: List<ProviderGroupRow>,
    val loopRows: List<AgentLoopTargetRow>,
    val metaRows: List<ProviderMetaRow>,
)

/** Keys of the provider_config_meta key/value table. */
object ProviderMetaKeys {
    const val DEFAULT_PRIMARY_GROUP_ID = "default_primary_group_id"
    const val DEFAULT_SUB_GROUP_ID = "default_sub_group_id"
    const val VOICE_INPUT_GROUP_ID = "voice_input_group_id"
    const val VOICE_OUTPUT_GROUP_ID = "voice_output_group_id"
    const val VISION_GROUP_ID = "vision_group_id"
    const val IMAGE_GENERATION_GROUP_IDS = "image_generation_group_ids"
    const val IMAGE_GENERATION_PROVIDER_INSTANCE_IDS = "image_generation_provider_instance_ids"
    /** Hash of the legacy JSON mirror at the moment we last wrote it. */
    const val JSON_SYNC_HASH = "json_sync_hash"
}

/** Model row key: "<instanceId>/<modelId>", shared with the JSON mirror after migration. */
fun entryCompositeId(instanceId: String, modelId: String): String = "$instanceId/$modelId"

private val stringList = ListSerializer(String.serializer())

private fun Boolean.asFlag(): Int = if (this) 1 else 0

/** Enum-by-name parse that answers null instead of throwing on foreign input. */
private inline fun <reified E : Enum<E>> enumOrNull(stored: String?): E? =
    stored?.let { runCatching { enumValueOf<E>(it) }.getOrNull() }

/**
 * Encodes [ProviderConfig] into table rows. [jsonSyncHash], when present, is
 * the digest of the JSON mirror accompanying this save and lands in the meta
 * table for later staleness detection.
 */
fun ProviderConfig.toRows(jsonForBlobs: Json, jsonSyncHash: String? = null): ProviderRowsSnapshot {
    val legacyIdToComposite = modelEntries.associate { entry ->
        entry.uuid to entryCompositeId(entry.providerInstanceId, entry.baseModel.id)
    }

    val instanceRows = instances.mapIndexed { position, inst ->
        ProviderRow(
            id = inst.id,
            label = inst.label,
            providerType = inst.providerType.name,
            credentialType = inst.credentialType.name,
            customBaseURL = inst.customBaseURL,
            appendV1Suffix = inst.appendV1Suffix.asFlag(),
            useResponsesAPI = inst.useResponsesAPI.asFlag(),
            azureMode = inst.azureMode.asFlag(),
            imageEndpointMode = inst.imageEndpointMode.name,
            imageEndpointResolved = inst.imageEndpointResolved?.name,
            customUserAgent = inst.customUserAgent,
            keyHelpUrl = inst.keyHelpUrl,
            autoResponsesFallback = inst.autoResponsesFallback.asFlag(),
            isEnabled = inst.isEnabled.asFlag(),
            sortOrder = position,
            createdAt = inst.createdAt,
        )
    }

    // Position within an instance decides a model's slot; the load-side
    // ORDER BY mirrors exactly this per-instance numbering.
    val modelRows = modelEntries
        .groupBy { proEntry -> proEntry.providerInstanceId }
        .flatMap { (instanceId, sameInstance) ->
            sameInstance.mapIndexed { slot, entry ->
                ProviderModelRow(
                    id = legacyIdToComposite[entry.uuid] ?: entryCompositeId(instanceId, entry.baseModel.id),
                    providerInstanceId = instanceId,
                    baseModelJson = jsonForBlobs.encodeToString(LLMModel.serializer(), entry.baseModel),
                    overridesJson = entry.overrides
                        .takeIf { !it.isEmpty }
                        ?.let { jsonForBlobs.encodeToString(ModelOverrides.serializer(), it) },
                    isCustom = entry.isCustom.asFlag(),
                    isHidden = entry.isHidden.asFlag(),
                    sortOrder = slot,
                    userModifiedAt = entry.userModifiedAt,
                )
            }
        }

    val groupRows = modelGroups.mapIndexed { position, group ->
        // Ids already in composite form (written by an upgraded build, read
        // back after a downgrade) simply pass through — both shapes stay
        // valid string keys for every consumer.
        ProviderGroupRow(
            id = group.id,
            name = group.name,
            strategy = group.strategy.name,
            fallbackStrategy = group.fallbackStrategy.name,
            defaultThinkingLevel = group.defaultThinkingLevel?.name,
            contextLimitTokens = group.contextLimitTokens,
            lastContextLimitTokens = group.lastContextLimitTokens,
            memberEntryIdsJson = jsonForBlobs.encodeToString(
                stringList,
                group.memberEntryIds.map { legacyIdToComposite[it] ?: it },
            ),
            sortOrder = position,
        )
    }

    val loopRows = buildList {
        agentLoopModelEntryIds.forEachIndexed { slot, entryId ->
            add(AgentLoopTargetRow("entry", legacyIdToComposite[entryId] ?: entryId, slot))
        }
        agentLoopGroupIds.forEachIndexed { slot, groupId ->
            add(AgentLoopTargetRow("group", groupId, slot))
        }
    }

    val metaRows = buildList {
        fun slot(key: String, value: String?) {
            if (value != null) add(ProviderMetaRow(key, value))
        }
        slot(ProviderMetaKeys.DEFAULT_PRIMARY_GROUP_ID, defaultPrimaryGroupId)
        slot(ProviderMetaKeys.DEFAULT_SUB_GROUP_ID, defaultSubGroupId)
        slot(ProviderMetaKeys.VOICE_INPUT_GROUP_ID, voiceInputGroupId)
        slot(ProviderMetaKeys.VOICE_OUTPUT_GROUP_ID, voiceOutputGroupId)
        slot(ProviderMetaKeys.VISION_GROUP_ID, visionGroupId)
        if (imageGenerationGroupIds.isNotEmpty()) {
            add(ProviderMetaRow(ProviderMetaKeys.IMAGE_GENERATION_GROUP_IDS, jsonForBlobs.encodeToString(stringList, imageGenerationGroupIds)))
        }
        if (imageGenerationProviderInstanceIds.isNotEmpty()) {
            add(ProviderMetaRow(ProviderMetaKeys.IMAGE_GENERATION_PROVIDER_INSTANCE_IDS, jsonForBlobs.encodeToString(stringList, imageGenerationProviderInstanceIds)))
        }
        slot(ProviderMetaKeys.JSON_SYNC_HASH, jsonSyncHash)
    }

    return ProviderRowsSnapshot(instanceRows, modelRows, groupRows, loopRows, metaRows)
}

/** Decodes rows back into a [ProviderConfig]; never throws on soft-invalid input. */
fun ProviderRowsSnapshot.toConfig(jsonForBlobs: Json): ProviderConfig {
    val instanceById = instanceRows.associate { row ->
        row.id to ProviderInstance(
            id = row.id,
            label = row.label,
            providerType = ProviderType.valueOf(row.providerType),
            credentialType = ProviderCredential.valueOf(row.credentialType),
            isEnabled = row.isEnabled != 0,
            createdAt = row.createdAt,
            customBaseURL = row.customBaseURL,
            appendV1Suffix = row.appendV1Suffix != 0,
            customUserAgent = row.customUserAgent,
            useResponsesAPI = row.useResponsesAPI != 0,
            azureMode = row.azureMode != 0,
            // Rows written before the picker existed (or by a newer build with
            // unknown names) resolve to auto / no probe instead of failing
            // the whole load.
            imageEndpointMode = enumOrNull<ImageEndpointMode>(row.imageEndpointMode) ?: ImageEndpointMode.auto,
            imageEndpointResolved = enumOrNull<ImageEndpointMode>(row.imageEndpointResolved),
            keyHelpUrl = row.keyHelpUrl,
            autoResponsesFallback = row.autoResponsesFallback != 0,
        )
    }

    val modelEntries = modelRows.map { row ->
        ModelEntry(
            providerInstanceId = row.providerInstanceId,
            baseModel = jsonForBlobs.decodeFromString(LLMModel.serializer(), row.baseModelJson),
            overrides = row.overridesJson
                ?.let { jsonForBlobs.decodeFromString(ModelOverrides.serializer(), it) }
                ?: ModelOverrides(),
            isCustom = row.isCustom != 0,
            isHidden = row.isHidden != 0,
            uuid = row.id,
            userModifiedAt = row.userModifiedAt,
        )
    }

    val modelGroups = groupRows.map { row ->
        ModelGroup(
            id = row.id,
            name = row.name,
            memberEntryIds = jsonForBlobs
                .decodeFromString(stringList, row.memberEntryIdsJson)
                .toMutableList(),
            strategy = RoutingStrategy.valueOf(row.strategy),
            fallbackStrategy = FallbackStrategy.valueOf(row.fallbackStrategy),
            // decoded() so a level persisted by a newer build cannot take the
            // whole provider load down; unknown names clamp to XHIGH.
            defaultThinkingLevel = row.defaultThinkingLevel?.let(ThinkingLevel::decoded),
            contextLimitTokens = row.contextLimitTokens,
            lastContextLimitTokens = row.lastContextLimitTokens,
        )
    }

    val loopIdsByKind = loopRows
        .groupBy { row -> row.kind }
        .mapValues { (_, rows) -> rows.sortedBy { row -> row.sortOrder }.map { row -> row.targetId } }

    val meta = metaRows.associate { row -> row.key to row.value }
    fun metaList(key: String): MutableList<String> = meta[key]
        ?.let { encoded -> runCatching { jsonForBlobs.decodeFromString(stringList, encoded) }.getOrNull() }
        ?.toMutableList()
        ?: mutableListOf()

    return ProviderConfig(
        instances = instanceById.values.toMutableList(),
        modelEntries = modelEntries.toMutableList(),
        modelGroups = modelGroups.toMutableList(),
        defaultPrimaryGroupId = meta[ProviderMetaKeys.DEFAULT_PRIMARY_GROUP_ID],
        defaultSubGroupId = meta[ProviderMetaKeys.DEFAULT_SUB_GROUP_ID],
        voiceInputGroupId = meta[ProviderMetaKeys.VOICE_INPUT_GROUP_ID],
        voiceOutputGroupId = meta[ProviderMetaKeys.VOICE_OUTPUT_GROUP_ID],
        visionGroupId = meta[ProviderMetaKeys.VISION_GROUP_ID],
        agentLoopModelEntryIds = (loopIdsByKind["entry"] ?: mutableListOf()).toMutableList(),
        agentLoopGroupIds = (loopIdsByKind["group"] ?: mutableListOf()).toMutableList(),
        imageGenerationGroupIds = metaList(ProviderMetaKeys.IMAGE_GENERATION_GROUP_IDS),
        imageGenerationProviderInstanceIds = metaList(ProviderMetaKeys.IMAGE_GENERATION_PROVIDER_INSTANCE_IDS),
    )
}
