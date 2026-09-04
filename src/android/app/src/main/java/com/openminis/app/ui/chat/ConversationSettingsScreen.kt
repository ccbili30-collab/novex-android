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
import com.openminis.app.novex.domain.AnswerIdentity
import com.openminis.app.novex.domain.ConversationControlBehavior
import com.openminis.app.novex.domain.ConversationControlDefinition
import com.openminis.app.novex.domain.ConversationControlSource
import com.openminis.app.novex.domain.InteractiveFictionRuntimeSnapshotFactory
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

private data class ImageStylePreset(val name: String, val prompt: String)

private val imageStylePresets = listOf(
    ImageStylePreset("写实摄影", "写实摄影风格，自然光影，真实材质与细节，避免插画感。"),
    ImageStylePreset("动漫插画", "高质量动漫插画风格，清晰线条，细腻上色，角色一致。"),
    ImageStylePreset("电影感", "电影画面风格，叙事性构图，戏剧化光影，统一电影调色。"),
    ImageStylePreset("水彩", "透明水彩画风格，柔和晕染，纸张纹理，轻盈自然。"),
    ImageStylePreset("油画", "古典油画风格，厚重笔触，丰富层次，柔和明暗过渡。"),
    ImageStylePreset("像素艺术", "精细像素艺术风格，统一像素密度与有限色板。"),
)

private enum class ConversationPicker { ANSWER, BACKGROUND, GAME, MANAGED }

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
    var options by remember { mutableStateOf<List<ConversationContentOption>>(emptyList()) }
    var gameSnapshots by remember { mutableStateOf<Map<String, ActiveInteractiveFictionSnapshot>>(emptyMap()) }
    var picker by remember { mutableStateOf<ConversationPicker?>(null) }
    var managedAction by remember { mutableStateOf<NovexContentAddress?>(null) }
    var addingControl by remember { mutableStateOf(false) }
    var editingControlId by remember { mutableStateOf<String?>(null) }
    var controlLabel by remember { mutableStateOf("") }
    var controlBehavior by remember { mutableStateOf(ConversationControlBehavior.VIEW) }
    var controlPrompt by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
    LaunchedEffect(workspace) {
        runCatching {
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
                        version.label.ifBlank { "分身" }
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
            options = worlds + characters + games
            gameSnapshots = gameCards.mapNotNull { card ->
                workspace.interactiveFiction(card.project.id)?.let { snapshot ->
                    card.project.id to InteractiveFictionRuntimeSnapshotFactory.create(snapshot)
                }
            }.toMap()
        }.onFailure { error = "读取内容库失败：${it.message ?: "未知错误"}" }
    }

    fun save() {
        if (saving) return
        saving = true
        viewModel.saveConversationSettings(draft.toSettings()) { result ->
            saving = false
            result.onSuccess { onBack() }.onFailure { failure ->
                error = "保存失败：${failure.message ?: failure::class.java.simpleName}"
            }
        }
    }

    val assistantPicker = conversationImagePicker("conversation-assistant-avatar") { path ->
        draft = draft.updateSettings { it.copy(assistantAvatarPath = path) }
    }
    val playerPicker = conversationImagePicker("conversation-player-avatar") { path ->
        draft = draft.updateSettings { it.copy(playerAvatarPath = path) }
    }
    val labels = options.associateBy(ConversationContentOption::address)
    val answerLabel = when (val identity = draft.configuration.answerIdentity) {
        AnswerIdentity.Nova -> "Nova · 通用人格"
        is AnswerIdentity.CharacterVersion -> labels[NovexContentAddress.characterVersion(identity.versionId)]?.label
            ?: "角色版本 · ${identity.versionId.take(8)}"
    }

    NovexEditorScaffold(
        title = "对话编辑",
        loaded = hydrated,
        canSave = true,
        saving = saving,
        baselineDraft = baseline,
        currentDraft = draft,
        onBack = onBack,
        onSave = ::save,
    ) {
        NovexEditorSection(
            header = "回答身份",
            footer = "Nova 是通用人格；背景角色不会自动替换回答身份。",
        ) {
            NovexSummaryRow("当前人格", answerLabel, onClick = { picker = ConversationPicker.ANSWER })
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
                    draft = draft.updateSettings { it.copy(conversationPrompt = viewModel.sourceConversationPrompt()) }
                },
            )
        }

        NovexEditorSection(
            header = "背景设定",
            footer = "可加入多个世界和角色版本，作为只读背景被检索；这不会授予编辑权限。",
        ) {
            draft.configuration.backgroundSettings.forEachIndexed { index, setting ->
                ConversationSubjectRow(
                    labels[setting.subject]?.label ?: setting.subject.fallbackLabel(),
                    labels[setting.subject]?.kindLabel ?: setting.subject.kind.displayName(),
                    onRemove = { draft = draft.removeBackground(setting.subject) },
                )
                if (index < draft.configuration.backgroundSettings.lastIndex) {
                    NovexDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            NovexTextActionRow("添加世界或角色背景", onClick = { picker = ConversationPicker.BACKGROUND })
            NovexDivider(Modifier.padding(horizontal = 16.dp))
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
            footer = "每段对话同时只能运行一个文游；更换会建立新的运行快照。",
        ) {
            draft.configuration.activeInteractiveFiction?.let { active ->
                ConversationSubjectRow(active.title, "正在运行", onRemove = { draft = draft.deactivateGame() })
            }
            NovexTextActionRow(
                if (draft.configuration.activeInteractiveFiction == null) "选择文游" else "更换文游",
                R.drawable.ic_phosphor_puzzle_piece,
                onClick = { picker = ConversationPicker.GAME },
            )
        }

        NovexEditorSection(
            header = "管理挂载",
            footer = "挂载表示本对话要查看或编辑这些作品。它与背景注入独立，同一张卡可同时出现。",
        ) {
            draft.configuration.managedSubjects.forEachIndexed { index, subject ->
                ConversationSubjectRow(
                    labels[subject.subject]?.label ?: subject.subject.fallbackLabel(),
                    "${labels[subject.subject]?.kindLabel ?: subject.subject.kind.displayName()} · " +
                        if (subject.access == ManagedAccess.EDIT) "可编辑" else "只读",
                    onClick = { managedAction = subject.subject },
                    onRemove = { draft = draft.unmount(subject.subject) },
                )
                if (index < draft.configuration.managedSubjects.lastIndex) {
                    NovexDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
            NovexTextActionRow("挂载世界、角色或文游", onClick = { picker = ConversationPicker.MANAGED })
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
        NovexSelectionSheet(
            title = active.pickerTitle(),
            actions = pickerActions(active, options, draft, gameSnapshots) { updated -> draft = updated },
            onDismissRequest = { picker = null },
        )
    }
    managedAction?.let { address ->
        val current = draft.configuration.managedSubjects.firstOrNull { it.subject == address }
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
                add(
                    NovexSelectionAction("移除挂载", R.drawable.ic_phosphor_trash) {
                        draft = draft.unmount(address)
                    },
                )
            },
        )
    }
    error?.let { message -> NovexNoticeDialog("操作失败", message) { error = null } }
}

private fun pickerActions(
    picker: ConversationPicker,
    options: List<ConversationContentOption>,
    draft: NovexConversationEditorDraftState,
    games: Map<String, ActiveInteractiveFictionSnapshot>,
    update: (NovexConversationEditorDraftState) -> Unit,
): List<NovexSelectionAction> = when (picker) {
    ConversationPicker.ANSWER -> listOf(
        NovexSelectionAction("Nova · 通用人格", R.drawable.ic_phosphor_sparkle) {
            update(draft.setAnswerIdentity(AnswerIdentity.Nova))
        },
    ) + options.filter { it.address.kind == NovexContentKind.CHARACTER_VERSION }.map { option ->
        NovexSelectionAction(option.label, R.drawable.ic_phosphor_puzzle_piece) {
            update(draft.setAnswerIdentity(AnswerIdentity.CharacterVersion(option.address.id)))
        }
    }
    ConversationPicker.BACKGROUND -> options
        .filter { it.address.kind == NovexContentKind.WORLD || it.address.kind == NovexContentKind.CHARACTER_VERSION }
        .filterNot { option -> draft.configuration.backgroundSettings.any { it.subject == option.address } }
        .map { option ->
            NovexSelectionAction("${option.kindLabel} · ${option.label}") {
                update(draft.addBackground(option.address))
            }
        }
    ConversationPicker.GAME -> options.filter { it.address.kind == NovexContentKind.INTERACTIVE_FICTION }.map { option ->
        NovexSelectionAction(option.label, R.drawable.ic_phosphor_puzzle_piece) {
            games[option.address.id]?.let { update(draft.activateGame(it)) }
        }
    }
    ConversationPicker.MANAGED -> options
        .filterNot { option -> draft.configuration.managedSubjects.any { it.subject == option.address } }
        .map { option ->
            NovexSelectionAction("${option.kindLabel} · ${option.label}") {
                update(draft.mount(option.address, ManagedAccess.EDIT))
            }
        }
}

private fun ConversationPicker.pickerTitle(): String = when (this) {
    ConversationPicker.ANSWER -> "选择回答身份"
    ConversationPicker.BACKGROUND -> "添加背景设定"
    ConversationPicker.GAME -> "选择活动文游"
    ConversationPicker.MANAGED -> "添加管理挂载"
}

@Composable
private fun ConversationSubjectRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    onRemove: () -> Unit,
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable(onClick = onRemove)) {
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
