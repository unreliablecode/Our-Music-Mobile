package com.github.musicyou.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExportThrottleTest {
    @Test fun slowFramesRetainTheSameCompleteSixtyFpsTimelineAndAudioDuration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "export_throttle_${System.nanoTime()}").apply { mkdirs() }
        try {
            val sampleRate = 48_000
            val pcmFrames = sampleRate * 2
            val bytes = ByteBuffer.allocate(pcmFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(pcmFrames) { index ->
                bytes.putShort((sin(2 * PI * 440 * index / sampleRate) * 20_000).toInt().toShort())
                bytes.putShort((sin(2 * PI * 880 * index / sampleRate) * 15_000).toInt().toShort())
            }
            val pcm = File(directory, "source.pcm").apply { writeBytes(bytes.array()) }
            val source = File(directory, "source.m4a")
            PcmExportAudio.encodeAac(ExportPcm(pcm, sampleRate, 2, pcmFrames.toLong()), source) {}
            val decoded = PcmExportAudio.decode(source, File(directory, "decoded.pcm")) {}
            val expectedFrames = VideoTimeline.frameCount(decoded.durationUs)
            val expectedVideoDurationUs = VideoTimeline.presentationTimeUs(expectedFrames)
            assertTrue("The fixture must exercise a partial audio ending", expectedVideoDurationUs > decoded.durationUs)
            val request = VideoExportRequest("throttle_fixture", "Rendering under load", "Local stereo audio",
                lyrics = listOf(VideoLyricCue(200, "The lyric keeps its song timestamp")),
                config = VideoExportConfig(visualization = VideoVisualization.SPECTRUM))
            var reference: Timeline? = null
            var delayedFrames = 0L
            var injectedDelayMs = 0L
            for (throttled in listOf(false, true)) {
                val work = File(directory, "render_$throttled").apply { mkdirs() }
                val beforeFrame: ((Long) -> Unit)? = if (throttled) { index ->
                    if (index % 2L == 0L) {
                        val pauseMs = 50L + index % 5L * 7L
                        SystemClock.sleep(pauseMs)
                        injectedDelayMs += pauseMs
                        delayedFrames++
                    }
                } else null
                val output = VideoExporter.render(context, request, source, work, beforeFrame = beforeFrame) { _, _ -> }
                if (!throttled) {
                    assertRejected("An incorrect expected frame count must not pass verification") {
                        VideoExportVerifier.verify(output, request.config, decoded.durationUs + 100_000L)
                    }
                    assertRejected("Changed output dimensions must not pass verification") {
                        VideoExportVerifier.verify(output, request.config.copy(aspectRatio = VideoAspectRatio.PORTRAIT), decoded.durationUs)
                    }
                    val truncated = File(directory, "truncated.mp4")
                    output.inputStream().use { input -> truncated.outputStream().use { stream ->
                        val buffer = ByteArray(16 * 1024)
                        var remaining = output.length() / 2
                        while (remaining > 0) {
                            val size = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (size < 0) break
                            stream.write(buffer, 0, size); remaining -= size
                        }
                    } }
                    assertRejected("A truncated MP4 must never be accepted as a complete export") {
                        VideoExportVerifier.verify(truncated, request.config, decoded.durationUs)
                    }
                }
                val timeline = readTimeline(output)
                assertEquals(expectedFrames.toInt(), timeline.timestamps.size)
                timeline.timestamps.forEachIndexed { index, timeUs ->
                    assertTrue("Frame $index lost its exact 60 FPS position under load", abs(timeUs - VideoTimeline.presentationTimeUs(index.toLong())) <= 2L)
                    if (index > 0) assertTrue(timeUs - timeline.timestamps[index - 1] in 16_666L..16_667L)
                }
                assertTrue(abs(timeline.videoDurationUs - expectedVideoDurationUs) <= 1_000L)
                Mp4VideoTiming.verify(output, expectedFrames)
                assertTrue(abs(timeline.audioDurationUs - decoded.durationUs) <= 1_000L)
                assertTrue("Only the final picture may outlast the unmodified audio", timeline.videoDurationUs - timeline.audioDurationUs in 0L..17_667L)
                if (reference == null) reference = timeline else assertEquals(reference, timeline)
            }
            assertTrue("The regression must force many frames beyond the 16.7 ms rendering budget", delayedFrames > 20)
            assertTrue("The regression must introduce sustained scheduling pressure", injectedDelayMs > 1_000)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "THROTTLE_CFR frames=$expectedFrames delayed_frames=$delayedFrames injected_ms=$injectedDelayMs video_duration_us=${reference!!.videoDurationUs} audio_duration_us=${reference!!.audioDurationUs} all_timestamps_identical=true\n")
            })
        } finally { directory.deleteRecursively() }
    }

    private suspend fun assertRejected(message: String, action: suspend () -> Unit) {
        var rejected = false
        try { action() }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            rejected = true
        }
        assertTrue(message, rejected)
    }

    private fun readTimeline(file: File): Timeline {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val videoTrack = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
            val audioTrack = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val videoDurationUs = extractor.getTrackFormat(videoTrack).getLong(MediaFormat.KEY_DURATION)
            val audioDurationUs = extractor.getTrackFormat(audioTrack).getLong(MediaFormat.KEY_DURATION)
            extractor.selectTrack(videoTrack)
            val timestamps = ArrayList<Long>()
            while (extractor.sampleTime >= 0) { timestamps += extractor.sampleTime; extractor.advance() }
            return Timeline(timestamps, videoDurationUs, audioDurationUs)
        } finally { extractor.release() }
    }

    private data class Timeline(val timestamps: List<Long>, val videoDurationUs: Long, val audioDurationUs: Long)
}
