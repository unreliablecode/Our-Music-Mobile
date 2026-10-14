package com.github.musicyou.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
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
