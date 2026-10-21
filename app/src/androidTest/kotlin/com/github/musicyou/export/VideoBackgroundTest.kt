package com.github.musicyou.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.AudioWaveformChannel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoBackgroundTest {
    @Test fun customPhotosRespectAllEightExifOrientations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "export_exif_${System.nanoTime()}.jpg")
        val original = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(original)
        val paint = Paint()
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        colors.forEachIndexed { index, color ->
            paint.color = color
            val left = (index % 2) * 40f; val top = (index / 2) * 20f
            canvas.drawRect(left, top, left + 40, top + 20, paint)
        }
        val expected = listOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2), intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 0, 1),
            intArrayOf(0, 2, 1, 3), intArrayOf(2, 0, 3, 1), intArrayOf(3, 1, 2, 0), intArrayOf(1, 3, 0, 2),
        )
        try {
            for (orientation in 1..8) {
                file.outputStream().use { original.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val decoded = VideoFramePainter.loadBitmap(context, Uri.fromFile(file).toString())
                assertNotNull(decoded)
                requireNotNull(decoded)
                try {
                    assertEquals(if (orientation >= 5) 40 else 80, decoded.width)
                    assertEquals(if (orientation >= 5) 80 else 40, decoded.height)
                    expected[orientation - 1].forEachIndexed { corner, colorIndex ->
                        val x = decoded.width * (if (corner % 2 == 0) 1 else 3) / 4
                        val y = decoded.height * (if (corner / 2 == 0) 1 else 3) / 4
                        val actual = decoded.getPixel(x, y)
                        val expectedColor = colors[colorIndex]
                        assertTrue("Orientation $orientation corner $corner", kotlin.math.abs(Color.red(actual) - Color.red(expectedColor)) < 8 && kotlin.math.abs(Color.green(actual) - Color.green(expectedColor)) < 8 && kotlin.math.abs(Color.blue(actual) - Color.blue(expectedColor)) < 8)
                    }
                } finally { decoded.recycle() }
            }
        } finally { original.recycle(); file.delete() }
    }

    @Test fun transparentPhotoClearsOldFramesAndWaveformRetainsBothPeaks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "export_alpha_${System.nanoTime()}.png")
        val transparent = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { transparent.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val request = VideoExportRequest("fixture", "", "", config = VideoExportConfig(visualization = VideoVisualization.WAVEFORM, background = VideoBackground.CUSTOM, customBackgroundUri = Uri.fromFile(file).toString()))
            VideoFramePainter.create(context, request, 320, 180).use { painter ->
                val clean = painter.draw(null, 0).copy(Bitmap.Config.ARGB_8888, false)
                try {
                    val frame = AudioVisualizationFrame(waveformChannels = listOf(AudioWaveformChannel(List(256) { -.9f }, List(256) { .9f })), ready = true)
                    val loud = painter.draw(frame, 0)
                    var peaks = 0
                    val y = (180 * .43 - 180 * .18 * .65).toInt()
                    for (x in 34..286) if (Color.green(loud.getPixel(x, y)) > Color.green(clean.getPixel(x, y)) + 40) peaks++
                    assertTrue("Symmetric audio must retain its positive and negative peaks", peaks > 50)
                    assertTrue("Transparent photos must not retain the previous trace", clean.sameAs(painter.draw(null, 0)))
                } finally { clean.recycle() }
            }
        } finally { transparent.recycle(); file.delete() }
    }

    @Test fun stereoWaveformKeepsSeparateChannelsAndSignedTransientPolarity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val request = VideoExportRequest("stereo_fixture", "", "", config = VideoExportConfig(visualization = VideoVisualization.WAVEFORM, glow = 0f))
        VideoFramePainter.create(context, request, 320, 180).use { painter ->
            val silentChannels = List(2) { AudioWaveformChannel(List(16) { 0f }, List(16) { 0f }) }
            val clean = painter.draw(AudioVisualizationFrame(waveformChannels = silentChannels), 0).copy(Bitmap.Config.ARGB_8888, false)
            val left = AudioWaveformChannel(List(16) { if (it == 4) .65f else 0f }, List(16) { if (it == 4) .95f else 0f })
            val right = AudioWaveformChannel(List(16) { if (it == 11) -.95f else 0f }, List(16) { if (it == 11) -.65f else 0f })
            try {
                val rendered = painter.draw(AudioVisualizationFrame(waveformChannels = listOf(left, right), ready = true), 0)
                val center = 180 * .43f
                val amplitude = 180 * .18f
                val laneAmplitude = amplitude * .44f
                val leftCenter = center - amplitude * .52f
                val rightCenter = center + amplitude * .52f
                fun hasTrace(x: Float, y: Float): Boolean {
                    for (py in y.toInt() - 2..y.toInt() + 2) for (px in x.toInt() - 2..x.toInt() + 2) {
                        if (Color.green(rendered.getPixel(px, py)) > Color.green(clean.getPixel(px, py)) + 35) return true
                    }
                    return false
                }
                val firstX = 32f + 256f * 4 / 15
                val secondX = 32f + 256f * 11 / 15
                assertTrue("The positive left transient must stay above the left baseline", hasTrace(firstX, leftCenter - laneAmplitude * .8f))
                assertTrue("The negative right transient must stay below the right baseline", hasTrace(secondX, rightCenter + laneAmplitude * .8f))
                assertTrue("Left polarity must not be mirrored or averaged into the right lane", !hasTrace(firstX, rightCenter - laneAmplitude * .8f))
                assertTrue("Right polarity must not be mirrored or averaged into the left lane", !hasTrace(secondX, leftCenter + laneAmplitude * .8f))
            } finally { clean.recycle() }
        }
    }
}
