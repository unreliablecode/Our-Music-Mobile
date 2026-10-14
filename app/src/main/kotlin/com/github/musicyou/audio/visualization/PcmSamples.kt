package com.github.musicyou.audio.visualization

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts a read-only duplicate; the playback buffer's bytes and position stay untouched. */
object PcmSamples {
    const val PCM_16 = 2
    const val PCM_FLOAT = 4

    fun read(buffer: ByteBuffer, encoding: Int): FloatArray {
        val source = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            PCM_16 -> 2
            PCM_FLOAT -> 4
            else -> error("Unsupported PCM encoding: $encoding")
        }
        return FloatArray(source.remaining() / bytesPerSample) {
            when (encoding) {
                PCM_16 -> source.short / 32768f
                else -> source.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
            }
        }
    }
}
