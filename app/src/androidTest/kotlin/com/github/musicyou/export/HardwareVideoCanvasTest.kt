package com.github.musicyou.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class HardwareVideoCanvasTest {
    @Test fun hardwareFramesPreserveCanvasOrientationColorsAndEverySubmission() {
        val legacy = render(false, 4) { canvas, index -> fixture(canvas, index) }
        val accelerated = render(true, 4) { canvas, index -> fixture(canvas, index) }
        legacy.zip(accelerated).forEachIndexed { index, (reference, actual) ->
            try {
                compare(reference, actual, 5.0)
                assertTrue("Frame $index must preserve its own marker", abs(Color.red(actual.getPixel(20, HEIGHT / 2)) - index * 50) <= 3)
            } finally { reference.recycle(); actual.recycle() }
        }
    }

    @Test fun hardwarePainterPreservesRichVideoComposition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val request = VideoExportRequest(
            "hardware_canvas_fixture", "Canvas composition", "Rendering fixture",
            lyrics = listOf(VideoLyricCue(100, "A synchronized lyric")),
        )
        val sampleRate = 48_000
        val samples = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * 2) { index -> (sin(2 * PI * 440 * (index / 2) / sampleRate) * .5).toFloat() }
        val frame = AudioFrameAnalyzer(sampleRate, 2).analyze(samples, 500_000, 0)
        val legacy = VideoFramePainter.create(context, request, WIDTH, HEIGHT).use { painter ->
            render(false, 1) { canvas, _ -> painter.drawTo(canvas, frame, 500_000) }.single()
        }
        val accelerated = VideoFramePainter.create(context, request, WIDTH, HEIGHT).use { painter ->
            render(true, 1) { canvas, _ -> painter.drawTo(canvas, frame, 500_000) }.single()
        }
        try {
            File(context.cacheDir, "export_canvas_legacy.png").outputStream().use { legacy.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(context.cacheDir, "export_canvas_hardware.png").outputStream().use { accelerated.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compare(legacy, accelerated, 12.0)
        } finally { legacy.recycle(); accelerated.recycle() }
    }

    private fun fixture(canvas: Canvas, frame: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.rgb(frame * 50, 20, 30))
        paint.shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Color.CYAN, Color.MAGENTA, Shader.TileMode.CLAMP)
        canvas.drawRect(70f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint); paint.shader = null
        paint.color = Color.YELLOW; canvas.drawCircle(55f, 120f, 23f, paint)
        paint.color = Color.GREEN; canvas.drawRect(0f, HEIGHT - 25f, 65f, HEIGHT.toFloat(), paint)
        paint.color = Color.WHITE; paint.strokeWidth = 3f; paint.style = Paint.Style.STROKE
        val path = Path().apply { moveTo(80f, 20f); cubicTo(120f, 170f, 220f, -40f, 300f, 140f) }
        canvas.drawPath(path, paint)
    }

    private fun render(hardware: Boolean, count: Int, draw: (Canvas, Int) -> Unit): List<Bitmap> {
        val frames = ArrayList<Bitmap>()
        ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3).use { reader ->
            EncoderCanvasSurface(reader.surface, WIDTH, HEIGHT, preferHardware = hardware).use { graphics ->
                repeat(count) { index ->
                    graphics.drawCanvas(VideoTimeline.presentationTimeUs(index.toLong())) { draw(it, index) }
                    assertTrue("The test must exercise its requested rendering backend", graphics.hardwareAccelerated == hardware)
                    val deadline = SystemClock.elapsedRealtime() + 5_000
                    var image = reader.acquireNextImage()
                    while (image == null && SystemClock.elapsedRealtime() < deadline) { SystemClock.sleep(2); image = reader.acquireNextImage() }
                    checkNotNull(image) { "The graphics surface did not deliver a frame" }.use { captured ->
                        val plane = captured.planes[0]
                        val buffer = plane.buffer
                        val pixels = IntArray(WIDTH * HEIGHT)
                        repeat(HEIGHT) { y -> repeat(WIDTH) { x ->
                            val offset = y * plane.rowStride + x * plane.pixelStride
                            pixels[y * WIDTH + x] = Color.argb(buffer.get(offset + 3).toInt() and 255, buffer.get(offset).toInt() and 255, buffer.get(offset + 1).toInt() and 255, buffer.get(offset + 2).toInt() and 255)
                        } }
                        frames += Bitmap.createBitmap(pixels, WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                    }
                }
            }
        }
        return frames
    }

    private fun compare(reference: Bitmap, actual: Bitmap, maximumMeanError: Double) {
        val expected = IntArray(WIDTH * HEIGHT); val result = IntArray(expected.size)
        reference.getPixels(expected, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        actual.getPixels(result, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        var error = 0L
        expected.indices.forEach { index ->
            error += abs(Color.red(expected[index]) - Color.red(result[index]))
            error += abs(Color.green(expected[index]) - Color.green(result[index]))
            error += abs(Color.blue(expected[index]) - Color.blue(result[index]))
        }
        val mean = error.toDouble() / (expected.size * 3)
        assertTrue("The accelerated composition changed Canvas colors or orientation: mean channel error $mean", mean < maximumMeanError)
    }

    companion object { private const val WIDTH = 320; private const val HEIGHT = 180 }
}
