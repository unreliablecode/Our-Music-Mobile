package com.github.musicyou.ui.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.AudioWaveformChannel
import com.github.musicyou.ui.glass.AdaptiveText
import com.github.musicyou.ui.glass.GlassSurface
import com.github.musicyou.ui.glass.adaptiveContentColor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class VisualizationMode { SPECTRUM, WAVEFORM, BOTH, OSCILLOSCOPE, OFF }

/** Displays the player's decoded samples without synthesizing motion or audio. */
@Composable
fun AudioVisualization(
    frame: AudioVisualizationFrame?,
    mode: VisualizationMode,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    AudioVisualization(frameProvider = { frame }, mode = mode, playing = playing, modifier = modifier)
}

/** Frame reads stay in drawing; the surrounding player need not recompose at audio cadence. */
@Composable
fun AudioVisualization(
    frameProvider: () -> AudioVisualizationFrame?,
    mode: VisualizationMode,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    if (mode == VisualizationMode.OFF) return
    if (mode == VisualizationMode.OSCILLOSCOPE) {
        OscilloscopeScreen(frameProvider, playing, modifier)
        return
    }
    val spectrum = mode == VisualizationMode.SPECTRUM || mode == VisualizationMode.BOTH
    val waveform = mode == VisualizationMode.WAVEFORM || mode == VisualizationMode.BOTH
    val presentation by remember(frameProvider, playing, spectrum, waveform) {
        derivedStateOf(structuralEqualityPolicy()) {
            val frame = frameProvider()
            val audio = frame?.takeIf { playing && it.ready }
            val channels = audio?.waveformChannels.orEmpty()
            val hasAudio = spectrum && audio != null && audio.spectrum.isNotEmpty() && audio.frequenciesHz.isNotEmpty() ||
                waveform && channels.any { it.minimum.isNotEmpty() && it.maximum.isNotEmpty() }
            PlotPresentation(
                status = if (!playing) "Paused" else if (hasAudio) "Playing" else "Waiting for audio",
                channelCount = channels.size,
                upperFrequency = min(20_000f, (frame?.sampleRate?.takeIf { it > 40 } ?: 40_000) / 2f),
            )
        }
    }
    val title = when (mode) {
        VisualizationMode.SPECTRUM -> "Frequency spectrum"
        VisualizationMode.WAVEFORM -> "Waveform"
        VisualizationMode.BOTH -> "Spectrum and waveform"
        VisualizationMode.OSCILLOSCOPE -> "Oscilloscope"
        VisualizationMode.OFF -> ""
    }

    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val chartCount = if (mode == VisualizationMode.BOTH) 2 else 1
            val overhead = if (chartCount == 2) 71.dp else 63.dp
            val largestChart = if (chartCount == 2) 54.dp else 88.dp
            val plotHeight = if (maxHeight.value.isFinite()) ((maxHeight - overhead) / chartCount).coerceIn(12.dp, largestChart) else largestChart
            val ink = adaptiveContentColor()
            val accent = adaptiveContentColor(MaterialTheme.colorScheme.primary)
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    AdaptiveText(title, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    AdaptiveText(presentation.status, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(8.dp))
                if (waveform) {
                    VisualizationPlot(
                        frameProvider = frameProvider, playing = playing, channelCount = presentation.channelCount,
                        spectrum = false, upperFrequency = presentation.upperFrequency,
                        plotHeight = plotHeight, accent = accent, ink = ink, status = presentation.status,
                        tag = if (mode == VisualizationMode.BOTH) "audio-waveform-plot" else "audio-visualization-plot",
                    )
                }
                if (waveform && spectrum) Spacer(Modifier.height(8.dp))
                if (spectrum) {
                    VisualizationPlot(
                        frameProvider = frameProvider, playing = playing, channelCount = presentation.channelCount,
                        spectrum = true, upperFrequency = presentation.upperFrequency,
                        plotHeight = plotHeight, accent = accent, ink = ink, status = presentation.status,
                        tag = if (mode == VisualizationMode.BOTH) "audio-spectrum-plot" else "audio-visualization-plot",
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 34.dp, top = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (spectrum) {
                        repeat(4) { index ->
                            val frequency = exp(ln(20f) + ln(presentation.upperFrequency / 20f) * index / 3f)
                            AxisLabel(frequencyLabel(frequency))
                        }
                    } else {
                        AxisLabel("Earlier")
                        AxisLabel("Recent audio")
                    }
                }
            }
        }
    }
}

private data class PlotPresentation(val status: String, val channelCount: Int, val upperFrequency: Float)

@Composable
private fun AxisLabel(label: String) {
    AdaptiveText(label, fontSize = 10.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

@Composable
private fun VisualizationPlot(
    frameProvider: () -> AudioVisualizationFrame?,
    playing: Boolean,
    channelCount: Int,
    spectrum: Boolean,
    upperFrequency: Float,
    plotHeight: Dp,
    accent: Color,
    ink: Color,
    status: String,
    tag: String,
) {
    val waveformPaths = remember { List(2) { Path() } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.width(34.dp).height(plotHeight),
            verticalArrangement = if (!spectrum && channelCount > 1) Arrangement.SpaceAround else Arrangement.SpaceBetween,
        ) {
            if (spectrum) {
                AxisLabel("0 dB")
                if (plotHeight >= 36.dp) AxisLabel("−36")
                AxisLabel("−72")
            } else if (channelCount > 1) {
                AxisLabel("L ±1")
                AxisLabel("R ±1")
            } else {
                AxisLabel("+1")
                if (plotHeight >= 36.dp) AxisLabel("0")
                AxisLabel("−1")
            }
        }
        Canvas(
            Modifier.weight(1f).height(plotHeight).preferredFrameRate(if (playing) 60f else 0f).testTag(tag).semantics {
                contentDescription = if (spectrum) {
                    "Audio frequency spectrum from 20 hertz to ${upperFrequency.roundToInt()} hertz"
                } else if (channelCount > 1) {
                    "Stereo audio waveform, left channel above right channel, signed amplitude"
                } else "Audio waveform with signed amplitude from minus one to plus one"
                stateDescription = status
            },
        ) {
            val frame = frameProvider()?.takeIf { playing && it.ready }
            if (spectrum) drawSpectrum(frame, upperFrequency, accent, ink.copy(alpha = 0.14f))
            else drawWaveform(frame?.waveformChannels.orEmpty(), waveformPaths, accent, ink.copy(alpha = 0.14f))
        }
    }
}

private fun DrawScope.drawSpectrum(frame: AudioVisualizationFrame?, upperFrequency: Float, accent: Color, grid: Color) {
    repeat(3) { index ->
        val y = (size.height - 1f) * index / 2f
        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
    }
    val spectrum = frame?.spectrum ?: return
    val frequencies = frame.frequenciesHz
    val count = min(spectrum.size, frequencies.size)
    if (count == 0) return
    val logSpan = ln(upperFrequency / 20f).coerceAtLeast(0.01f)
    fun x(frequency: Float): Float = (ln(frequency.coerceIn(20f, upperFrequency) / 20f) / logSpan) * size.width
    for (index in 0 until count) {
        val center = frequencies[index]
        val amplitude = spectrum[index]
        if (!center.isFinite() || !amplitude.isFinite() || amplitude <= 0f) continue
        val leftFrequency = if (index == 0) 20f else sqrt(frequencies[index - 1] * center)
        val rightFrequency = if (index == count - 1) upperFrequency else sqrt(center * frequencies[index + 1])
        if (!leftFrequency.isFinite() || !rightFrequency.isFinite()) continue
        val left = x(leftFrequency) + 0.6.dp.toPx()
        val right = x(rightFrequency) - 0.6.dp.toPx()
        val height = amplitude.coerceIn(0f, 1f) * size.height
        if (right > left && height > 0f) {
            drawRoundRect(
                accent,
                topLeft = Offset(left, size.height - height),
                size = Size(right - left, height),
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

private fun DrawScope.drawWaveform(channels: List<AudioWaveformChannel>, paths: List<Path>, accent: Color, grid: Color) {
    val laneCount = channels.size.coerceAtLeast(1)
    val laneHeight = size.height / laneCount
    repeat(laneCount) { channelIndex ->
        val top = laneHeight * channelIndex
        val baseline = top + laneHeight / 2f
        drawLine(grid, Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
        val channel = channels.getOrNull(channelIndex) ?: return@repeat
        val count = min(channel.minimum.size, channel.maximum.size)
        if (count == 0) return@repeat
        val amplitudeHeight = (laneHeight / 2f - 2.dp.toPx()).coerceAtLeast(1f)
        val stroke = (size.width / count).coerceAtLeast(1.dp.toPx())
        val path = paths[channelIndex]
        path.reset()
        for (index in 0 until count) {
            val minimum = channel.minimum[index]
            val maximum = channel.maximum[index]
            if (!minimum.isFinite() || !maximum.isFinite()) continue
            if (abs(minimum) < 0.0001f && abs(maximum) < 0.0001f) continue
            val x = size.width * (index + 0.5f) / count
            path.moveTo(x, baseline - maximum.coerceIn(-1f, 1f) * amplitudeHeight)
            path.lineTo(x + 0.001f, baseline - minimum.coerceIn(-1f, 1f) * amplitudeHeight)
        }
        drawPath(path, accent, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

private fun frequencyLabel(frequency: Float): String = if (frequency >= 1_000f) {
    val khz = frequency / 1_000f
    if (khz >= 10f || abs(khz - khz.roundToInt()) < 0.05f) "${khz.roundToInt()} kHz"
    else "${(khz * 10f).roundToInt() / 10f} kHz"
} else "${frequency.roundToInt()} Hz"
