package com.openminis.app.ui.novex

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.novex.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun NovexCharacterVersionRelationSection(versionId: String, onOpenVersion: (String) -> Unit) {
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var revision by remember(versionId) { mutableStateOf(0) }
    var versions by remember(versionId) { mutableStateOf<List<CharacterVersionEntity>>(emptyList()) }
    var relations by remember(versionId) { mutableStateOf<List<NovexCharacterVersionRelation>>(emptyList()) }
    var sheet by remember(versionId) { mutableStateOf<Pair<String, List<NovexSelectionAction>>?>(null) }
    var error by remember(versionId) { mutableStateOf<String?>(null) }
    var busy by remember(versionId) { mutableStateOf(false) }
    LaunchedEffect(versionId, revision) {
        try {
            versions = workspace.characterForVersion(versionId)?.character?.allVersions.orEmpty()
            relations = workspace.versionRelations(versionId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "读取版本关系失败" }
    }
    fun execute(command: NovexCommand) {
        if (busy) return
        sheet = null
        busy = true
        scope.launch {
            try { workspace.apply(command); revision++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "保存版本关系失败" }
            finally { busy = false }
        }
    }
    fun chooseKind(existing: NovexCharacterVersionRelation? = null) {
        sheet = "另一个版本与当前版本的关系" to NovexCharacterVersionRelationKind.entries.map { kind ->
            NovexSelectionAction(kind.label) {
                if (existing != null) execute(NovexCommand.PutVersionRelation(existing.copy(
                    kind = if (existing.sourceVersionId == versionId) kind else kind.reversed())))
                else {
                    val linked = relations.map { it.otherVersionId(versionId) }.toSet()
                    val choices = versions.filter { it.id != versionId && it.id !in linked }
                    if (choices.isEmpty()) { sheet = null; error = "请先创建同一人物的另一个版本；已有关系可直接修改。" }
                    else sheet = "选择${kind.label}" to choices.map { version ->
                        NovexSelectionAction(version.label, description = version.id) {
                            execute(NovexCommand.PutVersionRelation(NovexCharacterVersionRelation(UUID.randomUUID().toString(), versionId, version.id, kind)))
                        }
                    }
                }
            }
        }
    }
    NovexContentSection("人物版本关系", subtitle = "本体是默认版本；阶段和平行关系不自动同步知识或记忆") {
        if (busy) Text("正在保存关系…", color = NovexColors.SecondaryText)
        if (relations.isEmpty()) NovexSummaryRow("关系", "尚未指定人生阶段或平行分身")
        relations.forEach { relation ->
            val otherId = relation.otherVersionId(versionId)
            val other = versions.firstOrNull { it.id == otherId }
            val label = other?.label ?: "已删除版本 · $otherId"
            NovexSummaryRow(relation.kindFor(versionId).label, label, onClick = {
                sheet = label to buildList {
                    if (other != null) {
                        add(NovexSelectionAction("查看这个版本") { sheet = null; onOpenVersion(other.id) })
                        add(NovexSelectionAction("修改关系类型") { chooseKind(relation) })
                    }
                    add(NovexSelectionAction("移除关系，保留各版本") { execute(NovexCommand.RemoveVersionRelation(relation.id, relation.sourceVersionId)) })
                }
            })
        }
        NovexTextActionRow("添加版本关系", onClick = { chooseKind() })
    }
    sheet?.let { (title, actions) -> NovexSearchableSelectionSheet(title, actions, "搜索版本或操作",
        onDismissRequest = { sheet = null }, dismissOnSelection = false) }
    error?.let { NovexNoticeDialog("版本关系", it) { error = null } }
}
