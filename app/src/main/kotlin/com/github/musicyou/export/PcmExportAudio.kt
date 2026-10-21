package com.github.musicyou.export

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class ExportPcm(val file: File, val sampleRate: Int, val channels: Int, val frames: Long) {
    val durationUs: Long get() = frames * 1_000_000L / sampleRate
}

internal data class ExportAacTiming(val delayFrames: Int, val paddingFrames: Int, val firstTimestampUs: Long, val encoderName: String)

/** Decodes the entire source before rendering so export never depends on wall-clock playback. */
internal object PcmExportAudio {
    suspend fun decode(source: File, destination: File, progress: (Float) -> Unit): ExportPcm {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This download has no audio track.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var trimStartFrames = if (format.containsKey("encoder-delay")) format.getInteger("encoder-delay").coerceAtLeast(0) else 0
            val trimEndFrames = if (format.containsKey("encoder-padding")) format.getInteger("encoder-padding").coerceAtLeast(0) else 0
            format.setInteger("encoder-delay", 0)
            format.setInteger("encoder-padding", 0)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec.configure(format, null, null, 0)
            codec.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var writtenFrames = 0L
            var inputEnded = false
            var outputEnded = false
            var lastOutputAt = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            BufferedOutputStream(FileOutputStream(destination), 256 * 1024).use { output ->
                val converted = ByteArray(256 * 1024)
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime() - lastOutputAt < 60_000_000_000L) { "The audio decoder stopped responding." }
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(index))
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            val newSampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val newChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val newEncoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            check(writtenFrames == 0L || (sampleRate == newSampleRate && sourceChannels == newChannels && encoding == newEncoding)) { "This song changes its audio format during playback and cannot be exported." }
                            sampleRate = newSampleRate
                            sourceChannels = newChannels
                            encoding = newEncoding
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "The decoder returned an unsupported audio format." }
                            require(sampleRate in 8_000..96_000) { "This song's sample rate is not supported for video export." }
                            require(sourceChannels in 1..2) { "Video export supports mono and stereo songs. This multichannel song cannot be exported without losing channels." }
                        }
                        else -> if (index >= 0) {
                            lastOutputAt = System.nanoTime()
                            check(destination.parentFile?.usableSpace?.let { it > 16L * 1024 * 1024 } == true) { "There is not enough free storage to decode this song." }
                            val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.nativeOrder())
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frameBytes = bytesPerSample * sourceChannels
                            val count = info.size / frameBytes
                            val outputChannels = sourceChannels.coerceAtMost(2)
                            var used = 0
                            var retained = 0
                            repeat(count) {
                                val skip = trimStartFrames > 0
                                if (skip) trimStartFrames-- else retained++
                                repeat(sourceChannels) { channel ->
                                    val sample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                        val value = buffer.float
                                        ((if (value.isFinite()) value else 0f).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                    } else buffer.short
                                    if (channel < outputChannels && !skip) {
                                        if (used + 2 > converted.size) { output.write(converted, 0, used); used = 0 }
                                        converted[used++] = (sample.toInt() and 255).toByte()
                                        converted[used++] = (sample.toInt() shr 8).toByte()
                                    }
                                }
                            }
                            if (used > 0) output.write(converted, 0, used)
                            writtenFrames += retained
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                            if (durationUs > 0) progress((writtenFrames * 1_000_000.0 / sampleRate / durationUs).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
            writtenFrames = (writtenFrames - trimEndFrames).coerceAtLeast(0)
            if (durationUs > 0) writtenFrames = minOf(writtenFrames, (durationUs * sampleRate + 500_000L) / 1_000_000L)
            RandomAccessFile(destination, "rw").use { it.setLength(writtenFrames * sourceChannels.coerceAtMost(2) * 2) }
            check(writtenFrames > 0) { "The downloaded song contains no decoded audio." }
            return ExportPcm(destination, sampleRate, sourceChannels.coerceAtMost(2), writtenFrames)
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    suspend fun encodeAac(pcm: ExportPcm, destination: File, codecName: String? = null, progress: (Float) -> Unit): ExportAacTiming {
        val encoder = if (codecName == null) MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) else MediaCodec.createByCodecName(codecName)
        val muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var delayFrames = 0
        var paddingFrames = 0
        var firstTimestampUs = Long.MIN_VALUE
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, if (pcm.channels == 1) 128_000 else 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32 * 1024)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            check(encoder.inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == pcm.sampleRate && encoder.inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == pcm.channels) {
                "This device cannot encode this song's audio format without changing its sample rate or channels."
            }
            encoder.start()
            var track = -1
            var inputEnded = false
            var outputEnded = false
            var bytesRead = 0L
            var lastOutputAt = System.nanoTime()
            val info = MediaCodec.BufferInfo()
            RandomAccessFile(pcm.file, "r").use { input ->
                val bytes = ByteArray(32 * 1024)
                while (!outputEnded) {
                    currentCoroutineContext().ensureActive()
                    check(System.nanoTime() - lastOutputAt < 60_000_000_000L) { "The audio encoder stopped responding." }
                    if (!inputEnded) {
                        val index = encoder.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(encoder.getInputBuffer(index))
                            buffer.clear()
                            val capacity = minOf(buffer.remaining(), bytes.size) / (pcm.channels * 2) * (pcm.channels * 2)
                            val count = input.read(bytes, 0, capacity)
                            val timeUs = bytesRead / (pcm.channels * 2) * 1_000_000L / pcm.sampleRate
                            if (count < 0) {
                                encoder.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                buffer.put(bytes, 0, count)
                                encoder.queueInputBuffer(index, 0, count, timeUs, 0)
                                bytesRead += count
                            }
                        }
                    }
                    when (val index = encoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            val outputFormat = encoder.outputFormat
                            delayFrames = if (outputFormat.containsKey("encoder-delay")) outputFormat.getInteger("encoder-delay") else 0
                            paddingFrames = if (outputFormat.containsKey("encoder-padding")) outputFormat.getInteger("encoder-padding") else 0
                            track = muxer.addTrack(outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (index >= 0) {
                            lastOutputAt = System.nanoTime()
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                if (firstTimestampUs == Long.MIN_VALUE) firstTimestampUs = info.presentationTimeUs
                                check(muxerStarted)
                                val buffer = requireNotNull(encoder.getOutputBuffer(index))
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(index, false)
                            progress((bytesRead.toDouble() / pcm.file.length()).toFloat().coerceIn(0f, 1f))
                        }
                    }
                }
            }
            return ExportAacTiming(delayFrames, paddingFrames, firstTimestampUs, encoder.name)
        } finally {
            runCatching { encoder.stop() }; encoder.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
