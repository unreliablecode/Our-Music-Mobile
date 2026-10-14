package com.github.musicyou.ui.visualization

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.ui.glass.GlassBackdropRoot
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Real PCM is analyzed, then the hardware-rendered trace is checked. */
@RunWith(AndroidJUnit4::class)
class AudioVisualizationRenderingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun spectrumFollowsDecodedToneFrequencyAndHasNoInventedMotion() {
        val analyzer = AudioFrameAnalyzer(48_000, 1)
        val frame = mutableStateOf(analyzer.analyze(FloatArray(AudioFrameAnalyzer.WINDOW_SIZE)))
        compose.mainClock.autoAdvance = false
        scene(frame, mutableStateOf(VisualizationMode.SPECTRUM), mutableStateOf(true))
        settle()
        val silence = plot()

        compose.runOnIdle { frame.value = analyzer.analyze(tone(48_000, 187.5), sequence = 1L) }
        settle()
        val bass = plot()
        val bassPeak = strongestChangedColumn(silence, bass)
        assertTrue("187.5 Hz belongs in the lower third of a 20 Hz to 20 kHz log axis", bassPeak in 0.25f..0.4f)

        compose.runOnIdle { frame.value = analyzer.analyze(tone(48_000, 3_000.0), sequence = 2L) }
        settle()
        val treble = plot()
        val treblePeak = strongestChangedColumn(silence, treble)
        assertTrue("3 kHz appears near the upper quarter of the log axis", treblePeak in 0.65f..0.82f)
        assertTrue("The visible peak moves when the decoded frequency changes", treblePeak > bassPeak + 0.25f)

        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertTrue("Unchanged decoded audio does not produce synthetic animation", meanDifference(treble, plot()) < 0.001f)
    }

    @Test
    fun waveformPreservesSignedTransientsAndSeparateStereoChannels() {
        val analyzer = AudioFrameAnalyzer(48_000, 2)
        val samples = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * 2)
        val frame = mutableStateOf(analyzer.analyze(samples))
        scene(frame, mutableStateOf(VisualizationMode.WAVEFORM), mutableStateOf(true))
        compose.waitForIdle()
        val silence = plot()

        // A short positive transient occurs early on the left. A negative
        // transient occurs later on the right; neither may be averaged away.
        samples[AudioFrameAnalyzer.WINDOW_SIZE / 4 * 2] = 0.95f
        samples[AudioFrameAnalyzer.WINDOW_SIZE * 3 / 4 * 2 + 1] = -0.95f
        compose.runOnIdle { frame.value = analyzer.analyze(samples, sequence = 1L) }
        compose.waitForIdle()
        val waveform = plot()
        assertTrue("The left transient appears above its signed zero line", changedPixels(silence, waveform, 0.20f, 0.30f, 0.02f, 0.22f) > 15)
        assertTrue("A positive left sample is not drawn with negative polarity", changedPixels(silence, waveform, 0.20f, 0.30f, 0.28f, 0.47f) == 0)
        assertTrue("The right transient appears below its signed zero line", changedPixels(silence, waveform, 0.70f, 0.80f, 0.78f, 0.98f) > 15)
        assertTrue("A negative right sample is not drawn with positive polarity", changedPixels(silence, waveform, 0.70f, 0.80f, 0.53f, 0.72f) == 0)
        compose.onNodeWithTag("audio-visualization-plot").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Stereo audio waveform, left channel above right channel, signed amplitude"),
            ),
        )
    }

    @Test
    fun pausedAndMissingAudioShowNeutralTracesAndOffRemovesThePlot() {
        val analyzer = AudioFrameAnalyzer(48_000, 1)
        val frame = mutableStateOf(AudioVisualizationFrame(sampleRate = 48_000))
        val mode = mutableStateOf(VisualizationMode.SPECTRUM)
        val playing = mutableStateOf(true)
        scene(frame, mode, playing)
        compose.waitForIdle()
        val baseline = plot()
        assertStatus("Waiting for audio")

        compose.runOnIdle { frame.value = analyzer.analyze(tone(48_000, 1_000.0)) }
        compose.waitForIdle()
        assertTrue("Decoded audio adds measured bars", meanDifference(baseline, plot()) > 0.01f)
        assertStatus("Playing")

        compose.runOnIdle { playing.value = false }
        compose.waitForIdle()
        assertStatus("Paused")
        assertTrue("Pause returns to a neutral trace even when an old frame remains supplied", meanDifference(baseline, plot()) < 0.001f)
        compose.runOnIdle {
            playing.value = true
            frame.value = AudioVisualizationFrame(sampleRate = 48_000)
        }
        assertStatus("Waiting for audio")
        compose.runOnIdle { mode.value = VisualizationMode.OFF }
        compose.onNodeWithTag("audio-visualization-plot").assertDoesNotExist()
    }

    @Test
    fun frequencyCaptionStopsAtTheActualSampleRateNyquistLimit() {
        val frame = mutableStateOf(AudioFrameAnalyzer(8_000, 1).analyze(tone(8_000, 1_000.0)))
        scene(frame, mutableStateOf(VisualizationMode.SPECTRUM), mutableStateOf(true))
        compose.onNodeWithText("20 Hz").assertExists()
        compose.onNodeWithText("4 kHz").assertExists()
        compose.onNodeWithText("20 kHz").assertDoesNotExist()
        compose.onNodeWithTag("audio-visualization-plot").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Audio frequency spectrum from 20 hertz to 4000 hertz"),
            ),
        )
    }

    @Test
    fun bothModeRendersMeasuredSpectrumAndWaveformInsideOneCompactPanel() {
        val analyzer = AudioFrameAnalyzer(48_000, 1)
        val frame = mutableStateOf(analyzer.analyze(FloatArray(AudioFrameAnalyzer.WINDOW_SIZE)))
        scene(frame, mutableStateOf(VisualizationMode.BOTH), mutableStateOf(true), Modifier.height(180.dp))
        compose.waitForIdle()
        val silentSpectrum = compose.onNodeWithTag("audio-spectrum-plot").captureToImage()
        val silentWaveform = compose.onNodeWithTag("audio-waveform-plot").captureToImage()
        compose.runOnIdle { frame.value = analyzer.analyze(tone(48_000, 1_000.0), sequence = 1L) }
        compose.waitForIdle()
        assertTrue("Both mode displays the measured frequency energy", meanDifference(silentSpectrum, compose.onNodeWithTag("audio-spectrum-plot").captureToImage()) > 0.01f)
        assertTrue("Both mode displays the actual signed sample envelope", meanDifference(silentWaveform, compose.onNodeWithTag("audio-waveform-plot").captureToImage()) > 0.05f)
        compose.onNodeWithTag("audio-spectrum-plot").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.onNodeWithTag("audio-waveform-plot").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
        compose.onNodeWithText("Spectrum and waveform").assertExists()
        compose.onNodeWithText("20 kHz").assertExists()
    }

    private fun scene(
        frame: androidx.compose.runtime.State<AudioVisualizationFrame>,
        mode: androidx.compose.runtime.State<VisualizationMode>,
        playing: androidx.compose.runtime.State<Boolean>,
        panelModifier: Modifier = Modifier,
    ) {
        compose.setContent {
            MaterialTheme {
                GlassBackdropRoot(
                    modifier = Modifier.fillMaxSize(),
                    background = { Box(Modifier.fillMaxSize().background(Color.White)) },
                ) {
                    AudioVisualization(frame.value, mode.value, playing.value, Modifier.align(Alignment.Center).width(320.dp).then(panelModifier))
                }
            }
        }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun plot(): ImageBitmap = compose.onNodeWithTag("audio-visualization-plot").captureToImage()

    private fun assertStatus(status: String) {
        compose.onNodeWithTag("audio-visualization-plot").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
    }

    private fun tone(sampleRate: Int, frequency: Double): FloatArray =
        FloatArray(AudioFrameAnalyzer.WINDOW_SIZE) { (sin(2.0 * PI * frequency * it / sampleRate) * 0.8).toFloat() }

    private fun strongestChangedColumn(before: ImageBitmap, after: ImageBitmap): Float {
        val initial = before.toPixelMap()
        val changed = after.toPixelMap()
        val counts = IntArray(after.width) { x ->
            (0 until after.height).count { y -> colorDifference(initial[x, y], changed[x, y]) > 0.15f }
        }
        val column = counts.indices.maxBy { counts[it] }
        assertTrue("A measured tone must produce visible spectrum energy", counts[column] > after.height / 3)
        return (column + 0.5f) / after.width
    }

    private fun changedPixels(before: ImageBitmap, after: ImageBitmap, left: Float, right: Float, top: Float, bottom: Float): Int {
        val initial = before.toPixelMap()
        val changed = after.toPixelMap()
        var count = 0
        for (y in (after.height * top).toInt() until (after.height * bottom).toInt()) {
            for (x in (after.width * left).toInt() until (after.width * right).toInt()) {
                if (colorDifference(initial[x, y], changed[x, y]) > 0.15f) count++
            }
        }
        return count
    }

    private fun meanDifference(before: ImageBitmap, after: ImageBitmap): Float {
        val initial = before.toPixelMap()
        val changed = after.toPixelMap()
        var difference = 0f
        for (y in 0 until after.height) {
            for (x in 0 until after.width) difference += colorDifference(initial[x, y], changed[x, y])
        }
        return difference / (after.width * after.height)
    }

    private fun colorDifference(first: Color, second: Color): Float =
        abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)
}
