package com.github.musicyou.utils

data class LrcLine(val timeMs: Long, val text: String)

/** Positive delay means words appear later; a tap seeks to that same delayed cue. */
class LrcTimeline(val lines: List<LrcLine>) {
    fun currentIndex(positionMs: Long, delayMs: Long = 0): Int {
        val lyricPosition = positionMs - delayMs
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timeMs <= lyricPosition) {
                found = middle
                low = middle + 1
            } else high = middle - 1
        }
        return found
    }

    fun seekPosition(index: Int, delayMs: Long = 0): Long =
        (lines[index].timeMs + delayMs).coerceAtLeast(0L)
}

private val lrcTimestamp = Regex("\\[(?:(\\d{1,3}):)?(\\d{1,3}):(\\d{2})(?:[.,](\\d{1,3}))?]")
private val lrcOffset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
private val enhancedTimestamp = Regex("<\\d{1,3}:\\d{2}(?:[.,]\\d{1,3})?>")

fun parseLrc(text: String?): LrcTimeline {
    if (text.isNullOrBlank()) return LrcTimeline(emptyList())
    val offset = lrcOffset.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val lines = mutableListOf<LrcLine>()
    for (raw in text.lineSequence()) {
        val timestamps = lrcTimestamp.findAll(raw).toList()
        if (timestamps.isEmpty()) continue
        val words = raw.substring(timestamps.last().range.last + 1).replace(enhancedTimestamp, "").trim()
        for (stamp in timestamps) {
            val hours = stamp.groupValues[1].toLongOrNull() ?: 0L
            val minutes = stamp.groupValues[2].toLongOrNull() ?: continue
            val seconds = stamp.groupValues[3].toLongOrNull() ?: continue
            if (seconds >= 60L || (hours > 0 && minutes >= 60L)) continue
            val fraction = stamp.groupValues[4].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            lines.add(LrcLine(((hours * 3600 + minutes * 60 + seconds) * 1000 + fraction + offset).coerceAtLeast(0), words))
        }
    }
    val combined = lines.sortedBy { it.timeMs }.groupBy { it.timeMs }.map { (time, simultaneous) ->
        LrcLine(time, simultaneous.map { it.text }.filter { it.isNotBlank() }.distinct().joinToString("\n"))
    }
    return LrcTimeline(combined)
}
