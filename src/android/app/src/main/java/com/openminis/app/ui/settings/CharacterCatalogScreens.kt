package com.openminis.app.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openminis.app.R
import com.openminis.app.data.character.CharacterAggregate
import com.openminis.app.data.character.CharacterCustomAttribute
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterLibraryDocumentCodec
import com.openminis.app.data.character.CharacterRelationship
import com.openminis.app.data.character.CharacterVersionEntity
import com.openminis.app.data.character.CharacterVersionKind
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.MediaAssetEntity
import com.openminis.app.data.character.MediaAssetSlot
import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.NovexCardPackageCodec
import com.openminis.app.data.character.NovexCardTransferParser
import com.openminis.app.data.character.NovexValidatedCardImport
import com.openminis.app.data.character.SillyTavernCardParser
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.requireCharacter
import com.openminis.app.novex.domain.requireMedia
import com.openminis.app.novex.domain.requireNativeCard
import com.openminis.app.novex.domain.requireNativeImport
import com.openminis.app.novex.domain.requireVersion
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.NovexDetailScaffold
import com.openminis.app.ui.novex.NovexTopAction
import com.openminis.app.ui.novex.toNovexPresentation
import com.openminis.app.ui.novex.rememberNovexWorkspace
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

private sealed interface CharacterImportOutcome {
    data class NativePreview(val preview: NovexValidatedCardImport) : CharacterImportOutcome
    data class Imported(val characterId: String) : CharacterImportOutcome
}

private data class CharacterDetailData(
    val aggregate: CharacterAggregate,
    val worlds: Map<String, List<WorldEntity>>,
    val media: Map<String, Map<MediaAssetSlot, MediaAssetEntity>>,
)

internal data class CharacterPageData(
    val rootName: String,
    val version: CharacterVersionEntity,
    val profile: CharacterVersionProfile,
    val worlds: List<WorldEntity>,
    val media: Map<MediaAssetSlot, MediaAssetEntity>,
    val modules: List<ContentModuleEntity>,
    val moduleImages: Map<String, MediaAssetEntity>,
    val moduleItemImages: Map<String, Map<String, MediaAssetEntity>>,
    val variantCount: Int,
)

@Composable
fun CatalogCharacterLibraryScreen(
    onBack: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<CharacterLibraryRow>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var nativeImportPreview by remember { mutableStateOf<NovexValidatedCardImport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        rows = novex.characters().map { card ->
            val aggregate = card.character
            val character = aggregate.character
            CharacterLibraryRow(
                character = character,
                original = aggregate.original,
                profile = CharacterVersionProfile.fromJson(aggregate.original.profileJson, character.name),
                avatar = card.avatar,
                variantCount = aggregate.variants.size,
            )
        }
        loaded = true
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            importing = true
            runCatching {
                val source = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取角色卡文件")
                    val mime = context.contentResolver.getType(uri)
                    val name = context.displayName(uri)
                    Triple(bytes, mime, name)
                }
                val (bytes, mime, name) = source
                val native = name.orEmpty().endsWith(".novexcharacter", true) ||
                    (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
                if (native) {
                    val preview = NovexCardPackageCodec.decode(bytes)
                    require(preview.kind == NovexCardKind.CHARACTER) { "请选择 .novexcharacter 角色卡" }
                    return@runCatching CharacterImportOutcome.NativePreview(
                        NovexCardTransferParser.parse(preview),
                    )
                }
                val result = withContext(Dispatchers.Default) {
                    val structured = if (!name.orEmpty().endsWith(".png", true)) {
                        runCatching { CharacterLibraryDocumentCodec.decode(bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    } else null
                    if (structured != null) Triple(structured, null, "Novex 结构化数据") else {
                        val preview = SillyTavernCardParser.parse(bytes, mime, name)
                        Triple(CharacterLibraryDocumentCodec.fromTavernCard(preview.card), preview.avatarPng, preview.sourceLabel)
                    }
                }
                val created = novex.apply(NovexCommand.ImportCharacter(result.first)).requireCharacter()
                result.second?.let { avatarBytes ->
                    novex.apply(
                        NovexCommand.AttachImage(
                            ModuleOwner.characterVersion(created.original.id),
                            MediaAssetSlot.CHARACTER_AVATAR,
                            avatarBytes,
                            "image/png",
                        ),
                    )
                }
                CharacterImportOutcome.Imported(created.character.id)
            }.onSuccess { result ->
                importing = false
                when (result) {
                    is CharacterImportOutcome.NativePreview -> nativeImportPreview = result.preview
                    is CharacterImportOutcome.Imported -> {
                        refresh++
                        onOpenCharacter(result.characterId)
                    }
                }
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
                onClick = {
                    importer.launch(
                        arrayOf(
                            "application/zip",
                            "application/octet-stream",
                            "image/png",
                            "application/json",
                            "text/json",
                            "text/plain",
                        ),
                    )
                },
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
    nativeImportPreview?.let { preview ->
        NovexCardImportPreviewDialog(
            preview = preview,
            importing = importing,
            onDismiss = { nativeImportPreview = null },
            onConfirm = {
                scope.launch {
                    importing = true
                    runCatching {
                        novex.apply(NovexCommand.ImportNativeCard(preview)).requireNativeImport()
                    }.onSuccess { imported ->
                        nativeImportPreview = null
                        importing = false
                        refresh++
                        onOpenCharacter(imported.localId)
                    }.onFailure {
                        importing = false
                        error = it.message ?: "角色卡导入失败"
                    }
                }
            },
        )
    }
    error?.let { CharacterErrorDialog(it) { error = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val novex = rememberNovexWorkspace()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<CharacterDetailData?>(null) }
    var modulesByVersion by remember { mutableStateOf<Map<String, List<ContentModuleEntity>>>(emptyMap()) }
    var moduleImagesByVersion by remember {
        mutableStateOf<Map<String, Map<String, MediaAssetEntity>>>(emptyMap())
    }
    var moduleItemImagesByVersion by remember {
        mutableStateOf<Map<String, Map<String, Map<String, MediaAssetEntity>>>>(emptyMap())
    }
    var missing by remember { mutableStateOf(false) }
    var selectedVersionId by rememberSaveable(characterId) { mutableStateOf<String?>(null) }
    var confirmDeleteRoot by remember { mutableStateOf(false) }
    var confirmDeleteVariant by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var confirmSharedEdit by remember { mutableStateOf<CharacterVersionEntity?>(null) }
    var creatorNotice by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(characterId, refresh) {
        val snapshot = novex.character(characterId)
        if (snapshot == null) {
            missing = true
            data = null
        } else {
            val aggregate = snapshot.character
            missing = false
            selectedVersionId = selectedVersionId?.takeIf { id -> aggregate.allVersions.any { it.id == id } }
                ?: aggregate.original.id
            data = CharacterDetailData(
                aggregate = aggregate,
                worlds = snapshot.worldsByVersion,
                media = snapshot.mediaByVersion,
            )
            modulesByVersion = snapshot.modulesByVersion
            moduleImagesByVersion = snapshot.modulesByVersion.mapValues { (_, modules) ->
                modules.mapNotNull { module -> snapshot.moduleImages[module.id]?.let { module.id to it } }.toMap()
            }
            moduleItemImagesByVersion = snapshot.modulesByVersion.mapValues { (_, modules) ->
                modules.mapNotNull { module ->
                    snapshot.moduleItemImages[module.id]?.let { module.id to it }
                }.toMap()
            }
        }
    }
    val current = data
    val selected = current?.aggregate?.allVersions?.firstOrNull { it.id == selectedVersionId }
    val profile = selected?.let { CharacterVersionProfile.fromJson(it.profileJson, current.aggregate.character.name) }
    var versionSheet by remember { mutableStateOf(false) }
    val page = if (current != null && selected != null && profile != null) CharacterPageData(
        rootName = current.aggregate.character.name,
        version = selected,
        profile = profile,
        worlds = current.worlds[selected.id].orEmpty(),
        media = current.media[selected.id].orEmpty(),
        modules = modulesByVersion[selected.id].orEmpty(),
        moduleImages = moduleImagesByVersion[selected.id].orEmpty(),
        moduleItemImages = moduleItemImagesByVersion[selected.id].orEmpty(),
        variantCount = current.aggregate.variants.size,
    ) else null
    NovexDetailScaffold(
        title = current?.aggregate?.character?.name ?: "角色",
        onBack = onBack,
        actions = {
            if (page != null) {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_sparkle,
                    contentDescription = "帮我创作",
                    label = "帮我创作",
                    onClick = { creatorNotice = true },
                )
            }
        },
    ) {
        when {
            missing -> Text("角色不存在或已删除", modifier = Modifier.padding(24.dp))
            page == null -> Box(
                Modifier.fillMaxWidth().padding(48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            else -> {
                CharacterPrimaryContent(
                    data = page,
                    onChooseVersion = { versionSheet = true },
                    onOpenModule = onOpenModule,
                    onEdit = {
                        if (page.worlds.isEmpty()) onEditVersion(page.version.id)
                        else confirmSharedEdit = page.version
                    },
                )
                CharacterManagementActions(
                    isVariant = page.version.kind == CharacterVersionKind.VARIANT,
                    onCreateVariant = onCreateVariant,
                    onExport = {
                        scope.launch {
                            runCatching {
                                novex.apply(NovexCommand.ExportNativeCharacter(characterId)).requireNativeCard()
                            }
                                .onSuccess { shareNovexCardPackage(context, it) }
                                .onFailure { error = it.message }
                        }
                    },
                    onDuplicate = {
                        scope.launch {
                            runCatching {
                                novex.apply(NovexCommand.DuplicateCharacter(characterId)).requireCharacter()
                            }
                                .onSuccess { onDuplicated(it.character.id) }
                                .onFailure { error = it.message }
                        }
                    },
                    onDelete = {
                        if (page.version.kind == CharacterVersionKind.VARIANT) confirmDeleteVariant = page.version
                        else confirmDeleteRoot = true
                    },
                )
                Spacer(Modifier.height(40.dp))
            }
        }
    }
    if (versionSheet && current != null && selected != null) ModalBottomSheet(
        onDismissRequest = { versionSheet = false },
    ) {
        Text(
            "选择角色版本",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
        current.aggregate.allVersions.forEach { version ->
            CharacterVersionChoice(
                version = version,
                selected = version.id == selected.id,
                worlds = current.worlds[version.id].orEmpty(),
                avatar = current.media[version.id]?.get(MediaAssetSlot.CHARACTER_AVATAR),
                onClick = {
                    selectedVersionId = version.id
                    versionSheet = false
                },
            )
        }
        TextButton(onClick = {
            versionSheet = false
            onCreateVariant()
        }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Icon(painterResource(R.drawable.ic_phosphor_plus), contentDescription = null)
            Text("创建分身", modifier = Modifier.padding(start = 6.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
    if (confirmDeleteRoot && current != null) AlertDialog(
        onDismissRequest = { confirmDeleteRoot = false },
        title = { Text("删除整个角色？") },
        text = { Text("本体、全部分身及其世界关联会一并删除；已有对话中的快照仍保留。") },
        confirmButton = {
            Button(onClick = {
                confirmDeleteRoot = false
                scope.launch { novex.apply(NovexCommand.DeleteCharacter(characterId)); onBack() }
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
                    scope.launch {
                        novex.apply(NovexCommand.DeleteVariant(version.id))
                        selectedVersionId = null
                        refresh++
                    }
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
    onOpenModule: (String) -> Unit,
) {
    val context = LocalContext.current
    val novex = rememberNovexWorkspace()
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
    var previewData by remember { mutableStateOf<CharacterPageData?>(null) }
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
                val asset = novex.apply(
                    NovexCommand.AttachImage(
                        owner,
                        slot,
                        bytes,
                        context.contentResolver.getType(uri) ?: "image/*",
                    ),
                ).requireMedia()
                media = media + (slot to asset)
            }.onFailure { error = it.message }
        }
    }
    LaunchedEffect(characterId, versionId, createVariant) {
        if (characterId != null) {
            val snapshot = novex.character(characterId)
            val aggregate = snapshot?.character
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
                    media = snapshot.mediaByVersion[version.id].orEmpty()
                }
            }
            loaded = true
        }
    }
    fun draftProfile(): CharacterVersionProfile {
        val base = sourceVersion?.let { CharacterVersionProfile.fromJson(it.profileJson, name) }
            ?: sourceAggregate?.original?.let { CharacterVersionProfile.fromJson(it.profileJson, name) }
            ?: CharacterVersionProfile(name)
        return base.copy(
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
    }
    fun preview() {
        if (!loaded || name.isBlank()) return
        scope.launch {
            val now = System.currentTimeMillis()
            val draftVersion = sourceVersion?.copy(label = label, profileJson = draftProfile().toJson())
                ?: CharacterVersionEntity(
                    id = "preview-version",
                    characterId = sourceAggregate?.character?.id ?: "preview-character",
                    kind = if (createVariant) CharacterVersionKind.VARIANT else CharacterVersionKind.ORIGINAL,
                    label = label,
                    profileJson = draftProfile().toJson(),
                    createdAt = now,
                    updatedAt = now,
                )
            val draftOwner = sourceVersion?.id?.let { ModuleOwner.characterVersion(it) }
            val savedSnapshot = characterId?.let { novex.character(it) }
            val modules = draftOwner?.let { novex.modules(it).modules }.orEmpty()
            previewData = CharacterPageData(
                rootName = rootName.ifBlank { name },
                version = draftVersion,
                profile = draftProfile(),
                worlds = sourceVersion?.id?.let { savedSnapshot?.worldsByVersion?.get(it) }.orEmpty(),
                media = media,
                modules = modules,
                moduleImages = modules.mapNotNull { module ->
                    savedSnapshot?.moduleImages?.get(module.id)?.let { module.id to it }
                }.toMap(),
                moduleItemImages = modules.mapNotNull { module ->
                    savedSnapshot?.moduleItemImages?.get(module.id)?.let { module.id to it }
                }.toMap(),
                variantCount = (sourceAggregate?.variants?.size ?: 0) + if (createVariant) 1 else 0,
            )
        }
    }
    fun save() {
        if (saving || name.isBlank()) return
        saving = true
        scope.launch {
            runCatching {
                val profile = draftProfile()
                val saved = when {
                    sourceAggregate == null -> novex.apply(
                        NovexCommand.CreateCharacter(
                            name = rootName.ifBlank { name },
                            profileJson = profile.toJson(),
                        ),
                    ).requireCharacter().also { aggregate ->
                        if (worldId != null) {
                            val position = novex.world(worldId)?.versions?.size ?: 0
                            novex.apply(
                                NovexCommand.LinkCharacterVersion(
                                    worldId,
                                    aggregate.original.id,
                                    position,
                                ),
                            )
                        }
                    }
                    createVariant -> {
                        val variant = novex.apply(NovexCommand.CreateVariant(
                            characterId = sourceAggregate!!.character.id,
                            label = label,
                            profileJson = profile.toJson(),
                        )).requireVersion()
                        CharacterAggregate(sourceAggregate!!.character, sourceAggregate!!.original, sourceAggregate!!.variants + variant)
                    }
                    else -> {
                        novex.apply(
                            NovexCommand.SaveCharacterVersion(
                                characterId = sourceAggregate!!.character.id,
                                versionId = sourceVersion!!.id,
                                rootName = rootName.ifBlank { name },
                                label = label,
                                profileJson = profile.toJson(),
                            ),
                        ).requireCharacter()
                    }
                }
                saved.character.id
            }.onSuccess(onSaved).onFailure { error = it.message; saving = false }
        }
    }
    previewData?.let { draft ->
        NovexDetailScaffold(
            title = "角色草稿预览",
            onBack = { previewData = null },
            actions = {
                NovexTopAction(
                    icon = R.drawable.ic_phosphor_eye,
                    contentDescription = "返回编辑",
                    onClick = { previewData = null },
                )
            },
        ) {
            CharacterPrimaryContent(draft, onChooseVersion = null, onOpenModule = null)
            Spacer(Modifier.height(32.dp))
        }
        return
    }
    SettingsScaffold(
        title = when {
            characterId == null -> "创建角色"
            createVariant -> "创建分身"
            else -> "编辑角色版本"
        },
        onBack = onBack,
        centerTitle = true,
        actions = {
            IconButton(onClick = ::preview, enabled = loaded && name.isNotBlank()) {
                Icon(
                    painterResource(R.drawable.ic_phosphor_eye),
                    contentDescription = "预览角色草稿",
                )
            }
            TextButton(onClick = ::save, enabled = loaded && name.isNotBlank() && !saving) {
                Text(if (saving) "保存中" else "保存")
            }
        },
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
                                    novex.apply(NovexCommand.DetachImage(owner, slot))
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
            if (owner != null) {
                SharedContentModuleEditor(
                    owner = owner,
                    header = "内容模块",
                    footer = "模块可展开编辑并调整顺序；复制模块后不会与来源持续同步。",
                    onOpenModule = onOpenModule,
                )
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    error?.let { CharacterErrorDialog(it) { error = null } }
}

@Composable
internal fun CharacterPrimaryContent(
    data: CharacterPageData,
    onChooseVersion: (() -> Unit)?,
    onOpenModule: ((String) -> Unit)?,
    onEdit: (() -> Unit)? = null,
    mediaModels: Map<MediaAssetSlot, Any?> = emptyMap(),
) {
    CharacterHero(data, onChooseVersion, onEdit, mediaModels)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        characterOverviewRows(data.profile).forEach { row ->
            CharacterCompactContentRow(row.title, row.summary)
        }
        data.modules.forEach { module ->
            val presentation = module.toNovexPresentation()
            CharacterCompactContentRow(
                title = presentation.title,
                summary = presentation.summary,
                onClick = onOpenModule?.let { open -> { open(module.id) } },
            )
        }
        if (data.worlds.isNotEmpty()) {
            CharacterCompactContentRow(
                title = "关联世界",
                summary = data.worlds.joinToString(" · ") { it.name },
            )
        }
    }
}

@Composable
private fun CharacterHero(
    data: CharacterPageData,
    onChooseVersion: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    mediaModels: Map<MediaAssetSlot, Any?>,
) {
    val name = data.profile.name.ifBlank { data.rootName }
    val avatarModel = mediaModels[MediaAssetSlot.CHARACTER_AVATAR]
        ?: data.media[MediaAssetSlot.CHARACTER_AVATAR]?.managedPath.existingMediaFile()
    val backgroundModel = mediaModels[MediaAssetSlot.CHARACTER_PAGE_BACKGROUND]
        ?: data.media[MediaAssetSlot.CHARACTER_PAGE_BACKGROUND]?.managedPath.existingMediaFile()
    val representativeModel = backgroundModel ?: avatarModel
    Column(Modifier.fillMaxWidth().background(NovexColors.Surface).padding(horizontal = 24.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            NovexArtwork(
                kind = NovexArtworkKind.CHARACTER,
                seed = data.version.id,
                imageModel = representativeModel,
                contentDescription = "$name 代表图",
                modifier = Modifier.width(276.dp).height(216.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = NovexColors.Text,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(
                    Modifier.padding(top = 5.dp)
                        .then(if (onChooseVersion == null) Modifier else Modifier.clickable(onClick = onChooseVersion)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        characterVersionSelectorLabel(data.version.kind, data.version.label, data.variantCount),
                        color = NovexColors.SecondaryText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (onChooseVersion != null) Icon(
                        painterResource(R.drawable.ic_phosphor_caret_right),
                        contentDescription = "选择角色版本",
                        tint = NovexColors.SecondaryText,
                        modifier = Modifier.padding(start = 3.dp).size(14.dp),
                    )
                }
            }
        }
        if (onEdit != null) Row(
            Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            OutlinedButton(
                onClick = onEdit,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.width(144.dp).height(44.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_phosphor_pencil_simple),
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Text("编辑", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CharacterCompactContentRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Row(
        modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = NovexColors.Text,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(104.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            summary,
            color = NovexColors.SecondaryText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) Icon(
            painterResource(R.drawable.ic_phosphor_caret_right),
            contentDescription = "打开$title",
            tint = NovexColors.SecondaryText,
            modifier = Modifier.padding(start = 8.dp).size(17.dp),
        )
    }
    HorizontalDivider(color = NovexColors.Divider)
}

@Composable
private fun CharacterLabeledBlock(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            title,
            color = NovexColors.Text,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        content()
    }
}

@Composable
private fun CharacterVersionChoice(
    version: CharacterVersionEntity,
    selected: Boolean,
    worlds: List<WorldEntity>,
    avatar: MediaAssetEntity?,
    onClick: () -> Unit,
) {
    val profile = CharacterVersionProfile.fromJson(version.profileJson, version.label)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovexArtwork(
            kind = NovexArtworkKind.CHARACTER,
            seed = version.id,
            imageModel = avatar?.managedPath.existingMediaFile(),
            contentDescription = "${profile.name}头像",
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (version.kind == CharacterVersionKind.ORIGINAL) "本体 · ${profile.name}" else version.label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 12.dp),
            )
            Text(
                if (worlds.isEmpty()) "尚未加入世界" else worlds.joinToString(" · ") { it.name },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, top = 3.dp),
            )
        }
        if (selected) Text("当前", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CharacterManagementActions(
    isVariant: Boolean,
    onCreateVariant: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    CharacterLabeledBlock("管理") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onCreateVariant) { Text("创建分身") }
            TextButton(onClick = onExport) { Text("导出") }
            TextButton(onClick = onDuplicate) { Text("复制") }
            TextButton(onClick = onDelete) { Text(if (isVariant) "删除分身" else "删除角色") }
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
