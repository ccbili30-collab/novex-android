package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.*
import org.json.JSONObject

/** Uses the same commands and frozen adoption as the conversation settings screen. */
class NovexConversationActions(
    private val workspace: NovexWorkspace,
    private val mediaStore: NovexSnapshotMediaStore? = null,
    private val userStatements: List<String> = emptyList(),
) {
    suspend fun setPlayerIdentity(current: NovexConversationConfigurationSnapshot, args: JSONObject): NovexConversationConfigurationSnapshot {
        val description = args.getString("description")
        val clear = args.optBoolean("clear")
        require(if (clear) description.isBlank() else description.isNotBlank()) {
            "填写用户明确给出的身份说明；只有明确清空身份时才将 clear 设为 true 并留空正文"
        }
        require(description.length <= 32_000) { "玩家身份说明过长，请只保留用户明确给出的身份事实" }
        val previous = current.playerIdentity
        val identity = if (clear) null else ConversationPlayerIdentity(
            id = previous?.id ?: "player:${NovexFrozenContextCodec.digest(current.conversationId)}",
            label = args.optString("label").takeIf(String::isNotBlank) ?: previous?.label ?: "我的身份",
            description = description,
        )
        if (identity == previous) return current
        require(previous == null || args.optBoolean("replace_existing")) {
            "本对话已有玩家身份。只有用户明确要求修改或清空时才能替换；请保留原身份或说明冲突。"
        }
        val configured = NovexConversationConfiguration.open(current).apply(NovexConversationCommand.SetPlayerIdentity(identity)).snapshot
        return NovexConversationContextAdoption(workspace, mediaStore = mediaStore).adopt(configured)
    }

    suspend fun selectIdentity(current: NovexConversationConfigurationSnapshot, args: JSONObject): NovexConversationConfigurationSnapshot {
        val identity = when (args.getString("kind")) {
            "nova" -> AnswerIdentity.Nova
            "character" -> AnswerIdentity.CharacterVersion(args.getString("version_id"))
            "custom" -> AnswerIdentity.PersonaPreset("conversation:${current.conversationId}", args.getString("name"), args.getString("instructions"))
            else -> error("身份类型应为 nova（诺瓦）、character（角色）或 custom（自定义）")
        }
        var configured = NovexConversationConfiguration.open(current).apply(NovexConversationCommand.SetAnswerIdentity(identity))
        if (identity is AnswerIdentity.CharacterVersion) {
            val companions = NovexPlayerIdentityReader(workspace).read(NovexReferenceTarget(NovexContentAddress.characterVersion(identity.versionId)))
            val selectedId = args.optString("player_identity_id").takeIf(String::isNotBlank)
            val selected = if (selectedId != null) companions.singleOrNull { it.id == selectedId }
                ?: error("找不到指定的配套玩家身份，请重新选择") else companions.singleOrNull().takeIf { current.playerIdentity == null }
            require(selectedId != null || current.playerIdentity != null || companions.size <= 1) {
                "该角色有多个配套玩家身份，请让用户选择：${companions.joinToString { "${it.label} (${it.id})" }}"
            }
            if (selected != null) {
                require(current.playerIdentity == null || selected == current.playerIdentity || args.optBoolean("replace_player_identity")) {
                    "已有玩家身份，需明确替换才能采用配套身份"
                }
                configured = configured.apply(NovexConversationCommand.SetPlayerIdentity(selected))
            }
        }
        return NovexConversationContextAdoption(workspace, mediaStore = mediaStore).adopt(configured.snapshot)
    }

    suspend fun startGame(current: NovexConversationConfigurationSnapshot, args: JSONObject): NovexConversationConfigurationSnapshot {
        val projectId = args.getString("project_id")
        // A repeated start request must not refresh the snapshot or reset the current run.
        if (current.activeInteractiveFiction?.projectId == projectId) return current
        require(current.activeInteractiveFiction == null || args.optBoolean("replace_active_game")) {
            "已有正在游玩的文游，请明确是否切换；当前进度未改变"
        }
        val captured = NovexConversationContextAdoption(workspace, mediaStore = mediaStore).adopt(current)
        var game = NovexGameSnapshotAssembler(workspace, mediaStore).create(projectId, captured.backgroundSettings, captured.adoptedContexts)
        val selectedPlayerId = args.optString("player_identity_id").takeIf(String::isNotBlank)
        val playerDescription = args.optString("player_description").takeIf(String::isNotBlank)
        require(listOf(selectedPlayerId != null, args.optBoolean("use_current_player_identity"), playerDescription != null).count { it } <= 1) {
            "请选择用户提供的 player_description（身份说明）、当前身份或一个配套身份，不能混合采用"
        }
        if (playerDescription != null) {
            require(playerDescription.length <= 32_000) { "玩家身份说明过长，请只保留用户明确给出的身份事实" }
            requireUserIdentitySource(playerDescription)
            val identity = ConversationPlayerIdentity(
                captured.playerIdentity?.id ?: "player:${NovexFrozenContextCodec.digest(current.conversationId)}",
                captured.playerIdentity?.label ?: "我的身份", playerDescription)
            game = NovexGamePlayerChoices.useCurrent(game, identity)
        } else if (args.optBoolean("use_current_player_identity")) {
            game = NovexGamePlayerChoices.useCurrent(game, requireNotNull(captured.playerIdentity) {
                "当前没有已保存的玩家身份。用户已说明身份时，直接用 player_description（身份说明）填写其原话并重试启动，去掉 use_current_player_identity；不必追问未要求的姓名和来历。也可先用 set_player_identity（保存玩家身份）。"
            })
        } else selectedPlayerId?.let { game = NovexGamePlayerChoices.select(game, it) }
        val configured = NovexConversationConfiguration.open(captured).apply(NovexConversationCommand.ActivateInteractiveFiction(
            game, replacePlayerIdentity = args.optBoolean("replace_player_identity")))
        return NovexConversationContextAdoption(workspace, mediaStore = mediaStore).adopt(configured.snapshot)
    }

    /** This field copies user-provided identity facts; it does not generate a player's biography.
     * Matching source text validates provenance, not command intent or a list of trigger verbs. */
    private fun requireUserIdentitySource(description: String) {
        val quotes = description.lineSequence().map { it.trim().trimEnd('。', '；', '，', '.', ';', ',', '!', '！', '?', '？') }
            .filter(String::isNotBlank).toList()
        require(quotes.isNotEmpty() && quotes.all { quote -> userStatements.any { it.contains(quote) } }) {
            "玩家身份说明包含无法在用户原话中定位的改写。请从用户已给出的身份原句摘取，原句可分行组合；不要加入角色卡里的装备、动作或经历，不需要让用户重新描述。"
        }
    }
}
