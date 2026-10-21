package com.github.musicyou.export

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoProgressExportTest {
    @Test fun encodedPlayheadUsesDecodedAudioDurationInsteadOfIncorrectMetadata() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "export_progress_${System.nanoTime()}").apply { mkdirs() }
        try {
            val sampleRate = 48_000
            val frameCount = sampleRate * 3 / 2
            val bytes = ByteBuffer.allocate(frameCount * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frameCount) { index ->
                bytes.putShort((sin(2 * PI * 440 * index / sampleRate) * 18_000).toInt().toShort())
                bytes.putShort((sin(2 * PI * 880 * index / sampleRate) * 12_000).toInt().toShort())
            }
            val pcm = File(directory, "source.pcm").apply { writeBytes(bytes.array()) }
            val source = File(directory, "source.m4a")
            PcmExportAudio.encodeAac(ExportPcm(pcm, sampleRate, 2, frameCount.toLong()), source) {}
            val decoded = PcmExportAudio.decode(source, File(directory, "decoded.pcm")) {}
            val configuration = VideoExportConfig(
                aspectRatio = VideoAspectRatio.LANDSCAPE,
                visualization = VideoVisualization.SPECTRUM,
                background = VideoBackground.MIDNIGHT,
                backgroundMotion = 0f,
                glow = 0f,
                showProgressBar = true,
            )
            val request = VideoExportRequest(
                "progress_fixture", "Progress follows audio", "Local stereo source",
                lyrics = listOf(VideoLyricCue(0, "The playhead follows the original song")),
                config = configuration,
                durationUs = 60_000_000L,
            )
            val work = File(directory, "render").apply { mkdirs() }
            val output = VideoExporter.render(context, request, source, work) { _, _ -> }
            val expectedFrames = VideoTimeline.frameCount(decoded.durationUs)
            Mp4VideoTiming.verify(output, expectedFrames)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC
                }
                extractor.selectTrack(track)
                var count = 0L
                while (extractor.sampleTime >= 0L) {
                    assertTrue(abs(extractor.sampleTime - VideoTimeline.presentationTimeUs(count)) <= 2L)
                    count++
                    extractor.advance()
                }
                assertEquals(expectedFrames, count)
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(output.path)
                val middleFrame = expectedFrames / 2
                val timeUs = VideoTimeline.presentationTimeUs(middleFrame)
                val bitmap = requireNotNull(retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST))
                try {
                    assertEquals(configuration.width, bitmap.width)
                    assertEquals(configuration.height, bitmap.height)
                    val unit = min(bitmap.width, bitmap.height) / 720f
                    val lineY = (bitmap.height - 30f * unit).toInt()
                    val expectedX = bitmap.width * .1 + bitmap.width * .8 * timeUs / decoded.durationUs
                    val wrongX = bitmap.width * .1 + bitmap.width * .8 * timeUs / request.durationUs
                    var first = bitmap.width
                    var last = -1
                    for (x in 0 until bitmap.width) {
                        var white = false
                        for (y in lineY - 1..lineY + 1) {
                            val color = bitmap.getPixel(x, y)
                            if (min(Color.red(color), min(Color.green(color), Color.blue(color))) > 230) white = true
                        }
                        if (white) { first = minOf(first, x); last = maxOf(last, x) }
                    }
                    assertTrue("The actual encoded frame must contain a white playhead", last >= first)
                    val actualX = (first + last) / 2.0
                    assertTrue("The exported playhead must follow decoded PCM duration: expected=$expectedX actual=$actualX wrong_metadata=$wrongX", abs(actualX - expectedX) <= 12f * unit)
                    assertTrue("Incorrect song metadata must not place the exported playhead", abs(actualX - wrongX) > bitmap.width * .25)
                    File(context.cacheDir, "export_progress_validation.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "EXPORT_PROGRESS frames=$expectedFrames time_us=$timeUs pcm_duration_us=${decoded.durationUs} metadata_duration_us=${request.durationUs} playhead_x=$actualX expected_x=$expectedX fixed_60_fps=true\n")
                    })
                } finally { bitmap.recycle() }
            } finally { retriever.release() }
        } finally { directory.deleteRecursively() }
    }
}
