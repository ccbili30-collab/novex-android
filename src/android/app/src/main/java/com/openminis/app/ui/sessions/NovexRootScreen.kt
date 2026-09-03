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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.novex.NovexColors
import kotlin.math.abs

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
) {
    var selectedName by rememberSaveable { mutableStateOf(NovexRootSpace.CONVERSATIONS.name) }
    var dockExpanded by rememberSaveable { mutableStateOf(false) }
    var showRootDock by rememberSaveable { mutableStateOf(false) }
    val pageStateHolder = rememberSaveableStateHolder()
    val selected = NovexRootSpace.valueOf(selectedName)
    val currentSelected by rememberUpdatedState(selected)
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
                        val startedOnDock = isNovexRootDockHit(
                            x = down.position.x,
                            y = down.position.y,
                            pageWidth = size.width.toFloat(),
                            pageHeight = size.height.toFloat(),
                            dockWidth = dockGestureWidth,
                            dockHeight = dockGestureHeight,
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
                            if (
                                startedOnDock &&
                                abs(totalX) > viewConfiguration.touchSlop &&
                                abs(totalX) > abs(totalY)
                            ) {
                                select(
                                    novexRootSpaceAtPageX(
                                        x = down.position.x + totalX,
                                        pageWidth = size.width.toFloat(),
                                        dockWidth = dockGestureWidth,
                                    ),
                                    expand = false,
                                )
                            }
                        } while (event.changes.any { it.pressed })

                        val horizontalSwipe = abs(totalX) >= swipeThreshold && abs(totalX) > abs(totalY)
                        if (startedOnDock && horizontalSwipe) {
                                select(
                                    novexRootSpaceAtPageX(
                                        x = down.position.x + totalX,
                                        pageWidth = size.width.toFloat(),
                                        dockWidth = dockGestureWidth,
                                    ),
                                    expand = false,
                                )
                        } else if (!startedOnDock) {
                            if (horizontalSwipe) {
                                val delta = if (totalX < 0f) 1 else -1
                                select(
                                    NovexRootNavigationState(currentSelected).moveCompact(delta).selected,
                                    expand = false,
                                )
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
                        { visible -> showRootDock = nextNovexRootDockVisibility(visible) },
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
            .animateContentSize(tween(240))
            .padding(horizontal = horizontalPadding, vertical = 6.dp),
    ) {
        NovexRootSpace.entries.forEach { destination ->
            AnimatedContent(
                targetState = NovexRootNavigationState(selected, expanded).itemForm(destination),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "根导航形态",
            ) { form ->
                if (form == NovexRootItemForm.LABEL) {
                    val label = when (destination) {
                        NovexRootSpace.CONVERSATIONS -> "会话"
                        NovexRootSpace.WORLDS -> "世界"
                        NovexRootSpace.CHARACTERS -> "角色"
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clickable { onSelect(destination) }
                            .padding(horizontal = 14.dp),
                    ) {
                        Text(
                            label,
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
                                contentDescription = when (destination) {
                                    NovexRootSpace.CONVERSATIONS -> "切换到会话"
                                    NovexRootSpace.WORLDS -> "切换到世界"
                                    NovexRootSpace.CHARACTERS -> "切换到角色"
                                }
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
