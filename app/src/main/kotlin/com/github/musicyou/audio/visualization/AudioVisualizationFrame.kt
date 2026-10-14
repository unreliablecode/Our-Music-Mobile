package com.github.musicyou.audio.visualization

data class AudioWaveformChannel(
    val minimum: List<Float> = emptyList(),
    val maximum: List<Float> = emptyList(),
)

/** Measurements from a decoded PCM window centered on [positionUs] in the song. */
data class AudioVisualizationFrame(
    val sequence: Long = 0L,
    val positionUs: Long = 0L,
    val sampleRate: Int = 0,
    val spectrum: List<Float> = emptyList(),
    val frequenciesHz: List<Float> = emptyList(),
    val waveformChannels: List<AudioWaveformChannel> = emptyList(),
    val rms: Float = 0f,
    val peak: Float = 0f,
    val ready: Boolean = false,
)
