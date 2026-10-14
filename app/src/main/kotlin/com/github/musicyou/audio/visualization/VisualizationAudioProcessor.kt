package com.github.musicyou.audio.visualization

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** A byte-preserving PCM tap before silence removal and playback-speed processing. */
@OptIn(UnstableApi::class)
class VisualizationAudioProcessor(private val visualization: RealtimeAudioVisualization) : BaseAudioProcessor() {
    private var ring = FloatArray(0)
    private var writeFrame = 0
    private var availableFrames = 0
    private var framesSinceWindow = 0
    private var ringEpoch = -1L
    private var timestampUs = 0L
    private var framesWithinBuffer = 0L
    private var bufferTimestampUs = Long.MIN_VALUE

    internal fun beginBuffer(positionUs: Long) {
        if (positionUs != bufferTimestampUs) {
            bufferTimestampUs = positionUs
            timestampUs = positionUs.coerceAtLeast(0L)
            framesWithinBuffer = 0L
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.channelCount !in 1..8 || inputAudioFormat.sampleRate < 8_000
        ) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val channelCount = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate
        val bufferFrames = bytes / inputAudioFormat.bytesPerFrame
        if (visualization.capturing) {
            val epoch = visualization.epoch
            if (epoch != ringEpoch || ring.size != AudioFrameAnalyzer.WINDOW_SIZE * channelCount) {
                ringEpoch = epoch
                ring = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * channelCount)
                writeFrame = 0
                availableFrames = 0
                framesSinceWindow = 0
            }
            val samples = PcmSamples.read(inputBuffer, inputAudioFormat.encoding)
            for (frame in 0 until bufferFrames) {
                for (channel in 0 until channelCount) ring[writeFrame * channelCount + channel] = samples[frame * channelCount + channel]
                writeFrame = (writeFrame + 1) % AudioFrameAnalyzer.WINDOW_SIZE
                availableFrames = (availableFrames + 1).coerceAtMost(AudioFrameAnalyzer.WINDOW_SIZE)
                framesSinceWindow++
                if (availableFrames == AudioFrameAnalyzer.WINDOW_SIZE && framesSinceWindow >= AudioFrameAnalyzer.HOP_SIZE) {
                    framesSinceWindow = 0
                    val ordered = FloatArray(ring.size)
                    val after = ring.size - writeFrame * channelCount
                    ring.copyInto(ordered, 0, writeFrame * channelCount)
                    ring.copyInto(ordered, after, 0, writeFrame * channelCount)
                    val endFrame = framesWithinBuffer + frame + 1
                    val centerUs = timestampUs + (endFrame - AudioFrameAnalyzer.WINDOW_SIZE / 2) * 1_000_000L / sampleRate
                    visualization.offer(PcmWindow(epoch, centerUs.coerceAtLeast(0L), sampleRate, channelCount, ordered))
                }
            }
        }
        framesWithinBuffer += bufferFrames
        // A reusable output buffer preserves all decoded bytes, including when capture is disabled.
        replaceOutputBuffer(bytes).put(inputBuffer).flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        ring = FloatArray(0)
        ringEpoch = -1L
        // DefaultAudioSink may initialize and flush inside handleBuffer, after its
        // wrapper supplied the current PCM timestamp. Retain that pending stamp.
        if (bufferTimestampUs == Long.MIN_VALUE) timestampUs = streamMetadata.positionOffsetUs.coerceAtLeast(0L)
        framesWithinBuffer = 0L
    }

    override fun onReset() {
        ring = FloatArray(0)
        ringEpoch = -1L
        bufferTimestampUs = Long.MIN_VALUE
    }
}
