package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Stable persistence format for one conversation's Novex configuration snapshot. */
object NovexConversationConfigurationCodec {
    fun encode(snapshot: NovexConversationConfigurationSnapshot): String = JSONObject().apply {
        require(snapshot.unreadableConfiguration == null) { "对话设置未能恢复，未覆盖原数据" }
        put("version", 1)
        put("conversationId", snapshot.conversationId)
        put("executionMode", snapshot.executionMode.wireName)
        put("contextLimitTokens", snapshot.contextLimitTokens)
        snapshot.cardBindingJson?.let { put("cardBinding", JSONObject(it)) }
        put("answerIdentity", NovexAnswerIdentityCodec.encode(snapshot.answerIdentity))
        snapshot.activePlaythroughId?.let { put("activePlaythroughId", it) }
        snapshot.preGameAnswerIdentity?.let { put("preGameAnswerIdentity", NovexAnswerIdentityCodec.encode(it)) }
        snapshot.playerIdentity?.let { put("playerIdentity", it.toJson()) }
        snapshot.preGamePlayerIdentity?.let { put("preGamePlayerIdentity", it.toJson()) }
        put("backgroundSettings", JSONArray(snapshot.backgroundSettings.map { it.subject.toJson() }))
        put("adoptedContexts", JSONArray(snapshot.adoptedContexts.map(NovexAdoptedContextCodec::encode)))
        put("disabledSettings", JSONArray(snapshot.disabledSettings.map { it.subject.toJson().put("moduleId", it.moduleId).put("entryId", it.entryId) }))
        snapshot.preGameAdoptedIdentity?.let { put("preGameAdoptedIdentity", NovexAdoptedContextCodec.encode(it)) }
        put("managedSubjects", JSONArray(snapshot.managedSubjects.map { subject ->
            subject.subject.toJson().put("access", subject.access.wireName())
        }))
        snapshot.activeInteractiveFiction?.let { put("activeInteractiveFiction", it.toJson()) }
        put("completedPlaythroughs", JSONArray(snapshot.completedPlaythroughs.map { completed ->
            JSONObject().put("game", completed.game.toJson())
                .put("playthroughId", completed.playthroughId)
                .put("states", JSONArray(completed.states.values.map(PlaythroughState::toJson)))
                .put("controls", JSONArray(completed.controls.map(ConversationControlDefinition::toJson)))
        }))
        put("playthroughStates", JSONArray(snapshot.playthroughStates.values.map(PlaythroughState::toJson)))
        put("controls", JSONArray(snapshot.controls.map(ConversationControlDefinition::toJson)))
    }.toString()

    fun decode(raw: String?, conversationId: String): NovexConversationConfigurationSnapshot {
        if (raw.isNullOrBlank()) return NovexConversationConfiguration.empty(conversationId).snapshot
        return runCatching {
            val root = JSONObject(raw)
            root.optJSONObject("cardBinding")?.let {binding->
                listOf("primary","backgrounds","managed").forEach {key->
                    val items=binding.optJSONArray(key)?:JSONArray()
                    require(key!="primary" || items.length()<=1){"主要互动对象不能超过一个"}
                    for(i in 0 until items.length()) {
                        val item=items.getJSONObject(i)
                        require(item.getString("root").isNotBlank() && item.getString("target").isNotBlank()){"卡片关联编号缺失"}
                    }
                }
                binding.optJSONObject("overrides")?.let {items->items.keys().forEach {require(items.get(it) is Boolean)}}
            }
            val decodedId = root.optString("conversationId").ifBlank { conversationId }
            val executionMode = runCatching { NovexExecutionMode.decode(root.optString("executionMode").takeIf { root.has("executionMode") }) }
            val unsupported = executionMode.isFailure || root.optInt("version", 1) != 1
            val snapshot = NovexConversationConfigurationSnapshot(
                conversationId = decodedId,
                cardBindingJson = root.optJSONObject("cardBinding")?.toString(),
                contextLimitTokens = root.optInt("contextLimitTokens").takeIf { it > 0 },
                executionMode = if (unsupported) NovexExecutionMode.READ_ONLY else executionMode.getOrThrow(),
                unreadableConfiguration = raw.takeIf { unsupported },
                activePlaythroughId = root.optString("activePlaythroughId").ifBlank { null },
                // Preserve the legacy configuration fallback without discarding its other relations.
                answerIdentity = runCatching {
                    NovexAnswerIdentityCodec.decode(root.optJSONObject("answerIdentity"))
                }.getOrDefault(AnswerIdentity.Nova),
                preGameAnswerIdentity = root.optJSONObject("preGameAnswerIdentity")?.optionalIdentity(),
                playerIdentity = root.optJSONObject("playerIdentity")?.toPlayerIdentity(),
                preGamePlayerIdentity = root.optJSONObject("preGamePlayerIdentity")?.toPlayerIdentity(),
                backgroundSettings = root.optJSONArray("backgroundSettings").objects().map { value ->
                    BackgroundSetting(value.toContentAddress())
                },
                adoptedContexts = root.optJSONArray("adoptedContexts").objects().map(NovexAdoptedContextCodec::decode),
                disabledSettings = root.optJSONArray("disabledSettings").objects().mapTo(linkedSetOf()) {
                    NovexReferenceTarget(it.toContentAddress(), it.optString("moduleId").takeIf(String::isNotBlank), it.optString("entryId").takeIf(String::isNotBlank))
                },
                preGameAdoptedIdentity = root.optJSONObject("preGameAdoptedIdentity")?.let(NovexAdoptedContextCodec::decode),
                managedSubjects = root.optJSONArray("managedSubjects").objects().map { value ->
                    ManagedSubject(
                        subject = value.toContentAddress(),
                        access = value.optString("access").toManagedAccess(),
                    )
                },
                activeInteractiveFiction = root.optJSONObject("activeInteractiveFiction")?.toGameSnapshot(),
                completedPlaythroughs = root.optJSONArray("completedPlaythroughs").objects().map { value ->
                    CompletedPlaythrough(
                        game = value.getJSONObject("game").toGameSnapshot(),
                        playthroughId = value.optString("playthroughId"),
                        states = value.optJSONArray("states").objects().map(JSONObject::toPlaythroughState).associateBy(PlaythroughState::branchId),
                        controls = value.optJSONArray("controls").objects().map(JSONObject::toControl),
                    )
                },
                playthroughStates = root.optJSONArray("playthroughStates").objects()
                    .map(JSONObject::toPlaythroughState)
                    .associateBy(PlaythroughState::branchId),
                controls = root.optJSONArray("controls").objects().map(JSONObject::toControl),
            )
            NovexConversationConfiguration.open(
                snapshot.copy(conversationId = conversationId),
            ).snapshot
        }.getOrElse { NovexConversationConfigurationSnapshot(conversationId,
            executionMode = NovexExecutionMode.READ_ONLY, unreadableConfiguration = raw) }
    }
}

private fun ActiveInteractiveFictionSnapshot.toJson() = JSONObject()
    .put("projectId", projectId).put("snapshotId", snapshotId).put("title", title)
    .put("contentJson", contentJson).put("playerIdentity", playerIdentity?.toJson())
    .put("answerIdentity", answerIdentity?.let(NovexAnswerIdentityCodec::encode))
    .put("presetControls", JSONArray(presetControls.map(ConversationControlDefinition::toJson)))

private fun JSONObject.toGameSnapshot() = ActiveInteractiveFictionSnapshot(
    projectId = getString("projectId"), snapshotId = getString("snapshotId"), title = getString("title"),
    contentJson = optString("contentJson", "{}"),
    playerIdentity = optJSONObject("playerIdentity")?.toPlayerIdentity(),
    answerIdentity = optJSONObject("answerIdentity")?.optionalIdentity(),
    presetControls = optJSONArray("presetControls").objects().map(JSONObject::toControl)
        .map { it.copy(source = ConversationControlSource.PROJECT_PRESET) },
)

private fun ConversationPlayerIdentity.toJson() = JSONObject()
    .put("id", id).put("label", label).put("description", description)

private fun JSONObject.optionalIdentity(): AnswerIdentity? =
    takeIf { optString("kind") in setOf("nova", "personaPreset", "characterVersion") }
        ?.let { runCatching { NovexAnswerIdentityCodec.decode(it) }.getOrNull() }

private fun JSONObject.toPlayerIdentity(): ConversationPlayerIdentity? = runCatching {
    ConversationPlayerIdentity(getString("id"), optString("label"), optString("description"))
}.getOrNull()

private fun NovexContentAddress.toJson() = JSONObject()
    .put("kind", kind.wireName())
    .put("id", id)

private fun JSONObject.toContentAddress() = NovexContentAddress(
    kind = when (getString("kind")) {
        "world" -> NovexContentKind.WORLD
        "characterVersion" -> NovexContentKind.CHARACTER_VERSION
        "interactiveFiction" -> NovexContentKind.INTERACTIVE_FICTION
        "creativeArtifact" -> NovexContentKind.CREATIVE_ARTIFACT
        else -> error("未知内容类型")
    },
    id = getString("id"),
)

private fun NovexContentKind.wireName(): String = when (this) {
    NovexContentKind.WORLD -> "world"
    NovexContentKind.CHARACTER_VERSION -> "characterVersion"
    NovexContentKind.INTERACTIVE_FICTION -> "interactiveFiction"
    NovexContentKind.CREATIVE_ARTIFACT -> "creativeArtifact"
}

private fun ManagedAccess.wireName(): String = when (this) {
    ManagedAccess.READ_ONLY -> "readOnly"
    ManagedAccess.EDIT -> "edit"
}

private fun String.toManagedAccess(): ManagedAccess = when (this) {
    "edit" -> ManagedAccess.EDIT
    else -> ManagedAccess.READ_ONLY
}

private fun PlaythroughState.toJson() = JSONObject()
    .put("branchId", branchId)
    .put("values", JSONObject().apply {
        values.forEach { (key, value) -> put(key, value.toJson()) }
    })

private fun PlaythroughValue.toJson(): JSONObject = when (this) {
    is PlaythroughValue.Text -> JSONObject().put("kind", "text").put("value", value)
    is PlaythroughValue.Number -> JSONObject().put("kind", "number").put("value", value)
    is PlaythroughValue.Flag -> JSONObject().put("kind", "flag").put("value", value)
}

private fun JSONObject.toPlaythroughState(): PlaythroughState {
    val valuesObject = optJSONObject("values") ?: JSONObject()
    val values = valuesObject.keys().asSequence().mapNotNull { key ->
        val value = valuesObject.optJSONObject(key) ?: return@mapNotNull null
        val decoded = when (value.optString("kind")) {
            "text" -> PlaythroughValue.Text(value.optString("value"))
            "number" -> PlaythroughValue.Number(value.optDouble("value"))
            "flag" -> PlaythroughValue.Flag(value.optBoolean("value"))
            else -> null
        }
        decoded?.let { key to it }
    }.toMap()
    return PlaythroughState(getString("branchId"), values)
}

private fun ConversationControlDefinition.toJson() = JSONObject()
    .put("id", id)
    .put("label", label)
    .put("behavior", if (behavior == ConversationControlBehavior.VIEW) "view" else "action")
    .put("source", when (source) {
        ConversationControlSource.PROJECT_PRESET -> "projectPreset"
        ConversationControlSource.AI -> "ai"
        ConversationControlSource.USER -> "user"
    })
    .put("actionKey", actionKey)
    .put("payloadJson", payloadJson)
    .put("enabled", enabled)
    .put("branchId", branchId)

private fun JSONObject.toControl() = ConversationControlDefinition(
    id = getString("id"),
    label = getString("label"),
    behavior = if (optString("behavior") == "action") {
        ConversationControlBehavior.ACTION
    } else {
        ConversationControlBehavior.VIEW
    },
    source = when (optString("source")) {
        "projectPreset" -> ConversationControlSource.PROJECT_PRESET
        "ai" -> ConversationControlSource.AI
        else -> ConversationControlSource.USER
    },
    actionKey = getString("actionKey"),
    payloadJson = optString("payloadJson", "{}"),
    enabled = optBoolean("enabled", true),
    branchId = optString("branchId").ifBlank { null },
)

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) {
    emptyList()
} else {
    buildList {
        repeat(length()) { index -> optJSONObject(index)?.let(::add) }
    }
}
