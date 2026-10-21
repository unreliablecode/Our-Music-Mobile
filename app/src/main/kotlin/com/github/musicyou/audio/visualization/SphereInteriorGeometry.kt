package com.github.musicyou.audio.visualization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Deterministic inner filaments and particles, driven by song time and decoded energy. */
class SphereInteriorGeometry(val strandCount: Int = 7, val pointsPerStrand: Int = 72, val particleCount: Int = 84) {
    val strands = Array(strandCount) { FloatArray(pointsPerStrand * 2) }
    /** Each particle stores x, y and depth opacity. */
    val particles = FloatArray(particleCount * 3)

    init { require(strandCount > 0 && pointsPerStrand >= 16 && particleCount > 0) }

    fun fill(spectrum: List<Float>, rms: Float, timeUs: Long, centerX: Float, centerY: Float, radius: Float) {
        val seconds = timeUs.coerceAtLeast(0) / 1_000_000.0
        val energy = if (rms.isFinite()) rms.coerceIn(0f, 1f) else 0f
        var bassSum = 0.0
        var bassCount = 0
        for (band in 0 until minOf(6, spectrum.size)) if (spectrum[band].isFinite()) {
            bassSum += spectrum[band].coerceIn(0f, 1f)
            bassCount++
        }
        val bass = if (bassCount == 0) 0.0 else bassSum / bassCount
        val turn = seconds * 0.23
        for (strand in 0 until strandCount) {
            val band = spectrum.getOrNull(strand * (spectrum.size / strandCount).coerceAtLeast(1))
                ?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val twist = turn + strand * 0.63
            val tilt = 0.30 + strand * 0.07 + energy * 0.13
            val size = radius * (0.52 + strand * 0.037 + band * 0.08)
            for (point in 0 until pointsPerStrand) {
                val angle = point * 2.0 * PI / (pointsPerStrand - 1)
                val ripple = 1.0 + (0.03 + band * 0.08) * sin(angle * 3.0 + seconds * 0.8 + strand)
                val x = size * ripple * cos(angle)
                val y = size * ripple * sin(angle) * tilt + radius * 0.10 * sin(angle * 2.0 + seconds * 0.6 + strand)
                strands[strand][point * 2] = centerX + (x * cos(twist) - y * sin(twist)).toFloat()
                strands[strand][point * 2 + 1] = centerY + (x * sin(twist) + y * cos(twist)).toFloat()
            }
        }
        for (particle in 0 until particleCount) {
            val y = 1.0 - 2.0 * (particle + 0.5) / particleCount
            val ring = sqrt(1.0 - y * y)
            val angle = particle * 2.399963229728653 + seconds * 0.29
            val x = cos(angle) * ring
            val z = sin(angle) * ring
            val wobble = 1.0 + energy * 0.06 * sin(seconds * 1.4 + particle)
            val roll = seconds * 0.09 + bass * 0.07
            particles[particle * 3] = centerX + ((x * cos(roll) - y * sin(roll)) * radius * 0.78 * wobble).toFloat()
            particles[particle * 3 + 1] = centerY + ((x * sin(roll) + y * cos(roll)) * radius * 0.78 * wobble).toFloat()
            particles[particle * 3 + 2] = ((z + 1.0) * 0.35 + 0.12).toFloat()
        }
    }
}
