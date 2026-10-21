package com.github.musicyou.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.downloads.OfflineDownload
import com.github.musicyou.downloads.OfflineDownloads
import com.github.musicyou.downloads.sha256
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExportIntegrityTest {
    @Test fun foregroundExportPublishesACompletePlayableVideoAndKeepsItsDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "export_publish_${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val downloads = OfflineDownloads.get(context)
        val manager = VideoExports.get(context)
        var published: android.net.Uri? = null
        assertTrue(!manager.state.value.running)
        try {
            val pcm = File(directory, "fixture.pcm")
            val bytes = ByteBuffer.allocate(44_100 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(44_100 * 2) { bytes.putShort((sin(2 * PI * 440 * it / 44_100) * 16000).toInt().toShort()) }
            pcm.writeBytes(bytes.array())
            val compressed = downloads.store.partialFile(id)
            PcmExportAudio.encodeAac(ExportPcm(pcm, 44_100, 1, 88_200), compressed) {}
            downloads.store.upsert(OfflineDownload(id, id))
            downloads.store.complete(id, compressed.length(), sha256(compressed))
            val source = requireNotNull(downloads.localFile(id))
            val decoded = PcmExportAudio.decode(source, File(directory, "decoded.pcm")) {}
            manager.start(VideoExportRequest(id, id, "Fixture", lyrics = listOf(VideoLyricCue(500, "A synchronized lyric"))))
            withTimeout(120_000) { manager.state.first { !it.running } }
            assertEquals(null, manager.state.value.error)
            val uri = requireNotNull(manager.state.value.outputUri)
            published = uri
            assertTrue(downloads.localFile(id)?.isFile == true)
            withTimeout(5_000) { while (File(context.cacheDir, "video_exports").listFiles().orEmpty().isNotEmpty()) delay(50) }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.SIZE), null, null, null)?.use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertTrue(it.getLong(1) > 0)
                } ?: error("The saved video is not in the library")
            }
            context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(descriptor.fileDescriptor)
                    assertEquals(2, extractor.trackCount)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
                    extractor.selectTrack(track)
                    var frames = 0L
                    while (extractor.sampleTime >= 0) {
                        assertTrue(abs(extractor.sampleTime - VideoTimeline.presentationTimeUs(frames)) <= 2)
                        frames++; extractor.advance()
                    }
                    assertEquals(VideoTimeline.frameCount(decoded.durationUs), frames)
                } finally { extractor.release() }
            }
        } finally {
            manager.cancel()
            published?.let { context.contentResolver.delete(it, null, null) }
            downloads.store.remove(id); directory.deleteRecursively(); manager.clearResult()
        }
    }

    @Test fun aacRoundTripMeasuresTransientAlignment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "export_impulse_${System.nanoTime()}").apply { mkdirs() }
        try {
            val sampleRate = 48_000
            val onset = sampleRate / 2
            val data = ShortArray(sampleRate * 2)
            val random = java.util.Random(711)
            for (index in 0 until 512) data[onset + index] = ((random.nextFloat() * 2 - 1) * 24_000).toInt().toShort()
            val bytes = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            data.forEach { bytes.putShort(it) }
            val pcm = File(directory, "source.pcm").apply { writeBytes(bytes.array()) }
            val encoded = File(directory, "encoded.m4a")
            val timing = PcmExportAudio.encodeAac(ExportPcm(pcm, sampleRate, 1, data.size.toLong()), encoded) {}
            val decoded = PcmExportAudio.decode(encoded, File(directory, "decoded.pcm")) {}
            val out = ByteBuffer.wrap(decoded.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(out.remaining()).also { out.get(it) }
            var lag = 0
            var best = Double.NEGATIVE_INFINITY
            for (candidate in -sampleRate / 10..sampleRate / 10) {
                var score = 0.0
                for (index in 0 until 512) score += data[onset + index].toDouble() * samples[onset + index + candidate]
                if (score > best) { best = score; lag = candidate }
            }
            val evidence = "AAC transient lag_samples=$lag lag_ms=${lag * 1000.0 / sampleRate} encoder_delay=${timing.delayFrames} encoder_padding=${timing.paddingFrames} first_pts_us=${timing.firstTimestampUs} source_frames=${data.size} decoded_frames=${samples.size}"
            Log.i("VideoExportIntegrity", evidence)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$evidence\n") })
            assertTrue("The transient must survive audio encoding", best > 1_000_000)
            val work = File(directory, "render").apply { mkdirs() }
            val video = VideoExporter.render(context, VideoExportRequest("impulse", "Transient alignment", "Fixture"), encoded, work) { _, _ -> }
            video.copyTo(File(context.cacheDir, "export_validation_sync.mp4"), overwrite = true)
            decoded.file.copyTo(File(context.cacheDir, "export_validation_sync_source.pcm"), overwrite = true)
            encoded.copyTo(File(context.cacheDir, "export_validation_sync_aac.m4a"), overwrite = true)
            val rendered = PcmExportAudio.decode(video, File(directory, "rendered.pcm")) {}
            val renderedBuffer = ByteBuffer.wrap(rendered.file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val renderedSamples = ShortArray(renderedBuffer.remaining()).also { renderedBuffer.get(it) }
            var renderedLag = 0
            var renderedBest = Double.NEGATIVE_INFINITY
            for (candidate in -sampleRate / 10..sampleRate / 10) {
                var score = 0.0
                for (index in 0 until 512) score += samples[onset + lag + index].toDouble() * renderedSamples[onset + lag + index + candidate]
                if (score > renderedBest) { renderedBest = score; renderedLag = candidate }
            }
            val corrected = "MP4 transient lag_samples=$renderedLag source_frames=${decoded.frames} rendered_frames=${rendered.frames}"
            Log.i("VideoExportIntegrity", corrected)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$corrected\n") })
            assertTrue("The exported audio must stay aligned to the exact PCM used for lyrics and visualization", abs(renderedLag) <= 4)
            assertTrue("The exported audio duration must preserve the source timeline", abs(rendered.frames - decoded.frames) <= 8)
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelStopsForegroundExportAndRemovesTemporaryFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "export_cancel_${System.nanoTime()}"
        val title = "Export cancellation $id"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val downloads = OfflineDownloads.get(context)
        val manager = VideoExports.get(context)
        assertTrue("Another video export is running", !manager.state.value.running)
        try {
            val pcm = File(directory, "fixture.pcm")
            val bytes = ByteBuffer.allocate(44_100 * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(44_100 * 3) { bytes.putShort((sin(2 * PI * 440 * it / 44_100) * 16000).toInt().toShort()) }
            pcm.writeBytes(bytes.array())
            val compressed = downloads.store.partialFile(id)
            PcmExportAudio.encodeAac(ExportPcm(pcm, 44_100, 1, 44_100L * 3), compressed) {}
            downloads.store.upsert(OfflineDownload(id, title))
            downloads.store.complete(id, compressed.length(), sha256(compressed))
            manager.start(VideoExportRequest(id, title, "Fixture", config = VideoExportConfig(resolution = VideoResolution.FULL_HD)))
            withTimeout(120_000) {
                manager.state.first { !it.running || it.progress > .20f }
                assertTrue(manager.state.value.error.orEmpty(), manager.state.value.running)
                manager.cancel()
                manager.state.first { !it.running }
                while (File(context.cacheDir, "video_exports").listFiles().orEmpty().isNotEmpty()) delay(50)
            }
            assertEquals("Export canceled", manager.state.value.stage)
            assertEquals(null, manager.state.value.outputUri)
            assertTrue(downloads.localFile(id)?.isFile == true)
            val count = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?", arrayOf("$title%"), null,
            )?.use { it.count } ?: 0
            assertEquals(0, count)
        } finally { manager.cancel(); downloads.store.remove(id); directory.deleteRecursively(); manager.clearResult() }
    }

    @Test fun restartAcceptedDuringServiceShutdownFinishesTheNewExport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "export_restart_${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val downloads = OfflineDownloads.get(context)
        val manager = VideoExports.get(context)
        val releaseMain = CountDownLatch(1)
        var published: android.net.Uri? = null
        assertTrue(!manager.state.value.running)
        try {
            val samples = ByteBuffer.allocate(48_000 * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(48_000 * 3) { samples.putShort((sin(2 * PI * 440 * it / 48_000) * 16_000).toInt().toShort()) }
            val pcm = File(directory, "fixture.pcm").apply { writeBytes(samples.array()) }
            val compressed = downloads.store.partialFile(id)
            PcmExportAudio.encodeAac(ExportPcm(pcm, 48_000, 1, 144_000), compressed) {}
            downloads.store.upsert(OfflineDownload(id, id))
            downloads.store.complete(id, compressed.length(), sha256(compressed))
            manager.start(VideoExportRequest(id, "First export", "Fixture", config = VideoExportConfig(resolution = VideoResolution.FULL_HD)))
            withTimeout(120_000) { manager.state.first { !it.running || it.progress > .20f } }
            assertTrue(manager.state.value.error.orEmpty(), manager.state.value.running)
            val mainBlocked = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                mainBlocked.countDown()
                releaseMain.await(3, TimeUnit.SECONDS)
            }
            assertTrue("The main thread did not enter the shutdown handoff", mainBlocked.await(2, TimeUnit.SECONDS))
            manager.cancel()
            withTimeout(2_000) {
                manager.state.first { !it.running }
                while (!manager.state.value.running) {
                    manager.start(VideoExportRequest(id, "Restarted export", "Fixture"))
                    if (!manager.state.value.running) delay(10)
                }
            }
            releaseMain.countDown()
            withTimeout(120_000) { manager.state.first { !it.running } }
            assertEquals(null, manager.state.value.error)
            assertEquals("Restarted export", manager.state.value.sourceTitle)
            published = requireNotNull(manager.state.value.outputUri)
            assertTrue(downloads.localFile(id)?.isFile == true)
            withTimeout(5_000) { while (File(context.cacheDir, "video_exports").listFiles().orEmpty().isNotEmpty()) delay(50) }
        } finally {
            releaseMain.countDown()
            manager.cancel()
            published?.let { context.contentResolver.delete(it, null, null) }
            downloads.store.remove(id); directory.deleteRecursively(); manager.clearResult()
        }
    }

    @Test fun landscape720AndPortrait1080KeepEveryFrameAndBothAudioChannels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "export_integrity_${System.nanoTime()}").apply { mkdirs() }
        try {
            val pcmFile = File(directory, "fixture.pcm")
            val frames = 44_100 * 2
            val bytes = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { frame ->
                bytes.putShort((sin(2 * PI * 440 * frame / 44_100) * .6 * Short.MAX_VALUE).toInt().toShort())
                bytes.putShort((sin(2 * PI * 880 * frame / 44_100) * .4 * Short.MAX_VALUE).toInt().toShort())
            }
            FileOutputStream(pcmFile).use { it.write(bytes.array()) }
            val source = File(directory, "fixture.m4a")
            PcmExportAudio.encodeAac(ExportPcm(pcmFile, 44_100, 2, frames.toLong()), source) {}
            val decodedSource = PcmExportAudio.decode(source, File(directory, "source.pcm")) {}
            listOf(
                VideoExportConfig(VideoAspectRatio.LANDSCAPE, VideoResolution.HD, VideoVisualization.CIRCULAR, VideoBackground.AURORA),
                VideoExportConfig(VideoAspectRatio.PORTRAIT, VideoResolution.FULL_HD, VideoVisualization.WAVEFORM, VideoBackground.SUNSET),
            ).forEachIndexed { index, config ->
                val work = File(directory, "render_$index").apply { mkdirs() }
                val request = VideoExportRequest("fixture", "Stereo integrity", "440 Hz and 880 Hz", lyrics = listOf(VideoLyricCue(300, "First synchronized line"), VideoLyricCue(1200, "Second synchronized line")), config = config)
                val renderStartedAt = System.nanoTime()
                val video = VideoExporter.render(context, request, source, work) { _, _ -> }
                val renderSeconds = (System.nanoTime() - renderStartedAt) / 1_000_000_000.0
                val expectedFrames = VideoTimeline.frameCount(decodedSource.durationUs)
                val timingEvidence = "${config.width}x${config.height}: render_frames=$expectedFrames render_seconds=$renderSeconds render_fps=${expectedFrames / renderSeconds}"
                Log.i("VideoExportIntegrity", timingEvidence)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$timingEvidence\n") })
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(video.path)
                    assertEquals(2, extractor.trackCount)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC }
                    val format = extractor.getTrackFormat(track)
                    assertEquals(config.width, format.getInteger(MediaFormat.KEY_WIDTH))
                    assertEquals(config.height, format.getInteger(MediaFormat.KEY_HEIGHT))
                    extractor.selectTrack(track)
                    var sample = 0L
                    var previous = -1L
                    while (extractor.sampleTime >= 0) {
                        val time = extractor.sampleTime
                        assertTrue("Frame $sample has timestamp $time", abs(time - VideoTimeline.presentationTimeUs(sample)) <= 2)
                        if (previous >= 0) assertTrue(time - previous in 16_666L..16_667L)
                        previous = time; sample++; extractor.advance()
                    }
                    assertEquals(VideoTimeline.frameCount(decodedSource.durationUs), sample)
                    assertTrue(decodedSource.durationUs - previous in 1..16_667)
                    val decodedOutput = PcmExportAudio.decode(video, File(work, "verified.pcm")) {}
                    assertEquals(2, decodedOutput.channels)
                    assertEquals(44_100, decodedOutput.sampleRate)
                    assertTrue("Audio duration changed from ${decodedSource.durationUs} to ${decodedOutput.durationUs}", abs(decodedOutput.durationUs - decodedSource.durationUs) < 70_000)
                    val output = decodedOutput.file.readBytes()
                    val shorts = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samples = ShortArray(shorts.remaining()).also { shorts.get(it) }
                    repeat(2) { channel ->
                        val first = decodedOutput.sampleRate / 5
                        val last = minOf(decodedOutput.frames.toInt() - first, first + decodedOutput.sampleRate)
                        var crossings = 0
                        var energy = 0.0
                        for (frame in first + 1 until last) {
                            val value = samples[frame * 2 + channel] / 32768.0
                            energy += value * value
                            if (samples[(frame - 1) * 2 + channel] <= 0 && samples[frame * 2 + channel] > 0) crossings++
                        }
                        val frequency = crossings * decodedOutput.sampleRate.toDouble() / (last - first - 1)
                        assertEquals(if (channel == 0) 440.0 else 880.0, frequency, 3.0)
                        assertTrue(energy / (last - first) > .03)
                    }
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(video.path)
                        val firstFrame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        assertTrue(firstFrame != null && firstFrame.width == config.width)
                        firstFrame?.let { bitmap ->
                            File(context.cacheDir, "export_validation_${config.width}x${config.height}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        }
                        firstFrame?.recycle()
                        val lyricFrame = retriever.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST)
                        lyricFrame?.let { bitmap ->
                            File(context.cacheDir, "export_validation_lyrics_${config.width}x${config.height}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                        }
                    } finally { retriever.release() }
                    val evidence = "${config.width}x${config.height}: $sample frames, 60 FPS, stereo pitch and duration verified"
                    Log.i("VideoExportIntegrity", evidence)
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$evidence\n") })
                } finally { extractor.release() }
            }
        } finally { directory.deleteRecursively() }
    }
}
