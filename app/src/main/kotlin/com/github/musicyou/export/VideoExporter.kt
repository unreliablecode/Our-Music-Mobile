package com.github.musicyou.export

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
object VideoExporter {
    /** A static layout preview does not invent audio data before decoding. */
    suspend fun preview(context: Context, request: VideoExportRequest, width: Int = 640): Bitmap = withContext(Dispatchers.IO) {
        val height = (width.toLong() * request.config.height / request.config.width).toInt()
        VideoFramePainter.create(context, request, width, height).use { painter ->
            painter.draw(null, 0).copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    internal suspend fun render(
        context: Context,
        request: VideoExportRequest,
        source: File,
        workDirectory: File,
        useHardwareCanvas: Boolean = true,
        metrics: VideoExportMetrics? = null,
        beforeFrame: ((Long) -> Unit)? = null,
        progress: (Float, String) -> Unit,
    ): File {
        val started = if (metrics != null) System.nanoTime() else 0L
        progress(0f, "Decoding downloaded audio")
        val pcm = PcmExportAudio.decode(source, File(workDirectory, "audio.pcm")) { progress(it * .12f, "Decoding downloaded audio") }
        if (metrics != null) metrics.decodeNanos = System.nanoTime() - started
        currentCoroutineContext().ensureActive()
        check(workDirectory.usableSpace > 32L * 1024 * 1024 + pcm.durationUs / 1_000_000L * if (request.config.resolution == VideoResolution.HD) 2_200_000L else 4_000_000L) {
            "There is not enough free space to render this video. Free some storage or choose 720p."
        }
        val aac = File(workDirectory, "audio.m4a")
        val encodeStarted = if (metrics != null) System.nanoTime() else 0L
        val timing = PcmExportAudio.encodeAac(pcm, aac) { progress(.12f + it * .06f, "Encoding the audio track") }
        if (metrics != null) metrics.audioEncodeNanos = System.nanoTime() - encodeStarted
        progress(.18f, "Verifying audio timing")
        val calibrationStarted = if (metrics != null) System.nanoTime() else 0L
        val delayFrames = AacTimingCalibration.delayFrames(pcm, timing.encoderName, workDirectory)
        if (metrics != null) metrics.calibrationNanos = System.nanoTime() - calibrationStarted
        val output = File(workDirectory, "video.mp4")
        try {
            encodeVideo(context, request, pcm, aac, delayFrames, output, useHardwareCanvas, metrics, beforeFrame) { progress(.20f + it * .77f, "Rendering 60 FPS video") }
            progress(.98f, "Verifying every 60 FPS frame")
            VideoExportVerifier.verify(output, request.config, pcm.durationUs)
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
        if (metrics != null) metrics.totalNanos = System.nanoTime() - started
        return output
    }

    private suspend fun encodeVideo(
        context: Context,
        request: VideoExportRequest,
        pcm: ExportPcm,
        audio: File,
        audioDelayFrames: Int,
        output: File,
        useHardwareCanvas: Boolean,
        metrics: VideoExportMetrics?,
        beforeFrame: ((Long) -> Unit)?,
        progress: (Float) -> Unit,
    ) {
        val width = request.config.width
        val height = request.config.height
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (metrics != null) metrics.encoderName = encoder.name
        var encoderStarted = false
        var graphics: EncoderCanvasSurface? = null
        var painter: VideoFramePainter? = null
        val audioExtractor = MediaExtractor()
        val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(output.path)).setSampleBatchingEnabled(false).build()
        var muxerClosed = false
        try {
            audioExtractor.setDataSource(audio.path)
            val audioSourceTrack = (0 until audioExtractor.trackCount).first { audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            audioExtractor.selectTrack(audioSourceTrack)
            val audioTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(audioExtractor.getTrackFormat(audioSourceTrack)))
            val audioDelayUs = audioDelayFrames * 1_000_000L / pcm.sampleRate
            val audioBuffer = ByteBuffer.allocateDirect(1024 * 1024)
            var audioEnded = false
            suspend fun writeAudioThrough(untilUs: Long) {
                while (!audioEnded) {
                    currentCoroutineContext().ensureActive()
                    val sourceTimeUs = audioExtractor.sampleTime
                    val timeUs = sourceTimeUs - audioDelayUs
                    if (sourceTimeUs < 0 || timeUs >= pcm.durationUs) {
                        muxer.writeSampleData(audioTrack, ByteBuffer.allocate(0), BufferInfo(pcm.durationUs, 0, C.BUFFER_FLAG_END_OF_STREAM))
                        audioEnded = true
                        return
                    }
                    if (timeUs > untilUs) return
                    audioBuffer.clear()
                    val count = audioExtractor.readSampleData(audioBuffer, 0)
                    if (count < 0) {
                        muxer.writeSampleData(audioTrack, ByteBuffer.allocate(0), BufferInfo(pcm.durationUs, 0, C.BUFFER_FLAG_END_OF_STREAM))
                        audioEnded = true
                        return
                    }
                    audioBuffer.position(0); audioBuffer.limit(count)
                    muxer.writeSampleData(audioTrack, audioBuffer, BufferInfo(timeUs, count, C.BUFFER_FLAG_KEY_FRAME))
                    audioExtractor.advance()
                }
            }
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, if (request.config.resolution == VideoResolution.HD) 8_000_000 else 14_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, VideoTimeline.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_PRIORITY, 1)
                if (Build.VERSION.SDK_INT >= 29) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            graphics = EncoderCanvasSurface(encoder.createInputSurface(), width, height)
            encoder.start(); encoderStarted = true
            painter = VideoFramePainter.create(context, request.copy(durationUs = pcm.durationUs))
            val analyzer = AudioFrameAnalyzer(pcm.sampleRate, pcm.channels, oscilloscopeConfig = request.oscilloscopeConfig)
            val sampleWindow = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * pcm.channels)
            val sampleBytes = ByteArray(sampleWindow.size * 2)
            val info = MediaCodec.BufferInfo()
            var videoTrack = -1
            var videoEnded = false
            var writtenFrames = 0L
            var lastVideoTimeUs = -1L
            suspend fun drain(end: Boolean) {
                var lastOutputAt = System.nanoTime()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime() - lastOutputAt < 60_000_000_000L) { "The video encoder stopped responding." }
                    when (val index = encoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(videoTrack < 0)
                            videoTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(encoder.outputFormat))
                        }
                        else -> if (index >= 0) {
                            lastOutputAt = System.nanoTime()
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(videoTrack >= 0)
                                check(info.presentationTimeUs > lastVideoTimeUs && VideoTimeline.matchesPresentationTime(writtenFrames, info.presentationTimeUs)) {
                                    "The video encoder changed the fixed 60 FPS timeline. No video was saved."
                                }
                                val buffer = requireNotNull(encoder.getOutputBuffer(index))
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                writeAudioThrough(info.presentationTimeUs)
                                muxer.writeSampleData(videoTrack, buffer, BufferInfo(info.presentationTimeUs, info.size, if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) C.BUFFER_FLAG_KEY_FRAME else 0))
                                lastVideoTimeUs = info.presentationTimeUs; writtenFrames++
                            }
                            videoEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            if (videoEnded) return
                        }
                    }
                }
            }
            val frameCount = VideoTimeline.frameCount(pcm.durationUs)
            RandomAccessFile(pcm.file, "r").use { samples ->
                for (index in 0 until frameCount) {
                    currentCoroutineContext().ensureActive()
                    beforeFrame?.invoke(index)
                    currentCoroutineContext().ensureActive()
                    // Frame time follows the song index even when a frame takes longer to render.
                    val timeUs = VideoTimeline.presentationTimeUs(index)
                    val analysisStarted = if (metrics != null) System.nanoTime() else 0L
                    readWindow(samples, pcm, timeUs, sampleBytes, sampleWindow)
                    val analysis = analyzer.analyze(sampleWindow, timeUs, index)
                    if (metrics != null) metrics.analysisNanos += System.nanoTime() - analysisStarted
                    val renderStarted = if (metrics != null) System.nanoTime() else 0L
                    val hardware = if (useHardwareCanvas) graphics.draw(painter, analysis, timeUs) else {
                        graphics.draw(painter.draw(analysis, timeUs), timeUs)
                        false
                    }
                    if (metrics != null) {
                        metrics.frameRenderNanos += System.nanoTime() - renderStarted
                        metrics.frames++
                        if (hardware) metrics.hardwareCanvasFrames++
                    }
                    val drainStarted = if (metrics != null) System.nanoTime() else 0L
                    drain(false)
                    if (metrics != null) metrics.drainNanos += System.nanoTime() - drainStarted
                    if (index % 6L == 0L || index + 1 == frameCount) progress((index + 1).toFloat() / frameCount)
                }
            }
            encoder.signalEndOfInputStream()
            drain(true)
            check(videoEnded && writtenFrames == frameCount) { "The video encoder did not preserve every 60 FPS frame. Try 720p on this device." }
            writeAudioThrough(Long.MAX_VALUE)
            // Complete the final 1/60-second picture while preserving the exact audio ending.
            muxer.writeSampleData(videoTrack, ByteBuffer.allocate(0), BufferInfo(VideoTimeline.presentationTimeUs(frameCount), 0, C.BUFFER_FLAG_END_OF_STREAM))
            muxer.close(); muxerClosed = true
        } finally {
            painter?.close(); graphics?.close()
            if (encoderStarted) runCatching { encoder.stop() }
            encoder.release()
            audioExtractor.release()
            if (!muxerClosed) runCatching { muxer.close() }
        }
    }

    private fun readWindow(input: RandomAccessFile, pcm: ExportPcm, timeUs: Long, bytes: ByteArray, samples: FloatArray) {
        samples.fill(0f)
        val center = timeUs * pcm.sampleRate / 1_000_000L
        val start = center - AudioFrameAnalyzer.WINDOW_SIZE / 2
        val first = start.coerceAtLeast(0)
        val count = minOf(AudioFrameAnalyzer.WINDOW_SIZE.toLong() - (first - start), pcm.frames - first).coerceAtLeast(0).toInt()
        if (count == 0) return
        val byteCount = count * pcm.channels * 2
        input.seek(first * pcm.channels * 2)
        input.readFully(bytes, 0, byteCount)
        val offset = ((first - start) * pcm.channels).toInt()
        repeat(count * pcm.channels) { index ->
            val sample = ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
            samples[offset + index] = sample / 32768f
        }
    }
}
