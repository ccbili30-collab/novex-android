package com.openminis.app.ui.novex

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One relation editor shared by world, character-version and game detail pages. */
@Composable
internal fun NovexCardReferenceSection(
    source: NovexContentAddress,
    sourceModuleId: String? = null,
    onOpenModule: ((String) -> Unit)? = null,
) {
    val workspace = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var revision by remember(source, sourceModuleId) { mutableIntStateOf(0) }
    var outgoing by remember(source, sourceModuleId) { mutableStateOf<List<Pair<NovexCardReference, String>>>(emptyList()) }
    var incoming by remember(source, sourceModuleId) { mutableStateOf<List<Pair<NovexCardReference, String>>>(emptyList()) }
    var sheet by remember(source, sourceModuleId) { mutableStateOf<Pair<String, List<NovexSelectionAction>>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> Unit) {
        if (working) return
        working = true
        scope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "引用操作失败" }
            finally { working = false }
        }
    }

    suspend fun label(address: NovexContentAddress): String = when (address.kind) {
        NovexContentKind.WORLD -> workspace.world(address.id)?.world?.name
        NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(address.id)?.character?.let { root ->
            root.allVersions.firstOrNull { it.id == address.id }?.let { "${root.character.name} · ${it.label}" }
        }
        NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(address.id)?.project?.name
        NovexContentKind.CREATIVE_ARTIFACT -> null
    } ?: "缺失卡片"

    LaunchedEffect(source, sourceModuleId, revision) {
        try {
            outgoing = workspace.referencesFrom(source).filter { it.sourceModuleId == sourceModuleId }.map { reference ->
                val status = workspace.referenceStatus(reference.target)
                val name = if (status == NovexReferenceTargetStatus.MISSING_CARD && reference.targetLabel.isNotBlank()) reference.targetLabel
                    else label(reference.target.subject)
                reference to "$name · ${if (reference.enabled) status.label else "已关闭 · ${status.label}"}"
            }
            incoming = workspace.referencesTo(source).filter { sourceModuleId == null || it.target.moduleId == sourceModuleId }
                .map { it to (label(it.source) + if (it.enabled) "" else " · 已关闭") }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "读取引用失败" }
    }

    fun save(target: NovexReferenceTarget, purpose: NovexReferencePurpose, title: String, replacing: NovexCardReference?) {
        sheet = null
        perform {
            workspace.apply(NovexCommand.PutCardReference(NovexCardReference(
                replacing?.id ?: UUID.randomUUID().toString(), source, target, purpose, sourceModuleId,
                replacing?.position ?: outgoing.size, title, enabled = if (purpose in setOf(NovexReferencePurpose.BACKGROUND, NovexReferencePurpose.RULES)) replacing?.enabled ?: true else true)))
            revision++
        }
    }

    fun chooseScope(address: NovexContentAddress, title: String, purpose: NovexReferencePurpose, replacing: NovexCardReference?) {
        if (purpose == NovexReferencePurpose.ANSWER_IDENTITY) {
            save(NovexReferenceTarget(address), purpose, title, replacing)
            return
        }
        perform {
            val owner = when (address.kind) {
                NovexContentKind.WORLD -> ModuleOwner.world(address.id)
                NovexContentKind.CHARACTER_VERSION -> ModuleOwner.characterVersion(address.id)
                NovexContentKind.INTERACTIVE_FICTION -> ModuleOwner.interactiveFiction(address.id)
                NovexContentKind.CREATIVE_ARTIFACT -> error("文件不属于卡片引用")
            }
            val modules = workspace.modules(owner).modules.filter {
                purpose != NovexReferencePurpose.PLAYER_IDENTITY || NovexModuleVisibility.isPlayerIdentity(it.type)
            }
            sheet = "选择引用范围 · $title" to (listOf(NovexSelectionAction("整张卡片") {
                save(NovexReferenceTarget(address), purpose, title, replacing)
            }) + modules.map { module -> NovexSelectionAction(module.name) {
                val items = (ContentModuleDocumentCodec.decode(module.type, module.contentJson) as? ContentModuleDocument.Collection)?.items.orEmpty()
                    .filter { it.id.isNotBlank() }
                if (items.isEmpty()) save(NovexReferenceTarget(address, module.id), purpose, "$title · ${module.name}", replacing)
                else sheet = "选择模块或条目 · ${module.name}" to (listOf(NovexSelectionAction("整个模块") {
                    save(NovexReferenceTarget(address, module.id), purpose, "$title · ${module.name}", replacing)
                }) + items.map { item -> NovexSelectionAction("${item.name.ifBlank { "未命名条目" }} · ${item.id}") {
                    save(NovexReferenceTarget(address, module.id, item.id), purpose, "$title · ${module.name} · ${item.name}", replacing)
                } })
            } })
        }
    }

    fun chooseTarget(purpose: NovexReferencePurpose, replacing: NovexCardReference?) {
        perform {
            val choices = buildList {
                if (purpose != NovexReferencePurpose.ANSWER_IDENTITY) {
                    if (purpose != NovexReferencePurpose.PLAYER_IDENTITY) workspace.worlds().forEach { add(NovexContentAddress.world(it.world.id) to "世界 · ${it.world.name}") }
                    workspace.interactiveFictions().forEach { add(NovexContentAddress.interactiveFiction(it.project.id) to "文游 · ${it.project.name}") }
                }
                workspace.characters().forEach { root -> root.character.allVersions.forEach { version ->
                    add(NovexContentAddress.characterVersion(version.id) to "角色 · ${root.character.character.name} · ${version.label}")
                } }
            }
            require(choices.isNotEmpty()) { "内容库中还没有可引用的卡片" }
            sheet = "选择${purpose.label}目标" to choices.map { (address, title) ->
                NovexSelectionAction(title) { chooseScope(address, title, purpose, replacing) }
            }
        }
    }

    NovexContentSection("卡片引用", subtitle = "用途不同，使用范围不同；引用不会授予编辑权限") {
        if (working) Text("正在处理引用…", color = NovexColors.SecondaryText)
        if (outgoing.isEmpty()) NovexSummaryRow("它引用了谁", "尚未添加引用")
        outgoing.forEach { (reference, description) ->
            NovexSummaryRow(reference.purpose.label, "$description${reference.target.moduleId?.let { "\n模块：$it" }.orEmpty()}${reference.target.entryId?.let { " · 条目：$it" }.orEmpty()}", onClick = {
                sheet = description to buildList {
                    if (onOpenModule != null) reference.target.moduleId?.let { moduleId -> add(NovexSelectionAction("查看目标模块") { sheet = null; onOpenModule(moduleId) }) }
                    add(NovexSelectionAction("替换引用目标") { chooseTarget(reference.purpose, reference) })
                    add(NovexSelectionAction("移除这条引用，保留目标卡片") { sheet = null; perform {
                        workspace.apply(NovexCommand.RemoveCardReference(reference.id, source)); revision++
                    } })
                }
            })
        }
        NovexTextActionRow("添加带用途的引用", onClick = {
            sheet = "这条引用用来做什么" to NovexReferencePurpose.entries
                .map { purpose -> NovexSelectionAction(purpose.label) { chooseTarget(purpose, null) } }
        })
        if (incoming.isEmpty()) NovexSummaryRow("谁在使用它", "尚无其他卡片引用")
        incoming.forEach { (reference, name) ->
            NovexSummaryRow(name, "${reference.purpose.label} · 来源编号：${reference.source.id}")
        }
    }
    sheet?.let { (title, actions) -> NovexSearchableSelectionSheet(title, actions,
        searchPlaceholder = "搜索名称或编号", dismissOnSelection = false, onDismissRequest = { sheet = null }) }
    failure?.let { message -> NovexNoticeDialog("引用未完成", message) { failure = null } }
}

/** Null keeps deletion disabled until the concrete reference impact has been read. */
@Composable
internal fun rememberNovexReferenceDeletionImpact(subjects: List<NovexContentAddress>): String? {
    val workspace = rememberNovexWorkspace()
    var result by remember(subjects) { mutableStateOf<String?>(null) }
    LaunchedEffect(subjects) {
        try {
            val links = subjects.flatMap { workspace.referencesTo(it) }.filterNot { it.source in subjects }.distinctBy { it.id }
            val deletedVersionIds = subjects.filter { it.kind == NovexContentKind.CHARACTER_VERSION }.mapTo(mutableSetOf()) { it.id }
            val versionLinks = deletedVersionIds.flatMap { workspace.versionRelations(it) }
                .filter { it.targetVersionId in deletedVersionIds && it.sourceVersionId !in deletedVersionIds }.distinctBy { it.id }
            val cardImpact = if (links.isEmpty()) "没有其他卡片通过带用途引用使用它。" else buildString {
                appendLine("另有 ${links.size} 条引用会显示目标缺失；引用它的独立卡片和既有游玩快照会保留：")
                links.take(12).forEach { reference ->
                    val name = when (reference.source.kind) {
                        NovexContentKind.WORLD -> workspace.world(reference.source.id)?.world?.name
                        NovexContentKind.CHARACTER_VERSION -> workspace.characterForVersion(reference.source.id)?.character?.character?.name
                        NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(reference.source.id)?.project?.name
                        NovexContentKind.CREATIVE_ARTIFACT -> null
                    } ?: reference.source.id
                    appendLine("$name · ${reference.purpose.label}")
                }
                if (links.size > 12) append("其余 ${links.size - 12} 条可在引用列表查看。")
            }.trim()
            result = cardImpact + if (versionLinks.isEmpty()) "" else buildString {
                appendLine(); appendLine("另有 ${versionLinks.size} 条人物版本关系会显示目标缺失；关联版本及其资料会保留：")
                versionLinks.take(12).forEach { relation ->
                    val source = workspace.characterForVersion(relation.sourceVersionId)?.character?.allVersions?.firstOrNull { it.id == relation.sourceVersionId }
                    appendLine("${source?.label ?: relation.sourceVersionId} · ${relation.kind.label}")
                }
            }.trimEnd()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { result = null }
    }
    return result
}
