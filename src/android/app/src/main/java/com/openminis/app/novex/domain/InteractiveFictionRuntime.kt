package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleType
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Converts an editable project into one immutable, content-addressed conversation snapshot. */
object InteractiveFictionRuntimeSnapshotFactory {
    fun create(source: NovexInteractiveFictionSnapshot): ActiveInteractiveFictionSnapshot {
        val content = JSONObject().apply {
            put("version", 1)
            put("projectId", source.project.id)
            put("title", source.project.name)
            put("summary", source.project.summary)
            put("launchMode", source.project.launchMode.name)
            put("playerIdentity", source.project.playerIdentity)
            put("sourceUpdatedAt", source.project.updatedAt)
            put("modules", JSONArray().apply {
                source.modules.sortedBy(ContentModuleEntity::position).forEach { module ->
                    put(
                        JSONObject()
                            .put("id", module.id)
                            .put("type", module.type.name)
                            .put("name", module.name)
                            .put("contentJson", module.contentJson)
                            .put("position", module.position),
                    )
                }
            })
        }.toString()
        return ActiveInteractiveFictionSnapshot(
            projectId = source.project.id,
            snapshotId = content.sha256(),
            title = source.project.name,
            contentJson = content,
            presetControls = source.modules
                .filter { it.type == ContentModuleType.GAME_QUICK_ACTIONS }
                .sortedBy(ContentModuleEntity::position)
                .flatMap(::presetControls),
        )
    }
}

sealed interface ConversationControlOutcome {
    data class View(
        val title: String,
        val values: Map<String, PlaythroughValue>,
    ) : ConversationControlOutcome

    data class Action(val userTurn: String) : ConversationControlOutcome
}

/** Pure runtime decisions. UI and tools decide how to present or persist the result. */
object InteractiveFictionRuntime {
    fun resolveState(
        configuration: NovexConversationConfigurationSnapshot,
        activePathIds: List<String>,
    ): PlaythroughState {
        val nearest = activePathIds.asReversed().firstNotNullOfOrNull { id ->
            configuration.playthroughStates[id]
        }
        return nearest ?: PlaythroughState(activePathIds.lastOrNull() ?: "unstarted")
    }

    fun invoke(
        control: ConversationControlDefinition,
        state: PlaythroughState,
    ): ConversationControlOutcome {
        val payload = runCatching { JSONObject(control.payloadJson) }.getOrDefault(JSONObject())
        return when (control.behavior) {
            ConversationControlBehavior.ACTION -> ConversationControlOutcome.Action(
                payload.optString("prompt").trim().ifBlank { control.label },
            )
            ConversationControlBehavior.VIEW -> {
                val requested = payload.optJSONArray("stateKeys")?.stringValues().orEmpty()
                ConversationControlOutcome.View(
                    title = control.label,
                    values = if (requested.isEmpty()) {
                        state.values
                    } else {
                        requested.mapNotNull { key -> state.values[key]?.let { key to it } }.toMap()
                    },
                )
            }
        }
    }
}

/** Normalizes the agent tool payload into the same controls edited by the UI. */
object ConversationControlRegistration {
    fun registerAiControls(
        configuration: NovexConversationConfigurationSnapshot,
        controlsJson: String,
    ): NovexConversationConfigurationSnapshot {
        val values = JSONArray(controlsJson)
        val registered = buildList {
            repeat(values.length()) { index ->
                val value = values.optJSONObject(index) ?: return@repeat
                val label = value.optString("label").trim().take(40)
                if (label.isBlank()) return@repeat
                val legacyInstruction = value.optString("instruction").trim()
                val actionKey = value.optString("actionKey")
                    .ifBlank { value.optString("action_key") }
                    .trim()
                    .ifBlank { "control-${value.toString().sha256().take(12)}" }
                val behavior = when (value.optString("behavior").lowercase()) {
                    "view" -> ConversationControlBehavior.VIEW
                    "action" -> ConversationControlBehavior.ACTION
                    else -> if (legacyInstruction.isBlank()) {
                        ConversationControlBehavior.VIEW
                    } else {
                        ConversationControlBehavior.ACTION
                    }
                }
                if (value.optString("prompt").isBlank() && legacyInstruction.isNotBlank()) {
                    value.put("prompt", legacyInstruction)
                }
                add(
                    ConversationControlDefinition(
                        id = "ai:$actionKey",
                        label = label,
                        behavior = behavior,
                        source = ConversationControlSource.AI,
                        actionKey = actionKey,
                        payloadJson = value.toString(),
                        enabled = value.optBoolean("enabled", true),
                    ),
                )
            }
        }.distinctBy(ConversationControlDefinition::id).take(12)
        require(registered.isNotEmpty()) { "至少需要一个有效快捷操作" }
        return configuration.copy(
            controls = configuration.controls.filterNot { it.source == ConversationControlSource.AI } + registered,
        )
    }
}

/** Applies typed tool values through the public conversation domain commands. */
object PlaythroughStateRegistration {
    fun applyUpdates(
        configuration: NovexConversationConfigurationSnapshot,
        branchId: String,
        updatesJson: String,
    ): NovexConversationConfigurationSnapshot {
        require(branchId.isNotBlank()) { "没有可写入的活动消息分支" }
        val updates = JSONArray(updatesJson)
        require(updates.length() > 0) { "本局状态更新为空" }
        var domain = NovexConversationConfiguration.open(configuration)
        repeat(updates.length()) { index ->
            val item = updates.optJSONObject(index) ?: error("第 ${index + 1} 项状态格式无效")
            val key = item.optString("key").trim()
            require(key.isNotBlank()) { "本局状态字段名不能为空" }
            val raw = item.opt("value")
            val value = when (raw) {
                is Boolean -> PlaythroughValue.Flag(raw)
                is Number -> PlaythroughValue.Number(raw.toDouble())
                is String -> PlaythroughValue.Text(raw)
                else -> error("本局状态只支持文本、数字和布尔值")
            }
            domain = domain.apply(NovexConversationCommand.SetPlaythroughValue(branchId, key, value))
        }
        return domain.snapshot
    }
}

private fun presetControls(module: ContentModuleEntity): List<ConversationControlDefinition> {
    val document = ContentModuleDocumentCodec.decode(module.type, module.contentJson)
    val items = (document as? ContentModuleDocument.Collection)?.items.orEmpty()
    return items.mapIndexedNotNull { index, item ->
        val label = item.name.trim().ifBlank { item.summary.trim() }
        if (label.isBlank()) return@mapIndexedNotNull null
        val payload = runCatching { JSONObject(item.preservedJson) }.getOrDefault(JSONObject())
        if (payload.optString("prompt").isBlank() && item.description.isNotBlank()) {
            payload.put("prompt", item.description)
        }
        val behavior = if (payload.optString("behavior") == "action") {
            ConversationControlBehavior.ACTION
        } else {
            ConversationControlBehavior.VIEW
        }
        val id = item.id.trim().ifBlank { "${module.id}-$index" }
        ConversationControlDefinition(
            id = id,
            label = label,
            behavior = behavior,
            source = ConversationControlSource.PROJECT_PRESET,
            actionKey = payload.optString("actionKey").trim().ifBlank { "game.$id" },
            payloadJson = payload.toString(),
            enabled = payload.optBoolean("enabled", true),
        )
    }.distinctBy(ConversationControlDefinition::id)
}

private fun JSONArray.stringValues(): List<String> = buildList {
    repeat(length()) { index -> optString(index).takeIf(String::isNotBlank)?.let(::add) }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
