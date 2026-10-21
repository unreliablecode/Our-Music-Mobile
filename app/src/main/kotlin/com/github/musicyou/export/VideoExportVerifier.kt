package com.github.musicyou.export

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A video is eligible for publication only after its finalized MP4 timeline is checked. */
internal object VideoExportVerifier {
    suspend fun verify(file: File, configuration: VideoExportConfig, audioDurationUs: Long) {
        currentCoroutineContext().ensureActive()
        check(file.isFile && file.length() > 0) { "The video export is incomplete. No video was saved." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val tracks = (0 until extractor.trackCount).map { it to extractor.getTrackFormat(it) }
            val videoTracks = tracks.filter { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audioTracks = tracks.filter { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            check(videoTracks.size == 1 && audioTracks.size == 1) { "The video export is missing an audio or video track. No video was saved." }
            val (track, format) = videoTracks.single()
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_AVC &&
                format.getInteger(MediaFormat.KEY_WIDTH) == configuration.width && format.getInteger(MediaFormat.KEY_HEIGHT) == configuration.height) {
                "The video encoder changed the selected output format. No video was saved."
            }
            val expectedFrames = VideoTimeline.frameCount(audioDurationUs)
            val expectedDurationUs = VideoTimeline.presentationTimeUs(expectedFrames)
            // Android exposes this field through movie edits rounded to milliseconds.
            // The media sample table is checked separately at its exact integer timebase.
            check(format.containsKey(MediaFormat.KEY_DURATION) && abs(format.getLong(MediaFormat.KEY_DURATION) - expectedDurationUs) <= 1_000L) {
                "The video export did not preserve its full 60 FPS duration. No video was saved."
            }
            Mp4VideoTiming.verify(file, expectedFrames)
            val audioFormat = audioTracks.single().second
            check(audioFormat.containsKey(MediaFormat.KEY_DURATION) && abs(audioFormat.getLong(MediaFormat.KEY_DURATION) - audioDurationUs) <= 1_000L) {
                "The video export changed the audio duration. No video was saved."
            }
            var payload = ByteBuffer.allocateDirect(if (Build.VERSION.SDK_INT >= 28) 64 * 1024 else configuration.width * configuration.height * 4)
            fun readPayload(source: MediaExtractor) {
                val expectedSize = if (Build.VERSION.SDK_INT >= 28) source.sampleSize else -1L
                if (Build.VERSION.SDK_INT >= 28) {
                    check(expectedSize in 1L..32L * 1024 * 1024) { "The video export contains an invalid media sample. No video was saved." }
                    if (payload.capacity() < expectedSize) payload = ByteBuffer.allocateDirect(expectedSize.toInt())
                }
                payload.clear()
                val size = source.readSampleData(payload, 0)
                check(size > 0 && (expectedSize < 0 || size.toLong() == expectedSize)) {
                    "The video export contains incomplete media data. No video was saved."
                }
            }
            extractor.selectTrack(track)
            var count = 0L
            var previousUs = -1L
            while (extractor.sampleTime >= 0L) {
                if (count % 64L == 0L) currentCoroutineContext().ensureActive()
                val timeUs = extractor.sampleTime
                check(count < expectedFrames && timeUs > previousUs && VideoTimeline.matchesPresentationTime(count, timeUs)) {
                    "The finalized video did not preserve every fixed 60 FPS timestamp. No video was saved."
                }
                // Read the payload too: sample tables can remain valid after a file was truncated.
                readPayload(extractor)
                previousUs = timeUs
                count++
                extractor.advance()
            }
            check(count == expectedFrames) { "The finalized video is missing 60 FPS frames. No video was saved." }
            // A fresh extractor also includes AAC priming packets before timestamp zero.
            val audio = MediaExtractor()
            try {
                audio.setDataSource(file.path)
                audio.selectTrack(audioTracks.single().first)
                var audioSamples = 0L
                while (audio.sampleTime != -1L) {
                    if (audioSamples % 64L == 0L) currentCoroutineContext().ensureActive()
                    readPayload(audio)
                    audioSamples++
                    audio.advance()
                }
                check(audioSamples > 0) { "The video export is missing its audio data. No video was saved." }
            } finally { audio.release() }
        } finally { extractor.release() }
    }
}
