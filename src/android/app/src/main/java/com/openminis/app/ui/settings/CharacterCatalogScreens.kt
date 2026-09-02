package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.openminis.app.MinisApp
import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterCatalogRepository
import com.openminis.app.data.character.CharacterCustomAttribute
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterLibraryDocumentCodec
import com.openminis.app.data.character.CharacterLibraryService
import com.openminis.app.data.character.CharacterRelationship
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleRepository
import com.openminis.app.data.character.ManagedMediaAssetStore
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetRepository
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.SillyTavernCardParser
import com.openminis.app.data.character.WorldEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class CharacterLibraryRow(
    val character: CharacterEntity,
    val original: CharacterVersionEntity,
    val profile: CharacterVersionProfile,
    val avatar: MediaAssetEntity?,
    val variantCount: Int,
)

private data class CharacterDetailData(
    val aggregate: CharacterAggregate,
    val worlds: Map<String, List<WorldEntity>>,
    val media: Map<String, Map<MediaAssetSlot, MediaAssetEntity>>,
)

@Composable
fun CatalogCharacterLibraryScreen(
    onBack: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val moduleRepository = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
    val mediaRepository = rememberMediaRepository(app)
    val mediaStore = rememberManagedMediaStore(context, mediaRepository)
    val service = remember(app) { CharacterLibraryService(catalog, moduleRepository, mediaRepository) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<CharacterLibraryRow>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        rows = catalog.listCharacters().mapNotNull { character ->
            val aggregate = catalog.character(character.id) ?: return@mapNotNull null
            CharacterLibraryRow(
                character = character,
                original = aggregate.original,
                profile = CharacterVersionProfile.fromJson(aggregate.original.profileJson, character.name),
                avatar = mediaRepository.assetFor(
                    ModuleOwner.characterVersion(aggregate.original.id),
                    MediaAssetSlot.CHARACTER_AVATAR,
                ),
                variantCount = aggregate.variants.size,
            )
        }
        loaded = true
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取角色卡文件")
                    val mime = context.contentResolver.getType(uri)
                    val name = context.displayName(uri)
                    val structured = if (!name.orEmpty().endsWith(".png", true)) {
                        runCatching { CharacterLibraryDocumentCodec.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    } else null
                    if (structured != null) Triple(structured, null, "Novex 结构化数据") else {
                        val preview = SillyTavernCardParser.parse(bytes, mime, name)
                        Triple(CharacterLibraryDocumentCodec.fromTavernCard(preview.card), preview.avatarPng, preview.sourceLabel)
                    }
                }
                val created = service.importDocument(result.first)
                result.second?.let { avatarBytes ->
                    val asset = mediaStore.import(avatarBytes, "image/png")
                    mediaRepository.attach(
                        ModuleOwner.characterVersion(created.original.id),
                        MediaAssetSlot.CHARACTER_AVATAR,
                        asset.id,
                    )
                }
                created.character.id
            }.onSuccess { characterId ->
                importing = false
                refresh++
                onOpenCharacter(characterId)
            }.onFailure {
                importing = false
                error = it.message ?: "导入失败"
            }
        }
    }
    SettingsScaffold(
        title = "角色库",
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !importing,
                onClick = { importer.launch(arrayOf("image/png", "application/json", "text/json", "text/plain")) },
            ) { Icon(Icons.Default.Upload, contentDescription = "导入酒馆角色卡或 Novex 结构化数据") }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreateCharacter,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("创建角色") },
            )
        },
    ) {
        when {
            !loaded || importing -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rows.isEmpty() -> CharacterEmptyRow("角色库还是空的", "创建角色", onCreateCharacter)
            else -> rows.forEach { row ->
                Card(
                    onClick = { onOpenCharacter(row.character.id) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CharacterAvatar(row.avatar?.managedPath, row.character.name)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(row.character.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "本体${if (row.variantCount > 0) " · ${row.variantCount} 个分身" else ""}",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            row.profile.summary.takeIf(String::isNotBlank)?.let { summary ->
                                Text(
                                    summary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }
    error?.let { CharacterErrorDialog(it) { error = null } }
}

@Composable
fun CatalogCharacterDetailScreen(
    characterId: String,
    onBack: () -> Unit,
    onEditVersion: (String) -> Unit,
    onCreateVariant: () -> Unit,
    onDuplicated: (String) -> Unit,
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val moduleRepository = remember(app) { ContentModuleRepository(app.database.contentModuleDao()) }
    val mediaRepository = rememberMediaRepository(app)
    val service = remember(app) { CharacterLibraryService(catalog, moduleRepository, mediaRepository) }
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<CharacterDetailData?>(null) }
    var missing by remember { mutableStateOf(false) }
    var selectedVersionId by rememberSaveable(characterId) { mutableStateOf<String?>(null) }
    var confirmDeleteRoot by remember { mutableStateOf(false) }
    var confirmDeleteVariant by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var confirmSharedEdit by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var creatorNotice by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(characterId, refresh) {
        val aggregate = catalog.character(characterId)
        if (aggregate == null) {
            missing = true
            data = null
        } else {
            missing = false
            selectedVersionId = selectedVersionId?.takeIf { id -> aggregate.allVersions.any { it.id == id } }
                ?: aggregate.original.id
            data = CharacterDetailData(
                aggregate = aggregate,
                worlds = aggregate.allVersions.associate { it.id to catalog.worldsForVersion(it.id) },
                media = aggregate.allVersions.associate { version ->
                    val owner = ModuleOwner.characterVersion(version.id)
                    version.id to listOf(
                        MediaAssetSlot.CHARACTER_AVATAR,
                        MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
                    ).mapNotNull { slot -> mediaRepository.assetFor(owner, slot)?.let { slot to it } }.toMap()
                },
            )
        }
    }
    val current = data
    val selected = current?.aggregate?.allVersions?.firstOrNull { it.id == selectedVersionId }
    val profile = selected?.let { CharacterVersionProfile.fromJson(it.profileJson, current.aggregate.character.name) }
    SettingsScaffold(
        title = current?.aggregate?.character?.name ?: "角色",
        onBack = onBack,
        actions = {
            if (current != null) {
                IconButton(onClick = { creatorNotice = true }) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = "帮我创作")
                }
                IconButton(onClick = {
                    scope.launch {
                        runCatching { service.exportDocument(characterId) }
                            .onSuccess { shareCharacterDocument(context, it.name, CharacterLibraryDocumentCodec.encode(it).toString(2)) }
                            .onFailure { error = it.message }
                    }
                }) { Icon(Icons.Default.Share, contentDescription = "导出结构化角色数据") }
                IconButton(onClick = {
                    scope.launch {
                        runCatching { service.duplicateCharacter(characterId) }
                            .onSuccess { onDuplicated(it.character.id) }
                            .onFailure { error = it.message }
                    }
                }) { Icon(Icons.Default.ContentCopy, contentDescription = "复制角色") }
                IconButton(onClick = { confirmDeleteRoot = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "删除角色")
                }
            }
        },
    ) {
        when {
            missing -> Text("角色不存在或已删除", modifier = Modifier.padding(24.dp))
            current == null || selected == null || profile == null -> Box(
                Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                CharacterHero(
                    name = profile.name.ifBlank { current.aggregate.character.name },
                    label = selected.label,
                    avatarPath = current.media[selected.id]?.get(MediaAssetSlot.CHARACTER_AVATAR)?.managedPath,
                    backgroundPath = current.media[selected.id]?.get(MediaAssetSlot.CHARACTER_PAGE_BACKGROUND)?.managedPath,
                )
                SettingsSection(header = "本体与分身") {
                    current.aggregate.allVersions.forEach { version ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedVersionId = version.id }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (version.kind == CharacterVersionKind.ORIGINAL) "本体" else version.label,
                                    fontWeight = if (version.id == selected.id) FontWeight.Bold else FontWeight.Normal,
                                )
                                val usedWorlds = current.worlds[version.id].orEmpty()
                                Text(
                                    if (usedWorlds.isEmpty()) "尚未加入世界" else usedWorlds.joinToString(" · ") { it.name },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (version.id == selected.id) Text("当前", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    TextButton(onClick = onCreateVariant, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("创建分身")
                    }
                }
                SettingsSection(header = "基本信息") {
                    val facts = visibleCharacterFacts(profile)
                    if (facts.isEmpty()) Text(
                        "还没有补充可选信息。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    ) else facts.forEach { fact -> CharacterInfoRow(fact.label, fact.value) }
                    TextButton(
                        onClick = {
                            if (current.worlds[selected.id].orEmpty().isEmpty()) onEditVersion(selected.id)
                            else confirmSharedEdit = selected
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null)
                        Text("编辑这个版本")
                    }
                }
                if (profile.customAttributes.isNotEmpty()) SettingsSection(header = "自定义属性") {
                    profile.customAttributes.forEach { CharacterInfoRow(it.name, it.value) }
                }
                if (profile.relationships.isNotEmpty()) SettingsSection(header = "原创角色关系") {
                    profile.relationships.forEach { relation ->
                        CharacterInfoRow(
                            relation.characterName,
                            listOf(relation.relationship, relation.description).filter(String::isNotBlank).joinToString(" · "),
                        )
                    }
                }
                SharedContentModuleEditor(
                    owner = ModuleOwner.characterVersion(selected.id),
                    header = "角色模块",
                    footer = "模块在复制时复制一次，之后不会与来源持续同步。",
                    onOpenModule = onOpenModule,
                )
                if (selected.kind == CharacterVersionKind.VARIANT) {
                    OutlinedButton(
                        onClick = { confirmDeleteVariant = selected },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text("删除这个分身") }
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (confirmDeleteRoot && current != null) AlertDialog(
        onDismissRequest = { confirmDeleteRoot = false },
        title = { Text("删除整个角色？") },
        text = { Text("本体、全部分身及其世界关联会一并删除；已有对话中的快照仍保留。") },
        confirmButton = {
            Button(onClick = {
                confirmDeleteRoot = false
                scope.launch { service.deleteCharacter(characterId); onBack() }
            }) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = { confirmDeleteRoot = false }) { Text("取消") } },
    )
    confirmDeleteVariant?.let { version ->
        AlertDialog(
            onDismissRequest = { confirmDeleteVariant = null },
            title = { Text("删除${version.label}？") },
            text = { Text("这个分身会从所有世界移除，本体和其他分身不受影响。") },
            confirmButton = {
                Button(onClick = {
                    confirmDeleteVariant = null
                    scope.launch { service.deleteVariant(version.id); selectedVersionId = null; refresh++ }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteVariant = null }) { Text("取消") } },
        )
    }
    confirmSharedEdit?.let { version ->
        val affectedWorlds = current?.worlds?.get(version.id).orEmpty()
        AlertDialog(
            onDismissRequest = { confirmSharedEdit = null },
            title = { Text("编辑共享版本？") },
            text = {
                Text("保存后的修改会同步显示在：${affectedWorlds.joinToString("、") { it.name }}。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSharedEdit = null
                    onEditVersion(version.id)
                }) { Text("继续编辑") }
            },
            dismissButton = { TextButton(onClick = { confirmSharedEdit = null }) { Text("取消") } },
        )
    }
    error?.let { CharacterErrorDialog(it) { error = null } }
    if (creatorNotice) AlertDialog(
        onDismissRequest = { creatorNotice = false },
        title = { Text("帮我创作") },
        text = { Text("入口已保留，人工智能管理与写入本轮暂不开放，点击不会修改角色内容。") },
        confirmButton = { TextButton(onClick = { creatorNotice = false }) { Text("知道了") } },
    )
}

@Composable
fun CatalogCharacterEditorScreen(
    characterId: String?,
    versionId: String?,
    worldId: String?,
    createVariant: Boolean,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val catalog = remember(app) { CharacterCatalogRepository(app.database.characterCatalogDao()) }
    val mediaRepository = rememberMediaRepository(app)
    val mediaStore = rememberManagedMediaStore(context, mediaRepository)
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(characterId == null) }
    var sourceAggregate by remember { mutableStateOf<CharacterAggregate?>(null) }
    var sourceVersion by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var rootName by rememberSaveable(characterId) { mutableStateOf("") }
    var label by rememberSaveable(versionId, createVariant) { mutableStateOf(if (createVariant) "新分身" else "本体") }
    var name by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var tags by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var gender by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var age by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var race by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var occupation by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var summary by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var attributes by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var relationships by rememberSaveable(versionId, createVariant) { mutableStateOf("") }
    var expanded by rememberSaveable(versionId, createVariant) { mutableStateOf(false) }
    var media by remember { mutableStateOf<Map<MediaAssetSlot, MediaAssetEntity>>(emptyMap()) }
    var pendingSlot by remember { mutableStateOf<MediaAssetSlot?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val owner = sourceVersion?.id?.let { ModuleOwner.characterVersion(it) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null && owner != null) scope.launch {
            runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取图片")
                }
                val asset = mediaStore.import(bytes, context.contentResolver.getType(uri) ?: "image/*")
                mediaRepository.attach(owner, slot, asset.id)
                media = media + (slot to asset)
            }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(characterId, versionId, createVariant) {
        if (characterId != null) {
            val aggregate = catalog.character(characterId)
            sourceAggregate = aggregate
            val version = when {
                versionId != null -> aggregate?.allVersions?.firstOrNull { it.id == versionId }
                createVariant -> aggregate?.original
                else -> aggregate?.original
            }
            sourceVersion = if (createVariant) null else version
            if (aggregate != null && version != null) {
                val profile = CharacterVersionProfile.fromJson(version.profileJson, aggregate.character.name)
                rootName = aggregate.character.name
                label = if (createVariant) "新分身" else version.label
                name = profile.name.ifBlank { aggregate.character.name }
                tags = profile.tags.joinToString("、")
                gender = profile.gender
                age = profile.age
                race = profile.race
                occupation = profile.occupation
                summary = profile.summary
                attributes = profile.customAttributes.joinToString("\n") { "${it.name}：${it.value}" }
                relationships = profile.relationships.joinToString("\n") {
                    listOf(it.characterName, it.relationship, it.description).joinToString("｜")
                }
                if (!createVariant) {
                    val loadedOwner = ModuleOwner.characterVersion(version.id)
                    media = listOf(
                        MediaAssetSlot.CHARACTER_AVATAR,
                        MediaAssetSlot.CHARACTER_PAGE_BACKGROUND,
                    ).mapNotNull { slot -> mediaRepository.assetFor(loadedOwner, slot)?.let { slot to it } }.toMap()
                }
            }
            loaded = true
        }
    }
    fun save() {
        if (saving || name.isBlank()) return
        saving = true
        scope.launch {
            runCatching {
                val base = sourceVersion?.let { CharacterVersionProfile.fromJson(it.profileJson, name) }
                    ?: sourceAggregate?.original?.let { CharacterVersionProfile.fromJson(it.profileJson, name) }
                    ?: CharacterVersionProfile(name)
                val profile = base.copy(
                    name = name,
                    tags = tags.split(Regex("[、,，\\n]")).map(String::trim).filter(String::isNotEmpty),
                    gender = gender,
                    age = age,
                    race = race,
                    occupation = occupation,
                    summary = summary,
                    customAttributes = parseCharacterAttributes(attributes),
                    relationships = parseCharacterRelationships(relationships),
                )
                val saved = when {
                    sourceAggregate == null -> catalog.createCharacter(
                        name = rootName.ifBlank { name },
                        originalProfileJson = profile.toJson(),
                    ).also { aggregate ->
                        if (worldId != null) {
                            catalog.addVersionToWorld(
                                worldId,
                                aggregate.original.id,
                                catalog.versionsForWorld(worldId).size,
                            )
                        }
                    }
                    createVariant -> {
                        val variant = catalog.createVariant(
                            characterId = sourceAggregate!!.character.id,
                            label = label,
                            profileJson = profile.toJson(),
                        )
                        CharacterAggregate(sourceAggregate!!.character, sourceAggregate!!.original, sourceAggregate!!.variants + variant)
                    }
                    else -> {
                        val version = catalog.saveVersion(sourceVersion!!.copy(label = label, profileJson = profile.toJson()))
                        if (version.kind == CharacterVersionKind.ORIGINAL) {
                            catalog.saveCharacter(sourceAggregate!!.character.copy(name = rootName.ifBlank { name }))
                        }
                        sourceAggregate!!
                    }
                }
                saved.character.id
            }.onSuccess(onSaved).onFailure { error = it.message; saving = false }
        }
    }
    SettingsScaffold(
        title = when {
            characterId == null -> "创建角色"
            createVariant -> "创建分身"
            else -> "编辑角色版本"
        },
        onBack = onBack,
        actions = { TextButton(onClick = ::save, enabled = loaded && name.isNotBlank() && !saving) {
            Text(if (saving) "保存中" else "保存")
        } },
    ) {
        if (!loaded) Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else {
            SettingsSection(header = "必要信息") {
                OutlinedTextField(
                    value = rootName,
                    onValueChange = { updated ->
                        val previous = rootName
                        rootName = updated
                        if (characterId == null && (name.isBlank() || name == previous)) name = updated
                    },
                    label = { Text("角色库名称") },
                    singleLine = true,
                    enabled = sourceVersion?.kind != CharacterVersionKind.VARIANT && !createVariant,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("版本名称（本体或分身名称）") },
                    singleLine = true,
                    enabled = createVariant || sourceVersion?.kind == CharacterVersionKind.VARIANT,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(if (expanded) "收起可选内容" else "展开可选内容") }
            if (expanded) {
                SettingsSection(
                    header = "头像与主页背景（可选）",
                    footer = if (owner == null) "保存后即可添加图片。" else "图片由共享资源管理保护引用。",
                ) {
                    listOf(
                        MediaAssetSlot.CHARACTER_AVATAR to "头像",
                        MediaAssetSlot.CHARACTER_PAGE_BACKGROUND to "主页背景",
                    ).forEach { (slot, title) ->
                        CharacterImageRow(
                            title = title,
                            path = media[slot]?.managedPath,
                            enabled = owner != null,
                            onPick = {
                                pendingSlot = slot
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onRemove = {
                                if (owner != null) scope.launch {
                                    mediaRepository.detach(owner, slot)
                                    media = media - slot
                                }
                            },
                        )
                    }
                }
                SettingsSection(header = "基本信息（可选）") {
                    CharacterEditorField("标签（顿号或逗号分隔）", tags) { tags = it }
                    CharacterEditorField("性别", gender) { gender = it }
                    CharacterEditorField("年龄", age) { age = it }
                    CharacterEditorField("种族", race) { race = it }
                    CharacterEditorField("职业", occupation) { occupation = it }
                    CharacterEditorField("简介", summary, minLines = 4) { summary = it }
                }
                SettingsSection(header = "自定义属性（可选）", footer = "每行填写“属性名：内容”。") {
                    CharacterEditorField("自定义属性", attributes, minLines = 5) { attributes = it }
                }
                SettingsSection(header = "原创角色关系（可选）", footer = "每行填写“角色名｜关系｜说明”。") {
                    CharacterEditorField("原创角色关系", relationships, minLines = 5) { relationships = it }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    error?.let { CharacterErrorDialog(it) { error = null } }
}

@Composable
private fun CharacterHero(name: String, label: String, avatarPath: String?, backgroundPath: String?) {
    val avatarFile = avatarPath.existingMediaFile()
    val backgroundFile = backgroundPath.existingMediaFile()
    if (avatarFile == null && backgroundFile == null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text(name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            Text(
                "头像和主页背景都可留空",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        backgroundFile?.let { file ->
            AsyncImage(
                model = file,
                contentDescription = "$name 主页背景",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CharacterAvatar(avatarFile?.absolutePath, name)
            Column(Modifier.padding(start = 12.dp)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun CharacterAvatar(path: String?, name: String) {
    path.existingMediaFile()?.let { file ->
        AsyncImage(
            model = file,
            contentDescription = "$name 头像",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(CircleShape),
        )
    } ?: Box(
        Modifier.size(64.dp).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Person, contentDescription = null) }
}

@Composable
private fun CharacterInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, modifier = Modifier.weight(.32f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "未填写" }, modifier = Modifier.weight(.68f))
    }
}

@Composable
private fun CharacterImageRow(
    title: String,
    path: String?,
    enabled: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        path.existingMediaFile()?.let { file ->
            AsyncImage(
                model = file,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        Text(title, modifier = Modifier.weight(1f).padding(start = if (path == null) 0.dp else 12.dp))
        TextButton(onClick = onPick, enabled = enabled) { Text(if (path == null) "选择" else "更换") }
        if (path != null) TextButton(onClick = onRemove) { Text("移除") }
    }
}

@Composable
private fun CharacterEditorField(
    label: String,
    value: String,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = minLines,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CharacterEmptyRow(text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun CharacterErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("操作失败") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

internal fun parseCharacterAttributes(raw: String): List<CharacterCustomAttribute> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split(Regex("[：:]"), limit = 2).map(String::trim)
    if (parts.firstOrNull().isNullOrBlank()) null else CharacterCustomAttribute(parts[0], parts.getOrElse(1) { "" })
}.toList()

internal fun parseCharacterRelationships(raw: String): List<CharacterRelationship> = raw.lineSequence().mapNotNull { line ->
    val parts = line.split('｜').map(String::trim)
    if (parts.firstOrNull().isNullOrBlank()) null else CharacterRelationship(
        characterName = parts[0],
        relationship = parts.getOrElse(1) { "" },
        description = parts.drop(2).joinToString("｜"),
    )
}.toList()

private fun Context.displayName(uri: Uri): String? = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
}

private fun shareCharacterDocument(context: Context, name: String, content: String) {
    runCatching {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "character" }
        val file = File(directory, "$safeName.novex-character.json").apply { writeText(content) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出角色结构化数据"))
    }.onFailure {
        android.widget.Toast.makeText(context, it.message ?: "导出失败", android.widget.Toast.LENGTH_SHORT).show()
    }
}
