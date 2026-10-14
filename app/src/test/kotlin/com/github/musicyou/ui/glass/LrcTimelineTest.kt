package com.github.musicyou.ui.glass

import com.github.musicyou.utils.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcTimelineTest {
    @Test
    fun parserOrdersCuesAndSupportsRepeatedTimestampsAndFractionLengths() {
        val timeline = parseLrc("[00:12.345]Later\n[00:01.5][00:07,25]Repeat\n[00:04]Middle")
        assertEquals(listOf(1500L, 4000L, 7250L, 12345L), timeline.lines.map { it.timeMs })
        assertEquals(listOf("Repeat", "Middle", "Repeat", "Later"), timeline.lines.map { it.text })
    }

    @Test
    fun duplicateCuesCollapseWithoutLosingSimultaneousTranslations() {
        val timeline = parseLrc("[00:01.00]First\n[00:01.00]First\n[00:01.00]Translation\n[00:02.00]")
        assertEquals(2, timeline.lines.size)
        assertEquals("First\nTranslation", timeline.lines[0].text)
        assertEquals("", timeline.lines[1].text)
    }

    @Test
    fun fileOffsetAndUserDelayApplyInOppositeDirections() {
        val timeline = parseLrc("[offset:-500]\n[00:01.000]First\n[00:03.000]Second")
        assertEquals(500L, timeline.lines.first().timeMs)
        assertEquals(-1, timeline.currentIndex(749, delayMs = 250))
        assertEquals(0, timeline.currentIndex(750, delayMs = 250))
        assertEquals(2750L, timeline.seekPosition(1, delayMs = 250))
        assertEquals(1, timeline.currentIndex(2750, delayMs = 250))
    }

    @Test
    fun seekingBackwardAndNegativePlaybackPositionsResetTheHighlight() {
        val timeline = parseLrc("[00:02]First\n[00:04]Second\n[00:08]Third")
        assertEquals(2, timeline.currentIndex(9000))
        assertEquals(0, timeline.currentIndex(3000))
        assertEquals(-1, timeline.currentIndex(-10))
        assertEquals(-1, timeline.currentIndex(1999))
        assertEquals(0, timeline.currentIndex(2000))
        assertEquals(0L, timeline.seekPosition(0, delayMs = -5000))
    }

    @Test
    fun metadataMalformedTimesAndEmptyContentDoNotCreatePhantomWords() {
        val timeline = parseLrc("[ar:Artist]\n[ti:Title]\n[00:99]Bad\n[00:01.50]Good <00:01.6>words\nUntimed")
        assertEquals(1, timeline.lines.size)
        assertEquals("Good words", timeline.lines.first().text)
        assertEquals(-1, parseLrc(null).currentIndex(1000))
    }
}
