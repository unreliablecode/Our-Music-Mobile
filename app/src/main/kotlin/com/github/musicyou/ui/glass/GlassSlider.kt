package com.github.musicyou.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
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
import kotlin.math.roundToInt

/** A live glass slider with the spring, pressure and lens motion of AndroidLiquidGlass. */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    activeTrackColor: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
    onValueChangeCanceled: (() -> Unit)? = null,
) {
    require(valueRange.start.isFinite() && valueRange.endInclusive.isFinite() && valueRange.endInclusive > valueRange.start)
    val rangeSize = valueRange.endInclusive - valueRange.start
    val safeValue = if (value.isFinite()) value.coerceIn(valueRange) else valueRange.start
    val externalFraction = (safeValue - valueRange.start) / rangeSize
    val currentValue by rememberUpdatedState(safeValue)
    val changeValue by rememberUpdatedState(onValueChange)
    val finishChange by rememberUpdatedState(onValueChangeFinished)
    val cancelChange by rememberUpdatedState(onValueChangeCanceled)
    val scope = rememberCoroutineScope()
    val motion = remember(scope) { LiquidControlMotion(scope, externalFraction) }
    var dragging by remember { mutableStateOf(false) }
    var keyboardChanging by remember { mutableStateOf(false) }
    LaunchedEffect(externalFraction, dragging) {
        if (!dragging) motion.moveTo(externalFraction)
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            keyboardChanging = false
            motion.release()
        }
    }
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val ancestor = LocalBackdrop.current
    val style = LocalGlassStyle.current
    val resolvedActive = if (activeTrackColor == Color.Unspecified) adaptiveContentColor(MaterialTheme.colorScheme.primary) else activeTrackColor
    val resolvedTrack = if (trackColor == Color.Unspecified) adaptiveContentColor().copy(alpha = 0.22f) else trackColor
    val trackExport = rememberLayerBackdrop()
    val transformedTrack = rememberBackdrop(trackExport) { drawTrack ->
        val pressure = motion.pressProgress
        scale(2f / 3f + pressure / 3f, pressure) { drawTrack() }
    }
    val thumbBackdrop = rememberCombinedBackdrop(ancestor, transformedTrack)

    fun commitFraction(fraction: Float, finish: Boolean) {
        val target = valueRange.start + fraction.coerceIn(0f, 1f) * rangeSize
        motion.moveTo(fraction)
        if (target != currentValue) changeValue(target)
        if (finish) finishChange?.invoke()
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(safeValue, valueRange)
                contentDescription?.let { this.contentDescription = it }
                if (!enabled) disabled()
                if (enabled) setProgress { target ->
                    if (!target.isFinite() || target.coerceIn(valueRange) == currentValue) false else {
                        val clamped = target.coerceIn(valueRange)
                        motion.pulseTo((clamped - valueRange.start) / rangeSize)
                        if (clamped != currentValue) changeValue(clamped)
                        finishChange?.invoke()
                        true
                    }
                }
            }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionRight -> if (ltr) 1f else -1f
                    Key.DirectionLeft -> if (ltr) -1f else 1f
                    Key.DirectionUp -> 1f
                    Key.DirectionDown -> -1f
                    else -> return@onKeyEvent false
                }
                if (!enabled) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) {
                    keyboardChanging = true
                    val target = ((currentValue - valueRange.start) / rangeSize + direction * 0.05f).coerceIn(0f, 1f)
                    motion.pulseTo(target)
                    commitFraction(target, finish = false)
                } else if (event.type == KeyEventType.KeyUp && keyboardChanging) {
                    keyboardChanging = false
                    finishChange?.invoke()
                }
                true
            }
            .onFocusChanged {
                if (!it.isFocused && keyboardChanging) {
                    keyboardChanging = false
                    motion.release()
                }
            }
            .focusable(enabled),
        contentAlignment = AbsoluteAlignment.CenterLeft,
    ) {
        val density = LocalDensity.current
        val edge = with(density) { 20.dp.toPx() }
        val travel = (with(density) { maxWidth.toPx() } - edge * 2f).coerceAtLeast(1f)
        val trackWidth = with(density) { travel.toDp() }
        fun fractionAt(x: Float): Float = ((x - edge) / travel).coerceIn(0f, 1f).let { if (ltr) it else 1f - it }

        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(enabled, ltr, travel, valueRange) {
                    if (!enabled) return@pointerInput
                    liquidControlGestures(
                        onPress = { motion.press() },
                        onDragStart = { _, position ->
                            dragging = true
                            commitFraction(fractionAt(position.x), finish = false)
                        },
                        onDrag = { position, _ -> commitFraction(fractionAt(position.x), finish = false) },
                        onFinish = { _, position ->
                            commitFraction(fractionAt(position.x), finish = true)
                            dragging = false
                            motion.release()
                        },
                        onCancel = {
                            dragging = false
                            cancelChange?.invoke()
                            motion.moveTo((currentValue - valueRange.start) / rangeSize)
                            motion.release()
                        },
                    )
                },
            contentAlignment = AbsoluteAlignment.CenterLeft,
        ) {
            Box(
                Modifier.padding(horizontal = 20.dp).layerBackdrop(trackExport),
                contentAlignment = AbsoluteAlignment.CenterLeft,
            ) {
                Box(Modifier.width(trackWidth).height(6.dp).clip(RoundedCornerShape(50)).background(resolvedTrack))
                Box(
                    Modifier
                        .offset { IntOffset(if (ltr) 0 else (travel * (1f - motion.fraction)).roundToInt(), 0) }
                        .width(trackWidth * motion.fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(resolvedActive),
                )
            }
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = edge + travel * (if (ltr) motion.fraction else 1f - motion.fraction) - size.width / 2f
                    }
                    .drawBackdrop(
                        backdrop = thumbBackdrop,
                        shape = { RoundedCornerShape(50) },
                        effects = {
                            if (style.enabled) {
                                val pressure = motion.pressProgress
                                blur(8.dp.toPx() * (1f - pressure))
                                lens(10.dp.toPx() * pressure, 14.dp.toPx() * pressure, chromaticAberration = true)
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
                            val velocity = motion.velocity / 10f
                            scaleX = motion.scaleX / (1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f))
                            scaleY = motion.scaleY * (1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f))
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = if (style.enabled) 1f - motion.pressProgress else 1f))
                        },
                    )
                    .size(40.dp, 24.dp)
                    .testTag("glass-slider-thumb"),
            )
        }
    }
}
