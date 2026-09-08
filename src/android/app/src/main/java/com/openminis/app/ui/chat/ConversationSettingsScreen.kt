package com.openminis.app.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import com.openminis.app.R
import com.openminis.app.data.MAX_CONVERSATION_PROMPT_CHARS
import com.openminis.app.data.MAX_IMAGE_STYLE_PROMPT_CHARS
import com.openminis.app.data.character.CharacterCardStore
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.novex.domain.ActiveInteractiveFictionSnapshot
import com.openminis.app.novex.domain.NovexGamePlayerChoices
import com.openminis.app.novex.domain.AnswerIdentity
import com.openminis.app.novex.domain.ConversationPlayerIdentity
import com.openminis.app.novex.domain.ConversationControlBehavior
import com.openminis.app.novex.domain.ConversationControlDefinition
import com.openminis.app.novex.domain.ConversationControlSource
import com.openminis.app.novex.adapter.NovexGameSnapshotAssembler
import kotlinx.coroutines.launch
import com.openminis.app.novex.domain.ManagedAccess
import com.openminis.app.novex.domain.NovexContentAddress
import com.openminis.app.novex.domain.NovexContentKind
import com.openminis.app.ui.novex.NovexCheckToggle
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDimensions
import com.openminis.app.ui.novex.NovexDivider
import com.openminis.app.ui.novex.NovexEditorScaffold
import com.openminis.app.ui.novex.NovexEditorSection
import com.openminis.app.ui.novex.NovexInlineField
import com.openminis.app.ui.novex.NovexNoticeDialog
import com.openminis.app.ui.novex.NovexOptionalImageRow
import com.openminis.app.ui.novex.NovexOutlineButton
import com.openminis.app.ui.novex.NovexSelectionAction
import com.openminis.app.ui.novex.NovexSelectionSheet
import com.openminis.app.ui.novex.NovexSummaryRow
import com.openminis.app.ui.novex.NovexTextActionRow
import com.openminis.app.ui.novex.NovexTextField
import com.openminis.app.ui.novex.NovexType
import com.openminis.app.ui.novex.rememberNovexWorkspace
import com.openminis.app.ui.novex.rememberNovexCreativeArtifacts
import com.openminis.app.ui.novex.rememberNovexWorkGroups
import com.openminis.app.ui.novex.NovexSearchableSelectionSheet
import com.openminis.app.novex.domain.NovexWorkGroupSnapshot

private data class ImageStylePreset(val name: String, val prompt: String)

private val imageStylePresets = listOf(
    ImageStylePreset("写实摄影", "写实摄影风格，自然光影，真实材质与细节，避免插画感。"),
    ImageStylePreset("动漫插画", "高质量动漫插画风格，清晰线条，细腻上色，角色一致。"),
    ImageStylePreset("电影感", "电影画面风格，叙事性构图，戏剧化光影，统一电影调色。"),
    ImageStylePreset("水彩", "透明水彩画风格，柔和晕染，纸张纹理，轻盈自然。"),
    ImageStylePreset("油画", "古典油画风格，厚重笔触，丰富层次，柔和明暗过渡。"),
    ImageStylePreset("像素艺术", "精细像素艺术风格，统一像素密度与有限色板。"),
)



@Composable
fun ConversationSettingsScreen(
    sessionId: String,
    chatRepository: ChatRepository,
    providerRepository: ProviderRepository,
    memoryRepository: MemoryRepository? = null,
    skillRepository: SkillRepository? = null,
    mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val workspace = rememberNovexWorkspace()
    val artifacts = rememberNovexCreativeArtifacts()
    val workGroups = rememberNovexWorkGroups()
    val works by workGroups.snapshots.collectAsState(initial = null)
    var localWorkSelection by remember(sessionId) { mutableStateOf(NovexWorkGroupSnapshot.ALL) }
    val pickerWorks = works?.copy(selection = localWorkSelection)
    var pickingWork by remember { mutableStateOf(false) }
    var showingAdoptedSources by remember { mutableStateOf(false) }
    var showingSettingUse by remember { mutableStateOf(false) }
    var returnPicker by remember { mutableStateOf<ConversationPicker?>(null) }
    val viewModel: ChatViewModel = viewModel(
        viewModelStoreOwner = ChatViewModelStore.ownerFor(sessionId),
        factory = ChatViewModel.factory(
            sessionId = sessionId,
            chatRepository = chatRepository,
            providerRepository = providerRepository,
            appContext = context.applicationContext,
            memoryRepository = memoryRepository,
            skillRepository = skillRepository,
            mcpRepository = mcpRepository,
        ),
    )
    val ready by viewModel.conversationSettingsReady.collectAsState()
    val seed = remember(sessionId) {
        NovexConversationEditorDraftState.from(sessionId, viewModel.conversationSettingsSnapshot())
    }
    var baseline by remember(sessionId) { mutableStateOf<NovexConversationEditorDraftState?>(null) }
    var draft by remember(sessionId) { mutableStateOf(seed) }
    var hydrated by remember(sessionId) { mutableStateOf(false) }
    var choosingExecutionMode by remember(sessionId) { mutableStateOf(false) }
    var pendingContentPlans by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var ownedOptions by remember { mutableStateOf<List<ConversationContentOption>>(emptyList()) }
    var options by remember { mutableStateOf<List<ConversationContentOption>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var preparingGame by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<ConversationPicker?>(null) }
    var pendingGame by remember { mutableStateOf<ActiveInteractiveFictionSnapshot?>(null) }
    var pendingRole by remember { mutableStateOf<Pair<AnswerIdentity.CharacterVersion, List<ConversationPlayerIdentity>>?>(null) }
    var expandedPlaythrough by remember { mutableStateOf<Int?>(null) }
    var managedAction by remember { mutableStateOf<NovexContentAddress?>(null) }
    var addingControl by remember { mutableStateOf(false) }
    var editingControlId by remember { mutableStateOf<String?>(null) }
    var controlLabel by remember { mutableStateOf("") }
    var controlBehavior by remember { mutableStateOf(ConversationControlBehavior.VIEW) }
    var controlPrompt by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adoptedImagePreview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ready) {
        if (ready && !hydrated) {
            val loaded = NovexConversationEditorDraftState.from(
                sessionId,
                viewModel.conversationSettingsSnapshot(),
            )
            draft = loaded
            baseline = loaded
            hydrated = true
        }
    }
    LaunchedEffect(workspace, artifacts, ready) {
        if (!ready) return@LaunchedEffect
        val database = (context.applicationContext as com.openminis.app.MinisApp).database
        com.openminis.app.novex.adapter.observeNovexLibraryChanges(database).collect {
        runCatching {
            val owned = workspace.conversationDrafts(viewModel.activeSessionId)
            val ownCards = owned?.cards.orEmpty()
            pendingContentPlans = owned?.pendingWrites.orEmpty().map { reservation ->
                reservation.id to runCatching { org.json.JSONObject(reservation.planJson).getString("summary") }
                    .getOrDefault("待执行内容变更")
            }
            ownedOptions = ownCards.mapNotNull { card ->
                val label = when (card.subject.kind) {
                    NovexContentKind.WORLD -> workspace.world(card.rootId)?.world?.name
                    NovexContentKind.CHARACTER_VERSION -> workspace.character(card.rootId)?.character?.character?.name
                    NovexContentKind.INTERACTIVE_FICTION -> workspace.interactiveFiction(card.rootId)?.project?.name
                    NovexContentKind.CREATIVE_ARTIFACT -> null
                } ?: return@mapNotNull null
                ConversationContentOption(card.subject, label, if (card.isPrivate) "空白${card.subject.kind.displayName()}卡" else "本对话创建 · ${card.subject.kind.displayName()}")
            }
            val worlds = workspace.worlds().map { card ->
                ConversationContentOption(NovexContentAddress.world(card.world.id), card.world.name, "世界")
            }
            val characters = workspace.characters().flatMap { card ->
                card.character.allVersions.map { version ->
                    val profile = CharacterVersionProfile.fromJson(
                        version.profileJson,
                        card.character.character.name,
                    )
                    val suffix = if (version.kind == CharacterVersionKind.ORIGINAL) {
                        "本体"
                    } else {
                        version.label.ifBlank { "版本" }
                    }
                    ConversationContentOption(
                        NovexContentAddress.characterVersion(version.id),
                        "${profile.name.ifBlank { card.character.character.name }} · $suffix",
                        "角色版本",
                    )
                }
            }
            val gameCards = workspace.interactiveFictions()
            val games = gameCards.map { card ->
                ConversationContentOption(
                    NovexContentAddress.interactiveFiction(card.project.id),
                    card.project.name,
                    "文游",
                )
            }
            val artifactOptions = artifacts.availableArtifacts().map { artifact ->
                ConversationContentOption(
                    artifact.address,
                    artifact.title,
                    "创作成果",
                )
            }
            options = (worlds + characters + games + artifactOptions + ownedOptions).distinctBy { it.address }
        }.onFailure { error = "读取内容库失败：${it.message ?: "未知错误"}" }
        }
    }

    fun save() {
        if (saving || preparingGame) return
        saving = true
        viewModel.saveConversationSettings(draft.toSettings(), expectedConfigurationJson = baseline?.settings?.novexConfigurationJson) { result ->
            saving = false
            result.onSuccess { onBack() }.onFailure { failure ->
                error = "保存失败：${failure.message ?: failure::class.java.simpleName}"
            }
        }
    }

    fun refreshAdoptedSetting(root: NovexContentAddress? = null, acting: Boolean = false) {
        if (saving || preparingGame) return
        preparingGame = true
        val expected = draft.configuration
        scope.launch {
            try {
                val application = context.applicationContext as com.openminis.app.MinisApp
                val updated = application.database.withTransaction {
                    val adoption = com.openminis.app.novex.adapter.NovexConversationContextAdoption(workspace, mediaStore = application.novexSnapshotMediaStore)
                    if (root == null) adoption.refreshGame(expected) else adoption.refresh(expected, root, acting)
                }
                require(draft.configuration == expected) { "刷新期间对话设定已改变，请重新刷新" }
                draft = draft.copy(configuration = updated)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "资料尚未刷新：${failure.message ?: "读取原卡失败"}"
            } finally { preparingGame = false }
        }
    }

    val assistantPicker = conversationImagePicker("conversation-assistant-avatar") { path ->
        draft = draft.updateSettings { it.copy(assistantAvatarPath = path) }
    }
    val playerPicker = conversationImagePicker("conversation-player-avatar") { path ->
        draft = draft.updateSettings { it.copy(playerAvatarPath = path) }
    }
    val labels = options.associateBy(ConversationContentOption::address)
    val adoptedImages = com.openminis.app.novex.domain.NovexSnapshotMediaProjection.visible(draft.configuration)
    val answerLabel = when (val identity = draft.configuration.answerIdentity) {
        AnswerIdentity.Nova -> "Nova（诺瓦）"
        is AnswerIdentity.PersonaPreset -> "自定义 · ${identity.label}"
        is AnswerIdentity.CharacterVersion -> labels[NovexContentAddress.characterVersion(identity.versionId)]?.label
            ?: "角色版本 · ${identity.versionId.take(8)}"
    }

    NovexEditorScaffold(
        title = "对话编辑",
        loaded = hydrated,
        canSave = draft.configuration.unreadableConfiguration == null,
        saving = saving,
        baselineDraft = baseline,
        currentDraft = draft,
        onBack = onBack,
        onSave = ::save,
    ) {
        if (draft.configuration.unreadableConfiguration != null) {
            NovexSummaryRow("对话设置未能恢复", "原数据已保留。当前只可阅读，暂不能修改设置；可从对话菜单导出记录进行检查。")
            return@NovexEditorScaffold
        }
        NovexSummaryRow("执行权限", draft.configuration.executionMode.label,
            onClick = { choosingExecutionMode = true })
        NovexEditorSection(
            header = "回答身份",
            footer = "选择由谁回答；背景资料和执行权限分别设置。",
        ) {
            NovexSummaryRow("当前身份", answerLabel, onClick = { picker = ConversationPicker.ANSWER })
            (draft.configuration.answerIdentity as? AnswerIdentity.PersonaPreset)?.let { persona ->
                NovexTextField("身份名称", persona.label, onValueChange = { value ->
                    if (value.isNotBlank()) draft = draft.setAnswerIdentity(persona.copy(label = value.take(80)))
                })
                NovexTextField("职责与表达", persona.instructions, onValueChange = { value ->
                    draft = draft.setAnswerIdentity(persona.copy(instructions = value.take(MAX_CONVERSATION_PROMPT_CHARS)))
                }, minLines = 4)
            }
            (draft.configuration.answerIdentity as? AnswerIdentity.CharacterVersion)?.let { role ->
                NovexTextActionRow("从原卡刷新当前角色资料", onClick = {
                    refreshAdoptedSetting(NovexContentAddress.characterVersion(role.versionId), acting = true)
                })
            }
        }

        NovexEditorSection(
            header = "对话提示词",
            footer = "只属于当前对话；替换人格后仍可继续调整。",
        ) {
            NovexTextField(
                label = "系统提示词",
                value = draft.settings.conversationPrompt,
                onValueChange = { value ->
                    draft = draft.updateSettings {
                        it.copy(conversationPrompt = value.take(MAX_CONVERSATION_PROMPT_CHARS))
                    }
                },
                minLines = 8,
            )
            NovexTextActionRow(
                "恢复当前人格的来源提示词",
                R.drawable.ic_phosphor_arrow_left,
                onClick = {
                    draft = draft.updateSettings { it.copy(conversationPrompt = viewModel.sourceConversationPrompt(draft.configuration.answerIdentity)) }
                },
            )
        }

        NovexEditorSection(
            header = "使用的设定",
            footer = "可加入多个世界和角色作为背景。这里使用已选定的资料，需要更新时手动刷新；编辑权限在可管理内容中设置。",
        ) {
            NovexSummaryRow("选择使用哪些模块", "查看并调整本对话使用的设定范围",
                onClick = { showingSettingUse = true })
            NovexSummaryRow("查看已采用的资料", "查看本对话实际使用的版本和来源",
                onClick = { showingAdoptedSources = true })
            draft.configuration.backgroundSettings.sortedBy { !com.openminis.app.novex.domain.NovexSettingUse.enabled(draft.configuration,
                com.openminis.app.novex.domain.NovexReferenceTarget(it.subject)) }.forEachIndexed { index, setting ->
                ConversationSubjectRow(
                    labels[setting.subject]?.label ?: setting.subject.fallbackLabel(),
                    (labels[setting.subject]?.kindLabel ?: setting.subject.kind.displayName()) +
                        if (com.openminis.app.novex.domain.NovexSettingUse.enabled(draft.configuration, com.openminis.app.novex.domain.NovexReferenceTarget(setting.subject))) " · 直接加入" else " · 已关闭",
                    onRemove = { draft = draft.removeBackground(setting.subject) },
                )
                if (com.openminis.app.novex.domain.NovexEffectiveFrozenContext.gameSources(draft.configuration)
                        .any { it.adoptedByGame && it.target.subject == setting.subject }) {
                    NovexSummaryRow("仍有文游来源", "移除上面的直接加入关系后，文游采用的修订仍会继续使用")
                }
                NovexTextActionRow("从原卡刷新这项背景", onClick = { refreshAdoptedSetting(setting.subject) })
                if (index < draft.configuration.backgroundSettings.lastIndex) {
                    NovexDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            NovexTextActionRow("添加世界或角色背景", onClick = { picker = ConversationPicker.BACKGROUND })
            NovexDivider(Modifier.padding(horizontal = 16.dp))
            NovexTextField(
                label = "玩家身份说明",
                value = draft.configuration.playerIdentity?.description.orEmpty(),
                placeholder = "描述你是谁，不替你决定行动",
                onValueChange = { value ->
                    val current = draft.configuration.playerIdentity
                    draft = draft.setPlayerIdentity(
                        if (value.isBlank()) null else ConversationPlayerIdentity(
                            current?.id ?: "player:${java.util.UUID.randomUUID()}",
                            current?.label ?: draft.settings.playerDisplayName,
                            value.take(MAX_CONVERSATION_PROMPT_CHARS),
                        ),
                    )
                }, minLines = 3,
            )
            NovexInlineField(
                label = "玩家名称",
                value = draft.settings.playerDisplayName,
                placeholder = "可留空",
                onValueChange = { value ->
                    draft = draft.updateSettings { it.copy(playerDisplayName = value.take(80)) }
                },
            )
            NovexOptionalImageRow(
                "玩家头像",
                draft.settings.playerAvatarPath?.existingFile(),
                playerPicker,
                onRemove = { draft = draft.updateSettings { it.copy(playerAvatarPath = null) } },
            )
        }

        NovexEditorSection(
            header = "活动文游",
            footer = "结束文游后恢复启动前身份，保留消息、状态与存档。返回列表不结束文游。",
        ) {
            if (preparingGame) NovexSummaryRow("准备设定", "正在读取本次采用的资料…")
            draft.configuration.activeInteractiveFiction?.let { active ->
                NovexSummaryRow("正在运行", active.title)
                NovexTextActionRow("刷新文游正文与背景引用", onClick = { refreshAdoptedSetting() })
                NovexSummaryRow("刷新范围", "保存后更新采用的资料，保留本局身份、状态与已注册操作")
                NovexTextActionRow("结束文游并恢复原身份", onClick = { draft = draft.deactivateGame() })
            }
            NovexTextActionRow(
                if (draft.configuration.activeInteractiveFiction == null) "选择并启动文游" else "更换并启动文游",
                R.drawable.ic_phosphor_puzzle_piece,
                onClick = { picker = ConversationPicker.GAME },
            )
            NovexTextActionRow("仅挂载文游为只读资料，不启动", onClick = { picker = ConversationPicker.GAME_REFERENCE })
            draft.configuration.completedPlaythroughs.forEachIndexed { index, completed ->
                NovexSummaryRow(
                    "历史第 ${index + 1} 局 · ${completed.game.title}",
                    "${completed.states.size} 个消息分支 · ${completed.controls.size} 项操作",
                    onClick = { expandedPlaythrough = if (expandedPlaythrough == index) null else index },
                )
                if (expandedPlaythrough == index) {
                    completed.states.forEach { (branch, state) ->
                        state.values.forEach { (key, value) ->
                            NovexSummaryRow("${branch.take(8)} · $key", when (value) {
                                is com.openminis.app.novex.domain.PlaythroughValue.Text -> value.value
                                is com.openminis.app.novex.domain.PlaythroughValue.Number -> value.value.toString()
                                is com.openminis.app.novex.domain.PlaythroughValue.Flag -> if (value.value) "是" else "否"
                            })
                        }
                    }
                }
            }
        }

        if (adoptedImages.isNotEmpty()) NovexEditorSection(
            header = "采用的图片",
            footer = "按当前用途显示，保存后随对话保留。原卡改图或删除不会移除这些副本；刷新资料后再保存可采用新图片。",
        ) {
            val sources = com.openminis.app.novex.domain.NovexEffectiveFrozenContext.sources(draft.configuration)
            adoptedImages.forEach { media ->
                val source = sources.firstOrNull { it.target.subject == media.owner }
                val label = media.moduleId?.let { id -> source?.candidates?.firstOrNull {
                    it.sourceId == id || it.sourceId == "$id:entry:${media.entryId}"
                }?.label } ?: labels[media.owner]?.label ?: source?.candidates?.firstOrNull()?.label
                    ?: draft.configuration.activeInteractiveFiction?.title ?: "已采用资料"
                val slot = when (media.slot) {
                    com.openminis.app.data.character.MediaAssetSlot.WORLD_COVER -> "世界封面"
                    com.openminis.app.data.character.MediaAssetSlot.WORLD_LOGO -> "世界标识"
                    com.openminis.app.data.character.MediaAssetSlot.WORLD_BACKGROUND -> "世界背景图"
                    com.openminis.app.data.character.MediaAssetSlot.CHARACTER_AVATAR -> "角色头像"
                    com.openminis.app.data.character.MediaAssetSlot.CHARACTER_PAGE_BACKGROUND -> "角色背景图"
                    com.openminis.app.data.character.MediaAssetSlot.INTERACTIVE_FICTION_COVER -> "文游封面"
                    com.openminis.app.data.character.MediaAssetSlot.INTERACTIVE_FICTION_BACKGROUND -> "文游背景图"
                    com.openminis.app.data.character.MediaAssetSlot.MODULE_IMAGE -> "模块配图"
                }
                NovexSummaryRow(label, slot, onClick = { adoptedImagePreview = media.asset.path })
            }
        }

        if (pendingContentPlans.isNotEmpty()) NovexEditorSection(
            header = "待执行变更",
            footer = "计划与目标已保存，重启后仍可继续。取消只撤销这项待执行计划，不修改已经保存的卡片。",
        ) {
            pendingContentPlans.forEach { (id, summary) ->
                NovexSummaryRow("计划 ${id.take(8)}", summary)
                NovexTextActionRow("取消这项待执行计划", onClick = {
                    viewModel.cancelPendingContentPlan(id) { result ->
                        result.onSuccess { pendingContentPlans = pendingContentPlans.filterNot { it.first == id } }
                            .onFailure { error = "取消失败：${it.message}" }
                    }
                })
            }
        }

        NovexEditorSection(
            header = "可管理的内容",
            footer = "本对话自带三个空卡，可继续创建更多卡片。填写后立即保存到仓库；加入这里表示可管理，不会自动成为背景或启动文游。工具是否执行仍按对话权限处理。",
        ) {
            val managedRows = (draft.configuration.managedSubjects + ownedOptions.map {
                com.openminis.app.novex.domain.ManagedSubject(it.address, ManagedAccess.EDIT)
            }).distinctBy { it.subject }
            val ownedAddresses = ownedOptions.map { it.address }.toSet()
            managedRows.forEachIndexed { index, subject ->
                ConversationSubjectRow(
                    labels[subject.subject]?.label ?: subject.subject.fallbackLabel(),
                    "${labels[subject.subject]?.kindLabel ?: subject.subject.kind.displayName()} · " +
                        if (subject.access == ManagedAccess.EDIT) "可编辑" else "只读",
                    onClick = { managedAction = subject.subject },
                    onRemove = if (subject.subject in ownedAddresses) null else ({ draft = draft.unmount(subject.subject) }),
                )
                if (index < managedRows.lastIndex) {
                    NovexDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            NovexTextActionRow("从仓库加入内容", onClick = { picker = ConversationPicker.MANAGED })
        }

        NovexEditorSection(
            header = "对话快捷操作",
            footer = "用户可手动添加；文游预设和人工智能注册的操作也统一显示在这里。",
        ) {
            draft.configuration.controls.forEachIndexed { index, control ->
                ConversationControlRow(
                    control,
                    canMoveUp = index > 0,
                    canMoveDown = index < draft.configuration.controls.lastIndex,
                    onToggle = { draft = draft.upsertControl(control.copy(enabled = it)) },
                    onMoveUp = { draft = draft.moveControl(control.id, index - 1) },
                    onMoveDown = { draft = draft.moveControl(control.id, index + 1) },
                    onRemove = { draft = draft.removeControl(control.id) },
                    onEdit = {
                        editingControlId = control.id
                        controlLabel = control.label
                        controlBehavior = control.behavior
                        controlPrompt = runCatching {
                            org.json.JSONObject(control.payloadJson).optString("prompt")
                        }.getOrDefault("")
                        addingControl = true
                    },
                )
                if (index < draft.configuration.controls.lastIndex) {
                    NovexDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            if (addingControl) {
                NovexDivider(Modifier.padding(horizontal = 16.dp))
                NovexTextField(
                    "操作名称",
                    controlLabel,
                    onValueChange = { controlLabel = it.take(40) },
                    placeholder = "例如：角色状态",
                )
                NovexSummaryRow(
                    "操作类型",
                    if (controlBehavior == ConversationControlBehavior.VIEW) "查看状态" else "执行动作",
                    onClick = {
                        controlBehavior = if (controlBehavior == ConversationControlBehavior.VIEW) {
                            ConversationControlBehavior.ACTION
                        } else {
                            ConversationControlBehavior.VIEW
                        }
                    },
                )
                if (controlBehavior == ConversationControlBehavior.ACTION) {
                    NovexTextField(
                        "发送内容",
                        controlPrompt,
                        onValueChange = { controlPrompt = it.take(2_000) },
                        placeholder = "留空时发送操作名称",
                        minLines = 3,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    NovexOutlineButton(
                        "取消",
                        {
                            addingControl = false
                            editingControlId = null
                            controlLabel = ""
                            controlPrompt = ""
                        },
                        Modifier.weight(1f),
                    )
                    NovexOutlineButton(
                        if (editingControlId == null) "添加" else "完成",
                        onClick = {
                            val existing = editingControlId?.let { id ->
                                draft.configuration.controls.firstOrNull { it.id == id }
                            }
                            val id = existing?.id ?: "user-${System.currentTimeMillis()}"
                            val payload = runCatching {
                                org.json.JSONObject(existing?.payloadJson ?: "{}")
                            }.getOrDefault(org.json.JSONObject()).apply {
                                if (controlBehavior == ConversationControlBehavior.ACTION && controlPrompt.isNotBlank()) {
                                    put("prompt", controlPrompt.trim())
                                } else {
                                    remove("prompt")
                                }
                            }.toString()
                            draft = draft.upsertControl(
                                ConversationControlDefinition(
                                    id = id,
                                    label = controlLabel.trim(),
                                    behavior = controlBehavior,
                                    source = existing?.source ?: ConversationControlSource.USER,
                                    actionKey = existing?.actionKey ?: if (controlBehavior == ConversationControlBehavior.VIEW) {
                                        "user.view.$id"
                                    } else {
                                        "user.action.$id"
                                    },
                                    payloadJson = payload,
                                    enabled = existing?.enabled ?: true,
                                ),
                            )
                            controlLabel = ""
                            controlPrompt = ""
                            editingControlId = null
                            addingControl = false
                        },
                        modifier = Modifier.weight(1f),
                        enabled = controlLabel.isNotBlank(),
                    )
                }
            } else {
                NovexTextActionRow(
                    "添加快捷操作",
                    onClick = {
                        editingControlId = null
                        controlLabel = ""
                        controlPrompt = ""
                        controlBehavior = ConversationControlBehavior.VIEW
                        addingControl = true
                    },
                )
            }
        }

        NovexEditorSection(
            header = "显示方式",
            footer = "只改变头像和气泡，不改变回答人格或背景设定。",
        ) {
            ConversationToggleRow(
                "显示双方头像和对话气泡",
                draft.settings.rolePresentationEnabled,
            ) { checked ->
                draft = draft.updateSettings { it.copy(rolePresentationEnabled = checked) }
            }
            if (draft.settings.rolePresentationEnabled) {
                NovexDivider(Modifier.padding(horizontal = 16.dp))
                NovexInlineField(
                    "助手名称",
                    draft.settings.assistantDisplayName,
                    "跟随人格",
                    onValueChange = { value ->
                        draft = draft.updateSettings { it.copy(assistantDisplayName = value.take(80)) }
                    },
                )
                NovexOptionalImageRow(
                    "助手头像",
                    draft.settings.assistantAvatarPath?.existingFile(),
                    assistantPicker,
                    onRemove = { draft = draft.updateSettings { it.copy(assistantAvatarPath = null) } },
                )
            }
        }

        NovexEditorSection(
            header = "图片生成提示词",
            footer = "会附加到本对话每次生成图片或编辑参考图的请求末尾。",
        ) {
            imageStylePresets.chunked(3).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    row.forEach { preset ->
                        NovexOutlineButton(
                            preset.name,
                            { draft = draft.updateSettings { it.copy(imageStylePrompt = preset.prompt) } },
                            Modifier.weight(1f),
                        )
                    }
                }
            }
            NovexTextField(
                "固定风格（可留空）",
                draft.settings.imageStylePrompt,
                onValueChange = { value ->
                    draft = draft.updateSettings {
                        it.copy(imageStylePrompt = value.take(MAX_IMAGE_STYLE_PROMPT_CHARS))
                    }
                },
                minLines = 4,
            )
        }
        Spacer(Modifier.height(32.dp))
    }

    picker?.let { active ->
        ConversationSelectionSheet(
            picker = active,
            title = if (active == ConversationPicker.ANSWER) active.pickerTitle() else "${active.pickerTitle()} · ${pickerWorks?.label ?: "全部作品"}",
            actions = (if (active == ConversationPicker.ANSWER) emptyList() else listOf(
                NovexSelectionAction("按作品筛选", description = pickerWorks?.label ?: "全部作品") {
                    returnPicker = active; picker = null; pickingWork = true
                })) + pickerActions(active, options.filter { pickerWorks?.includes(it.address) != false || it.address.kind == NovexContentKind.CREATIVE_ARTIFACT }, draft,
                onChooseRole = { picker = ConversationPicker.ROLE }, onRoleSelected = { versionId ->
                if (!preparingGame) {
                    preparingGame = true
                    picker = null
                    val expected = draft.configuration
                    scope.launch {
                        try {
                            val identity = AnswerIdentity.CharacterVersion(versionId)
                            val companions = com.openminis.app.novex.adapter.NovexPlayerIdentityReader(workspace).read(
                                com.openminis.app.novex.domain.NovexReferenceTarget(NovexContentAddress.characterVersion(versionId)))
                            require(draft.configuration == expected) { "准备角色期间对话设定已改变，请重新选择角色" }
                            val current = expected.playerIdentity
                            if (companions.size > 1 || (companions.isNotEmpty() && current != null && companions.singleOrNull() != current)) {
                                pendingRole = identity to companions
                            } else {
                                draft = draft.setAnswerIdentity(identity)
                                if (current == null && companions.size == 1) draft = draft.setPlayerIdentity(companions.single())
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "尚未切换角色：${failure.message ?: "读取配套身份失败"}"
                        } finally { preparingGame = false }
                    }
                }
            }, onGameSelected = { projectId ->
                if (!preparingGame) {
                    preparingGame = true
                    picker = null
                    val expected = draft.configuration
                    scope.launch {
                        try {
                            val application = context.applicationContext as com.openminis.app.MinisApp
                            val (capturedConfiguration, game) = application.database.withTransaction {
                                val captured = com.openminis.app.novex.adapter.NovexConversationContextAdoption(workspace, mediaStore = application.novexSnapshotMediaStore).adopt(expected)
                                captured to NovexGameSnapshotAssembler(workspace, application.novexSnapshotMediaStore).create(projectId, captured.backgroundSettings, captured.adoptedContexts)
                            }
                            require(draft.configuration == expected) { "准备文游期间对话设定已改变，请重新选择文游" }
                            draft = draft.copy(configuration = capturedConfiguration)
                            val currentPlayer = draft.configuration.playerIdentity
                            if (NovexGamePlayerChoices.needsSelection(game) ||
                                (game.playerIdentity != null && currentPlayer != null && game.playerIdentity != currentPlayer)) {
                                pendingGame = game
                            } else draft = draft.activateGame(game)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "文游尚未启动：${failure.message ?: "读取设定失败"}"
                        } finally { preparingGame = false }
                    }
                }
            }) { updated -> draft = updated },
            onDismissRequest = { picker = null },
        )
    }
    if (showingSettingUse) NovexSettingUseControls(draft.configuration, onToggle = { target, enabled ->
        try { draft = draft.setSettingEnabled(target, enabled) }
        catch (failure: IllegalArgumentException) { error = failure.message ?: "设定开关尚未保存" }
    }, onDismiss = { showingSettingUse = false })
    if (showingAdoptedSources) com.openminis.app.ui.novex.NovexContentDialog("实际采用的来源", onDismiss = { showingAdoptedSources = false },
        confirmButton = { com.openminis.app.ui.novex.TextButton(onClick = { showingAdoptedSources = false }) { Text("返回对话配置") } }) {
        val sources = com.openminis.app.novex.domain.NovexAdoptedSourceUsageProjection.read(draft.configuration)
        if (sources.isEmpty()) Text("当前没有已保存的采用快照；新选择的资料在保存对话配置时采用。")
        sources.forEach { usage ->
            Text(usage.source.candidates.firstOrNull()?.label ?: usage.source.target.subject.id)
            Text("修订 ${usage.revision.take(12)} · ${usage.origins.joinToString("、")}")
            Text("${usage.source.candidates.size} 个正文或模块 · ${usage.source.target.subject.id}")
        }
        Text("这里显示软件实际保存的采用关系，不能把正文存在视为事实核验通过。尚未保存的配置修改不会改变原卡。")
    }
    if (pickingWork) NovexSearchableSelectionSheet("按作品筛选", buildList {
        fun choice(id: String, title: String) = NovexSelectionAction(title, selected = localWorkSelection == id) {
            localWorkSelection = id
            pickingWork = false
            picker = returnPicker
        }
        add(choice(NovexWorkGroupSnapshot.ALL, "全部作品"))
        add(choice(NovexWorkGroupSnapshot.UNCLASSIFIED, "未归类"))
        works?.groups.orEmpty().forEach { add(choice(it.id, it.name)) }
    }, "搜索作品", onDismissRequest = { pickingWork = false; picker = returnPicker })
    pendingGame?.let { game ->
        NovexSelectionSheet(
            title = "选择本局玩家身份",
            actions = buildList {
                NovexGamePlayerChoices.read(game).forEach { identity ->
                    add(NovexSelectionAction(identity.label, description = identity.description) {
                        draft = draft.activateGame(NovexGamePlayerChoices.select(game, identity.id), replacePlayerIdentity = true)
                        pendingGame = null
                    })
                }
                draft.configuration.playerIdentity?.let { current ->
                    add(NovexSelectionAction("保留当前身份 · ${current.label}", description = current.description) {
                        draft = draft.activateGame(NovexGamePlayerChoices.useCurrent(game, current), replacePlayerIdentity = true)
                        pendingGame = null
                    })
                }
                add(NovexSelectionAction("取消启动") { pendingGame = null })
            },
            onDismissRequest = { pendingGame = null },
        )
    }
    pendingRole?.let { (role, companions) ->
        NovexSelectionSheet(
            title = "选择与该角色扮演时的玩家身份",
            actions = companions.map { identity ->
                NovexSelectionAction(identity.label, description = identity.description) {
                    draft = draft.setAnswerIdentity(role).setPlayerIdentity(identity)
                    pendingRole = null
                }
            } + listOf(
                NovexSelectionAction(if (draft.configuration.playerIdentity == null) "不采用配套身份" else "保留当前玩家身份") {
                    draft = draft.setAnswerIdentity(role)
                    pendingRole = null
                },
                NovexSelectionAction("取消切换角色") { pendingRole = null },
            ),
            onDismissRequest = { pendingRole = null },
        )
    }
    managedAction?.let { address ->
        val ownedHere = ownedOptions.any { it.address == address }
        val current = draft.configuration.managedSubjects.firstOrNull { it.subject == address }
            ?: if (ownedHere) com.openminis.app.novex.domain.ManagedSubject(address, ManagedAccess.EDIT) else null
        NovexSelectionSheet(
            title = labels[address]?.label ?: address.fallbackLabel(),
            onDismissRequest = { managedAction = null },
            actions = buildList {
                if (current?.access != ManagedAccess.EDIT) add(
                    NovexSelectionAction("设为可编辑", R.drawable.ic_phosphor_pencil_simple) {
                        draft = draft.mount(address, ManagedAccess.EDIT)
                    },
                )
                if (current?.access != ManagedAccess.READ_ONLY) add(
                    NovexSelectionAction("设为只读", R.drawable.ic_phosphor_eye) {
                        draft = draft.mount(address, ManagedAccess.READ_ONLY)
                    },
                )
                if (!ownedHere) add(
                    NovexSelectionAction("移出管理范围", R.drawable.ic_phosphor_trash) {
                        draft = draft.unmount(address)
                    },
                )
            },
        )
    }
    if (choosingExecutionMode) {
        com.openminis.app.ui.novex.NovexSelectionSheet(
            title = "执行权限",
            actions = com.openminis.app.novex.domain.NovexExecutionMode.entries.map { mode ->
                com.openminis.app.ui.novex.NovexSelectionAction(mode.label,
                    description = mode.description, selected = mode == draft.configuration.executionMode) {
                    draft = draft.setExecutionMode(mode)
                    choosingExecutionMode = false
                }
            },
            onDismissRequest = { choosingExecutionMode = false },
        )
    }
    error?.let { message -> NovexNoticeDialog("操作失败", message) { error = null } }
    adoptedImagePreview?.let { path ->
        com.openminis.app.ui.components.FullscreenImageViewer(java.io.File(path)) { adoptedImagePreview = null }
    }
}


@Composable
private fun ConversationSubjectRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
            .padding(start = NovexDimensions.PageHorizontal, top = 10.dp, bottom = 10.dp, end = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = NovexColors.Text, style = NovexType.Body, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable(onClick = onRemove)) {
            Icon(
                painterResource(R.drawable.ic_phosphor_trash),
                contentDescription = "移除$title",
                tint = NovexColors.Danger,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun ConversationToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 16.dp, end = 4.dp),
    ) {
        Text(title, color = NovexColors.Text, style = NovexType.Body, modifier = Modifier.weight(1f))
        NovexCheckToggle(checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConversationControlRow(
    control: ConversationControlDefinition,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onEdit).padding(vertical = 7.dp)) {
            Text(control.label, color = NovexColors.Text, style = NovexType.Body, fontWeight = FontWeight.Medium)
            Text(
                control.source.sourceLabel() + " · " +
                    if (control.behavior == ConversationControlBehavior.VIEW) "查看" else "动作",
                color = NovexColors.SecondaryText,
                style = NovexType.Metadata,
            )
        }
        NovexCheckToggle(control.enabled, onCheckedChange = onToggle)
        ControlMoveAction("上移", -90f, canMoveUp, onMoveUp)
        ControlMoveAction("下移", 90f, canMoveDown, onMoveDown)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(42.dp).clickable(onClick = onRemove)) {
            Icon(
                painterResource(R.drawable.ic_phosphor_trash),
                contentDescription = "删除${control.label}",
                tint = NovexColors.Danger,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ControlMoveAction(
    description: String,
    degrees: Float,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(38.dp).clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(
            painterResource(R.drawable.ic_phosphor_caret_right),
            contentDescription = description,
            tint = if (enabled) NovexColors.SecondaryText else NovexColors.Divider,
            modifier = Modifier.size(17.dp).rotate(degrees),
        )
    }
}

private fun ConversationControlSource.sourceLabel(): String = when (this) {
    ConversationControlSource.PROJECT_PRESET -> "文游预设"
    ConversationControlSource.AI -> "人工智能注册"
    ConversationControlSource.USER -> "用户添加"
}

private fun NovexContentKind.displayName(): String = when (this) {
    NovexContentKind.WORLD -> "世界"
    NovexContentKind.CHARACTER_VERSION -> "角色版本"
    NovexContentKind.INTERACTIVE_FICTION -> "文游"
    NovexContentKind.CREATIVE_ARTIFACT -> "创作成果"
}

private fun NovexContentAddress.fallbackLabel(): String = "${kind.displayName()} · ${id.take(8)}"
private fun String.existingFile(): java.io.File? = java.io.File(this).takeIf(java.io.File::exists)

@Composable
private fun conversationImagePicker(kind: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) runCatching { CharacterCardStore.copyMedia(context, uri, kind) }.onSuccess(onPicked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
