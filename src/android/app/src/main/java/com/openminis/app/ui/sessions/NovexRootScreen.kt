package com.openminis.app.ui.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.novex.NovexColors
import kotlinx.coroutines.launch

private val NovexRootColors = NovexColors

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
    onOpenSettings: () -> Unit,
) {
    var dockExpanded by rememberSaveable { mutableStateOf(false) }
    var showRootDock by rememberSaveable { mutableStateOf(false) }
    val pageStateHolder = rememberSaveableStateHolder()
    val pagerState = rememberPagerState(initialPage = 0) { NovexRootSpace.entries.size }
    val scope = rememberCoroutineScope()
    val headerHost = remember { NovexRootHeaderHost() }
    val selected = NovexRootSpace.entries[pagerState.currentPage]

    fun collapseDock() {
        dockExpanded = NovexRootNavigationState(
            selected = selected,
            expanded = dockExpanded,
        ).dispatch(NovexRootNavigationEvent.OUTSIDE_TAP).expanded
    }

    fun select(destination: NovexRootSpace, expand: Boolean = true) {
        showRootDock = true
        if (expand) dockExpanded = true
        scope.launch { pagerState.animateScrollToPage(destination.ordinal) }
    }

    val rootBackAction = novexRootBackAction(selected = selected, searchActive = false)
    BackHandler(enabled = rootBackAction == NovexRootBackAction.SWITCH_TO_CONVERSATIONS) {
        if (rootBackAction == NovexRootBackAction.SWITCH_TO_CONVERSATIONS) {
            select(NovexRootSpace.CONVERSATIONS, expand = false)
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalNovexRootHeaderHost provides headerHost) {
        Column(Modifier.fillMaxSize().background(NovexRootColors.Canvas).statusBarsPadding()) {
            headerHost.current(selected)?.let { header ->
                NovexRootPageHeader(
                    space = selected,
                    searching = header.searching,
                    searchDescription = header.searchDescription,
                    onSettings = header.onSettings,
                    onSearchToggle = header.onSearchToggle,
                    createItems = header.createItems,
                )
            } ?: Box(Modifier.fillMaxWidth().height(64.dp))
            val outsideTapInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (dockExpanded) {
                            Modifier.clickable(
                                interactionSource = outsideTapInteraction,
                                indication = null,
                                onClick = ::collapseDock,
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = showRootDock,
                    key = { NovexRootSpace.entries[it].name },
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val destination = NovexRootSpace.entries[page]
                    pageStateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            NovexRootSpace.CONVERSATIONS -> conversationContent(
                                { select(NovexRootSpace.WORLDS) },
                                { visible -> showRootDock = nextNovexRootDockVisibility(visible) },
                            )
                            NovexRootSpace.WORLDS -> NovexWorldLibraryRoot(
                                onOpenWorld = onOpenWorld,
                                onCreateWorld = onCreateWorld,
                                onOpenSettings = onOpenSettings,
                            )
                            NovexRootSpace.CHARACTERS -> NovexCharacterLibraryRoot(
                                onOpenCharacter = onOpenCharacter,
                                onCreateCharacter = onCreateCharacter,
                                onOpenSettings = onOpenSettings,
                            )
                            NovexRootSpace.INTERACTIVE_FICTION -> NovexInteractiveFictionLibraryRoot(
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }
                }
                if (showRootDock) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(116.dp)
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.46f to NovexRootColors.Canvas.copy(alpha = 0.78f),
                                    1f to NovexRootColors.Canvas,
                                ),
                            ),
                    )
                    NovexRootDock(
                        selected = selected,
                        expanded = dockExpanded,
                        onSelect =(::select),
                        onDragSelect = { select(it, expand = false) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun NovexRootDock(
    selected: NovexRootSpace,
    expanded: Boolean,
    onSelect: (NovexRootSpace) -> Unit,
    onDragSelect: (NovexRootSpace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val horizontalPadding by animateDpAsState(
        targetValue = if (expanded) 8.dp else 14.dp,
        animationSpec = tween(240),
        label = "根导航水平边距",
    )
    var dragX by remember { mutableStateOf(0f) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(
            if (expanded) 4.dp else 12.dp,
            Alignment.CenterHorizontally,
        ),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .animateContentSize(tween(240))
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dragX = it.x },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX = (dragX + amount.x).coerceIn(0f, size.width.toFloat())
                        onDragSelect(novexRootSpaceAtOffset(dragX, size.width.toFloat()))
                    },
                )
            }
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
    ) {
        NovexRootSpace.entries.forEach { destination ->
            AnimatedContent(
                targetState = NovexRootNavigationState(selected, expanded).itemForm(destination),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "根导航形态",
            ) { form ->
                if (form == NovexRootItemForm.LABEL) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable { onSelect(destination) }
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            novexRootSpaceLabel(destination),
                            color = NovexRootColors.Text,
                            fontSize = 15.sp,
                            fontWeight = if (selected == destination) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = "切换到${novexRootSpaceLabel(destination)}"
                            }
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
