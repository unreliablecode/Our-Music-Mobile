package com.github.musicyou.ui.visualization

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.musicyou.audio.visualization.AudioOscilloscopeFrame
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.OscilloscopeAcquisition
import com.github.musicyou.audio.visualization.OscilloscopeConfig
import com.github.musicyou.audio.visualization.ScopeTriggerEdge
import com.github.musicyou.ui.glass.AdaptiveIcon
import com.github.musicyou.ui.glass.AdaptiveText
import com.github.musicyou.ui.glass.GlassIconButton
import com.github.musicyou.ui.glass.GlassSurface
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

private val ScreenColor = Color(0xFF06141A)
private val LeftTrace = Color(0xFF77F5DB)
private val RightTrace = Color(0xFFFFC78D)

/** Native decoded samples, exact time coordinates, and an explicitly digital amplitude scale. */
@Composable
internal fun OscilloscopeScreen(frameProvider: () -> AudioVisualizationFrame?, playing: Boolean, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) { ScopePreferences(context) }
    val requested = preferences.value
    var running by remember { mutableStateOf(true) }
    var heldAudio by remember { mutableStateOf<AudioVisualizationFrame?>(null) }
    var controlsOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val capture = remember { mutableStateOf(AudioOscilloscopeFrame()) }
    val readout = remember { mutableStateOf<AudioOscilloscopeFrame?>(null) }
    val status = remember { mutableStateOf(if (playing) "Waiting for audio" else "Paused") }
    val lastValidFormat = remember { mutableStateOf(ScopeFormat(0, 0, 0)) }
    val observedFormat = remember(frameProvider, running, heldAudio) {
        {
            val frame = if (running) frameProvider() else heldAudio
            ScopeFormat(frame?.sampleRate ?: 0, frame?.oscilloscope?.rawChannels?.firstOrNull()?.size ?: 0,
                frame?.oscilloscope?.rawChannels?.size ?: 0)
        }
    }
    LaunchedEffect(observedFormat) {
        snapshotFlow { observedFormat() }.collect { if (it.sampleRate > 0 && it.sampleCount > 0) lastValidFormat.value = it }
    }
    val format by remember(observedFormat) {
        derivedStateOf(structuralEqualityPolicy()) {
            observedFormat().takeIf { it.sampleRate > 0 && it.sampleCount > 0 } ?: lastValidFormat.value
        }
    }
    val effective = requested.normalized(format.sampleRate, format.sampleCount, format.channelCount)
    val drawing = remember(frameProvider, playing, running, heldAudio, requested) {
        derivedStateOf {
            val audio = if (running) frameProvider()?.takeIf { playing && it.ready } else heldAudio
            val scope = audio?.oscilloscope
            val target = requested.normalized(audio?.sampleRate ?: 0,
                scope?.rawChannels?.firstOrNull()?.size ?: 0, scope?.rawChannels?.size ?: 0)
            if (audio == null && running) AudioOscilloscopeFrame()
            else if (scope != null && scope.configuration.sameAcquisitionAs(target) && !scope.waitingForTrigger) scope
            else capture.value.takeIf { it.configuration.sameAcquisitionAs(target) } ?: AudioOscilloscopeFrame()
        }
    }
    LaunchedEffect(frameProvider, playing, running, heldAudio, requested) {
        var lastReadoutNanos = Long.MIN_VALUE
        snapshotFlow { if (running) frameProvider()?.takeIf { playing && it.ready } else heldAudio }.collectLatest { audio ->
            if (audio == null) {
                capture.value = AudioOscilloscopeFrame()
                readout.value = null
                status.value = if (running && !playing) "Paused" else "Waiting for audio"
                return@collectLatest
            }
            val config = requested.normalized(audio.sampleRate,
                audio.oscilloscope.rawChannels.firstOrNull()?.size ?: 0, audio.oscilloscope.rawChannels.size)
            val next = if (audio.oscilloscope.configuration.sameAcquisitionAs(config)) audio.oscilloscope.copy(configuration = config)
                else withContext(Dispatchers.Default) {
                    OscilloscopeAcquisition.capture(audio.oscilloscope.rawChannels, audio.sampleRate, config)
            }
            status.value = when {
                !running -> "Hold"
                next.waitingForTrigger -> "Waiting trigger"
                next.channels.isEmpty() -> "Waiting for audio"
                next.triggered -> "Triggered"
                else -> "Auto"
            }
            capture.value = if (next.waitingForTrigger) next.copy(channels = emptyList()) else next
            if (next.waitingForTrigger) readout.value = null
            val now = SystemClock.elapsedRealtimeNanos()
            if (lastReadoutNanos == Long.MIN_VALUE || now - lastReadoutNanos >= 200_000_000L || !running) {
                readout.value = next.takeUnless { it.waitingForTrigger }
                lastReadoutNanos = now
            }
        }
    }
    val hold = {
        if (running) heldAudio = frameProvider()?.takeIf { it.ready }
        running = !running
    }
    if (!expanded) {
        ScopePanel(drawing, readout, status, effective, format, running, playing && running, hold,
            onExpand = { expanded = true }, onControls = { controlsOpen = true }, modifier = modifier)
    } else {
        GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tint = ScreenColor.copy(alpha = 0.93f)) {
            Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                AdaptiveText("Oscilloscope expanded", fontSize = 12.sp)
            }
        }
        Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            ScopePanel(drawing, readout, status, effective, format, running, playing && running, hold,
                onExpand = { expanded = false }, onControls = { controlsOpen = true },
                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(max = 620.dp), expanded = true)
        }
    }
    if (controlsOpen) {
        ScopeControlsDialog(config = effective, sampleRate = format.sampleRate, sampleCount = format.sampleCount,
            channelCount = format.channelCount.coerceAtLeast(1), onChange = preferences::update,
            onDismiss = { controlsOpen = false })
    }
}

private data class ScopeFormat(val sampleRate: Int, val sampleCount: Int, val channelCount: Int)

private fun OscilloscopeConfig.sameAcquisitionAs(other: OscilloscopeConfig): Boolean =
    timebaseUsPerDivision == other.timebaseUsPerDivision && triggerLevel == other.triggerLevel &&
        triggerEdge == other.triggerEdge && triggerMode == other.triggerMode && triggerSource == other.triggerSource

@Composable
private fun ScopePanel(
    capture: State<AudioOscilloscopeFrame>, readout: State<AudioOscilloscopeFrame?>, status: State<String>,
    config: OscilloscopeConfig, format: ScopeFormat, running: Boolean, presenting: Boolean, onHold: () -> Unit,
    onExpand: () -> Unit, onControls: () -> Unit, modifier: Modifier, expanded: Boolean = false,
) {
    VisualizationFrameRate(active = presenting)
    val paths = remember { List(2) { Path() } }
    val unit = if (config.calibratedVoltsPerFullScale == null) "FS" else "V"
    val factor = config.calibratedVoltsPerFullScale?.toDouble() ?: 1.0
    val triggerStyle = if (config.triggerEdge == ScopeTriggerEdge.RISING) "rising" else "falling"
    val triggerSource = if (config.triggerSource == 0) "L" else "R"
    val verticalLabel = "${scopeNumber(config.fullScalePerDivision * factor)} $unit/div"
    val timebaseLabel = scopeTime(config.timebaseUsPerDivision.toDouble()) + "/div"
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), tint = ScreenColor.copy(alpha = 0.93f)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showReadouts = expanded || !maxHeight.value.isFinite() || maxHeight >= 140.dp
            val overhead = (if (showReadouts) { if (config.cursorsEnabled) 116.dp else 100.dp } else 84.dp) + if (expanded) 60.dp else 0.dp
            val plotHeight = if (maxHeight.value.isFinite()) (maxHeight - overhead).coerceIn(22.dp, if (expanded) 450.dp else 160.dp)
                else if (expanded) 360.dp else 120.dp
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        AdaptiveText("Oscilloscope", fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        ScopeStatus(status)
                    }
                    ScopeAction(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (running) "Hold oscilloscope" else "Run oscilloscope", onHold)
                    ScopeAction(if (expanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                        if (expanded) "Collapse oscilloscope" else "Expand oscilloscope", onExpand)
                    ScopeAction(Icons.Rounded.Tune, "Oscilloscope controls", onControls)
                }
                Row(Modifier.fillMaxWidth().height(plotHeight)) {
                    Box(Modifier.width(33.dp).height(plotHeight)) {
                        val labels = if (plotHeight >= 60.dp) listOf(4, 2, 0, -2, -4) else if (plotHeight >= 35.dp) listOf(4, 0, -4) else listOf(4, -4)
                        for (division in labels) {
                            AdaptiveText(scopeNumber(division * config.fullScalePerDivision * factor),
                                modifier = Modifier.offset(y = plotHeight * (4 - division) / 8 - 5.dp),
                                fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
                        }
                    }
                    Canvas(
                        Modifier.weight(1f).height(plotHeight).graphicsLayer().preferredFrameRate(if (presenting) 60f else 0f).testTag("audio-visualization-plot").semantics {
                            contentDescription = "Digital stereo oscilloscope, ten time divisions and eight amplitude divisions, $timebaseLabel, $verticalLabel, $triggerSource $triggerStyle trigger"
                            stateDescription = status.value
                        },
                    ) {
                        drawRect(ScreenColor)
                        val baseline = size.height / 2f
                        val pixelsPerFullScale = size.height / (8f * config.fullScalePerDivision)
                        for (division in 0..10) {
                            val x = size.width * division / 10f
                            drawLine(Color.White.copy(alpha = if (division == 5) 0.27f else 0.10f), Offset(x, 0f), Offset(x, size.height), 0.7.dp.toPx())
                        }
                        for (division in -4..4) {
                            val y = baseline - division * size.height / 8f
                            drawLine(Color.White.copy(alpha = if (division == 0) 0.27f else 0.10f), Offset(0f, y), Offset(size.width, y), 0.7.dp.toPx())
                        }
                        for (tick in 0..50) {
                            val x = size.width * tick / 50f
                            drawLine(Color.White.copy(alpha = 0.23f), Offset(x, baseline - 1.5.dp.toPx()), Offset(x, baseline + 1.5.dp.toPx()), 0.6.dp.toPx())
                        }
                        for (tick in 0..40) {
                            val y = size.height * tick / 40f
                            drawLine(Color.White.copy(alpha = 0.23f), Offset(size.width / 2f - 1.5.dp.toPx(), y), Offset(size.width / 2f + 1.5.dp.toPx(), y), 0.6.dp.toPx())
                        }
                        clipRect {
                            val triggerY = baseline - config.triggerLevel * pixelsPerFullScale
                            drawLine(LeftTrace.copy(alpha = 0.22f), Offset(0f, triggerY), Offset(size.width, triggerY),
                                strokeWidth = 0.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                            val frame = capture.value
                            frame.channels.forEachIndexed { index, channel ->
                                if (index > 1 || channel.size < 2 || frame.sampleRate <= 0 || frame.durationUs <= 0L) return@forEachIndexed
                                val path = paths[index]
                                path.reset()
                                val pixelsPerSample = size.width * 1_000_000.0 / (frame.sampleRate.toDouble() * frame.durationUs)
                                for (point in 0 until channel.size) {
                                    val x = ((point - frame.originSamples) * pixelsPerSample).toFloat()
                                    val y = baseline - channel[point] * pixelsPerFullScale
                                    if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                val color = if (index == 0) LeftTrace else RightTrace
                                drawPath(path, color.copy(alpha = 0.06f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawPath(path, color.copy(alpha = 0.18f), style = Stroke(2.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                                drawPath(path, color.copy(alpha = 0.96f), style = Stroke(1.05.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            if (config.cursorsEnabled) {
                                for ((cursor, color) in listOf(config.cursorA to Color(0xFFFFE49B), config.cursorB to Color(0xFFF6B4EA))) {
                                    val x = size.width * cursor
                                    drawLine(color.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                    val value = scopeCursorValue(frame, cursor, config.triggerSource)
                                    if (value != null) drawCircle(color, 2.5.dp.toPx(), Offset(x, baseline - value * pixelsPerFullScale))
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(start = 33.dp, top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (division in listOf(0, 2, 4, 6, 8, 10)) {
                        AdaptiveText(scopeTime(division * config.timebaseUsPerDivision.toDouble()), fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    AdaptiveText(timebaseLabel, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1)
                    AdaptiveText(verticalLabel, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AdaptiveText("L", color = LeftTrace, fontSize = 10.sp, lineHeight = 11.sp)
                        if (format.channelCount > 1) AdaptiveText("R", color = RightTrace, fontSize = 10.sp, lineHeight = 11.sp)
                    }
                }
                if (showReadouts) ScopeReadout(readout, config)
                if (expanded) {
                    AdaptiveText(
                        if (config.calibratedVoltsPerFullScale == null) "Decoded digital PCM before output volume. FS is digital full scale. Sampled peaks are not analog true peaks."
                        else "Calibrated volts use your V per 1 FS factor. This app does not measure the physical headphone or speaker voltage.",
                        modifier = Modifier.padding(top = 8.dp), fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeStatus(status: State<String>) {
    AdaptiveText(status.value, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1)
}

@Composable
private fun ScopeAction(image: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    GlassIconButton(onClick = onClick, modifier = Modifier.size(36.dp).padding(1.dp)) {
        AdaptiveIcon(imageVector = image, contentDescription = label, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ScopeReadout(readout: State<AudioOscilloscopeFrame?>, config: OscilloscopeConfig) {
    val frame = readout.value
    val measurement = frame?.measurements?.getOrNull(config.triggerSource)
    val factor = config.calibratedVoltsPerFullScale?.toDouble() ?: 1.0
    val unit = if (config.calibratedVoltsPerFullScale == null) "FS" else "V calibrated"
    val source = if (config.triggerSource == 0) "L" else "R"
    val text = if (measurement == null) "RMS $source: —  Sampled peak: —"
        else "RMS $source ${scopeNumber(measurement.rms * factor)}  Pk ${scopeNumber(measurement.sampledPeak * factor)}  P-P ${scopeNumber(measurement.peakToPeak * factor)} $unit"
    AdaptiveText(text, modifier = Modifier.padding(top = 4.dp), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
    if (config.cursorsEnabled) {
        val duration = frame?.durationUs ?: config.timebaseUsPerDivision * 10L
        val deltaUs = abs(config.cursorB.toDouble() - config.cursorA) * duration
        val reciprocal = if (deltaUs > 0.0001) "${scopeNumber(1_000_000.0 / deltaUs)} Hz" else "—"
        val a = frame?.let { scopeCursorValue(it, config.cursorA, config.triggerSource) }
        val b = frame?.let { scopeCursorValue(it, config.cursorB, config.triggerSource) }
        val deltaAmplitude = if (a != null && b != null) scopeNumber((b - a) * factor) else "—"
        AdaptiveText("Δt ${scopeTime(deltaUs.toDouble())}  1/Δt $reciprocal  Δ $deltaAmplitude ${if (config.calibratedVoltsPerFullScale == null) "FS" else "V"}",
            modifier = Modifier.padding(top = 3.dp), fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1)
    }
}

private fun scopeCursorValue(frame: AudioOscilloscopeFrame, fraction: Float, channelIndex: Int): Float? {
    val channel = frame.channels.getOrNull(channelIndex) ?: return null
    if (channel.size < 2 || frame.sampleRate <= 0) return null
    val position = frame.originSamples + fraction.toDouble() * frame.durationUs * frame.sampleRate / 1_000_000.0
    val first = position.toInt().coerceIn(0, channel.size - 1)
    val next = (first + 1).coerceAtMost(channel.size - 1)
    val blend = (position - first).toFloat().coerceIn(0f, 1f)
    return channel[first] + (channel[next] - channel[first]) * blend
}

private fun scopeTime(microseconds: Double): String = if (abs(microseconds) >= 1_000.0) "${scopeNumber(microseconds / 1_000.0)} ms" else "${scopeNumber(microseconds)} µs"

private fun scopeNumber(value: Double): String = when {
    !value.isFinite() -> "—"
    value == 0.0 -> "0"
    abs(value) >= 10_000.0 || abs(value) < 0.001 -> String.format(Locale.US, "%.2g", value)
    else -> String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}
