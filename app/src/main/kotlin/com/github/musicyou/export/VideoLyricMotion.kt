package com.github.musicyou.export

import kotlin.math.min

/** Prepares the next line before its cue, with no animation queue or playback offset. */
internal object VideoLyricMotion {
    private const val ADVANCE_US = 280_000L
    private const val REVEAL_US = 100_000L

    data class Presentation(val currentIndex: Int, val advance: Float, val reveal: Float)

    fun normalize(cues: List<VideoLyricCue>): List<VideoLyricCue> {
        val result = ArrayList<VideoLyricCue>()
        for (cue in cues.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }) {
            if (result.lastOrNull()?.timeMs == cue.timeMs) result[result.lastIndex] = cue
            else result.add(cue)
        }
        return result
    }

    fun sample(cues: List<VideoLyricCue>, timeUs: Long): Presentation {
        val timeMs = if (timeUs >= 0L) timeUs / 1_000L else (timeUs + 1L) / 1_000L - 1L
        val index = VideoTimeline.lyricIndex(cues, timeMs)
        val currentUs = cues.getOrNull(index)?.let { timestampUs(it.timeMs) }
        val nextUs = cues.getOrNull(index + 1)?.let { timestampUs(it.timeMs) }
        val advanceDuration = if (currentUs != null && nextUs != null) {
            min(ADVANCE_US, ((nextUs.toDouble() - currentUs) * .45).toLong()).coerceAtLeast(1L)
        } else ADVANCE_US
        val advance = if (nextUs == null) 0f else {
            smooth(((timeUs.toDouble() - nextUs + advanceDuration) / advanceDuration).toFloat())
        }
        var revealDuration = REVEAL_US
        if (currentUs != null) {
            cues.getOrNull(index - 1)?.let { previous ->
                revealDuration = min(revealDuration, ((currentUs.toDouble() - timestampUs(previous.timeMs)) * .2).toLong())
            }
            if (nextUs != null) revealDuration = min(revealDuration, ((nextUs.toDouble() - currentUs) * .2).toLong())
        }
        val reveal = if (currentUs == null) 1f else {
            smooth(((timeUs.toDouble() - currentUs) / revealDuration.coerceAtLeast(1L)).toFloat())
        }
        return Presentation(index, advance, reveal)
    }

    private fun timestampUs(timeMs: Long) = timeMs.coerceIn(Long.MIN_VALUE / 1_000L, Long.MAX_VALUE / 1_000L) * 1_000L

    private fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return (t * t * t * (t * (t * 6f - 15f) + 10f)).coerceIn(0f, 1f)
    }
}
