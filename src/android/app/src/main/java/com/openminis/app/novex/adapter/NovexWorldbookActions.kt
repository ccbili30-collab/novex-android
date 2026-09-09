package com.openminis.app.novex.adapter

import com.openminis.app.novex.domain.*
import com.openminis.app.tools.NovexWorldbookTools
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Shared persisted choices; this service does not own a second permission or approval system. */
class NovexWorldbookActions(private val workspace: NovexWorkspace, private val defaults: NovexGameWorldbooks) {
    data class Result(val configuration: NovexConversationConfigurationSnapshot, val payload: JSONObject)
    suspend fun execute(configuration: NovexConversationConfigurationSnapshot, name: String, args: JSONObject): Result {
        val gameId = args.optString("project_id").takeIf { it.isNotBlank() }
        val original = if (gameId != null) {
            require(workspace.interactiveFictions().any { it.project.id == gameId }) { "请选择文游库里已保存的卡片" }
            defaults.load(gameId)
        } else NovexWorldbookUse.references(configuration).filter { it.target.subject.kind == NovexContentKind.WORLD }
        var updated = configuration
        var selected = original
        if (name != NovexWorldbookTools.INSPECT) {
            require(args.getString("expected_revision") == revision(original)) { "选择已更新，请重新查看后调整；本次未保存" }
            when (name) {
                NovexWorldbookTools.DEFAULTS -> {
                    val projectId = requireNotNull(gameId) { "请指定要修改默认选择的文游" }
                    require(configuration.managedSubjects.any { it.subject == NovexContentAddress.interactiveFiction(projectId) && it.access == ManagedAccess.EDIT }) {
                        "这张文游尚不在本对话可编辑内容中，请先通过对话设置加入"
                    }
                    val array = args.getJSONArray("worldbooks")
                    require(array.length() <= 200) { "请把选择分成较小的文游组合" }
                    selected = (0 until array.length()).map { index ->
                        val row = array.getJSONObject(index)
                        val target = NovexReferenceTarget(NovexContentAddress.world(row.getString("world_id")), row.optString("module_id").takeIf { it.isNotBlank() }, row.optString("entry_id").takeIf { it.isNotBlank() })
                        val old = original.singleOrNull { it.target == target }
                        val title = workspace.world(target.subject.id)?.world?.name ?: old?.targetLabel ?: "缺失世界书"
                        require(row.get("enabled") is Boolean) { "启用状态须为真或假" }
                        (old ?: NovexCardReference(UUID.randomUUID().toString(), NovexContentAddress.interactiveFiction(projectId), target, NovexReferencePurpose.RULES, targetLabel = title))
                            .copy(position = index, enabled = row.getBoolean("enabled"))
                    }
                    selected = defaults.save(projectId, original, selected)
                }
                NovexWorldbookTools.CURRENT -> {
                    require(gameId == null) { "本局开关不接收原卡编号；修改默认选择请用设置文游世界书" }
                    val changes = args.getJSONArray("changes")
                    require(changes.length() in 1..200) { "每次选择一到两百项修改" }
                    val seen = mutableSetOf<String>()
                    repeat(changes.length()) { index ->
                        val change = changes.getJSONObject(index)
                        val id = change.getString("reference_id")
                        require(original.any { it.id == id }) { "这条世界书引用不属于本局选择" }
                        require(seen.add(id)) { "同一引用不能在一次修改中重复出现" }
                        require(change.get("enabled") is Boolean) { "启用状态须为真或假" }
                        updated = NovexConversationConfiguration.open(updated).apply(NovexConversationCommand.SetReferenceEnabled(id, change.getBoolean("enabled"))).snapshot
                    }
                    selected = NovexWorldbookUse.references(updated).filter { it.target.subject.kind == NovexContentKind.WORLD }
                }
                else -> error("未知世界书操作")
            }
        }
        return Result(updated, JSONObject().put("scope", if (gameId == null) "current_conversation" else "game_defaults")
            .put("revision", revision(selected)).put("saved", name != NovexWorldbookTools.INSPECT)
            .put("message", if (name == NovexWorldbookTools.INSPECT) "世界书选择已读取" else if (gameId == null) "本局世界书选择已保存" else "文游默认世界书选择已保存，已开始的游玩保持原设置")
            .put("choices", JSONArray(selected.map { ref -> JSONObject().put("reference_id", ref.id).put("world_id", ref.target.subject.id)
                .put("module_id", ref.target.moduleId).put("entry_id", ref.target.entryId).put("name", ref.targetLabel.ifBlank { "已采用的设定" })
                .put("enabled", ref.enabled) })))
    }
    suspend fun review(configuration: NovexConversationConfigurationSnapshot, name: String, args: JSONObject): String {
        if (name == NovexWorldbookTools.INSPECT) return "查看已保存的世界书选择，不修改内容。"
        if (name == NovexWorldbookTools.DEFAULTS) {
            val game = requireNotNull(workspace.interactiveFiction(args.getString("project_id"))) { "所选文游已不存在" }
            val array = args.getJSONArray("worldbooks")
            require(array.length() <= 200)
            val rows = (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val title = workspace.world(value.getString("world_id"))?.world?.name ?: "缺失世界书"
                "$title：${if (value.getBoolean("enabled")) "启用" else "关闭"}${if (value.has("module_id")) "（指定范围）" else ""}"
            }
            return "设置《${game.project.name}》以后启动时使用的世界书，已开始的游玩保持原设置。\n" + rows.joinToString("\n").ifBlank { "清除全部默认世界书选择。" }
        }
        val current = NovexWorldbookUse.references(configuration)
        val changes = args.getJSONArray("changes")
        require(changes.length() in 1..200)
        return "只调整本局，下次请求生效。\n" + (0 until changes.length()).joinToString("\n") { index ->
            val row = changes.getJSONObject(index)
            val reference = requireNotNull(current.singleOrNull { it.id == row.getString("reference_id") }) { "所选引用不属于当前对话" }
            "${reference.targetLabel.ifBlank { "已采用的世界书" }}：${if (row.getBoolean("enabled")) "启用" else "关闭"}"
        }
    }

    private fun revision(choices: List<NovexCardReference>) = NovexFrozenContextCodec.digest(JSONArray(choices.sortedBy { it.id }.map { JSONObject(NovexCardReferenceCodec.encode(it)) }).toString())
}
