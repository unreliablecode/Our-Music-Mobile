package com.github.musicyou.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProgressTimelineTest {
    @Test fun progressUsesExactSourceDurationAndClampsOutsideIt() {
        val duration = 123_456_789L
        assertEquals(0.0, VideoProgressTimeline.fraction(-1L, duration), 0.0)
        assertEquals(0.0, VideoProgressTimeline.fraction(0L, duration), 0.0)
        assertEquals(60_000_000.0 / duration, VideoProgressTimeline.fraction(60_000_000L, duration), 0.0)
        assertEquals(1.0, VideoProgressTimeline.fraction(duration, duration), 0.0)
        assertEquals(1.0, VideoProgressTimeline.fraction(Long.MAX_VALUE, duration), 0.0)
        assertEquals(duration, VideoProgressTimeline.positionUs(Long.MAX_VALUE, duration))
        assertEquals(0.0, VideoProgressTimeline.fraction(10L, 0L), 0.0)
        assertEquals(0.0, VideoProgressTimeline.fraction(10L, -10L), 0.0)
    }

    @Test fun everySixtyFpsFrameAdvancesWithoutWaitingForWallClockTime() {
        val duration = 3_000_000L
        var previous = -1.0
        repeat(180) { frame ->
            val timestamp = VideoTimeline.presentationTimeUs(frame.toLong())
            val progress = VideoProgressTimeline.fraction(timestamp, duration)
            assertTrue(progress > previous)
            assertEquals(timestamp.toDouble() / duration, progress, 0.0)
            previous = progress
        }
    }

    @Test fun backwardSeeksImmediatelyReturnTheCorrespondingPosition() {
        val duration = 10_000_000L
        val before = VideoProgressTimeline.fraction(8_000_000L, duration)
        assertEquals(.2, VideoProgressTimeline.fraction(2_000_000L, duration), 0.0)
        assertEquals(before, VideoProgressTimeline.fraction(8_000_000L, duration), 0.0)
        assertEquals(.5, VideoProgressTimeline.fraction(Long.MAX_VALUE / 2, Long.MAX_VALUE), 1e-15)
    }
}
