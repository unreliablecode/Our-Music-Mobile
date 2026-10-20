package com.github.musicyou.audio.visualization

import kotlin.math.ceil
import kotlin.math.abs

enum class ScopeTriggerEdge { RISING, FALLING }
enum class ScopeTriggerMode { AUTO, NORMAL }

data class OscilloscopeConfig(
    val timebaseUsPerDivision: Int = 1_000,
    val fullScalePerDivision: Float = 0.25f,
    val triggerLevel: Float = 0f,
    val triggerEdge: ScopeTriggerEdge = ScopeTriggerEdge.RISING,
    val triggerMode: ScopeTriggerMode = ScopeTriggerMode.AUTO,
    val triggerSource: Int = 0,
    val calibratedVoltsPerFullScale: Float? = null,
    val cursorsEnabled: Boolean = false,
    val cursorA: Float = 0.25f,
    val cursorB: Float = 0.75f,
) {
    fun normalized(sampleRate: Int, sampleCount: Int, channelCount: Int): OscilloscopeConfig {
        val choices = supportedTimebases(sampleRate, sampleCount)
        val timebase = choices.lastOrNull { it <= timebaseUsPerDivision } ?: choices.firstOrNull()
            ?: timebaseUsPerDivision.takeIf { it in listOf(100, 200, 500, 1_000, 2_000, 5_000) } ?: 1_000
        return copy(
            timebaseUsPerDivision = timebase,
            fullScalePerDivision = fullScalePerDivision.takeIf { it.isFinite() }?.coerceIn(0.125f, 1f) ?: 0.25f,
            triggerLevel = triggerLevel.takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f,
            triggerSource = triggerSource.coerceIn(0, (channelCount - 1).coerceIn(0, 1)),
            calibratedVoltsPerFullScale = calibratedVoltsPerFullScale?.takeIf { it.isFinite() && it > 0f },
            cursorA = cursorA.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.25f,
            cursorB = cursorB.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.75f,
        )
    }

    companion object {
        /** Every selectable ten-division screen fits in the actual decoded PCM window. */
        fun supportedTimebases(sampleRate: Int, sampleCount: Int): List<Int> {
            if (sampleRate <= 0 || sampleCount < 4) return emptyList()
            return listOf(100, 200, 500, 1_000, 2_000, 5_000).filter {
                ceil(it * 10.0 * sampleRate / 1_000_000.0).toInt() + 2 <= sampleCount
            }
        }
    }
}

data class ScopeChannelMeasurement(val rms: Float, val sampledPeak: Float, val peakToPeak: Float)

/** Selects native samples and a fractional trigger origin without resampling their amplitudes. */
object OscilloscopeAcquisition {
    fun capture(
        rawChannels: List<AudioOscilloscopeChannel>,
        sampleRate: Int,
        requested: OscilloscopeConfig = OscilloscopeConfig(),
    ): AudioOscilloscopeFrame {
        val sampleCount = rawChannels.minOfOrNull { it.size } ?: 0
        if (sampleRate <= 0 || sampleCount < 4) return AudioOscilloscopeFrame(rawChannels = rawChannels)
        val config = requested.normalized(sampleRate, sampleCount, rawChannels.size)
        if (OscilloscopeConfig.supportedTimebases(sampleRate, sampleCount).isEmpty()) return AudioOscilloscopeFrame(rawChannels = rawChannels)
        val durationUs = config.timebaseUsPerDivision * 10L
        val spanSamples = durationUs * sampleRate / 1_000_000.0
        val finalOrigin = sampleCount - 1.0 - spanSamples
        val nominalOrigin = finalOrigin / 2.0
        val triggerChannel = rawChannels[config.triggerSource]
        var origin = nominalOrigin
        var nearest = Double.POSITIVE_INFINITY
        var triggered = false
        var previous = triggerChannel[0]
        for (index in 1 until sampleCount) {
            val value = triggerChannel[index]
            val crossing = when (config.triggerEdge) {
                ScopeTriggerEdge.RISING -> previous < config.triggerLevel && value >= config.triggerLevel
                ScopeTriggerEdge.FALLING -> previous > config.triggerLevel && value <= config.triggerLevel
            }
            if (crossing && value != previous) {
                val candidate = index - 1.0 + (config.triggerLevel.toDouble() - previous) / (value.toDouble() - previous)
                if (candidate in 0.0..finalOrigin) {
                    val distance = abs(candidate - nominalOrigin)
                    if (distance < nearest) {
                        nearest = distance
                        origin = candidate
                        triggered = true
                    }
                }
            }
            previous = value
        }
        val first = origin.toInt()
        val last = ceil(origin + spanSamples).toInt().coerceAtMost(sampleCount - 1)
        val channels = rawChannels.map { it.view(first, last - first + 1) }
        val relativeOrigin = origin - first
        val measurements = channels.map { channel ->
            val firstVisible = ceil(relativeOrigin).toInt()
            val lastVisible = kotlin.math.floor(relativeOrigin + spanSamples).toInt().coerceAtMost(channel.size - 1)
            var squareSum = 0.0
            var peak = 0f
            var low = Float.POSITIVE_INFINITY
            var high = Float.NEGATIVE_INFINITY
            for (index in firstVisible..lastVisible) {
                val value = channel[index]
                squareSum += value.toDouble() * value
                peak = maxOf(peak, abs(value))
                low = minOf(low, value)
                high = maxOf(high, value)
            }
            val count = (lastVisible - firstVisible + 1).coerceAtLeast(1)
            ScopeChannelMeasurement(kotlin.math.sqrt(squareSum / count).toFloat(), peak, (high - low).takeIf { it.isFinite() } ?: 0f)
        }
        return AudioOscilloscopeFrame(
            channels = channels,
            durationUs = durationUs,
            risingEdgeTriggered = triggered && config.triggerEdge == ScopeTriggerEdge.RISING,
            rawChannels = rawChannels,
            originSamples = relativeOrigin,
            sampleRate = sampleRate,
            configuration = config,
            triggered = triggered,
            waitingForTrigger = !triggered && config.triggerMode == ScopeTriggerMode.NORMAL,
            measurements = measurements,
        )
    }
}
