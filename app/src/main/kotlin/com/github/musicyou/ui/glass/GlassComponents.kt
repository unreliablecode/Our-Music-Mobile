package com.github.musicyou.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt

/** The scene immediately behind the current glass surface, including ancestors. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop> { emptyBackdrop() }

@Immutable
data class GlassStyle(
    val enabled: Boolean = true,
    val tintAlpha: Float = 0.18f,
    val blurDp: Float = 18f,
)

val LocalGlassStyle = staticCompositionLocalOf { GlassStyle() }
private val LocalGlassDepth = staticCompositionLocalOf { 0 }

/**
 * Captures a live background scene, then draws uncaptured glass controls above it.
 * For a scrolling page behind a navigation bar, put that page in [background].
 */
@Composable
fun GlassBackdropRoot(
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val scene = rememberLayerBackdrop()
    Box(modifier) {
        Box(Modifier.matchParentSize().layerBackdrop(scene), content = background)
        CompositionLocalProvider(LocalBackdrop provides scene) {
            content()
        }
    }
}

/**
 * A live refracting material from the vendored AndroidLiquidGlass renderer.
 * Its export records only the material, never its children. A child combines that
 * export with the ancestor scene, so arbitrary nesting remains acyclic.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val ancestor = LocalBackdrop.current
    val style = LocalGlassStyle.current
    val depth = LocalGlassDepth.current
    val exported = rememberLayerBackdrop()
    val nested = rememberCombinedBackdrop(ancestor, exported)
    var readableScrim by remember { mutableStateOf(Color.Transparent) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.45f
    val resolvedTint = if (tint != Color.Unspecified) tint else {
        (if (dark) Color(0xFF242429) else Color.White)
            .copy(alpha = style.tintAlpha.coerceIn(0f, 1f))
    }
    val materialBase = if (dark) Color(0xFF242429) else Color.White
    val contrastTint = if (style.enabled) resolvedTint else resolvedTint.compositeOver(materialBase)
    ContrastRegion(
        modifier = modifier.drawBackdrop(
            backdrop = ancestor,
            shape = { shape },
            effects = {
                if (style.enabled) {
                    vibrancy()
                    blur(style.blurDp.coerceIn(0f, 60f).dp.toPx())
                    lens(12.dp.toPx(), 24.dp.toPx(), chromaticAberration = true)
                }
            },
            exportedBackdrop = exported,
            highlight = {
                if (style.enabled) {
                    Highlight(
                        width = if (depth > 0) 0.45.dp else 0.7.dp,
                        blurRadius = if (depth > 0) 0.25.dp else 0.35.dp,
                        style = HighlightStyle.Default(color = Color.White.copy(alpha = if (depth > 0) 0.45f else 0.62f)),
                    )
                } else null
            },
            shadow = {
                Shadow(
                    radius = if (depth > 0) 6.dp else 14.dp,
                    color = Color.Black.copy(alpha = if (depth > 0) 0.04f else if (dark) 0.17f else 0.09f),
                )
            },
            onDrawSurface = {
                if (!style.enabled) {
                    // Keep accent/selection tints relative to a solid material.
                    // Making an explicit faint black tint opaque would turn
                    // the selected navigation capsule black when glass is off.
                    drawRect(materialBase.copy(alpha = 0.96f))
                }
                drawRect(resolvedTint)
                drawRect(readableScrim)
            },
        ),
        overlay = contrastTint,
        drawScrim = false,
        onScrimChanged = { readableScrim = it },
    ) {
        CompositionLocalProvider(LocalBackdrop provides nested, LocalGlassDepth provides depth + 1) {
            content()
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(0.7f, 500f), label = "glass press")
    GlassSurface(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(50))
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(50),
        tint = tint,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
    }
}

/** iOS-style switch: a live glass thumb samples both the scene and its colored track. */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ancestor = LocalBackdrop.current
    val style = LocalGlassStyle.current
    val trackExport = rememberLayerBackdrop()
    val thumbBackdrop = rememberCombinedBackdrop(ancestor, trackExport)
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val currentChecked by rememberUpdatedState(checked)
    val changeChecked by rememberUpdatedState(onCheckedChange)
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val travel = with(LocalDensity.current) { 24.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    val fraction by animateFloatAsState(
        if (dragging) dragFraction else if (checked) 1f else 0f,
        spring(0.85f, 700f), label = "glass toggle position",
    )
    val pressure by animateFloatAsState(if (pressed || dragging) 1f else 0f, spring(0.7f, 600f), label = "glass toggle pressure")
    val trackColor = lerp(Color(0xFF787880).copy(alpha = 0.28f), Color(0xFF34C759).copy(alpha = 0.93f), fraction)
    Box(
        modifier
            .size(64.dp, 38.dp)
            .toggleable(
                value = checked,
                interactionSource = interactions,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .pointerInput(ltr, travel) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragFraction = if (currentChecked) 1f else 0f },
                    onDragCancel = { dragging = false },
                    onDragEnd = { changeChecked(dragFraction >= 0.5f); dragging = false },
                ) { change, amount ->
                    change.consume()
                    dragFraction = (dragFraction + (if (ltr) amount else -amount) / travel).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.matchParentSize().padding(vertical = 3.dp).drawBackdrop(
                backdrop = ancestor,
                shape = { RoundedCornerShape(50) },
                effects = { if (style.enabled) { blur(8.dp.toPx()); lens(6.dp.toPx(), 10.dp.toPx()) } },
                exportedBackdrop = trackExport,
                highlight = { Highlight(width = 0.5.dp, alpha = 0.45f) },
                shadow = null,
                onDrawSurface = { drawRect(trackColor) },
            ),
        )
        CompositionLocalProvider(LocalBackdrop provides thumbBackdrop) {
            GlassSurface(
                modifier = Modifier
                    .offset { IntOffset((with(this) { 2.dp.toPx() } + if (ltr) fraction * travel else (1f - fraction) * travel).roundToInt(), 0) }
                    .size(36.dp, 28.dp)
                    .graphicsLayer { scaleX = 1f + pressure * 0.18f; scaleY = 1f + pressure * 0.24f },
                shape = RoundedCornerShape(50),
                tint = Color.White.copy(alpha = 0.88f - pressure * 0.66f),
            ) {}
        }
    }
}
