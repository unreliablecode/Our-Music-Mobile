package com.github.musicyou.ui.glass

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.musicyou.ui.glass.motion.LiquidControlMotion
import com.github.musicyou.ui.glass.motion.liquidControlGestures
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/** A switch whose white capsule becomes a live refracting lens while pressed. */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val motion = remember(scope) { LiquidControlMotion(scope, if (checked) 1f else 0f) }
    val currentChecked by rememberUpdatedState(checked)
    val changeChecked by rememberUpdatedState(onCheckedChange)
    var dragging by remember { mutableStateOf(false) }
    var keyboardPressed by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }
    LaunchedEffect(checked, dragging) {
        val target = if (checked) 1f else 0f
        if (!dragging && target != motion.targetFraction) motion.pulseTo(target)
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            keyboardPressed = false
            motion.release()
        }
    }
    val ancestor = LocalBackdrop.current
    val style = LocalGlassStyle.current
    val density = LocalDensity.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val travel = with(density) { 20.dp.toPx() }
    val trackExport = rememberLayerBackdrop()
    val transformedTrack = rememberBackdrop(trackExport) { drawTrack ->
        val pressure = motion.pressProgress
        scale(2f / 3f + pressure / 12f, pressure * 0.75f) { drawTrack() }
    }
    val thumbBackdrop = rememberCombinedBackdrop(ancestor, transformedTrack)
    val inactiveColor = adaptiveContentColor().copy(alpha = 0.22f)
    val activeColor = Color(0xFF34C759)

    fun select(target: Boolean, pulse: Boolean) {
        val fraction = if (target) 1f else 0f
        if (pulse) motion.pulseTo(fraction) else motion.moveTo(fraction)
        if (target != currentChecked) changeChecked(target)
    }

    Box(
        modifier
            .size(64.dp, 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .semantics {
                role = Role.Switch
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                if (!enabled) disabled()
                if (enabled) onClick {
                    select(!currentChecked, pulse = true)
                    true
                }
            }
            .onKeyEvent { event ->
                if (!enabled || event.key !in listOf(Key.Spacebar, Key.Enter, Key.NumPadEnter)) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    keyboardPressed = true
                    motion.press()
                } else if (event.type == KeyEventType.KeyUp && keyboardPressed) {
                    keyboardPressed = false
                    select(!currentChecked, pulse = false)
                    motion.release()
                }
                true
            }
            .onFocusChanged {
                if (!it.isFocused && keyboardPressed) {
                    keyboardPressed = false
                    motion.release()
                }
            }
            .focusable(enabled)
            .pointerInput(enabled, ltr, travel) {
                if (!enabled) return@pointerInput
                liquidControlGestures(
                    onPress = {
                        dragFraction = if (currentChecked) 1f else 0f
                        motion.press()
                    },
                    onDragStart = { down, position ->
                        dragging = true
                        val amount = (position.x - down.x) / travel
                        dragFraction = (dragFraction + if (ltr) amount else -amount).coerceIn(0f, 1f)
                        motion.moveTo(dragFraction)
                    },
                    onDrag = { _, amount ->
                        dragFraction = (dragFraction + (if (ltr) amount.x else -amount.x) / travel).coerceIn(0f, 1f)
                        motion.moveTo(dragFraction)
                    },
                    onFinish = { didDrag, _ ->
                        val target = if (didDrag) dragFraction >= 0.5f else !currentChecked
                        select(target, pulse = false)
                        dragging = false
                        motion.release()
                    },
                    onCancel = {
                        dragging = false
                        motion.moveTo(if (currentChecked) 1f else 0f)
                        motion.release()
                    },
                )
            },
        contentAlignment = AbsoluteAlignment.CenterLeft,
    ) {
        Box(
            Modifier
                .layerBackdrop(trackExport)
                .clip(RoundedCornerShape(50))
                .drawBehind { drawRect(lerp(inactiveColor, activeColor, motion.fraction)) }
                .size(64.dp, 28.dp),
        )
        Box(
            Modifier
                .graphicsLayer {
                    translationX = 2.dp.toPx() + travel * if (ltr) motion.fraction else 1f - motion.fraction
                }
                .drawBackdrop(
                    backdrop = thumbBackdrop,
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        if (style.enabled) {
                            val pressure = motion.pressProgress
                            blur(8.dp.toPx() * (1f - pressure))
                            lens(5.dp.toPx() * pressure, 10.dp.toPx() * pressure, chromaticAberration = true)
                        }
                    },
                    highlight = {
                        if (style.enabled) Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = motion.pressProgress,
                        ) else null
                    },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.08f)) },
                    innerShadow = { if (style.enabled) InnerShadow(radius = 4.dp * motion.pressProgress, alpha = motion.pressProgress) else null },
                    layerBlock = {
                        val velocity = motion.velocity / 50f
                        scaleX = motion.scaleX / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                        scaleY = motion.scaleY * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = if (style.enabled) 1f - motion.pressProgress else 1f))
                    },
                )
                .size(40.dp, 24.dp)
                .testTag("glass-toggle-thumb"),
        )
    }
}
