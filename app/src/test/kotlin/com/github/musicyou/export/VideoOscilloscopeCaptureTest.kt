package com.github.musicyou.export

import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import com.github.musicyou.audio.visualization.OscilloscopeConfig
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOscilloscopeCaptureTest {
    @Test
    fun liveVideoCaptureUsesTheSavedTimebaseAndAmplitudeSettings() {
        val samples = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE) {
            (0.6 * sin(2.0 * PI * 1_000.0 * it / 48_000.0)).toFloat()
        }
        val liveFrame = AudioFrameAnalyzer(48_000, 1).analyze(samples)
        val requested = OscilloscopeConfig(
            timebaseUsPerDivision = 200,
            fullScalePerDivision = 0.5f,
            calibratedVoltsPerFullScale = 2f,
        )

        val scope = requireNotNull(videoOscilloscopeCapture(liveFrame, requested))

        assertEquals(2_000L, scope.durationUs)
        assertEquals(200, scope.configuration.timebaseUsPerDivision)
        assertEquals(0.5f, scope.configuration.fullScalePerDivision, 0f)
        assertEquals(2f, scope.configuration.calibratedVoltsPerFullScale!!, 0f)
    }
}
