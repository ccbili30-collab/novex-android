package com.openminis.app.novex.domain

import org.json.JSONArray
import org.json.JSONObject

/** Stable persistence format for one conversation's Novex configuration snapshot. */
object NovexConversationConfigurationCodec {
    fun encode(snapshot: NovexConversationConfigurationSnapshot): String = JSONObject().apply {
        put("version", 1)
        put("conversationId", snapshot.conversationId)
        put("answerIdentity", snapshot.answerIdentity.toJson())
        put("backgroundSettings", JSONArray(snapshot.backgroundSettings.map { it.subject.toJson() }))
        put("managedSubjects", JSONArray(snapshot.managedSubjects.map { subject ->
            subject.subject.toJson().put("access", subject.access.wireName())
        }))
        snapshot.activeInteractiveFiction?.let { active ->
            put(
                "activeInteractiveFiction",
                JSONObject()
                    .put("projectId", active.projectId)
                    .put("snapshotId", active.snapshotId)
                    .put("title", active.title)
                    .put("contentJson", active.contentJson)
                    .put("presetControls", JSONArray(active.presetControls.map(ConversationControlDefinition::toJson))),
            )
        }
        put("playthroughStates", JSONArray(snapshot.playthroughStates.values.map(PlaythroughState::toJson)))
        put("controls", JSONArray(snapshot.controls.map(ConversationControlDefinition::toJson)))
    }.toString()

    fun decode(raw: String?, conversationId: String): NovexConversationConfigurationSnapshot {
        if (raw.isNullOrBlank()) return NovexConversationConfiguration.empty(conversationId).snapshot
        return runCatching {
            val root = JSONObject(raw)
            val decodedId = root.optString("conversationId").ifBlank { conversationId }
            val snapshot = NovexConversationConfigurationSnapshot(
                conversationId = decodedId,
                answerIdentity = root.optJSONObject("answerIdentity").toAnswerIdentity(),
                backgroundSettings = root.optJSONArray("backgroundSettings").objects().map { value ->
                    BackgroundSetting(value.toContentAddress())
                },
                managedSubjects = root.optJSONArray("managedSubjects").objects().map { value ->
                    ManagedSubject(
                        subject = value.toContentAddress(),
                        access = value.optString("access").toManagedAccess(),
                    )
                },
                activeInteractiveFiction = root.optJSONObject("activeInteractiveFiction")?.let { value ->
                    ActiveInteractiveFictionSnapshot(
                        projectId = value.getString("projectId"),
                        snapshotId = value.getString("snapshotId"),
                        title = value.getString("title"),
                        contentJson = value.optString("contentJson", "{}"),
                        presetControls = value.optJSONArray("presetControls").objects()
                            .map(JSONObject::toControl)
                            .map { it.copy(source = ConversationControlSource.PROJECT_PRESET) },
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
        }.getOrElse { NovexConversationConfiguration.empty(conversationId).snapshot }
    }
}

private fun AnswerIdentity.toJson(): JSONObject = when (this) {
    AnswerIdentity.Nova -> JSONObject().put("kind", "nova")
    is AnswerIdentity.CharacterVersion -> JSONObject()
        .put("kind", "characterVersion")
        .put("versionId", versionId)
}

private fun JSONObject?.toAnswerIdentity(): AnswerIdentity = when (this?.optString("kind")) {
    "characterVersion" -> optString("versionId").takeIf(String::isNotBlank)
        ?.let(AnswerIdentity::CharacterVersion)
        ?: AnswerIdentity.Nova
    else -> AnswerIdentity.Nova
}

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
