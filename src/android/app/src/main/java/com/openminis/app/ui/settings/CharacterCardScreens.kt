package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import androidx.core.content.FileProvider
import com.openminis.app.data.character.CharacterCard
import com.openminis.app.data.character.CharacterCardStore
import com.openminis.app.data.character.PlayerPersona
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CharacterHubScreen(
    onBack: () -> Unit,
    onEditCharacter: (String?) -> Unit,
    onEditPersona: (String?) -> Unit,
    onStartCharacter: (String) -> Unit,
) {
    val context = LocalContext.current
    CharacterCardStore.initialize(context)
    val characters by CharacterCardStore.characters.collectAsState()
    val personas by CharacterCardStore.personas.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    CharacterCardStore.importCharacter(context, reader.readText())
                } ?: error("无法读取角色卡")
            }.onFailure { error = it.message ?: "导入失败" }
        }
    }

    SettingsScaffold(
        title = "角色与身份",
        onBack = onBack,
        actions = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("导入角色卡") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            importLauncher.launch("application/json")
                        },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEditCharacter(null) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("创建角色") },
            )
        },
    ) {
        SettingsSection(
            header = "玩家身份",
            footer = "玩家身份决定你在故事中是谁；普通聊天仍可不选择身份。",
        ) {
            if (personas.isEmpty()) {
                EmptyLibraryRow("还没有玩家身份", "创建身份", { onEditPersona(null) })
            } else {
                personas.forEach { persona ->
                    LibraryRow(
                        imagePath = persona.avatarPath,
                        title = persona.name,
                        subtitle = buildString {
                            if (persona.isDefault) append("默认身份")
                            if (persona.description.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(persona.description)
                            }
                        },
                        onClick = { onEditPersona(persona.id) },
                    )
                }
                OutlinedButton(
                    onClick = { onEditPersona(null) },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("创建玩家身份")
                }
            }
        }

        SettingsSection(
            header = "角色库",
            footer = "每次新对话会保存角色卡快照；以后修改角色卡不会改变旧对话。",
        ) {
            if (characters.isEmpty()) {
                EmptyLibraryRow("还没有角色卡", "创建第一个角色", { onEditCharacter(null) })
            } else {
                characters.forEach { card ->
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        LibraryRow(
                            imagePath = card.avatarPath,
                            title = card.name,
                            subtitle = card.summary.ifBlank { card.personality },
                            onClick = { onEditCharacter(card.id) },
                            outerPadding = false,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = { shareCharacterCard(context, card) },
                                modifier = Modifier.weight(0.8f),
                            ) {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("导出")
                            }
                            OutlinedButton(
                                onClick = { onEditCharacter(card.id) },
                                modifier = Modifier.weight(1f),
                            ) { Text("编辑") }
                            Button(
                                onClick = { onStartCharacter(card.id) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("开始对话")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(88.dp))
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("角色卡导入失败") },
            text = { Text(it) },
            confirmButton = { Button(onClick = { error = null }) { Text("知道了") } },
        )
    }
}

@Composable
fun CharacterEditorScreen(cardId: String?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(cardId) { CharacterCardStore.character(context, cardId) }
    val now = remember { System.currentTimeMillis() }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var summary by remember { mutableStateOf(existing?.summary.orEmpty()) }
    var personality by remember { mutableStateOf(existing?.personality.orEmpty()) }
    var background by remember { mutableStateOf(existing?.background.orEmpty()) }
    var scenario by remember { mutableStateOf(existing?.scenario.orEmpty()) }
    var greeting by remember { mutableStateOf(existing?.greeting.orEmpty()) }
    var exampleDialogue by remember { mutableStateOf(existing?.exampleDialogue.orEmpty()) }
    var avatarPath by remember { mutableStateOf(existing?.avatarPath) }
    var coverPath by remember { mutableStateOf(existing?.coverPath) }
    var defaultBackgroundPath by remember { mutableStateOf(existing?.defaultBackgroundPath) }
    var showDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val avatarPicker = imagePicker("character-avatar") { avatarPath = it }
    val coverPicker = imagePicker("character-cover") { coverPath = it }
    val backgroundPicker = imagePicker("character-background") { defaultBackgroundPath = it }

    SettingsScaffold(
        title = if (existing == null) "创建角色" else "编辑角色",
        onBack = onBack,
        actions = {
            if (existing != null) {
                IconButton(onClick = { showDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除角色")
                }
            }
        },
    ) {
        SettingsSection(header = "视觉形象") {
            ImageField("角色头像", avatarPath, true) { avatarPicker() }
            ImageField("卡片封面", coverPath, false) { coverPicker() }
            ImageField("默认对话背景", defaultBackgroundPath, false) { backgroundPicker() }
        }
        SettingsSection(header = "基本资料") {
            EditorField("名称", name, { name = it }, singleLine = true)
            EditorField("一句话简介", summary, { summary = it })
            EditorField("人格与说话方式", personality, { personality = it }, minLines = 4)
            EditorField("角色背景", background, { background = it }, minLines = 4)
            EditorField("默认场景", scenario, { scenario = it }, minLines = 3)
            EditorField("开场白", greeting, { greeting = it }, minLines = 3)
            EditorField("示例对话", exampleDialogue, { exampleDialogue = it }, minLines = 4)
        }
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            CharacterCardStore.saveCharacter(
                                context,
                                CharacterCard(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    name = name,
                                    summary = summary,
                                    personality = personality,
                                    background = background,
                                    scenario = scenario,
                                    greeting = greeting,
                                    exampleDialogue = exampleDialogue,
                                    avatarPath = avatarPath,
                                    coverPath = coverPath,
                                    defaultBackgroundPath = defaultBackgroundPath,
                                    createdAt = existing?.createdAt ?: now,
                                    updatedAt = now,
                                ),
                            )
                        }
                    }.onSuccess { onSaved() }.onFailure { error = it.message ?: "保存失败" }
                }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("保存角色卡") }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除角色卡？") },
            text = { Text("已经创建的对话仍保留角色快照，不会随角色卡一起删除。") },
            confirmButton = {
                Button(onClick = {
                    existing?.let { CharacterCardStore.deleteCharacter(context, it.id) }
                    onSaved()
                }) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
    error?.let { SaveErrorDialog(it) { error = null } }
}

@Composable
fun PersonaEditorScreen(personaId: String?, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(personaId) { CharacterCardStore.persona(context, personaId) }
    val now = remember { System.currentTimeMillis() }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var relationship by remember { mutableStateOf(existing?.relationship.orEmpty()) }
    var preferredAddress by remember { mutableStateOf(existing?.preferredAddress.orEmpty()) }
    var avatarPath by remember { mutableStateOf(existing?.avatarPath) }
    var isDefault by remember { mutableStateOf(existing?.isDefault ?: false) }
    var showDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val avatarPicker = imagePicker("persona-avatar") { avatarPath = it }

    SettingsScaffold(
        title = if (existing == null) "创建玩家身份" else "编辑玩家身份",
        onBack = onBack,
        actions = {
            if (existing != null) IconButton(onClick = { showDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "删除身份")
            }
        },
    ) {
        SettingsSection(header = "玩家形象") {
            ImageField("玩家头像", avatarPath, true) { avatarPicker() }
        }
        SettingsSection(header = "身份资料") {
            EditorField("名称", name, { name = it }, singleLine = true)
            EditorField("身份描述", description, { description = it }, minLines = 3)
            EditorField("与角色的关系", relationship, { relationship = it }, minLines = 2)
            EditorField("角色如何称呼你", preferredAddress, { preferredAddress = it }, singleLine = true)
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("设为默认身份", fontWeight = FontWeight.Medium)
                    Text("新建角色对话时优先选择", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = isDefault, onCheckedChange = { isDefault = it })
            }
        }
        Button(
            onClick = {
                runCatching {
                    CharacterCardStore.savePersona(
                        context,
                        PlayerPersona(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            name = name,
                            description = description,
                            relationship = relationship,
                            preferredAddress = preferredAddress,
                            avatarPath = avatarPath,
                            isDefault = isDefault,
                            createdAt = existing?.createdAt ?: now,
                            updatedAt = now,
                        ),
                    )
                }.onSuccess { onSaved() }.onFailure { error = it.message ?: "保存失败" }
            },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text("保存玩家身份") }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除玩家身份？") },
            text = { Text("旧对话仍会保留这个身份的快照。") },
            confirmButton = {
                Button(onClick = {
                    existing?.let { CharacterCardStore.deletePersona(context, it.id) }
                    onSaved()
                }) { Text("删除") }
            },
            dismissButton = { OutlinedButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
    error?.let { SaveErrorDialog(it) { error = null } }
}

@Composable
fun StartCharacterChatScreen(
    characterId: String,
    onBack: () -> Unit,
    onCreateDraft: (String) -> Unit,
    onCreatePersona: () -> Unit,
) {
    val context = LocalContext.current
    val card = remember(characterId) { CharacterCardStore.character(context, characterId) }
    val personas by CharacterCardStore.personas.collectAsState()
    var selectedPersonaId by remember(personas) {
        mutableStateOf(personas.firstOrNull { it.isDefault }?.id ?: personas.firstOrNull()?.id)
    }
    SettingsScaffold(title = "开始角色对话", onBack = onBack) {
        if (card == null) {
            Text("角色卡不存在或已删除", modifier = Modifier.padding(24.dp))
            return@SettingsScaffold
        }
        SettingsSection(header = "本次角色") {
            LibraryRow(card.avatarPath, card.name, card.summary.ifBlank { card.scenario }, {})
            if (card.defaultBackgroundPath != null) {
                AsyncImage(
                    model = File(card.defaultBackgroundPath),
                    contentDescription = "默认背景预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(150.dp).padding(12.dp).clip(RoundedCornerShape(14.dp)),
                )
            }
        }
        SettingsSection(header = "选择玩家身份") {
            if (personas.isEmpty()) {
                EmptyLibraryRow("尚未创建玩家身份", "现在创建", onCreatePersona)
            } else {
                personas.forEach { persona ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedPersonaId = persona.id }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selectedPersonaId == persona.id, onClick = { selectedPersonaId = persona.id })
                        ProfileImage(persona.avatarPath, true)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(persona.name, fontWeight = FontWeight.SemiBold)
                            Text(persona.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Button(
            onClick = {
                val draft = buildString {
                    append("__new__").append(UUID.randomUUID())
                    append("__char__").append(card.id)
                    selectedPersonaId?.let { append("__persona__").append(it) }
                }
                onCreateDraft(draft)
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("进入对话")
        }
    }
}

@Composable
private fun LibraryRow(
    imagePath: String?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    outerPadding: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .then(if (outerPadding) Modifier.padding(horizontal = 12.dp, vertical = 10.dp) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileImage(imagePath, true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileImage(path: String?, circle: Boolean) {
    val shape = if (circle) CircleShape else RoundedCornerShape(12.dp)
    if (!path.isNullOrBlank() && File(path).exists()) {
        AsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(54.dp).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier.size(54.dp).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Person, contentDescription = null) }
    }
}

@Composable
private fun EmptyLibraryRow(text: String, button: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
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
private fun ImageField(label: String, path: String?, circle: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileImage(path, circle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(if (path == null) "点击选择图片" else "点击更换图片", style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Default.Photo, contentDescription = null)
    }
}

@Composable
private fun imagePicker(kind: String, onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) runCatching { CharacterCardStore.copyMedia(context, uri, kind) }.onSuccess(onPicked)
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

@Composable
private fun SaveErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存失败") },
        text = { Text(message) },
        confirmButton = { Button(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun shareCharacterCard(context: android.content.Context, card: CharacterCard) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = card.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "character" }
        val file = File(dir, "$safeName.novex-card.json")
        file.writeText(card.toJson().toString(2))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出角色卡"))
    }.onFailure {
        android.widget.Toast.makeText(context, it.message ?: "导出失败", android.widget.Toast.LENGTH_SHORT).show()
    }
}
