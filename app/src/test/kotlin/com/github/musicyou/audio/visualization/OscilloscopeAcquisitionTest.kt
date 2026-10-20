package com.github.musicyou.audio.visualization

import kotlin.math.abs
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OscilloscopeAcquisitionTest {
    @Test
    fun fractionalTriggerPreservesEveryNyquistSampleAndItsFullAmplitude() {
        val samples = FloatArray(4096) { if (it % 2 == 0) -1f else 1f }
        val capture = OscilloscopeAcquisition.capture(listOf(channel(samples)), 48_000)
        assertTrue(capture.triggered)
        assertEquals(0.5, capture.originSamples, 0.000001)
        val trace = capture.channels.single()
        for (index in 0 until trace.size) {
            assertEquals("A fractional trigger must never average away native full-scale samples", 1f, abs(trace[index]), 0f)
            if (index > 0) assertEquals("The native alternating polarity is preserved", -trace[index - 1], trace[index], 0f)
        }
        val measurement = capture.measurements.single()
        assertEquals(1f, measurement.rms, 0f)
        assertEquals(1f, measurement.sampledPeak, 0f)
        assertEquals(2f, measurement.peakToPeak, 0f)
    }

    @Test
    fun fortyFourPointOneKilohertzKeepsAnExactContinuousTimebase() {
        val samples = FloatArray(4096) { it / 4096f }
        val capture = OscilloscopeAcquisition.capture(
            listOf(channel(samples)), 44_100,
            OscilloscopeConfig(timebaseUsPerDivision = 100),
        )
        assertEquals("Ten divisions remain exactly one millisecond", 1_000L, capture.durationUs)
        assertEquals(100, capture.configuration.timebaseUsPerDivision)
        val trace = capture.channels.single()
        val firstUs = -capture.originSamples * 1_000_000.0 / capture.sampleRate
        val lastUs = (trace.size - 1 - capture.originSamples) * 1_000_000.0 / capture.sampleRate
        assertTrue("A native point before the trigger permits clipping at the exact left edge", firstUs < 0.0)
        assertTrue("A native point after the screen permits clipping at the exact right edge", lastUs > capture.durationUs)
        for (index in 1 until trace.size) {
            assertEquals("Native amplitudes are not resampled to rounded screen intervals", 1f / 4096f, trace[index] - trace[index - 1], 0f)
            val previousUs = (index - 1 - capture.originSamples) * 1_000_000.0 / capture.sampleRate
            val currentUs = (index - capture.originSamples) * 1_000_000.0 / capture.sampleRate
            assertEquals("Adjacent points keep the exact decoded sample interval", 1_000_000.0 / 44_100, currentUs - previousUs, 0.000001)
        }
    }

    @Test
    fun selectedChannelLevelAndBothTriggerSlopesLocateTheMeasuredCrossing() {
        val right = FloatArray(4096) { index -> (index % 100) / 50f - 1f }
        val left = FloatArray(4096) { 0.8f }
        for (edge in ScopeTriggerEdge.entries) {
            val capture = OscilloscopeAcquisition.capture(
                listOf(channel(left), channel(right)), 48_000,
                OscilloscopeConfig(triggerSource = 1, triggerLevel = 0.25f, triggerEdge = edge),
            )
            assertTrue("The selected right channel has a valid $edge crossing", capture.triggered)
            assertEquals(edge == ScopeTriggerEdge.RISING, capture.risingEdgeTriggered)
            val trace = capture.channels[1]
            val before = trace[0]
            val after = trace[1]
            val levelAtOrigin = before + (after - before) * capture.originSamples
            assertEquals("The fractional x origin locates the requested trigger level", 0.25, levelAtOrigin, 0.000001)
            assertTrue("The captured edge has the requested direction", if (edge == ScopeTriggerEdge.RISING) after > before else after < before)
            assertTrue("Stereo channels share the same time origin", (0 until capture.channels[0].size).all { capture.channels[0][it] == 0.8f })
        }
    }

    @Test
    fun normalModeReportsAnUnavailableTriggerWithoutInventingAnEdge() {
        val capture = OscilloscopeAcquisition.capture(
            listOf(channel(FloatArray(4096) { 0.4f })), 48_000,
            OscilloscopeConfig(triggerMode = ScopeTriggerMode.NORMAL),
        )
        assertFalse(capture.triggered)
        assertFalse(capture.risingEdgeTriggered)
        assertTrue(capture.waitingForTrigger)
        val automatic = OscilloscopeAcquisition.capture(
            capture.rawChannels, 48_000, OscilloscopeConfig(triggerMode = ScopeTriggerMode.AUTO),
        )
        assertFalse(automatic.triggered)
        assertFalse(automatic.waitingForTrigger)
        assertEquals("Automatic acquisition keeps real DC at its measured amplitude", 0.4f, automatic.measurements.single().rms, 0.000001f)
    }

    @Test
    fun everyTimebaseFitsTheRealSampleWindowAtItsActualRate() {
        for (rate in listOf(8_000, 44_100, 48_000, 96_000, 192_000, 384_000)) {
            val choices = OscilloscopeConfig.supportedTimebases(rate, 4096)
            assertTrue(choices.isNotEmpty())
            for (choice in choices) {
                assertTrue("A selectable screen must fit its complete native window", ceil(choice * 10.0 * rate / 1_000_000.0).toInt() + 2 <= 4096)
            }
            val capture = OscilloscopeAcquisition.capture(
                listOf(channel(FloatArray(4096))), rate,
                OscilloscopeConfig(timebaseUsPerDivision = Int.MAX_VALUE),
            )
            assertEquals("An unsupported request selects an actual supported timebase", choices.last(), capture.configuration.timebaseUsPerDivision)
            assertEquals("The label duration follows the acquired timebase", choices.last() * 10L, capture.durationUs)
            assertTrue(capture.channels.single().size <= 4096)
        }
        assertEquals(listOf(100, 200, 500, 1_000), OscilloscopeConfig.supportedTimebases(384_000, 4096))
        assertTrue(OscilloscopeConfig.supportedTimebases(48_000, 6).isEmpty())
    }

    @Test
    fun invalidCalibrationAndCoordinatesCannotCreateInvalidMeasurements() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            val config = OscilloscopeConfig(
                fullScalePerDivision = Float.NaN,
                triggerLevel = Float.POSITIVE_INFINITY,
                triggerSource = 1,
                calibratedVoltsPerFullScale = invalid,
                cursorA = Float.NaN,
                cursorB = 2f,
            ).normalized(48_000, 4096, 1)
            assertEquals(null, config.calibratedVoltsPerFullScale)
            assertEquals(0.25f, config.fullScalePerDivision, 0f)
            assertEquals(0f, config.triggerLevel, 0f)
            assertEquals("Mono uses its available channel", 0, config.triggerSource)
            assertEquals(0.25f, config.cursorA, 0f)
            assertEquals(1f, config.cursorB, 0f)
        }
        val calibrated = OscilloscopeConfig(calibratedVoltsPerFullScale = 2f).normalized(48_000, 4096, 2)
        assertEquals(2f, calibrated.calibratedVoltsPerFullScale!!, 0f)
    }

    private fun channel(samples: FloatArray) = AudioOscilloscopeChannel(samples)
}
