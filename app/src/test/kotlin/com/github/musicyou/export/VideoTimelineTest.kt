package com.github.musicyou.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimelineTest {
    @Test fun longSongHasNoCumulativeFrameDriftAndRetainsItsLastPartialFrame() {
        val duration = 5L * 60 * 1_000_000 + 7_000L
        val count = VideoTimeline.frameCount(duration)
        assertEquals(18_001L, count)
        assertTrue(VideoTimeline.presentationTimeUs(count - 1) < duration)
        assertTrue(VideoTimeline.presentationTimeUs(count) >= duration)
        assertEquals(300_000_000L, VideoTimeline.presentationTimeUs(18_000))
    }

    @Test fun lyricsDoNotAppearEarlyAndSameTimestampUsesLastCue() {
        val cues = listOf(VideoLyricCue(1000, "First"), VideoLyricCue(2000, "Second"), VideoLyricCue(2000, "Revised"), VideoLyricCue(5000, "Last"))
        assertEquals(-1, VideoTimeline.lyricIndex(cues, 999))
        assertEquals(0, VideoTimeline.lyricIndex(cues, 1000))
        assertEquals(2, VideoTimeline.lyricIndex(cues, 2000))
        assertEquals(2, VideoTimeline.lyricIndex(cues, 4999))
        assertEquals(3, VideoTimeline.lyricIndex(cues, 50_000))
    }
}
