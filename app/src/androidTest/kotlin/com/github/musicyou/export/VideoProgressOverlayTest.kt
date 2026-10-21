package com.github.musicyou.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoProgressOverlayTest {
    @Test fun disabledOrUnknownDurationDoesNotChangeTheFrame() {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val expected = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            val overlay = VideoProgressOverlay()
            overlay.draw(Canvas(bitmap), 5_000_000L, 10_000_000L, 320, 180, .25f, Color.CYAN, VideoExportConfig())
            assertTrue(bitmap.sameAs(expected))
            overlay.draw(Canvas(bitmap), 5_000_000L, 0L, 320, 180, .25f, Color.CYAN, VideoExportConfig(showProgressBar = true))
            assertTrue(bitmap.sameAs(expected))
        } finally { bitmap.recycle(); expected.recycle() }
    }

    @Test fun portraitAndLandscapePlayheadsTrackTheExactMediaPosition() {
        listOf(640 to 360, 360 to 640).forEach { (width, height) ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val overlay = VideoProgressOverlay()
            val config = VideoExportConfig(showProgressBar = true)
            val unit = minOf(width, height) / 720f
            val canvas = Canvas(bitmap)
            fun draw(timeUs: Long) {
                bitmap.eraseColor(Color.BLACK)
                overlay.draw(canvas, timeUs, 10_000_000L, width, height, unit, Color.CYAN, config)
            }
            try {
                draw(5_000_000L)
                val middle = bitmap.getPixel(width / 2, (height - 30f * unit).toInt())
                assertTrue("The playhead must reach the middle at exactly half the song", Color.red(middle) > 230 && Color.green(middle) > 230)
                val halfway = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    draw(2_500_000L)
                    val quarter = bitmap.getPixel((width * .3f).toInt(), (height - 30f * unit).toInt())
                    assertTrue("Seeking must move the playhead immediately", Color.red(quarter) > 230 && Color.green(quarter) > 230)
                    draw(5_000_000L)
                    assertTrue("Revisiting a timestamp must reproduce the exact overlay", bitmap.sameAs(halfway))
                } finally { halfway.recycle() }
            } finally { bitmap.recycle() }
        }
    }
}
