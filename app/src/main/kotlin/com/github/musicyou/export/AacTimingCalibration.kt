package com.github.musicyou.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Measures codec priming instead of assuming that every device reports its encoder delay. */
internal object AacTimingCalibration {
    private val measured = ConcurrentHashMap<String, Int>()

    suspend fun delayFrames(pcm: ExportPcm, codecName: String, directory: File): Int {
        val key = "$codecName:${pcm.sampleRate}:${pcm.channels}"
        measured[key]?.let { return it }
        val probe = File(directory, "timing_probe.pcm")
        val encoded = File(directory, "timing_probe.m4a")
        val decoded = File(directory, "timing_probe_decoded.pcm")
        try {
            val length = pcm.sampleRate
            val onset = length / 4
            val burst = ShortArray(512)
            val random = java.util.Random(711)
            for (index in burst.indices) burst[index] = ((random.nextFloat() * 2 - 1) * 24_000).toInt().toShort()
            val input = ByteBuffer.allocate(length * pcm.channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(length) { frame ->
                val sample = if (frame in onset until onset + burst.size) burst[frame - onset] else 0
                repeat(pcm.channels) { input.putShort(sample.toShort()) }
            }
            probe.writeBytes(input.array())
            PcmExportAudio.encodeAac(ExportPcm(probe, pcm.sampleRate, pcm.channels, length.toLong()), encoded, codecName) {}
            val output = PcmExportAudio.decode(encoded, decoded) {}
            check(output.sampleRate == pcm.sampleRate && output.channels == pcm.channels)
            val shortBuffer = ByteBuffer.wrap(decoded.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
            var bestLag = 0
            var bestScore = Double.NEGATIVE_INFINITY
            var bestPower = 0.0
            var sourcePower = 0.0
            burst.forEach { sourcePower += it.toDouble() * it }
            val maximumLag = minOf(maxOf(4096, length / 10), output.frames.toInt() - onset - burst.size)
            check(maximumLag > 0)
            for (lag in 0..maximumLag) {
                if (lag % 256 == 0) currentCoroutineContext().ensureActive()
                var score = 0.0
                var power = 0.0
                for (index in burst.indices) {
                    val value = samples[(onset + lag + index) * pcm.channels].toDouble()
                    score += burst[index] * value
                    power += value * value
                }
                if (score > bestScore) { bestScore = score; bestLag = lag; bestPower = power }
            }
            check(bestScore / sqrt(sourcePower * bestPower).coerceAtLeast(1.0) > .40) {
                "The audio encoder's timing could not be verified. Restart the export to try again."
            }
            measured[key] = bestLag
            return bestLag
        } finally { probe.delete(); encoded.delete(); decoded.delete() }
    }
}
