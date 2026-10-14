package com.github.musicyou.ui.glass.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
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

    var targetFraction by mutableFloatStateOf(initialFraction.coerceIn(0f, 1f))
        private set
    val fraction get() = position.value.coerceIn(0f, 1f)
    val pressProgress get() = pressure.value.coerceIn(0f, 1f)
    val scaleX get() = horizontalScale.value
    val scaleY get() = verticalScale.value
    val velocity get() = position.velocity

    fun moveTo(fraction: Float) {
        val target = fraction.coerceIn(0f, 1f)
        if (target == targetFraction) return
        targetFraction = target
        positionJob?.cancel()
        positionJob = scope.launch {
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
            snapshotFlow { position.value }.first { abs(it - targetFraction) < 0.025f }
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
