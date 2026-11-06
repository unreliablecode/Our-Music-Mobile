package com.github.musicyou.export

import android.net.Uri
import com.github.musicyou.audio.visualization.OscilloscopeConfig

enum class VideoAspectRatio { LANDSCAPE, PORTRAIT }
enum class VideoResolution { HD, FULL_HD }
enum class VideoVisualization { CIRCULAR, SPECTRUM, WAVEFORM, OSCILLOSCOPE }
enum class VideoBackground { AURORA, MIDNIGHT, SUNSET, ARTWORK, CUSTOM }
enum class VideoAccentPalette(val argb: Int) {
    MINT(0xFF77FFE1.toInt()), ROSE(0xFFFF73AA.toInt()), OCEAN(0xFF76BDFF.toInt()), AMBER(0xFFFFC866.toInt()),
}
enum class VideoStylePreset { NEON, SOFT, PRISM, CUSTOM }

data class VideoExportConfig(
    val aspectRatio: VideoAspectRatio = VideoAspectRatio.LANDSCAPE,
    val resolution: VideoResolution = VideoResolution.HD,
    val visualization: VideoVisualization = VideoVisualization.CIRCULAR,
    val background: VideoBackground = VideoBackground.AURORA,
    val customBackgroundUri: String? = null,
    val accentPalette: VideoAccentPalette = VideoAccentPalette.MINT,
    val glow: Float = .7f,
    val intensity: Float = 1f,
    val visualizerScale: Float = 1f,
    val lyricsScale: Float = 1f,
    val backgroundDim: Float = .35f,
    val backgroundMotion: Float = .5f,
    val stylePreset: VideoStylePreset = VideoStylePreset.NEON,
    val showProgressBar: Boolean = false,
) {
    val width: Int get() = if (aspectRatio == VideoAspectRatio.LANDSCAPE) longEdge else shortEdge
    val height: Int get() = if (aspectRatio == VideoAspectRatio.LANDSCAPE) shortEdge else longEdge
    private val longEdge: Int get() = if (resolution == VideoResolution.HD) 1280 else 1920
    private val shortEdge: Int get() = if (resolution == VideoResolution.HD) 720 else 1080
    internal fun normalized() = copy(
        glow = glow.finiteIn(0f, 1f, .7f), intensity = intensity.finiteIn(0f, 2f, 1f),
        visualizerScale = visualizerScale.finiteIn(.65f, 1.3f, 1f), lyricsScale = lyricsScale.finiteIn(.8f, 1.4f, 1f),
        backgroundDim = backgroundDim.finiteIn(0f, .8f, .35f), backgroundMotion = backgroundMotion.finiteIn(0f, 1f, .5f),
    )
}

private fun Float.finiteIn(minimum: Float, maximum: Float, fallback: Float) = if (isFinite()) coerceIn(minimum, maximum) else fallback

data class VideoLyricCue(val timeMs: Long, val text: String)

data class VideoExportRequest(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: String? = null,
    val lyrics: List<VideoLyricCue> = emptyList(),
    val config: VideoExportConfig = VideoExportConfig(),
    val oscilloscopeConfig: OscilloscopeConfig = OscilloscopeConfig(),
    val durationUs: Long = 0L,
)

internal object VideoProgressTimeline {
    fun positionUs(timeUs: Long, durationUs: Long): Long =
        if (durationUs > 0L) timeUs.coerceIn(0L, durationUs) else 0L

    fun fraction(timeUs: Long, durationUs: Long): Double =
        if (durationUs > 0L) positionUs(timeUs, durationUs).toDouble() / durationUs else 0.0
}

data class VideoExportState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val outputUri: Uri? = null,
    val error: String? = null,
    val sourceTitle: String? = null,
)

internal object VideoTimeline {
    const val FPS = 60
    const val TIMESTAMP_TOLERANCE_US = 2L
    fun presentationTimeUs(frame: Long): Long = frame * 1_000_000L / FPS
    fun frameCount(durationUs: Long): Long = (durationUs * FPS + 999_999L) / 1_000_000L
    fun matchesPresentationTime(frame: Long, timeUs: Long): Boolean =
        kotlin.math.abs(timeUs - presentationTimeUs(frame)) <= TIMESTAMP_TOLERANCE_US
    fun lyricIndex(cues: List<VideoLyricCue>, timeMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cues[middle].timeMs <= timeMs) { found = middle; low = middle + 1 } else high = middle - 1
        }
        return found
    }
}
