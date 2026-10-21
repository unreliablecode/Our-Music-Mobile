package com.github.musicyou.audio.visualization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A mirrored circular spectrum shared by playback and video rendering. */
class CircularSpectrumGeometry(val pointCount: Int = 192) {
    init { require(pointCount >= 16 && pointCount % 2 == 0) }

    val coordinates = FloatArray(pointCount * 2)
    private val directionX = FloatArray(pointCount) { cos(2.0 * PI * it / pointCount - PI / 2).toFloat() }
    private val directionY = FloatArray(pointCount) { sin(2.0 * PI * it / pointCount - PI / 2).toFloat() }

    /** No clock or generated signal is involved; silence retains an unmodulated ring. */
    fun fill(spectrum: List<Float>, rms: Float, peak: Float, centerX: Float, centerY: Float, baseRadius: Float) {
        val energy = rms.finiteUnit()
        val transient = peak.finiteUnit()
        val radius = baseRadius * (1f + energy * 0.12f + transient * 0.025f)
        for (point in 0 until pointCount) {
            val mirrored = if (point <= pointCount / 2) point else pointCount - point
            val bin = mirrored.toFloat() / (pointCount / 2) * (spectrum.size - 1).coerceAtLeast(0)
            val first = bin.toInt().coerceAtMost((spectrum.size - 1).coerceAtLeast(0))
            val a = spectrum.getOrNull(first)?.finiteUnit() ?: 0f
            val b = spectrum.getOrNull(first + 1)?.finiteUnit() ?: a
            val magnitude = a + (b - a) * (bin - first)
            val r = radius + baseRadius * 0.34f * magnitude
            coordinates[point * 2] = centerX + directionX[point] * r
            coordinates[point * 2 + 1] = centerY + directionY[point] * r
        }
    }
}

private fun Float.finiteUnit(): Float = if (isFinite()) coerceIn(0f, 1f) else 0f
