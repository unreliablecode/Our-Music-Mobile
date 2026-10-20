package com.github.musicyou.ui.apple

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.musicyou.utils.LrcLine
import com.github.musicyou.utils.LrcTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsMotionTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun timedSelectionAndTappingStayAlignedWithTheDelayedCue() {
        val timeline = LrcTimeline(List(12) { LrcLine(it * 4_000L, "Lyric line number $it") })
        var active by mutableIntStateOf(0)
        var seekPosition = -1L
        show(timeline, activeIndex = { active }, onSeek = { seekPosition = timeline.seekPosition(it, 750L) })

        compose.onNodeWithTag("lyric-line-0").assertIsSelected()
        compose.runOnIdle { active = timeline.currentIndex(4_750L, 750L) }
        compose.onNodeWithTag("lyric-line-1").assertIsSelected().performClick()
        compose.runOnIdle { assertEquals(4_750L, seekPosition) }
    }

    @Test
    fun manualBrowsingIsNotInterruptedByTheNextCueAndFollowReturnsToPlayback() {
        val timeline = LrcTimeline(List(30) { LrcLine(it * 4_000L, "Lyric line number $it") })
        var active by mutableIntStateOf(0)
        val follow = LyricsFollowState()
        lateinit var list: LazyListState
        show(timeline, activeIndex = { active }, follow = follow, onList = { list = it })

        compose.onNodeWithTag("timed-lyrics").performTouchInput { swipeUp(durationMillis = 450) }
        var first = 0
        var offset = 0
        compose.runOnIdle {
            assertTrue("Dragging pauses automatic following", follow.browsing)
            first = list.firstVisibleItemIndex
            offset = list.firstVisibleItemScrollOffset
            assertTrue("The lyrics can be browsed beyond the active line", first > 0)
            active = 1
        }
        compose.runOnIdle {
            assertEquals("The next cue does not override browsing", first, list.firstVisibleItemIndex)
            assertEquals(offset, list.firstVisibleItemScrollOffset)
            follow.resume()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lyric-line-1").assertIsSelected()
        compose.runOnIdle {
            assertFalse(follow.browsing)
            assertTrue("Following returns to the active cue", list.firstVisibleItemIndex <= 1)
        }
    }

    @Test
    fun animatedEmphasisKeepsWrappedLineMeasurementsStable() {
        val timeline = LrcTimeline(List(8) {
            LrcLine(it * 4_000L, "A longer lyric line keeps the same wrapping when the next cue becomes active")
        })
        var active by mutableIntStateOf(0)
        lateinit var list: LazyListState
        show(timeline, activeIndex = { active }, onList = { list = it })
        var firstHeight = 0
        var nextHeight = 0
        compose.runOnIdle {
            firstHeight = list.layoutInfo.visibleItemsInfo.first { it.index == 0 }.size
            nextHeight = list.layoutInfo.visibleItemsInfo.first { it.index == 1 }.size
            active = 1
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(firstHeight, list.layoutInfo.visibleItemsInfo.first { it.index == 0 }.size)
            assertEquals(nextHeight, list.layoutInfo.visibleItemsInfo.first { it.index == 1 }.size)
        }
    }

    private fun show(
        timeline: LrcTimeline,
        activeIndex: () -> Int,
        follow: LyricsFollowState = LyricsFollowState(),
        onSeek: (Int) -> Unit = {},
        onList: (LazyListState) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(350.dp, 360.dp)) {
                    val list = rememberLazyListState()
                    onList(list)
                    TimedLyricsList(
                        timeline = timeline,
                        activeIndex = activeIndex(),
                        style = LyricsStyle.ANIMATED_SMOOTH,
                        follow = follow,
                        onSeek = onSeek,
                        list = list,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }
}
