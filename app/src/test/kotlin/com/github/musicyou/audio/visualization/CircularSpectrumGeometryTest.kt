package com.github.musicyou.audio.visualization

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class CircularSpectrumGeometryTest {
    @Test fun silenceIsAStableCircleWithoutGeneratedMotion() {
        val shape = CircularSpectrumGeometry()
        shape.fill(emptyList(), 0f, 0f, 100f, 100f, 50f)
        val first = shape.coordinates.copyOf()
        for (point in 0 until shape.pointCount) {
            assertEquals(50f, hypot(first[point * 2] - 100f, first[point * 2 + 1] - 100f), 0.0001f)
        }
        shape.fill(emptyList(), 0f, 0f, 100f, 100f, 50f)
        assertArrayEquals(first, shape.coordinates, 0f)
    }

    @Test fun spectrumDisplacesOnlyItsMirroredFrequencyRegion() {
        val shape = CircularSpectrumGeometry(64)
        shape.fill(List(8) { 0f }, 0f, 0f, 0f, 0f, 100f)
        val quiet = shape.coordinates.copyOf()
        shape.fill(List(8) { if (it == 4) 1f else 0f }, 0f, 0f, 0f, 0f, 100f)
        assertEquals(quiet[0], shape.coordinates[0], 0f)
        assertEquals(quiet[1], shape.coordinates[1], 0f)
        assertTrue(shape.coordinates.indices.any { kotlin.math.abs(shape.coordinates[it] - quiet[it]) > 1f })
        for (point in 1 until 32) {
            val mirror = 64 - point
            assertEquals(-shape.coordinates[point * 2], shape.coordinates[mirror * 2], 0.0001f)
            assertEquals(shape.coordinates[point * 2 + 1], shape.coordinates[mirror * 2 + 1], 0.0001f)
        }
    }

    @Test fun corruptOrOutOfRangeInputCannotProduceInvalidGeometry() {
        val shape = CircularSpectrumGeometry()
        shape.fill(listOf(Float.NaN, Float.POSITIVE_INFINITY, -5f, 10f), Float.NaN, 5f, 0f, 0f, 100f)
        assertTrue(shape.coordinates.all { it.isFinite() })
        for (point in 0 until shape.pointCount) assertTrue(hypot(shape.coordinates[point * 2], shape.coordinates[point * 2 + 1]) <= 148f)
    }
}
