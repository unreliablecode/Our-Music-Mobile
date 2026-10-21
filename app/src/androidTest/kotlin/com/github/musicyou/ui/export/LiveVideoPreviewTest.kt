package com.github.musicyou.ui.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import com.github.musicyou.export.VideoAccentPalette
import com.github.musicyou.export.VideoAspectRatio
import com.github.musicyou.export.VideoBackground
import com.github.musicyou.export.VideoExportConfig
import com.github.musicyou.export.VideoExportRequest
import com.github.musicyou.export.VideoLyricCue
import com.github.musicyou.export.VideoPreviewRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Counts actual canvas draws, checks pixels and verifies that a paused preview stops work. */
@RunWith(AndroidJUnit4::class)
class LiveVideoPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun replacingTheCompositionOwnerDoesNotInvalidateTheNativeViewsCurrentPhoto() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val photo = writePhoto("retained_preview", Color.RED)
        val pixels = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val request = VideoExportRequest("retained-photo", "", "", config = VideoExportConfig(
            background = VideoBackground.CUSTOM, customBackgroundUri = Uri.fromFile(photo).toString(),
        ))
        val renderer = VideoPreviewRenderer.create(context, request, 320, 180)
        var native: VideoPreviewView? = null
        try {
            instrumentation.runOnMainSync {
                native = VideoPreviewView(context).apply {
                    layout(0, 0, 320, 180)
                    update(renderer, request.config, { null }, { 0L }, active = false)
                }
                renderer.close()
                requireNotNull(native).draw(Canvas(pixels))
            }
            val color = pixels.getPixel(2, 90)
            assertTrue("The old native draw must retain its photo until the view replaces it", Color.red(color) > Color.blue(color) + 40)
        } finally {
            instrumentation.runOnMainSync { native?.release() }
            renderer.close()
            pixels.recycle()
            photo.delete()
        }
    }

    @Test
    fun activePreviewCanReplacePhotosArtworkAndShapeThenDismissAndReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val firstPhoto = writePhoto("preview_switch_red", Color.RED)
        val secondPhoto = writePhoto("preview_switch_blue", Color.BLUE)
        val shown = mutableStateOf(true)
        val view = AtomicReference<VideoPreviewView?>()
        val initial = VideoExportRequest(
            "switching-preview", "Background switching", "Local photo",
            artworkUri = Uri.fromFile(firstPhoto).toString(),
            lyrics = listOf(VideoLyricCue(0, "Live timed lyrics")),
        )
        val request = mutableStateOf(initial)
        val started = System.nanoTime()
        val position: () -> Long = { (System.nanoTime() - started) / 1_000L }
        val analyzer = AudioFrameAnalyzer(48_000, 1)
        val frame = analyzer.analyze(FloatArray(AudioFrameAnalyzer.WINDOW_SIZE) { (sin(2 * PI * 440 * it / 48_000) * .6).toFloat() })
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (shown.value) LiveVideoPreviewSurface(request.value, { frame }, position, active = true,
                        modifier = Modifier.size(350.dp, 250.dp), onView = view::set)
                }
            }
        }
        try {
            compose.waitUntil(10_000) { (view.get()?.renderedFrames ?: 0) >= 5 }
            repeat(12) { index ->
                val native = requireNotNull(view.get())
                val before = native.rendererChanges
                val draws = native.renderedFrames
                instrumentation.runOnMainSync {
                    val background = when (index % 4) {
                        0, 2 -> VideoBackground.CUSTOM
                        1 -> VideoBackground.ARTWORK
                        else -> VideoBackground.SUNSET
                    }
                    request.value = initial.copy(config = VideoExportConfig(
                        aspectRatio = if (index % 2 == 0) VideoAspectRatio.PORTRAIT else VideoAspectRatio.LANDSCAPE,
                        background = background,
                        customBackgroundUri = Uri.fromFile(if (index % 4 == 0) firstPhoto else secondPhoto).toString(),
                    ))
                    Snapshot.sendApplyNotifications()
                }
                compose.waitUntil(10_000) { native.rendererChanges > before && native.renderedFrames > draws + 1 }
                if (index % 4 == 3) {
                    instrumentation.runOnMainSync { shown.value = false; Snapshot.sendApplyNotifications() }
                    compose.waitForIdle()
                    val stopped = native.renderedFrames
                    Thread.sleep(60)
                    assertEquals("A dismissed preview releases its frame callback", stopped, native.renderedFrames)
                    instrumentation.runOnMainSync { shown.value = true; Snapshot.sendApplyNotifications() }
                    compose.waitUntil(10_000) { view.get() !== native && (view.get()?.renderedFrames ?: 0) >= 3 }
                }
            }
            compose.onNodeWithTag("video-export-preview").captureToImage()
        } finally {
            instrumentation.runOnMainSync { shown.value = false; Snapshot.sendApplyNotifications() }
            compose.waitForIdle()
            firstPhoto.delete()
            secondPhoto.delete()
        }
    }

    private fun writePhoto(prefix: String, color: Int): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "${prefix}_${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        return file
    }

    @Test
    fun nativePreviewDrawsAtSixtyFpsAndStylesDoNotReloadItsAssets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val view = AtomicReference<VideoPreviewView?>()
        val parentCompositions = AtomicInteger()
        val active = mutableStateOf(true)
        val request = mutableStateOf(VideoExportRequest(
            mediaId = "live-preview-fixture", title = "Live preview fixture", artist = "Local PCM",
            lyrics = listOf(VideoLyricCue(0, "First timed line"), VideoLyricCue(1_000, "Next timed line")),
        ))
        val analyzer = AudioFrameAnalyzer(48_000, 2)
        val frames = List(24) { frame ->
            val amplitude = 0.45 + 0.25 * sin(2 * PI * frame / 24)
            analyzer.analyze(FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * 2) { index ->
                (amplitude * sin(2 * PI * (if (index % 2 == 0) 1_000 else 3_000) * (index / 2 + frame * 800) / 48_000)).toFloat()
            })
        }
        val started = System.nanoTime()
        val pausedPosition = AtomicLong(-1L)
        val position: () -> Long = { pausedPosition.get().takeIf { it >= 0 } ?: ((System.nanoTime() - started) / 1_000L) }
        val provider = { frames[((position() / 16_667L) % frames.size).toInt()] }
        compose.setContent {
            MaterialTheme {
                SideEffect { parentCompositions.incrementAndGet() }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LiveVideoPreviewSurface(request.value, provider, position, active.value, Modifier.size(350.dp, 210.dp), onView = view::set)
                }
            }
        }
        compose.waitUntil(10_000) { (view.get()?.renderedFrames ?: 0) > 10 }
        val native = requireNotNull(view.get())
        val before = compose.onNodeWithTag("video-export-preview").captureToImage().toPixelMap()
        val startFrames = native.renderedFrames
        val startCompositions = parentCompositions.get()
        val startNanos = System.nanoTime()
        Thread.sleep(2_000L)
        val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
        val fps = (native.renderedFrames - startFrames) / elapsedSeconds
        assertTrue("Actual hardware canvas drawing must sustain at least 55 fps, observed $fps", fps >= 55.0)
        assertEquals("Audio frames must not recompose the editor parent", startCompositions, parentCompositions.get())
        val after = compose.onNodeWithTag("video-export-preview").captureToImage().toPixelMap()
        var change = 0.0
        for (y in 0 until before.height step 7) for (x in 0 until before.width step 7) {
            val a = before[x, y]
            val b = after[x, y]
            change += abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
        }
        assertTrue("Song time, measured audio and lyrics produce visibly different frames", change > 5.0)
        val assetCreations = native.rendererChanges
        instrumentation.runOnMainSync {
            request.value = request.value.copy(config = VideoExportConfig(accentPalette = VideoAccentPalette.ROSE, glow = 0.2f, visualizerScale = 0.8f))
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        Thread.sleep(200L)
        assertEquals("Changing style values must reuse the loaded artwork and renderer", assetCreations, native.rendererChanges)
        instrumentation.runOnMainSync {
            pausedPosition.set(position())
            active.value = false
            Snapshot.sendApplyNotifications()
        }
        compose.waitForIdle()
        Thread.sleep(100L)
        val stoppedFrames = native.renderedFrames
        Thread.sleep(250L)
        assertEquals("A paused preview must stop scheduling canvas work", stoppedFrames, native.renderedFrames)
    }
}
