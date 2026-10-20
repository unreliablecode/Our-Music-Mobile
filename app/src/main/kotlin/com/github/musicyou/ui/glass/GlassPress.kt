package com.github.musicyou.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

/** A spring press that remains visible when a tap is shorter than one frame. */
@Composable
fun Modifier.glassClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val interactions = remember { MutableInteractionSource() }
    val pressure = remember { Animatable(0f) }
    LaunchedEffect(interactions, enabled) {
        if (!enabled) {
            pressure.snapTo(0f)
            return@LaunchedEffect
        }
        interactions.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> pressure.animateTo(1f, spring(0.7f, 800f))
                is PressInteraction.Release -> {
                    if (pressure.value < 0.55f) {
                        pressure.animateTo(0.55f, tween(45, easing = LinearOutSlowInEasing))
                    }
                    pressure.animateTo(0f, spring(0.65f, 600f))
                }
                is PressInteraction.Cancel -> pressure.animateTo(0f, spring(0.8f, 800f))
            }
        }
    }
    return graphicsLayer {
        val scale = 1f - pressure.value.coerceIn(0f, 1f) * (1f - pressedScale)
        scaleX = scale
        scaleY = scale
    }.clickable(
        enabled = enabled,
        interactionSource = interactions,
        indication = null,
        role = role,
        onClick = onClick,
    )
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    role: Role = Role.Button,
    pressedScale: Float = 0.96f,
    content: @Composable BoxScope.() -> Unit,
) {
    GlassSurface(
        modifier = modifier.glassClickable(enabled, role, pressedScale, onClick),
        shape = shape,
        tint = tint,
        content = content,
    )
}
