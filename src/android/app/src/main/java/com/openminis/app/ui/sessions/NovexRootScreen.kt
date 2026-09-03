package com.openminis.app.ui.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.character.CharacterEntity
import com.openminis.app.data.character.CharacterVersionProfile
import com.openminis.app.data.character.NovexCardKind
import com.openminis.app.data.character.WorldEntity
import com.openminis.app.ui.novex.NovexArtwork
import com.openminis.app.ui.novex.NovexArtworkKind
import com.openminis.app.ui.novex.NovexColors
import com.openminis.app.ui.novex.rememberNovexWorkspace
import com.openminis.app.ui.settings.existingMediaFile
import com.openminis.app.ui.settings.rememberNovexNativeCardImporter
import kotlin.math.abs

private val NovexRootColors = NovexColors

private data class WorldRootRow(
    val world: WorldEntity,
    val imagePath: String?,
    val characterCount: Int,
    val moduleCount: Int,
)

private data class CharacterRootRow(
    val character: CharacterEntity,
    val profile: CharacterVersionProfile,
    val imagePath: String?,
    val variantCount: Int,
)

@Composable
fun NovexRootScreen(
    conversationContent: @Composable (
        onWorldsClick: () -> Unit,
        onRootNavigationVisibilityChange: (Boolean) -> Unit,
    ) -> Unit,
    onOpenWorld: (String) -> Unit,
    onCreateWorld: () -> Unit,
    onOpenCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
) {
    var selectedName by rememberSaveable { mutableStateOf(NovexRootSpace.CONVERSATIONS.name) }
    var dockExpanded by rememberSaveable { mutableStateOf(false) }
    var showRootDock by rememberSaveable { mutableStateOf(false) }
    val pageStateHolder = rememberSaveableStateHolder()
    val selected = NovexRootSpace.valueOf(selectedName)
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }
    val dockGestureWidth = with(density) { 244.dp.toPx() }
    val dockGestureHeight = with(density) { 96.dp.toPx() }

    fun select(destination: NovexRootSpace, expand: Boolean = true) {
        selectedName = destination.name
        showRootDock = true
        if (expand) dockExpanded = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NovexRootColors.Background)
            .pointerInput(
                selected,
                showRootDock,
                dockExpanded,
                swipeThreshold,
                dockGestureWidth,
                dockGestureHeight,
            ) {
                if (showRootDock) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        var totalX = 0f
                        var totalY = 0f
                        var event: androidx.compose.ui.input.pointer.PointerEvent
                        do {
                            event = awaitPointerEvent(PointerEventPass.Final)
                            event.changes.forEach { change ->
                                totalX += change.position.x - change.previousPosition.x
                                totalY += change.position.y - change.previousPosition.y
                            }
                        } while (event.changes.any { it.pressed })

                        val startedOnDock = isNovexRootDockHit(
                            x = down.position.x,
                            y = down.position.y,
                            pageWidth = size.width.toFloat(),
                            pageHeight = size.height.toFloat(),
                            dockWidth = dockGestureWidth,
                            dockHeight = dockGestureHeight,
                        )
                        val horizontalSwipe = abs(totalX) >= swipeThreshold && abs(totalX) > abs(totalY)
                        if (startedOnDock && horizontalSwipe) {
                            select(
                                novexRootSpaceAtPageX(
                                    x = down.position.x + totalX,
                                    pageWidth = size.width.toFloat(),
                                    dockWidth = dockGestureWidth,
                                ),
                            )
                        } else if (!startedOnDock) {
                            if (horizontalSwipe) {
                                val delta = if (totalX < 0f) 1 else -1
                                select(NovexRootNavigationState(selected).move(delta).selected)
                            } else if (
                                dockExpanded &&
                                abs(totalX) < viewConfiguration.touchSlop &&
                                abs(totalY) < viewConfiguration.touchSlop
                            ) {
                                dockExpanded = false
                            }
                        }
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            pageStateHolder.SaveableStateProvider(selected.name) {
                when (selected) {
                    NovexRootSpace.CONVERSATIONS -> conversationContent(
                        { select(NovexRootSpace.WORLDS) },
                        { visible ->
                            showRootDock = nextNovexRootDockVisibility(visible)
                        },
                    )

                    NovexRootSpace.WORLDS -> NovexWorldLibraryRoot(
                        onOpenWorld = onOpenWorld,
                        onCreateWorld = onCreateWorld,
                    )

                    NovexRootSpace.CHARACTERS -> NovexCharacterLibraryRoot(
                        onOpenCharacter = onOpenCharacter,
                        onCreateCharacter = onCreateCharacter,
                    )
                }
            }
        }

        if (showRootDock) {
            NovexRootDock(
                selected = selected,
                expanded = dockExpanded,
                onSelect =(::select),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun NovexRootDock(
    selected: NovexRootSpace,
    expanded: Boolean,
    onSelect: (NovexRootSpace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding by animateDpAsState(
        targetValue = if (expanded) 8.dp else 14.dp,
        animationSpec = tween(240),
        label = "根导航水平边距",
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(
            if (expanded) 4.dp else 12.dp,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .width(244.dp)
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
    ) {
        NovexRootSpace.entries.forEach { destination ->
            AnimatedContent(
                targetState = expanded || selected == destination,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "根导航形态",
            ) { showLabel ->
                if (showLabel) {
                    val label = when (destination) {
                        NovexRootSpace.CONVERSATIONS -> "会话"
                        NovexRootSpace.WORLDS -> "世界"
                        NovexRootSpace.CHARACTERS -> "角色"
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (selected == destination) NovexRootColors.PrimarySoft
                                else Color.Transparent,
                            )
                            .clickable { onSelect(destination) }
                            .padding(horizontal = 18.dp),
                    ) {
                        Text(
                            label,
                            color = if (selected == destination) NovexRootColors.Primary else NovexRootColors.Text,
                            fontSize = 15.sp,
                            fontWeight = if (selected == destination) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onSelect(destination) },
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NovexRootColors.Text),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NovexWorldLibraryRoot(
    onOpenWorld: (String) -> Unit,
    onCreateWorld: () -> Unit,
) {
    val novex = rememberNovexWorkspace()
    var rows by remember { mutableStateOf<List<WorldRootRow>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val importer = rememberNovexNativeCardImporter(NovexCardKind.WORLD) { importedId ->
        refresh++
        onOpenWorld(importedId)
    }

    LaunchedEffect(refresh) {
        rows = novex.worlds().map { card ->
            WorldRootRow(
                world = card.world,
                imagePath = card.image?.managedPath,
                characterCount = card.characterCount,
                moduleCount = card.moduleCount,
            )
        }
        loaded = true
    }
    val filtered = remember(rows, query) {
        rows.filter { row ->
            query.isBlank() || row.world.name.contains(query, ignoreCase = true) ||
                row.world.overview.contains(query, ignoreCase = true)
        }
    }

    NovexLibraryFrame(
        title = "世界",
        searching = searching,
        query = query,
        searchDescription = "搜索世界",
        onSearchToggle = {
            searching = !searching
            if (!searching) query = ""
        },
        onQueryChange = { query = it },
    ) {
        when {
            !loaded || importer.importing -> NovexLoading()
            filtered.isEmpty() && query.isNotBlank() -> NovexEmptyMessage("没有找到匹配的世界")
            rows.isEmpty() -> NovexEmptyWorldLibrary(onCreateWorld, importer.launch)
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.world.id }) { row ->
                    NovexWorldCard(row = row, onClick = { onOpenWorld(row.world.id) })
                }
                item(key = "create_world") {
                    NovexCreateRow(label = "新建世界", onClick = onCreateWorld)
                }
                item(key = "import_world") {
                    NovexImportRow(label = "导入世界卡", onClick = importer.launch)
                }
            }
        }
    }
}

@Composable
private fun NovexCharacterLibraryRoot(
    onOpenCharacter: (String) -> Unit,
    onCreateCharacter: () -> Unit,
) {
    val novex = rememberNovexWorkspace()
    var rows by remember { mutableStateOf<List<CharacterRootRow>>(emptyList()) }
    var refresh by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val importer = rememberNovexNativeCardImporter(NovexCardKind.CHARACTER) { importedId ->
        refresh++
        onOpenCharacter(importedId)
    }

    LaunchedEffect(refresh) {
        rows = novex.characters().map { card ->
            val aggregate = card.character
            val character = aggregate.character
            val profile = CharacterVersionProfile.fromJson(aggregate.original.profileJson, character.name)
            CharacterRootRow(character, profile, card.avatar?.managedPath, aggregate.variants.size)
        }
        loaded = true
    }
    val filtered = remember(rows, query) {
        rows.filter { row ->
            query.isBlank() || row.character.name.contains(query, ignoreCase = true) ||
                row.profile.summary.contains(query, ignoreCase = true)
        }
    }

    NovexLibraryFrame(
        title = "角色",
        searching = searching,
        query = query,
        searchDescription = "搜索角色",
        onSearchToggle = {
            searching = !searching
            if (!searching) query = ""
        },
        onQueryChange = { query = it },
    ) {
        when {
            !loaded || importer.importing -> NovexLoading()
            filtered.isEmpty() && query.isNotBlank() -> NovexEmptyMessage("没有找到匹配的角色")
            rows.isEmpty() -> NovexEmptyCharacterLibrary(onCreateCharacter, importer.launch)
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.character.id }) { row ->
                    NovexCharacterRow(row = row, onClick = { onOpenCharacter(row.character.id) })
                }
                item(key = "create_character") {
                    NovexCreateRow(label = "新建角色", onClick = onCreateCharacter)
                }
                item(key = "import_character") {
                    NovexImportRow(label = "导入角色卡", onClick = importer.launch)
                }
            }
        }
    }
}

@Composable
private fun NovexLibraryFrame(
    title: String,
    searching: Boolean,
    query: String,
    searchDescription: String,
    onSearchToggle: () -> Unit,
    onQueryChange: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(NovexRootColors.Background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp),
        ) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = NovexRootColors.Text)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSearchToggle) {
                Icon(
                    painterResource(R.drawable.ic_phosphor_search),
                    contentDescription = searchDescription,
                    tint = NovexRootColors.Text,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (searching) {
            NovexSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = searchDescription,
            )
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun NovexSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NovexRootColors.Surface)
            .padding(horizontal = 12.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_phosphor_search),
            contentDescription = null,
            tint = NovexRootColors.SecondaryText,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = NovexRootColors.SecondaryText, fontSize = 14.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = NovexRootColors.Text, fontSize = 14.sp),
                cursorBrush = SolidColor(NovexRootColors.Primary),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
    }
}

@Composable
private fun NovexWorldCard(row: WorldRootRow, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(154.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
    ) {
        NovexArtwork(
            kind = NovexArtworkKind.WORLD,
            seed = row.world.id,
            imageModel = row.imagePath.existingMediaFile(),
            contentDescription = "${row.world.name}封面",
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.48f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.78f),
                    ),
                ),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Text(
                row.world.name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.world.overview.isNotBlank()) {
                Text(
                    row.world.overview,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "角色 ${row.characterCount}  ·  模块 ${row.moduleCount}",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun NovexCharacterRow(row: CharacterRootRow, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(width = 88.dp, height = 104.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            NovexArtwork(
                kind = NovexArtworkKind.CHARACTER,
                seed = row.character.id,
                imageModel = row.imagePath.existingMediaFile(),
                contentDescription = "${row.character.name}头像",
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(
                row.character.name,
                color = NovexRootColors.Text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.profile.summary.isNotBlank()) {
                Text(
                    row.profile.summary,
                    color = NovexRootColors.SecondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "本体${if (row.variantCount > 0) " · ${row.variantCount} 个分身" else ""}",
                color = NovexRootColors.SecondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
        Icon(
            painterResource(R.drawable.ic_phosphor_more_vertical),
            contentDescription = "${row.character.name}更多操作",
            tint = NovexRootColors.Text,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun NovexCreateRow(label: String, onClick: () -> Unit) {
    NovexLibraryActionRow(
        label = label,
        iconRes = R.drawable.ic_phosphor_plus,
        onClick = onClick,
    )
}

@Composable
private fun NovexImportRow(label: String, onClick: () -> Unit) {
    NovexLibraryActionRow(
        label = label,
        iconRes = R.drawable.ic_phosphor_arrow_up,
        onClick = onClick,
    )
}

@Composable
private fun NovexLibraryActionRow(label: String, iconRes: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = NovexRootColors.Text,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = NovexRootColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NovexEmptyWorldLibrary(onCreateWorld: () -> Unit, onImportWorld: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "empty_world_visual") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(154.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onCreateWorld),
            ) {
                NovexDefaultWorldArtwork(seed = "novex-empty-world")
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.08f),
                                0.46f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.7f),
                            ),
                        ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text("建立第一个世界", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "只需一个名称，封面和内容都可以稍后添加",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        item(key = "create_world") {
            NovexCreateRow(label = "新建世界", onClick = onCreateWorld)
        }
        item(key = "import_world") {
            NovexImportRow(label = "导入世界卡", onClick = onImportWorld)
        }
    }
}

@Composable
private fun NovexEmptyCharacterLibrary(onCreateCharacter: () -> Unit, onImportCharacter: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 108.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "empty_character_visual") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCreateCharacter)
                    .padding(vertical = 8.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 88.dp, height = 104.dp)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    NovexDefaultCharacterArtwork(seed = "novex-empty-character")
                }
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(
                        "创建第一个角色",
                        color = NovexRootColors.Text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "先写下名字，头像、背景和角色模块都可以留空",
                        color = NovexRootColors.SecondaryText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "本体",
                        color = NovexRootColors.SecondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
            }
        }
        item(key = "create_character") {
            NovexCreateRow(label = "新建角色", onClick = onCreateCharacter)
        }
        item(key = "import_character") {
            NovexImportRow(label = "导入角色卡", onClick = onImportCharacter)
        }
    }
}

@Composable
private fun NovexLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NovexRootColors.Primary, strokeWidth = 2.dp)
    }
}

@Composable
private fun NovexEmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = NovexRootColors.SecondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun NovexDefaultWorldArtwork(seed: String) {
    NovexArtwork(
        kind = NovexArtworkKind.WORLD,
        seed = seed,
        imageModel = null,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun NovexDefaultCharacterArtwork(seed: String) {
    NovexArtwork(
        kind = NovexArtworkKind.CHARACTER,
        seed = seed,
        imageModel = null,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}
