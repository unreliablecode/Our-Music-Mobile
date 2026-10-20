package com.github.musicyou.audio.visualization

data class AudioWaveformChannel(
    val minimum: List<Float> = emptyList(),
    val maximum: List<Float> = emptyList(),
)

/** A read-only trace owns its primitive samples without boxing every audio value. */
class AudioOscilloscopeChannel internal constructor(
    private val samples: FloatArray,
    private val offset: Int = 0,
    val size: Int = samples.size,
) {
    operator fun get(index: Int): Float {
        require(index in 0 until size)
        return samples[offset + index]
    }
    internal fun view(first: Int, count: Int): AudioOscilloscopeChannel {
        require(first >= 0 && count >= 0 && first + count <= size)
        return AudioOscilloscopeChannel(samples, offset + first, count)
    }
}

data class AudioOscilloscopeFrame(
    val channels: List<AudioOscilloscopeChannel> = emptyList(),
    val durationUs: Long = 0L,
    val risingEdgeTriggered: Boolean = false,
    val rawChannels: List<AudioOscilloscopeChannel> = emptyList(),
    val originSamples: Double = 0.0,
    val sampleRate: Int = 0,
    val configuration: OscilloscopeConfig = OscilloscopeConfig(),
    val triggered: Boolean = false,
    val waitingForTrigger: Boolean = false,
    val measurements: List<ScopeChannelMeasurement> = emptyList(),
)

/** Measurements from a decoded PCM window centered on [positionUs] in the song. */
data class AudioVisualizationFrame(
    val sequence: Long = 0L,
    val positionUs: Long = 0L,
    val sampleRate: Int = 0,
    val spectrum: List<Float> = emptyList(),
    val frequenciesHz: List<Float> = emptyList(),
    val waveformChannels: List<AudioWaveformChannel> = emptyList(),
    val oscilloscope: AudioOscilloscopeFrame = AudioOscilloscopeFrame(),
    val rms: Float = 0f,
    val peak: Float = 0f,
    val ready: Boolean = false,
)
