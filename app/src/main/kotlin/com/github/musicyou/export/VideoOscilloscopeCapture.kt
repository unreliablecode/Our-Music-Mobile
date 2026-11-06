package com.github.musicyou.export

import com.github.musicyou.audio.visualization.AudioOscilloscopeFrame
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.OscilloscopeAcquisition
import com.github.musicyou.audio.visualization.OscilloscopeConfig

/** Applies saved scope controls to live preview frames that were analyzed with app defaults. */
internal fun videoOscilloscopeCapture(
    frame: AudioVisualizationFrame?,
    configuration: OscilloscopeConfig,
): AudioOscilloscopeFrame? {
    if (frame == null) return null
    val source = frame.oscilloscope
    val requested = configuration.normalized(
        frame.sampleRate,
        source.rawChannels.firstOrNull()?.size ?: 0,
        source.rawChannels.size,
    )
    return when {
        source.rawChannels.isEmpty() -> source
        source.configuration.hasSameAcquisitionSettings(requested) -> source.copy(configuration = requested)
        else -> OscilloscopeAcquisition.capture(source.rawChannels, frame.sampleRate, requested)
    }
}

private fun OscilloscopeConfig.hasSameAcquisitionSettings(other: OscilloscopeConfig): Boolean =
    timebaseUsPerDivision == other.timebaseUsPerDivision && triggerLevel == other.triggerLevel &&
        triggerEdge == other.triggerEdge && triggerMode == other.triggerMode && triggerSource == other.triggerSource
