package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import com.openminis.app.ui.novex.AlertDialog
import com.openminis.app.ui.novex.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.openminis.app.ui.novex.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.openminis.app.ui.novex.OutlinedButton
import com.openminis.app.ui.novex.OutlinedTextField
import com.openminis.app.ui.novex.NovexCheckToggle
import androidx.compose.material3.Text
import com.openminis.app.ui.novex.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.openminis.app.data.character.CharacterCard
import com.openminis.app.data.character.CharacterCardImportPreview
import com.openminis.app.data.character.CharacterCardStore
import com.openminis.app.data.character.PlayerPersona
import com.openminis.app.data.character.SillyTavernCardExporter
import com.openminis.app.data.character.SillyTavernCardParser
import com.openminis.app.data.character.StoryWorld
import com.openminis.app.data.db.ChatSessionEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun WorldLibraryScreen(
    onBack: () -> Unit,
    onOpenWorld: (String) -> Unit,
    onCreateWorld: () -> Unit,
) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val worlds by CharacterCardStore.worlds.collectAsState()
    val characters by CharacterCardStore.characters.collectAsState()
    SettingsScaffold(
        title = "我的世界",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateWorld,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("创建世界") },
            )
        },
    ) {
        if (worlds.isEmpty()) {
            EmptyCard3Row("还没有世界", "创建世界", onCreateWorld)
        } else {
            worlds.forEach { world ->
                val count = characters.count { it.worldId == world.id }
                Card(
                    onClick = { onOpenWorld(world.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    world.backgroundPath.existingFile()?.let { image ->
                        AsyncImage(
                            model = image,
                            contentDescription = "${world.name}背景",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(132.dp),
                        )
                    }
                    Column(Modifier.padding(16.dp)) {
                        Text(world.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "$count 张角色卡",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (world.description.isNotBlank()) Text(
                            world.description,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
fun StoryWorldDetailScreen(
    worldId: String,
    sessions: List<ChatSessionEntity>,
    onBack: () -> Unit,
    onEditWorld: () -> Unit,
    onEditPersona: (String?) -> Unit,
    onCreateCharacter: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onStartWorldNovax: (String?) -> Unit,
) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val worlds by CharacterCardStore.worlds.collectAsState()
    val allCharacters by CharacterCardStore.characters.collectAsState()
    val allPersonas by CharacterCardStore.personas.collectAsState()
    val world = worlds.firstOrNull { it.id == worldId }
    val characters = allCharacters.filter { it.worldId == worldId }
    val personas = allPersonas.filter { it.worldId == worldId }
    val worldSessions = sessions.filter { it.worldSnapshotJson.worldIdOrNull() == worldId }
    var addMenu by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<CharacterCardImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取角色卡文件")
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    }
                    SillyTavernCardParser.parse(bytes, context.contentResolver.getType(uri), name)
                }
            }.onSuccess { importPreview = it }.onFailure { error = it.message ?: "导入失败" }
        }
    }
    LaunchedEffect(worldId) { CharacterCardStore.selectWorld(context, worldId) }

    SettingsScaffold(title = world?.name ?: "世界", onBack = onBack) {
        if (world == null) {
            Text("世界不存在或已删除", modifier = Modifier.padding(24.dp))
            return@SettingsScaffold
        }
        SettingsSection(header = "世界观") {
            Card3SummaryRow(
                imagePath = world.backgroundPath,
                title = world.name,
                subtitle = world.description.ifBlank { "尚未填写世界规则" },
                onClick = onEditWorld,
                circle = false,
            )
        }
        SettingsSection(header = "玩家身份", footer = "所有字段均可选；未填写时以普通玩家身份进入。") {
            if (personas.isEmpty()) {
                EmptyCard3Row("尚未设置玩家身份", "添加", { onEditPersona(null) })
            } else {
                personas.forEach { persona ->
                    Card3SummaryRow(
                        imagePath = persona.avatarPath,
                        title = persona.name.ifBlank { "玩家" },
                        subtitle = persona.description.ifBlank { if (persona.isDefault) "默认身份" else "未填写身份说明" },
                        onClick = { onEditPersona(persona.id) },
                    )
                }
                TextButton(onClick = { onEditPersona(null) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("添加玩家身份")
                }
            }
        }
        SettingsSection(header = "角色卡", footer = "角色卡只需要名称；其他内容可稍后补充。") {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${characters.size} 张角色卡", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, contentDescription = "添加角色卡") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("创建空白角色卡") },
                            onClick = { addMenu = false; onCreateCharacter() },
                        )
                        DropdownMenuItem(
                            text = { Text("导入酒馆角色卡") },
                            onClick = {
                                addMenu = false
                                importLauncher.launch(arrayOf("image/png", "application/json", "text/json", "text/plain"))
                            },
                        )
                    }
                }
            }
            if (characters.isEmpty()) {
                EmptyCard3Row("还没有角色卡", "创建", onCreateCharacter)
            } else characters.forEach { card ->
                Card3SummaryRow(
                    imagePath = card.avatarPath,
                    title = card.name,
                    subtitle = card.summary.ifBlank { "点击查看角色对话" },
                    onClick = { onOpenCharacter(card.id) },
                )
            }
        }
        SettingsSection(header = "Nova 世界助手") {
            Card3ActionRow(
                title = "与 Nova 讨论这个世界",
                subtitle = "读取世界观和玩家身份，但不扮演角色卡。",
                onClick = { onStartWorldNovax(personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id) },
            )
        }
        SettingsSection(header = "这个世界的最近对话") {
            if (worldSessions.isEmpty()) {
                Text("还没有对话", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else worldSessions.take(20).forEach { session ->
                val roleName = session.characterSnapshotJson.characterNameOrNull() ?: "Nova"
                Card3ActionRow(
                    title = session.title?.ifBlank { null } ?: "新对话",
                    subtitle = "$roleName · ${session.lastMessage.orEmpty().ifBlank { "暂无消息" }}",
                    onClick = { onOpenSession(session.id) },
                )
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("导入 ${preview.card.name}") },
            text = { Text("将作为新角色卡加入“${world?.name}”。角色记忆从空白开始。") },
            confirmButton = {
                Button(onClick = {
                    runCatching { CharacterCardStore.saveImportedCharacter(context, preview, worldId) }
                        .onFailure { error = it.message ?: "导入失败" }
                    importPreview = null
                }) { Text("确认导入") }
            },
            dismissButton = { TextButton(onClick = { importPreview = null }) { Text("取消") } },
        )
    }
    error?.let { Card3ErrorDialog(it) { error = null } }
}

@Composable
fun CharacterDetailScreen(
    characterId: String,
    sessions: List<ChatSessionEntity>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val characters by CharacterCardStore.characters.collectAsState()
    val card = characters.firstOrNull { it.id == characterId }
    val roleSessions = sessions.filter { it.characterId == characterId }.sortedByDescending { it.updatedAt }
    SettingsScaffold(
        title = card?.name ?: "角色卡",
        onBack = onBack,
        actions = {
            if (card != null) {
                IconButton(onClick = { shareStandardCard3Json(context, card) }) {
                    Icon(Icons.Default.Upload, contentDescription = "导出角色卡 JSON")
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑角色卡") }
            }
        },
        floatingActionButton = {
            if (card != null) ExtendedFloatingActionButton(
                onClick = onNewChat,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建对话") },
            )
        },
    ) {
        if (card == null) {
            Text("角色卡不存在或已删除", modifier = Modifier.padding(24.dp))
            return@SettingsScaffold
        }
        SettingsSection(header = "角色卡") {
            Card3SummaryRow(card.avatarPath, card.name, card.summary.ifBlank { card.background.ifBlank { "尚未填写角色说明" } }, onEdit)
        }
        SettingsSection(header = "对话", footer = "一张角色卡可以拥有多个互不覆盖的对话。") {
            if (roleSessions.isEmpty()) {
                EmptyCard3Row("还没有对话", "新建对话", onNewChat)
            } else roleSessions.forEach { session ->
                Card3ActionRow(
                    title = session.title?.ifBlank { null } ?: "新对话",
                    subtitle = session.lastMessage.orEmpty().ifBlank { "暂无消息" },
                    onClick = { onOpenSession(session.id) },
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
fun Card3WorldEditorScreen(worldId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val existing = remember(worldId) { CharacterCardStore.world(context, worldId) }
    val prefs = remember { context.getSharedPreferences("novex_card3_drafts", android.content.Context.MODE_PRIVATE) }
    val draftKey = "world:${worldId ?: "new"}"
    val draft = remember { prefs.getString(draftKey, null)?.let { runCatching { StoryWorld.fromJson(JSONObject(it)) }.getOrNull() } }
    val seed = draft ?: existing
    val now = remember { System.currentTimeMillis() }
    var name by remember { mutableStateOf(seed?.name ?: "我的世界") }
    var description by remember { mutableStateOf(seed?.description.orEmpty()) }
    var backgroundPath by remember { mutableStateOf(seed?.backgroundPath) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = card3ImagePicker("world-background") { backgroundPath = it }
    fun value() = StoryWorld(existing?.id ?: draft?.id ?: UUID.randomUUID().toString(), name, description, backgroundPath, existing?.createdAt ?: now, now)
    LaunchedEffect(name, description, backgroundPath) {
        delay(350)
        prefs.edit().putString(draftKey, value().toJson().toString()).apply()
    }
    fun save() = runCatching { CharacterCardStore.saveWorld(context, value()) }
        .onSuccess { prefs.edit().remove(draftKey).apply(); onSaved(it.id) }
        .onFailure { error = it.message ?: "保存失败" }
    SettingsScaffold(
        title = if (existing == null) "创建世界" else "编辑世界观",
        onBack = onBack,
        actions = { TextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("保存") } },
    ) {
        SettingsSection(header = "世界") {
            Card3ImageField("世界背景", backgroundPath, false, picker)
            Card3EditorField("世界名称", name, { name = it }, true)
        }
        SettingsSection(header = "可选世界观") {
            Card3EditorField("时代、地点、规则与背景事件", description, { description = it }, minLines = 12)
        }
    }
    error?.let { Card3ErrorDialog(it) { error = null } }
}

@Composable
fun Card3CharacterEditorScreen(worldId: String, cardId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val existing = remember(cardId) { CharacterCardStore.character(context, cardId) }
    val prefs = remember { context.getSharedPreferences("novex_card3_drafts", android.content.Context.MODE_PRIVATE) }
    val draftKey = "character:${cardId ?: "new:$worldId"}"
    val draft = remember { prefs.getString(draftKey, null)?.let { runCatching { CharacterCard.fromJson(JSONObject(it)) }.getOrNull() } }
    val seed = draft ?: existing
    val now = remember { System.currentTimeMillis() }
    var name by remember { mutableStateOf(seed?.name.orEmpty()) }
    var summary by remember { mutableStateOf(seed?.summary.orEmpty()) }
    var personality by remember { mutableStateOf(seed?.personality.orEmpty()) }
    var background by remember { mutableStateOf(seed?.background.orEmpty()) }
    var scenario by remember { mutableStateOf(seed?.scenario.orEmpty()) }
    var greeting by remember { mutableStateOf(seed?.greeting.orEmpty()) }
    var exampleDialogue by remember { mutableStateOf(seed?.exampleDialogue.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(seed?.systemPrompt.orEmpty()) }
    var postHistory by remember { mutableStateOf(seed?.postHistoryInstructions.orEmpty()) }
    var knowledge by remember { mutableStateOf(seed?.knowledge.orEmpty()) }
    var alternateGreetings by remember { mutableStateOf(seed?.alternateGreetings?.joinToString("\n---\n").orEmpty()) }
    var creatorNotes by remember { mutableStateOf(seed?.creatorNotes.orEmpty()) }
    var tags by remember { mutableStateOf(seed?.tags?.joinToString("、").orEmpty()) }
    var contentBoundary by remember { mutableStateOf(seed?.contentBoundary.orEmpty()) }
    var avatarPath by remember { mutableStateOf(seed?.avatarPath) }
    var coverPath by remember { mutableStateOf(seed?.coverPath) }
    var chatBackground by remember { mutableStateOf(seed?.defaultBackgroundPath) }
    var choicesTool by remember { mutableStateOf(seed?.allowedTools?.contains("present_choices") == true) }
    var imageTool by remember { mutableStateOf(seed?.allowedTools?.contains("generate_image") == true) }
    var expanded by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val avatarPicker = card3ImagePicker("character-avatar") { avatarPath = it }
    val coverPicker = card3ImagePicker("character-cover") { coverPath = it }
    val backgroundPicker = card3ImagePicker("character-chat-background") { chatBackground = it }
    fun value() = CharacterCard(
        id = existing?.id ?: draft?.id ?: UUID.randomUUID().toString(), name = name, worldId = worldId,
        summary = summary, personality = personality, background = background, scenario = scenario,
        greeting = greeting, exampleDialogue = exampleDialogue, systemPrompt = systemPrompt,
        postHistoryInstructions = postHistory,
        alternateGreetings = alternateGreetings.split(Regex("\\n---\\n")).map(String::trim).filter(String::isNotEmpty),
        creatorNotes = creatorNotes,
        tags = tags.split(Regex("[、,，\\n]")).map(String::trim).filter(String::isNotEmpty),
        knowledge = knowledge,
        allowedTools = buildList { if (choicesTool) add("present_choices"); if (imageTool) add("generate_image") },
        contentBoundary = contentBoundary, sourceFormat = seed?.sourceFormat, avatarPath = avatarPath,
        coverPath = coverPath, defaultBackgroundPath = chatBackground,
        createdAt = existing?.createdAt ?: draft?.createdAt ?: now, updatedAt = now,
    )
    LaunchedEffect(name, summary, personality, background, scenario, greeting, exampleDialogue, systemPrompt, postHistory, knowledge, alternateGreetings, creatorNotes, tags, contentBoundary, avatarPath, coverPath, chatBackground, choicesTool, imageTool) {
        delay(350)
        prefs.edit().putString(draftKey, value().toJson().toString()).apply()
    }
    fun save() = runCatching { CharacterCardStore.saveCharacter(context, value()) }
        .onSuccess { prefs.edit().remove(draftKey).apply(); onSaved(it.id) }
        .onFailure { error = it.message ?: "保存失败" }

    SettingsScaffold(
        title = if (existing == null) "创建角色卡" else "编辑角色卡",
        onBack = onBack,
        actions = {
            TextButton(onClick = ::save, enabled = name.isNotBlank()) { Text("保存") }
            if (existing != null) IconButton(onClick = { showDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "删除角色卡")
            }
        },
    ) {
        SettingsSection(header = "角色卡") {
            Card3ImageField("角色头像（可选）", avatarPath, true, avatarPicker)
            Card3EditorField("角色名称", name, { name = it }, true)
        }
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(if (expanded) "收起可选项" else "展开可选项") }
        if (expanded) {
            SettingsSection(header = "角色资料（可选）") {
                Card3EditorField("一句话简介", summary, { summary = it })
                Card3EditorField("角色描述", background, { background = it }, minLines = 5)
                Card3EditorField("性格与说话方式", personality, { personality = it }, minLines = 4)
                Card3EditorField("当前场景", scenario, { scenario = it }, minLines = 3)
                Card3EditorField("开场白", greeting, { greeting = it }, minLines = 3)
                Card3EditorField("备用开场白（多条用单独一行 --- 分隔）", alternateGreetings, { alternateGreetings = it }, minLines = 4)
                Card3EditorField("示例对话", exampleDialogue, { exampleDialogue = it }, minLines = 4)
                Card3EditorField("标签（用逗号或顿号分隔）", tags, { tags = it }, minLines = 2)
                Card3EditorField("创作者备注", creatorNotes, { creatorNotes = it }, minLines = 3)
            }
            SettingsSection(header = "对话外观（可选）") {
                Card3ImageField("卡片封面", coverPath, false, coverPicker)
                Card3ImageField("角色专属对话背景", chatBackground, false, backgroundPicker)
            }
            SettingsSection(header = "内容和指令（可选）") {
                Card3EditorField("内容边界", contentBoundary, { contentBoundary = it }, minLines = 3)
                Card3EditorField("角色专属系统提示词", systemPrompt, { systemPrompt = it }, minLines = 3)
                Card3EditorField("历史后置指令", postHistory, { postHistory = it }, minLines = 3)
                Card3EditorField("角色世界书", knowledge, { knowledge = it }, minLines = 6)
            }
            SettingsSection(header = "角色工具（默认关闭）") {
                Card3SwitchRow("呈现剧情选项", choicesTool) { choicesTool = it }
                Card3SwitchRow("生成图片", imageTool) { imageTool = it }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (showDelete) AlertDialog(
        onDismissRequest = { showDelete = false },
        title = { Text("删除角色卡？") },
        text = { Text("已有对话仍保留角色快照，不会随角色卡一起删除。") },
        confirmButton = { Button(onClick = { existing?.let { CharacterCardStore.deleteCharacter(context, it.id) }; onBack() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
    )
    error?.let { Card3ErrorDialog(it) { error = null } }
}

@Composable
fun Card3PersonaEditorScreen(worldId: String, personaId: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val existing = remember(personaId) { CharacterCardStore.persona(context, personaId) }
    val prefs = remember { context.getSharedPreferences("novex_card3_drafts", android.content.Context.MODE_PRIVATE) }
    val draftKey = "persona:${personaId ?: "new:$worldId"}"
    val draft = remember {
        prefs.getString(draftKey, null)?.let {
            runCatching { PlayerPersona.fromJson(JSONObject(it)) }.getOrNull()
        }
    }
    val seed = draft ?: existing
    val now = remember { System.currentTimeMillis() }
    var name by remember { mutableStateOf(seed?.name.orEmpty()) }
    var description by remember { mutableStateOf(seed?.description.orEmpty()) }
    var appearance by remember { mutableStateOf(seed?.appearance.orEmpty()) }
    var abilities by remember { mutableStateOf(seed?.abilities.orEmpty()) }
    var personality by remember { mutableStateOf(seed?.personality.orEmpty()) }
    var relationship by remember { mutableStateOf(seed?.relationship.orEmpty()) }
    var address by remember { mutableStateOf(seed?.preferredAddress.orEmpty()) }
    var boundaries by remember { mutableStateOf(seed?.boundaries.orEmpty()) }
    var avatarPath by remember { mutableStateOf(seed?.avatarPath) }
    var isDefault by remember { mutableStateOf(seed?.isDefault ?: true) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = card3ImagePicker("player-avatar") { avatarPath = it }
    fun value() = PlayerPersona(
            id = existing?.id ?: draft?.id ?: UUID.randomUUID().toString(), name = name, worldId = worldId,
            description = description, appearance = appearance, abilities = abilities, personality = personality,
            relationship = relationship, preferredAddress = address, boundaries = boundaries,
            avatarPath = avatarPath, isDefault = isDefault,
            createdAt = existing?.createdAt ?: draft?.createdAt ?: now, updatedAt = now,
        )
    LaunchedEffect(name, description, appearance, abilities, personality, relationship, address, boundaries, avatarPath, isDefault) {
        delay(350)
        prefs.edit().putString(draftKey, value().toJson().toString()).apply()
    }
    fun save() = runCatching { CharacterCardStore.savePersona(context, value()) }
        .onSuccess { prefs.edit().remove(draftKey).apply(); onSaved(it.id) }
        .onFailure { error = it.message ?: "保存失败" }
    SettingsScaffold(
        title = if (existing == null) "添加玩家身份" else "编辑玩家身份",
        onBack = onBack,
        actions = { TextButton(onClick = ::save) { Text("保存") } },
    ) {
        SettingsSection(header = "玩家身份（全部可选）") {
            Card3ImageField("玩家头像", avatarPath, true, picker)
            Card3EditorField("名称", name, { name = it }, true)
            Card3SwitchRow("设为这个世界的默认身份", isDefault) { isDefault = it }
        }
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(if (expanded) "收起可选项" else "展开可选项")
        }
        if (expanded) SettingsSection(header = "身份资料（可选）") {
            Card3EditorField("身份描述", description, { description = it }, minLines = 3)
            Card3EditorField("外貌", appearance, { appearance = it }, minLines = 2)
            Card3EditorField("能力与限制", abilities, { abilities = it }, minLines = 2)
            Card3EditorField("性格倾向", personality, { personality = it }, minLines = 2)
            Card3EditorField("与角色的初始关系", relationship, { relationship = it }, minLines = 2)
            Card3EditorField("角色如何称呼你", address, { address = it }, true)
            Card3EditorField("禁止模型代替玩家决定的边界", boundaries, { boundaries = it }, minLines = 3)
        }
    }
    error?.let { Card3ErrorDialog(it) { error = null } }
}

@Composable
private fun Card3SummaryRow(imagePath: String?, title: String, subtitle: String, onClick: () -> Unit, circle: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card3ProfileImage(imagePath, circle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun Card3ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}

@Composable
private fun EmptyCard3Row(text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun Card3ProfileImage(path: String?, circle: Boolean) {
    val shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
    val file = path.existingFile()
    if (file != null) AsyncImage(
        model = file,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(56.dp).clip(shape),
    ) else Box(
        modifier = Modifier.size(56.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Person, contentDescription = null) }
}

@Composable
private fun Card3ImageField(label: String, path: String?, circle: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Card3ProfileImage(path, circle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(if (path == null) "点击选择图片" else "点击更换图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Card3EditorField(label: String, value: String, onChange: (String) -> Unit, singleLine: Boolean = false, minLines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Card3SwitchRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        NovexCheckToggle(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun card3ImagePicker(kind: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) runCatching { CharacterCardStore.copyMedia(context, uri, kind) }.onSuccess(onPicked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun Card3ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作失败") },
        text = { Text(message) },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun shareStandardCard3Json(context: android.content.Context, card: CharacterCard) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = card.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "character" }
        val file = File(dir, "$safeName.character-card-v2.json")
        file.writeText(SillyTavernCardExporter.exportV2(card).toString(2))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出角色卡 JSON"))
    }.onFailure {
        android.widget.Toast.makeText(context, it.message ?: "导出失败", android.widget.Toast.LENGTH_SHORT).show()
    }
}

private fun String?.existingFile(): File? = this?.let(::File)?.takeIf { it.exists() }

private fun String?.worldIdOrNull(): String? = this?.let {
    runCatching { JSONObject(it).optString("id").takeIf(String::isNotBlank) }.getOrNull()
}

private fun String?.characterNameOrNull(): String? = this?.let {
    runCatching { JSONObject(it).optString("name").takeIf(String::isNotBlank) }.getOrNull()
}
