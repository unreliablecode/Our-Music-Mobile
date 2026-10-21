package com.github.musicyou.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Compares the same native export with CPU and GPU canvas rendering on the same device. */
@RunWith(AndroidJUnit4::class)
class VideoExportPerformanceTest {
    @Test fun compareCanvasPipelinesWithoutDroppingVideoFrames() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "export_benchmark_${System.nanoTime()}").apply { mkdirs() }
        try {
            val sampleRate = 48_000
            val frames = sampleRate * 3
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { index ->
                bytes.putShort((sin(2 * PI * 220 * index / sampleRate) * 20_000).toInt().toShort())
                bytes.putShort((sin(2 * PI * 880 * index / sampleRate) * 15_000).toInt().toShort())
            }
            val pcm = File(directory, "source.pcm").apply { writeBytes(bytes.array()) }
            val source = File(directory, "source.m4a")
            PcmExportAudio.encodeAac(ExportPcm(pcm, sampleRate, 2, frames.toLong()), source) {}
            for (resolution in VideoResolution.entries) {
                var expectedFrames = -1L
                for (hardware in listOf(false, true)) {
                    val work = File(directory, "${resolution.name}_$hardware").apply { mkdirs() }
                    val metrics = VideoExportMetrics()
                    val config = VideoExportConfig(
                        aspectRatio = if (resolution == VideoResolution.HD) VideoAspectRatio.LANDSCAPE else VideoAspectRatio.PORTRAIT,
                        resolution = resolution,
                    )
                    val video = VideoExporter.render(context, VideoExportRequest("benchmark", "Rendering benchmark", "Local stereo audio",
                        lyrics = listOf(VideoLyricCue(0, "A timed line for the rendering test"), VideoLyricCue(1_500, "The next line follows the same timeline")),
                        config = config), source, work, useHardwareCanvas = hardware, metrics = metrics) { _, _ -> }
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(video.path)
                        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
                        extractor.selectTrack(track)
                        var count = 0L
                        while (extractor.sampleTime >= 0) {
                            assertTrue(kotlin.math.abs(extractor.sampleTime - VideoTimeline.presentationTimeUs(count)) <= 2L)
                            count++
                            extractor.advance()
                        }
                        assertEquals(metrics.frames, count)
                        if (expectedFrames < 0) expectedFrames = count else assertEquals(expectedFrames, count)
                        assertEquals(if (hardware) count else 0L, metrics.hardwareCanvasFrames)
                    } finally { extractor.release() }
                    val report = String.format(Locale.US,
                        "EXPORT_BENCH %dx%d path=%s frames=%d total_s=%.3f render_submit_s=%.3f analysis_s=%.3f drain_s=%.3f decode_s=%.3f audio_s=%.3f calibration_s=%.3f render_fps=%.2f codec=%s",
                        config.width, config.height, if (hardware) "GPU" else "CPU", metrics.frames,
                        metrics.totalNanos / 1e9, metrics.frameRenderNanos / 1e9, metrics.analysisNanos / 1e9,
                        metrics.drainNanos / 1e9, metrics.decodeNanos / 1e9, metrics.audioEncodeNanos / 1e9,
                        metrics.calibrationNanos / 1e9, metrics.frames * 1e9 / (metrics.frameRenderNanos + metrics.analysisNanos + metrics.drainNanos), metrics.encoderName)
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$report\n") })
                }
            }
        } finally { directory.deleteRecursively() }
    }
}
