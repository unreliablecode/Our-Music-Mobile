package com.github.musicyou.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Immutable
data class GlassTab(val label: String, val icon: ImageVector)

/** A persistent glass lens travels between equal-width accessible navigation tabs. */
@Composable
fun AnimatedGlassTabs(
    tabs: List<GlassTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (tabs.isEmpty()) return
    val lastIndex = tabs.lastIndex
    val select by rememberUpdatedState(onSelect)
    val selected by rememberUpdatedState(selectedIndex)
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val interactions = remember(tabs.map { it.label }) { tabs.map { MutableInteractionSource() } }
    val presses = interactions.map { it.collectIsPressedAsState().value }
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(selectedIndex.coerceIn(0, lastIndex).toFloat()) }
    var lastSelection by remember { mutableIntStateOf(selectedIndex.coerceIn(0, lastIndex)) }
    LaunchedEffect(selectedIndex) { if (selectedIndex in tabs.indices) lastSelection = selectedIndex }
    val pressure by animateFloatAsState(
        targetValue = if (dragging || presses.any { it }) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 650f),
        label = "navigation glass pressure",
    )
    val visibility by animateFloatAsState(
        targetValue = if (dragging || selectedIndex in tabs.indices) 1f else 0f,
        label = "navigation glass visibility",
    )

    GlassSurface(modifier.height(70.dp), shape = RoundedCornerShape(50)) {
        val inactive = adaptiveContentColor()
        BoxWithConstraints(Modifier.fillMaxSize().padding(5.dp)) {
            val targetWidth = maxWidth / tabs.size
            val pillWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 600f),
                label = "navigation glass width",
            )
            val travel = with(LocalDensity.current) { targetWidth.toPx() }.coerceAtLeast(1f)
            val destination = if (dragging) dragPosition else if (selectedIndex in tabs.indices) selectedIndex.toFloat() else lastSelection.toFloat()
            val pillOffset by animateDpAsState(
                targetValue = targetWidth * destination,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
                label = "navigation glass position",
            )
            val currentPosition by rememberUpdatedState(pillOffset / targetWidth)
            GlassSurface(
                modifier = Modifier
                    .offset { IntOffset(pillOffset.roundToPx(), 0) }
                    .width(pillWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = visibility
                        scaleX = 1f + pressure * 0.035f
                        scaleY = 1f + pressure * 0.035f
                    }
                    .testTag("glass-tab-indicator"),
                shape = RoundedCornerShape(50),
                tint = inactive.copy(alpha = 0.07f),
            ) {}
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .pointerInput(tabs.size, ltr, travel) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                dragPosition = currentPosition.coerceIn(0f, lastIndex.toFloat())
                                dragging = true
                            },
                            onDragCancel = { dragging = false },
                            onDragEnd = {
                                val destination = dragPosition.roundToInt().coerceIn(0, lastIndex)
                                if (destination != selected) select(destination)
                                dragging = false
                            },
                        ) { change, amount ->
                            change.consume()
                            dragPosition = (dragPosition + (if (ltr) amount else -amount) / travel)
                                .coerceIn(0f, lastIndex.toFloat())
                        }
                    },
            ) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = index == selectedIndex
                    val scale by animateFloatAsState(
                        targetValue = if (presses[index]) 0.9f else if (isSelected) 1.03f else 1f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 650f),
                        label = "${tab.label} press",
                    )
                    ContrastRegion(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = isSelected,
                                role = Role.Tab,
                                interactionSource = interactions[index],
                                indication = null,
                                onClick = { if (!isSelected) onSelect(index) },
                            )
                            .graphicsLayer { scaleX = scale; scaleY = scale },
                        overlay = if (isSelected) inactive.copy(alpha = 0.07f) else Color.Transparent,
                    ) {
                        val cellInk = adaptiveContentColor()
                        val cellAccent = adaptiveContentColor(accentColor)
                        val interpolatedColor by animateColorAsState(if (isSelected) cellAccent else cellInk, label = "${tab.label} color")
                        val color = adaptiveContentColor(interpolatedColor)
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            AdaptiveIcon(tab.icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = color)
                            AdaptiveText(tab.label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
