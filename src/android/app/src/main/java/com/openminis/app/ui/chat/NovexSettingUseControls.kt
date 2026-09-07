package com.openminis.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Text
import com.openminis.app.novex.domain.*
import com.openminis.app.ui.novex.*
import org.json.JSONObject

/** One sheet at a time; child switches stay stored when their card is turned off. */
@Composable
internal fun NovexSettingUseControls(configuration: NovexConversationConfigurationSnapshot,
    onToggle: (NovexReferenceTarget, Boolean) -> Unit, onDismiss: () -> Unit) {
    var selectedKind by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedKind?.let { kind -> selectedId?.let { NovexContentAddress(NovexContentKind.valueOf(kind), it) } }
    val usages = NovexAdoptedSourceUsageProjection.read(configuration).filter { it.source.actorVersionId == null }
    val addresses = (configuration.backgroundSettings.map { it.subject } + usages.map { it.source.target.subject } +
        listOfNotNull(configuration.activeInteractiveFiction?.let { NovexContentAddress.interactiveFiction(it.projectId) })).distinct()
    val active = NovexEffectiveFrozenContext.sources(configuration).map { it.target.subject }.toSet()
    fun isGame(address: NovexContentAddress) = address.kind == NovexContentKind.INTERACTIVE_FICTION && configuration.activeInteractiveFiction?.projectId == address.id
    fun label(address: NovexContentAddress) = if (isGame(address)) configuration.activeInteractiveFiction!!.title else
        usages.firstOrNull { it.source.target.subject == address }?.source?.candidates?.firstOrNull()?.label ?: address.id
    if (selected == null) {
        NovexSearchableSelectionSheet("使用的设定与开关", addresses.sortedBy { !NovexSettingUse.enabled(configuration, NovexReferenceTarget(it)) }.map { address ->
            val status = if (!NovexSettingUse.enabled(configuration, NovexReferenceTarget(address))) "已关闭" else if (address in active || isGame(address)) "使用中" else "待采用或来源已暂停"
            NovexSelectionAction(label(address), description = "$status · 查看模块与采用来源") {
                selectedKind = address.kind.name; selectedId = address.id
            }
        }, searchPlaceholder = "搜索已选择的设定", onDismissRequest = onDismiss, dismissOnSelection = false)
    } else {
        val rows = usages.filter { it.source.target.subject == selected }
        val parent = NovexReferenceTarget(selected)
        val parentEnabled = NovexSettingUse.enabled(configuration, parent)
        val modules = linkedMapOf<String, String>()
        rows.forEach { usage -> usage.source.candidates.forEach { candidate ->
            NovexSettingUse.moduleId(usage.source, candidate)?.let { modules.putIfAbsent(it, candidate.label) }
        } }
        if (isGame(selected)) {
            val array = JSONObject(configuration.activeInteractiveFiction!!.contentJson).optJSONArray("modules")
            if (array != null) repeat(array.length()) { index -> array.getJSONObject(index).let { module ->
                if (module.optString("type") !in setOf("GAME_ANSWER_IDENTITY", "GAME_PLAYER_IDENTITY")) {
                    modules[module.getString("id")] = module.optString("name").ifBlank { "文游模块" }
                }
            } }
        }
        fun back() { selectedKind = null; selectedId = null }
        NovexContentDialog(label(selected), onDismiss = ::back,
            confirmButton = { TextButton(onClick = ::back) { Text("返回设定列表") } }) {
            if (!isGame(selected)) NovexSettingsVectorToggleRow("使用这张卡的背景设定",
                "关闭会停用本对话所有背景来源中的该卡，保留模块开关；回答身份和管理权限分别设置。",
                checked = parentEnabled, onCheckedChange = { onToggle(parent, it) })
            else Text("活动文游通过原有结束入口停止；这里单独设置配套规则模块。")
            Text(if (parentEnabled) "模块独立保存；关闭的模块排在本卡底部。" else "本卡已关闭；下列勾选保留为重新启用后的状态。")
            modules.entries.sortedBy { NovexReferenceTarget(selected, it.key) in configuration.disabledSettings }.forEach { (id, title) ->
                val target = NovexReferenceTarget(selected, id)
                val checked = target !in configuration.disabledSettings
                NovexSettingsVectorToggleRow(title, if (checked) "已开启${if (!parentEnabled) "，随父级暂停" else ""}" else "已关闭",
                    checked = checked, enabled = parentEnabled, onCheckedChange = { onToggle(target, it) })
            }
            if (modules.isEmpty()) Text("没有已保存的可选模块；新加入的卡片在保存配置后采用。")
            rows.forEach { usage ->
                Text("修订 ${usage.revision.take(12)} · ${usage.origins.joinToString("、")}")
            }
            Text("移除直接加入关系请回对话配置操作；配套组成在原卡编辑。关闭规则不结束文游，也不会自行补回缺少的玩法。")
        }
    }
}
