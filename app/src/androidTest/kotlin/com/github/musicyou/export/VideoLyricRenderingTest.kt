package com.github.musicyou.export

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoLyricRenderingTest {
    @Test fun firstAndLaterCuesShowTheCurrentLineFullyHighlightedOnTime() {
        forEachShape { fixture ->
            for (cue in CUES) {
                val onTime = fixture.render(cue.timeMs)
                val settled = fixture.render(cue.timeMs + 500)
                val active = onTime.highlight()
                val reference = settled.highlight()
                assertTrue("${fixture.name} cue ${cue.timeMs} must have visible current text", active.count > 30)
                assertTrue("${fixture.name} cue ${cue.timeMs} must be fully highlighted at its timestamp", active.maximum >= 250)
                assertTrue("${fixture.name} cue ${cue.timeMs} must show the complete incoming line", active.count >= reference.count * .75)
                assertTrue("${fixture.name} cue ${cue.timeMs} must retain the incoming line's measured width", active.width >= reference.width * .90)
            }
        }
    }

    @Test fun cueBoundariesRemainContinuousForTheFirstLineAndWrappedLinesAtSixtyFps() {
        forEachShape { fixture ->
            for (cue in CUES) {
                var previous = fixture.render(cue.timeMs - 300)
                var largestFrameChange = 0.0
                for (frame in 1..24) {
                    val current = fixture.render(cue.timeMs - 300 + frame * 1_000L / 60)
                    largestFrameChange = maxOf(largestFrameChange, previous.difference(current))
                    previous = current
                }
                val before = fixture.render(cue.timeMs - 1)
                val exact = fixture.render(cue.timeMs)
                val after = fixture.render(cue.timeMs + 1)
                val boundaryChange = maxOf(before.difference(exact), exact.difference(after))
                assertTrue(
                    "${fixture.name} cue ${cue.timeMs} has a one-frame lyric reset: boundary=$boundaryChange ordinary_frame=$largestFrameChange",
                    boundaryChange <= largestFrameChange * .35 + .00002,
                )
                assertTrue("${fixture.name} cue ${cue.timeMs} must not have a blank boundary frame", exact.highlight().count > 30)
            }
        }
    }

    @Test fun seekingBackwardRestoresIdenticalFirstAndMultilineLyricFrames() {
        forEachShape { fixture ->
            val times = listOf(950L, 1_000L, 1_050L, 2_850L, 3_000L, 3_050L, 5_900L, 6_000L)
            val reference = times.associateWith { fixture.render(it) }
            fixture.render(7_000)
            for (time in times.reversed()) {
                val restored = fixture.render(time)
                assertTrue("${fixture.name} seeking back to $time must restore the exact lyric composition", reference.getValue(time).pixels.contentEquals(restored.pixels))
            }
        }
    }

    private fun forEachShape(action: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val background = File(context.cacheDir, "lyrics_black_${System.nanoTime()}.png")
        val black = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try {
            background.outputStream().use { black.compress(Bitmap.CompressFormat.PNG, 100, it) }
            for (shape in VideoAspectRatio.entries) {
                val width = if (shape == VideoAspectRatio.LANDSCAPE) 720 else 405
                val height = if (shape == VideoAspectRatio.LANDSCAPE) 405 else 720
                val config = VideoExportConfig(
                    aspectRatio = shape, visualization = VideoVisualization.SPECTRUM,
                    background = VideoBackground.CUSTOM, customBackgroundUri = Uri.fromFile(background).toString(),
                    backgroundMotion = 0f, backgroundDim = 0f, lyricsScale = 1.2f,
                )
                val request = VideoExportRequest("lyric_rendering_fixture", "", "", lyrics = CUES, config = config)
                VideoFramePainter.create(context, request, width, height).use { painter ->
                    action(Fixture(shape.name, painter, width, height))
                }
            }
        } finally { black.recycle(); background.delete() }
    }

    private class Fixture(val name: String, private val painter: VideoFramePainter, private val width: Int, private val height: Int) {
        fun render(timeMs: Long): Photograph {
            val bitmap = painter.draw(null, timeMs * 1_000L)
            val top = (height * .60f).toInt()
            val pixels = IntArray(width * (height - top))
            bitmap.getPixels(pixels, 0, width, 0, top, width, height - top)
            return Photograph(width, pixels)
        }
    }

    private class Photograph(private val width: Int, val pixels: IntArray) {
        fun highlight(): Highlight {
            var count = 0
            var first = width
            var last = -1
            var maximum = 0
            pixels.forEachIndexed { index, color ->
                val white = min(Color.red(color), min(Color.green(color), Color.blue(color)))
                maximum = maxOf(maximum, white)
                if (white >= 220) {
                    count++
                    first = minOf(first, index % width)
                    last = maxOf(last, index % width)
                }
            }
            return Highlight(count, if (last >= first) last - first + 1 else 0, maximum)
        }

        fun difference(other: Photograph): Double {
            var difference = 0L
            pixels.indices.forEach { index -> difference += abs(Color.red(pixels[index]) - Color.red(other.pixels[index])) }
            return difference.toDouble() / (pixels.size * 255L)
        }
    }

    private data class Highlight(val count: Int, val width: Int, val maximum: Int)

    companion object {
        private val CUES = listOf(
            VideoLyricCue(1_000, "Opening line"),
            VideoLyricCue(3_000, "This longer incoming line wraps across the screen while each word keeps its original cue and stays fully readable"),
            VideoLyricCue(6_000, "The final line stays visible"),
        )
    }
}
