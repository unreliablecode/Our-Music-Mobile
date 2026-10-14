package com.github.musicyou.ui.apple

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import com.github.musicyou.ui.glass.GlassBackdropRoot
import com.github.musicyou.ui.visualization.VisualizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarPlayerLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun portraitShowsLargeControlsBothChartsAndExit() {
        withOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT) {
            showPlayer(400.dp, 850.dp)
            assertAllControlsFit()
            assertInside("car-artwork")
            assertInside("car-song-title")
            assertInside("car-song-artist")
            assertInside("audio-waveform-plot")
            assertInside("audio-spectrum-plot")
            val density = compose.activity.resources.displayMetrics.density
            val play = compose.onNodeWithTag("car-play-pause").fetchSemanticsNode().boundsInRoot
            assertTrue("Portrait playback has a 112 dp target", play.height >= 111f * density)
        }
    }

    @Test
    fun landscapeFitsArtworkTransportAndEveryAction() {
        withOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE) {
            val callbacks = mutableMapOf<String, Int>()
            showPlayer(800.dp, 360.dp, callbacks)
            assertAllControlsFit()
            assertInside("car-artwork")
            assertInside("car-song-title")
            assertInside("car-song-artist")
            assertInside("audio-waveform-plot")
            assertInside("audio-spectrum-plot")
            val artwork = compose.onNodeWithTag("car-artwork").fetchSemanticsNode().boundsInRoot
            val transport = compose.onNodeWithTag("car-play-pause").fetchSemanticsNode().boundsInRoot
            assertTrue("Landscape places artwork beside the transport", artwork.right < transport.left)
            for (tag in controls) compose.onNodeWithTag(tag).performClick()
            compose.runOnIdle {
                for (tag in controls) assertEquals("$tag reaches its supplied action once", 1, callbacks[tag])
            }
        }
    }

    private fun showPlayer(width: Dp, height: Dp, callbacks: MutableMap<String, Int> = mutableMapOf()) {
        val media = MediaItem.Builder().setMediaId("layout-test").setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("The Long Road Home Live Concert Edition")
                .setArtist("An Orchestra with a Long Artist Name")
                .build(),
        ).build()
        val frame = AudioFrameAnalyzer(48_000, 2).analyze(FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * 2))
        fun action(tag: String): () -> Unit = { callbacks[tag] = (callbacks[tag] ?: 0) + 1 }
        compose.setContent {
            MaterialTheme {
                GlassBackdropRoot(
                    modifier = Modifier.fillMaxSize(),
                    background = { Box(Modifier.fillMaxSize().background(Color(0xFFF0ECE8))) },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CarPlayerContent(
                            media = media,
                            playing = true,
                            frame = frame,
                            visualization = VisualizationMode.BOTH,
                            onClose = action("car-close"),
                            onOptions = action("car-options"),
                            onPrevious = action("car-previous"),
                            onPlayPause = action("car-play-pause"),
                            onNext = action("car-next"),
                            onExit = action("car-exit"),
                            error = "Playback is temporarily unavailable. Tap Play to retry.",
                            modifier = Modifier.size(width, height),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertAllControlsFit() {
        val density = compose.activity.resources.displayMetrics.density
        for (tag in controls) {
            compose.onNodeWithTag(tag).assertHasClickAction()
            assertInside(tag)
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag retains a 64 dp wide touch target", bounds.width >= 63.5f * density)
            assertTrue("$tag retains a 64 dp high touch target", bounds.height >= 63.5f * density)
        }
    }

    private fun assertInside(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        val viewport = compose.onNodeWithTag("car-player-content").fetchSemanticsNode().boundsInRoot
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag stays inside the car viewport horizontally", bounds.left >= viewport.left - 1f && bounds.right <= viewport.right + 1f)
        assertTrue("$tag stays inside the car viewport vertically", bounds.top >= viewport.top - 1f && bounds.bottom <= viewport.bottom + 1f)
        assertTrue("$tag has visible bounds", bounds.width > 0f && bounds.height > 0f)
    }

    private fun withOrientation(requested: Int, configuration: Int, test: () -> Unit) {
        var original = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        compose.activityRule.scenario.onActivity { activity ->
            original = activity.requestedOrientation
            activity.requestedOrientation = requested
        }
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.waitUntil(5_000L) { compose.activity.resources.configuration.orientation == configuration }
            test()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = original }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private val controls = listOf("car-close", "car-options", "car-previous", "car-play-pause", "car-next", "car-exit")
}
