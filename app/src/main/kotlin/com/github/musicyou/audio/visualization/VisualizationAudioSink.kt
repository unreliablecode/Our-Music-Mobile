package com.github.musicyou.audio.visualization

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** Supplies PCM presentation timestamps and prevents encoded paths from bypassing the tap. */
@OptIn(UnstableApi::class)
class VisualizationAudioSink(
    sink: AudioSink,
    private val processor: VisualizationAudioProcessor,
) : ForwardingAudioSink(sink) {
    private var outputStreamOffsetUs = 0L

    override fun supportsFormat(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun setOffloadMode(offloadMode: Int) = super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        this.outputStreamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        processor.beginBuffer(presentationTimeUs - outputStreamOffsetUs)
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}
