package com.github.musicyou.audio.visualization

import org.junit.Assert.*
import org.junit.Test

class SphereInteriorGeometryTest {
    @Test fun innerMotionUsesTheSongTimelineAndRepeatsExactlyForExport() {
        val shape = SphereInteriorGeometry()
        val spectrum = List(32) { if (it < 8) .6f else .2f }
        shape.fill(spectrum, .25f, 1_000_000L, 100f, 100f, 50f)
        val first = shape.strands.map { it.copyOf() }
        val particles = shape.particles.copyOf()
        shape.fill(spectrum, .25f, 1_000_000L, 100f, 100f, 50f)
        first.forEachIndexed { index, points -> assertArrayEquals(points, shape.strands[index], 0f) }
        assertArrayEquals(particles, shape.particles, 0f)
        shape.fill(spectrum, .25f, 1_016_667L, 100f, 100f, 50f)
        assertTrue(shape.particles.indices.any { kotlin.math.abs(particles[it] - shape.particles[it]) > .001f })
    }

    @Test fun energyChangesTheFilamentsWithoutInvalidCoordinates() {
        val shape = SphereInteriorGeometry()
        shape.fill(emptyList(), 0f, 10_000L, 0f, 0f, 50f)
        val quiet = shape.strands[0].copyOf()
        shape.fill(listOf(Float.NaN, Float.POSITIVE_INFINITY, .8f), .8f, 10_000L, 0f, 0f, 50f)
        assertTrue(shape.strands.all { strand -> strand.all { it.isFinite() } })
        assertTrue(shape.particles.all { it.isFinite() })
        assertTrue(quiet.indices.any { kotlin.math.abs(quiet[it] - shape.strands[0][it]) > .01f })
        for (particle in 0 until shape.particleCount) assertTrue(shape.particles[particle * 3 + 2] in 0f..1f)
    }
}
