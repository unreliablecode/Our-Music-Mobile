package com.github.musicyou.audio.visualization

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Sample-rate-aware Hann FFT and signed, peak-preserving waveform envelopes. */
class AudioFrameAnalyzer(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val fftSize: Int = WINDOW_SIZE,
    private val bandCount: Int = 32,
    private val waveformPoints: Int = 256,
) {
    init {
        require(sampleRate >= 8_000 && channelCount in 1..8)
        require(fftSize >= 32 && fftSize and (fftSize - 1) == 0)
        require(bandCount > 0 && waveformPoints in 1..fftSize)
    }

    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1)) }
    private val windowSum = window.sum()
    private val real = DoubleArray(fftSize)
    private val imaginary = DoubleArray(fftSize)
    private val power = DoubleArray(fftSize / 2 + 1)
    private val frequencyLimit = min(20_000.0, sampleRate / 2.0)
    private val edges = DoubleArray(bandCount + 1) { 20.0 * (frequencyLimit / 20.0).pow(it.toDouble() / bandCount) }
    private val centers = List(bandCount) { sqrt(edges[it] * edges[it + 1]).toFloat() }

    /** [samples] contains [channelCount] interleaved, signed channels in -1..1. */
    fun analyze(samples: FloatArray, positionUs: Long = 0L, sequence: Long = 0L): AudioVisualizationFrame {
        require(samples.size == fftSize * channelCount)
        power.fill(0.0)
        var squareSum = 0.0
        var peak = 0f
        val waveform = ArrayList<AudioWaveformChannel>(min(channelCount, 2))
        for (channel in 0 until channelCount) {
            var mean = 0.0
            for (frame in 0 until fftSize) mean += finiteSample(samples[frame * channelCount + channel])
            mean /= fftSize
            for (frame in 0 until fftSize) {
                val sample = finiteSample(samples[frame * channelCount + channel])
                squareSum += sample.toDouble() * sample
                peak = max(peak, kotlin.math.abs(sample))
                real[frame] = (sample - mean) * window[frame]
                imaginary[frame] = 0.0
            }
            fft(real, imaginary)
            for (bin in power.indices) {
                val magnitude = (real[bin] * real[bin] + imaginary[bin] * imaginary[bin]) * 4.0 / (windowSum * windowSum)
                power[bin] += magnitude / channelCount
            }
            if (channel < 2) {
                val minimum = ArrayList<Float>(waveformPoints)
                val maximum = ArrayList<Float>(waveformPoints)
                for (point in 0 until waveformPoints) {
                    val first = point * fftSize / waveformPoints
                    val last = (point + 1) * fftSize / waveformPoints
                    var low = 1f
                    var high = -1f
                    for (frame in first until last) {
                        val sample = finiteSample(samples[frame * channelCount + channel])
                        low = min(low, sample)
                        high = max(high, sample)
                    }
                    minimum += low
                    maximum += high
                }
                waveform += AudioWaveformChannel(minimum, maximum)
            }
        }
        val binWidth = sampleRate.toDouble() / fftSize
        val spectrum = List(bandCount) { band ->
            val first = max(1, ceil(edges[band] / binWidth).toInt())
            val last = min(power.lastIndex, floor(edges[band + 1] / binWidth).toInt())
            var bandPower = 0.0
            if (first <= last) {
                for (bin in first..last) bandPower = max(bandPower, power[bin])
            } else {
                // Narrow bass bands need interpolation when their width is below one FFT bin.
                val bin = (centers[band] / binWidth).coerceIn(1.0, power.lastIndex.toDouble())
                val lower = floor(bin).toInt()
                val upper = min(lower + 1, power.lastIndex)
                val fraction = bin - lower
                bandPower = power[lower] * (1.0 - fraction) + power[upper] * fraction
            }
            ((10.0 * log10(max(bandPower, 1e-12)) + 72.0) / 72.0).coerceIn(0.0, 1.0).toFloat()
        }
        return AudioVisualizationFrame(
            sequence = sequence,
            positionUs = positionUs,
            sampleRate = sampleRate,
            spectrum = spectrum,
            frequenciesHz = centers,
            waveformChannels = waveform,
            rms = sqrt(squareSum / samples.size).toFloat(),
            peak = peak,
            ready = true,
        )
    }

    private fun finiteSample(value: Float) = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        var reversed = 0
        for (index in 1 until fftSize) {
            var bit = fftSize shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val previousReal = real[index]
                real[index] = real[reversed]
                real[reversed] = previousReal
                val previousImaginary = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = previousImaginary
            }
        }
        var length = 2
        while (length <= fftSize) {
            val angle = -2.0 * PI / length
            val rootReal = cos(angle)
            val rootImaginary = sin(angle)
            for (start in 0 until fftSize step length) {
                var phaseReal = 1.0
                var phaseImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * phaseReal - imaginary[odd] * phaseImaginary
                    val oddImaginary = real[odd] * phaseImaginary + imaginary[odd] * phaseReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextPhase = phaseReal * rootReal - phaseImaginary * rootImaginary
                    phaseImaginary = phaseReal * rootImaginary + phaseImaginary * rootReal
                    phaseReal = nextPhase
                }
            }
            length = length shl 1
        }
    }

    companion object {
        const val WINDOW_SIZE = 4096
        const val HOP_SIZE = 1024
    }
}
