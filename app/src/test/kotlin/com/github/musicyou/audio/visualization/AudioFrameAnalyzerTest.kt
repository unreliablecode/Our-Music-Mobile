package com.github.musicyou.audio.visualization

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFrameAnalyzerTest {
    @Test
    fun knownTonesKeepTheirFrequencyAcrossSampleRates() {
        for (rate in listOf(8_000, 22_050, 44_100, 48_000, 96_000)) {
            for (frequency in listOf(440.0, 1_000.0)) {
                val frame = AudioFrameAnalyzer(rate, 1).analyze(tone(rate, frequency, 0.5f))
                val strongest = frame.spectrum.indices.maxBy { frame.spectrum[it] }
                assertTrue("$frequency Hz at $rate Hz stays in its logarithmic band", abs(frame.frequenciesHz[strongest] / frequency - 1.0) < 0.18)
                assertTrue("A decoded tone has visible measured energy", frame.spectrum[strongest] > 0.75f)
                assertEquals(0.5f / sqrt(2f), frame.rms, 0.004f)
                assertEquals(0.5f, frame.peak, 0.003f)
            }
        }
    }

    @Test
    fun lowerAmplitudeLowersMeasuredEnergyWithoutChangingPitch() {
        val analyzer = AudioFrameAnalyzer(48_000, 1)
        val loud = analyzer.analyze(tone(48_000, 1_000.0, 0.8f))
        val quiet = analyzer.analyze(tone(48_000, 1_000.0, 0.08f))
        val strongest = loud.spectrum.indices.maxBy { loud.spectrum[it] }
        assertEquals(20f / 72f, loud.spectrum[strongest] - quiet.spectrum[strongest], 0.005f)
        assertEquals(loud.rms / 10f, quiet.rms, 0.001f)
        assertEquals(loud.peak / 10f, quiet.peak, 0.001f)
    }

    @Test
    fun silenceHasNoSpectrumOrWaveformAndKeepsItsTimestamp() {
        val frame = AudioFrameAnalyzer(44_100, 2).analyze(FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * 2), 123_456L, 8L)
        assertTrue(frame.ready)
        assertEquals(123_456L, frame.positionUs)
        assertEquals(8L, frame.sequence)
        assertTrue(frame.spectrum.all { it == 0f })
        assertEquals(0f, frame.rms, 0f)
        assertEquals(0f, frame.peak, 0f)
        assertTrue(frame.waveformChannels.all { channel -> channel.minimum.all { it == 0f } && channel.maximum.all { it == 0f } })
    }

    @Test
    fun oppositeStereoChannelsPreservePowerAndSignedWaveforms() {
        val mono = tone(48_000, 1_000.0, 0.6f)
        val samples = FloatArray(mono.size * 2) { index -> if (index % 2 == 0) mono[index / 2] else -mono[index / 2] }
        val analyzer = AudioFrameAnalyzer(48_000, 2)
        val stereo = analyzer.analyze(samples)
        val single = AudioFrameAnalyzer(48_000, 1).analyze(mono)
        assertEquals(single.rms, stereo.rms, 0.0001f)
        assertEquals(single.spectrum.max(), stereo.spectrum.max(), 0.0001f)
        val left = stereo.waveformChannels[0]
        val right = stereo.waveformChannels[1]
        for (point in left.minimum.indices) {
            assertEquals("Opposite polarity retains its positive peak", -left.minimum[point], right.maximum[point], 0.0001f)
            assertEquals("Opposite polarity retains its negative peak", -left.maximum[point], right.minimum[point], 0.0001f)
        }
    }

    @Test
    fun narrowImpulsesSurviveWaveformDownsampling() {
        val samples = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE)
        samples[321] = 0.9f
        samples[789] = -0.8f
        val frame = AudioFrameAnalyzer(48_000, 1).analyze(samples)
        val channel = frame.waveformChannels.single()
        assertEquals(0.9f, channel.maximum.max(), 0f)
        assertEquals(-0.8f, channel.minimum.min(), 0f)
        assertEquals(0.9f, frame.peak, 0f)
    }

    @Test
    fun pcm16ConversionPreservesTheBufferAndBothPolarities() {
        val buffer = ByteBuffer.allocate(12).order(ByteOrder.nativeOrder())
        buffer.putShort(1234).putShort(Short.MIN_VALUE).putShort(-16384).putShort(0).putShort(16384).putShort(Short.MAX_VALUE).flip()
        buffer.position(2)
        val before = buffer.array().copyOf()
        val values = PcmSamples.read(buffer, PcmSamples.PCM_16)
        assertEquals(2, buffer.position())
        assertTrue(before.contentEquals(buffer.array()))
        assertTrue(floatArrayOf(-1f, -0.5f, 0f, 0.5f, 32767f / 32768f).contentEquals(values))
    }

    @Test
    fun floatConversionKeepsSilenceFiniteAndLeavesAudioUntouched() {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
        for (sample in floatArrayOf(-0.75f, 0f, 0.25f, Float.NaN, Float.POSITIVE_INFINITY, 3f)) buffer.putFloat(sample)
        buffer.flip()
        val before = buffer.array().copyOf()
        val values = PcmSamples.read(buffer, PcmSamples.PCM_FLOAT)
        assertEquals(0, buffer.position())
        assertTrue(before.contentEquals(buffer.array()))
        assertTrue(floatArrayOf(-0.75f, 0f, 0.25f, 0f, 0f, 1f).contentEquals(values))
    }

    private fun tone(sampleRate: Int, frequency: Double, amplitude: Float) =
        FloatArray(AudioFrameAnalyzer.WINDOW_SIZE) { amplitude * sin(2.0 * PI * frequency * it / sampleRate).toFloat() }
}
