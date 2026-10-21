package com.github.musicyou.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLyricMotionTest {
    @Test fun eachCueIsAlreadyActiveAndSettledOnItsFirstVideoFrame() {
        val cues = VideoLyricMotion.normalize(listOf(VideoLyricCue(0, "First"), VideoLyricCue(1001, "Second"), VideoLyricCue(2700, "Third")))
        for ((index, cue) in cues.withIndex()) {
            val exact = VideoLyricMotion.sample(cues, cue.timeMs * 1_000L)
            assertEquals(index, exact.currentIndex)
            assertEquals(0f, exact.advance, 0f)
            if (index > 0) assertEquals(index - 1, VideoLyricMotion.sample(cues, cue.timeMs * 1_000L - 1).currentIndex)
            val frame = (cue.timeMs * 60L + 999L) / 1_000L
            val firstFrame = VideoLyricMotion.sample(cues, VideoTimeline.presentationTimeUs(frame))
            assertEquals(index, firstFrame.currentIndex)
            assertEquals(0f, firstFrame.advance, 0f)
        }
    }

    @Test fun incomingLineFinishesBeforeActivationWithoutAPostCueSettle() {
        val cues = listOf(VideoLyricCue(0, "First"), VideoLyricCue(1000, "Second"), VideoLyricCue(4000, "Last"))
        val before = VideoLyricMotion.sample(cues, 999_999)
        val onCue = VideoLyricMotion.sample(cues, 1_000_000)
        assertEquals(0, before.currentIndex)
        assertEquals(1f, before.advance, .00001f)
        assertEquals(1, onCue.currentIndex)
        assertEquals(0f, onCue.advance, 0f)
        assertEquals(0f, onCue.reveal, 0f)
        assertEquals(1f, VideoLyricMotion.sample(cues, 1_100_000).reveal, 0f)
    }

    @Test fun denseCuesNeverQueueOrDelayTheActiveLine() {
        val cues = listOf(0L, 40L, 80L, 81L, 200L).map { VideoLyricCue(it, "Line $it") }
        for (cue in cues) {
            val onCue = VideoLyricMotion.sample(cues, cue.timeMs * 1_000)
            assertEquals(cues.indexOf(cue), onCue.currentIndex)
            assertEquals(0f, onCue.advance, 0f)
        }
        assertEquals(3, VideoLyricMotion.sample(cues, 199_999).currentIndex)
        val original = VideoLyricMotion.sample(cues, 100_000)
        VideoLyricMotion.sample(cues, 210_000)
        VideoLyricMotion.sample(cues, 1_000)
        assertEquals(original, VideoLyricMotion.sample(cues, 100_000))
    }

    @Test fun duplicateTimestampsKeepTheSameIncomingAndActiveText() {
        val cues = VideoLyricMotion.normalize(listOf(VideoLyricCue(1000, "Old"), VideoLyricCue(0, "First"), VideoLyricCue(1000, "Revised"), VideoLyricCue(1000, " ")))
        assertEquals(listOf(VideoLyricCue(0, "First"), VideoLyricCue(1000, "Revised")), cues)
        assertEquals("Revised", cues[VideoLyricMotion.sample(cues, 999_999).currentIndex + 1].text)
        assertEquals("Revised", cues[VideoLyricMotion.sample(cues, 1_000_000).currentIndex].text)
    }

    @Test fun zeroNegativeAndPositiveFirstCuesHaveNoIntroDelay() {
        for (timeMs in listOf(-500L, 0L)) {
            val cues = listOf(VideoLyricCue(timeMs, "First"), VideoLyricCue(5000, "Next"))
            assertEquals(0, VideoLyricMotion.sample(cues, 0).currentIndex)
            assertEquals(0f, VideoLyricMotion.sample(cues, 0).advance, 0f)
        }
        val cues = listOf(VideoLyricCue(1000, "First"))
        assertEquals(-1, VideoLyricMotion.sample(cues, 999_999).currentIndex)
        assertTrue(VideoLyricMotion.sample(cues, 999_999).advance > .999f)
        assertEquals(0, VideoLyricMotion.sample(cues, 1_000_000).currentIndex)
        assertEquals(0f, VideoLyricMotion.sample(cues, 1_000_000).advance, 0f)
    }
}
