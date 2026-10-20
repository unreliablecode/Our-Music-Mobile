package com.github.musicyou.ui.glass.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Spring motion adapted from AndroidLiquidGlass's Apache 2.0 catalog controls. */
internal class LiquidControlMotion(
    private val scope: CoroutineScope,
    initialFraction: Float,
) {
    private val position = Animatable(initialFraction.coerceIn(0f, 1f), 0.001f)
    private val pressure = Animatable(0f, 0.001f)
    private val horizontalScale = Animatable(1f, 0.001f)
    private val verticalScale = Animatable(1f, 0.001f)
    private var positionJob: Job? = null
    private var pressureJob: Job? = null
    private var pulseJob: Job? = null
    private var releaseJob: Job? = null
    private var directPosition by mutableStateOf<Float?>(null)
    private var dragVelocity by mutableFloatStateOf(0f)
    private var lastDragNanos = 0L

    var targetFraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))
        private set
    val fraction get() = (directPosition ?: position.value).coerceIn(0f, 1f)
    val pressProgress get() = pressure.value.coerceIn(0f, 1f)
    val scaleX get() = horizontalScale.value
    val scaleY get() = verticalScale.value
    val velocity get() = if (directPosition != null) dragVelocity else position.velocity

    /** Pointer positions are already sampled motion; springing each event adds latency. */
    fun dragTo(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        val now = System.nanoTime()
        if (directPosition == null) {
            positionJob?.cancel()
            dragVelocity = 0f
        } else {
            val elapsed = (now - lastDragNanos) / 1_000_000_000f
            if (elapsed in 0.001f..0.2f) {
                val sampledVelocity = ((target - this.fraction) / elapsed).coerceIn(-12f, 12f)
                dragVelocity += (sampledVelocity - dragVelocity) * 0.4f
            }
        }
        lastDragNanos = now
        targetFraction = target
        directPosition = target
    }

    fun moveTo(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        if (target == targetFraction && directPosition == null) return
        targetFraction = target
        positionJob?.cancel()
        val fingerPosition = directPosition
        positionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (fingerPosition != null) {
                position.snapTo(fingerPosition)
                directPosition = null
                dragVelocity = 0f
                lastDragNanos = 0L
            }
            position.animateTo(target, spring(1f, 1000f, 0.001f))
        }
    }

    fun press() {
        pulseJob?.cancel()
        releaseJob?.cancel()
        pressureJob?.cancel()
        pressureJob = scope.launch {
            launch { pressure.animateTo(1f, spring(1f, 1000f, 0.001f)) }
            launch { horizontalScale.animateTo(1.5f, spring(0.6f, 250f, 0.001f)) }
            launch { verticalScale.animateTo(1.5f, spring(0.7f, 250f, 0.001f)) }
        }
    }

    fun release() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            withFrameNanos { }
            // Keep the lens open until the positional spring catches the finger.
            snapshotFlow { fraction }.first { abs(it - targetFraction) < 0.025f }
            pressureJob?.cancel()
            pressureJob = scope.launch {
                launch { pressure.animateTo(0f, spring(1f, 1000f, 0.001f)) }
                launch { horizontalScale.animateTo(1f, spring(0.6f, 250f, 0.001f)) }
                launch { verticalScale.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
            }
        }
    }

    fun pulseTo(fraction: Float) {
        press()
        moveTo(fraction)
        pulseJob = scope.launch {
            snapshotFlow { pressure.value }.first { it >= 0.95f }
            release()
        }
    }
}
