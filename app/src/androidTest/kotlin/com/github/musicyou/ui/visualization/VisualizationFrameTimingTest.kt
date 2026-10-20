package com.github.musicyou.ui.visualization

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.audio.visualization.AudioFrameAnalyzer
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.ui.glass.GlassBackdropRoot
import com.github.musicyou.ui.glass.GlassSurface
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/** Measures real window rendering; the test clock is not used as a frame timer. */
@RunWith(AndroidJUnit4::class)
class VisualizationFrameTimingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val scopeContext by lazy {
        ScopeTestContext(compose.activity, "scope_timing_test_${UUID.randomUUID()}")
    }

    @After
    fun deleteScopePreferences() {
        scopeContext.deleteTestPreferences()
    }

    @Test
    fun everyVisualizationSustainsSixtyHertzWithoutRecomposingTheGlassParent() {
        assumeTrue("GPU frame metrics require Android 12 or newer", Build.VERSION.SDK_INT >= 31)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val window = compose.activity.window
        val originalMode = window.attributes.preferredDisplayModeId
        val originalRate = window.attributes.preferredRefreshRate
        val worker = HandlerThread("visualization-frame-metrics").apply { start() }
        val handler = Handler(worker.looper)
        val samples = ConcurrentLinkedQueue<RenderedFrame>()
        val collecting = AtomicBoolean(false)
        val droppedReports = AtomicInteger()
        val parentCompositions = AtomicInteger()
        val windowDraws = AtomicInteger()
        val providerReads = AtomicInteger()
        val modes = listOf(VisualizationMode.SPECTRUM, VisualizationMode.WAVEFORM, VisualizationMode.OSCILLOSCOPE)
        val mode = mutableStateOf(modes.first())
        val frames = measuredPcmFrames()
        val current = mutableStateOf(frames.first())
        val provider: () -> AudioVisualizationFrame? = { providerReads.incrementAndGet(); current.value }
        val feeder = DisplayFrameFeeder { index -> current.value = frames[index % frames.size] }
        val drawListener = ViewTreeObserver.OnDrawListener { windowDraws.incrementAndGet() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            if (collecting.get()) {
                droppedReports.addAndGet(dropped)
                samples.add(RenderedFrame(
                    intendedNanos = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                    totalNanos = metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                    gpuNanos = metrics.getMetric(FrameMetrics.GPU_DURATION),
                    drawNanos = metrics.getMetric(FrameMetrics.DRAW_DURATION),
                    syncNanos = metrics.getMetric(FrameMetrics.SYNC_DURATION),
                    commandNanos = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                    layoutNanos = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    animationNanos = metrics.getMetric(FrameMetrics.ANIMATION_DURATION),
                    unknownNanos = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                    swapNanos = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                ))
            }
        }

        instrumentation.runOnMainSync {
            assertTrue("The timing scene uses hardware rendering", window.decorView.isHardwareAccelerated)
            val display = window.decorView.display
            val activeMode = display.mode
            val sixtyHertz = display.supportedModes.firstOrNull {
                it.physicalWidth == activeMode.physicalWidth && it.physicalHeight == activeMode.physicalHeight &&
                    it.refreshRate in 59f..61f
            }
            window.attributes = window.attributes.apply {
                preferredRefreshRate = 60f
                if (sixtyHertz != null) preferredDisplayModeId = sixtyHertz.modeId
            }
            window.addOnFrameMetricsAvailableListener(listener, handler)
            window.decorView.viewTreeObserver.addOnDrawListener(drawListener)
        }
        try {
            val testContext = scopeContext
            compose.setContent {
                CompositionLocalProvider(LocalContext provides testContext) {
                    MaterialTheme {
                        GlassBackdropRoot(
                            modifier = Modifier.fillMaxSize(),
                            background = {
                                Canvas(Modifier.fillMaxSize()) {
                                    drawRect(Brush.linearGradient(listOf(Color(0xFFDEC8E5), Color(0xFFB6D5DB), Color(0xFFF5D3C5))))
                                    drawCircle(Color.White.copy(alpha = 0.45f), size.minDimension * 0.35f, Offset(size.width * 0.7f, size.height * 0.4f))
                                }
                            },
                        ) {
                            GlassSurface(Modifier.align(Alignment.Center).width(350.dp)) {
                                SideEffect { parentCompositions.incrementAndGet() }
                                AudioVisualization(
                                    frameProvider = provider,
                                    mode = mode.value,
                                    playing = true,
                                    modifier = Modifier.padding(12.dp).height(180.dp),
                                )
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()

            val failures = mutableListOf<String>()
            for (requestedMode in modes) {
                instrumentation.runOnMainSync { mode.value = requestedMode }
                compose.waitForIdle()
                instrumentation.runOnMainSync { feeder.start() }
                Thread.sleep(1_200L)
                samples.clear()
                droppedReports.set(0)
                val initialCompositions = parentCompositions.get()
                val initialUpdates = feeder.updates.get()
                val initialDraws = windowDraws.get()
                val initialReads = providerReads.get()
                val initialCallbacks = feeder.callbacks.get()
                collecting.set(true)
                Thread.sleep(3_000L)
                collecting.set(false)
                instrumentation.runOnMainSync { feeder.stop() }
                drain(handler)
                compose.waitForIdle()

                val measured = samples.sortedBy { it.intendedNanos }
                assertTrue("$requestedMode reports rendered window frames", measured.size >= 2)
                val intervals = measured.zipWithNext { first, second -> (second.intendedNanos - first.intendedNanos) / 1_000_000.0 }
                val elapsed = (measured.last().intendedNanos - measured.first().intendedNanos) / 1_000_000_000.0
                val fps = (measured.size - 1) / elapsed
                val total = measured.map { it.totalNanos / 1_000_000.0 }
                val gpu = measured.filter { it.gpuNanos >= 0L }.map { it.gpuNanos / 1_000_000.0 }
                val recompositions = parentCompositions.get() - initialCompositions
                val updates = feeder.updates.get() - initialUpdates
                val draws = windowDraws.get() - initialDraws
                val reads = providerReads.get() - initialReads
                val callbacks = feeder.callbacks.get() - initialCallbacks
                val result = String.format(
                    Locale.US,
                    "%s rendered=%.2f fps frames=%d updates=%d interval_ms p50=%.2f p95=%.2f render_ms p50=%.2f p95=%.2f gpu_ms p50=%.2f p95=%.2f parent_recompositions=%d dropped_metric_reports=%d window_draws=%d provider_reads=%d vsync_callbacks=%d",
                    requestedMode, fps, measured.size, updates, percentile(intervals, 0.5), percentile(intervals, 0.95),
                    percentile(total, 0.5), percentile(total, 0.95), percentile(gpu, 0.5), percentile(gpu, 0.95),
                    recompositions, droppedReports.get(), draws, reads, callbacks,
                )
                Log.i(TAG, result)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$result\n") })
                val phases = listOf(
                    "draw" to measured.map { it.drawNanos },
                    "sync" to measured.map { it.syncNanos },
                    "command" to measured.map { it.commandNanos },
                    "layout" to measured.map { it.layoutNanos },
                    "animation" to measured.map { it.animationNanos },
                    "unknown_delay" to measured.map { it.unknownNanos },
                    "swap" to measured.map { it.swapNanos },
                ).joinToString(" ") { (name, values) ->
                    val milliseconds = values.map { it / 1_000_000.0 }
                    String.format(Locale.US, "%s_ms p50=%.2f p95=%.2f", name, percentile(milliseconds, 0.5), percentile(milliseconds, 0.95))
                }
                val phaseReport = "$requestedMode $phases"
                Log.i(TAG, phaseReport)
                instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "$phaseReport\n") })
                if (fps < 55.0 || measured.size < 165 || percentile(total, 0.95) > 33.4 || recompositions != 0 || updates < 165) failures += result
            }
            assertTrue(
                "Steady-state rendering targets at least 55 fps, a 33.4 ms p95 budget and no parent recomposition:\n${failures.joinToString("\n")}",
                failures.isEmpty(),
            )
        } finally {
            collecting.set(false)
            instrumentation.runOnMainSync {
                feeder.stop()
                window.removeOnFrameMetricsAvailableListener(listener)
                window.decorView.viewTreeObserver.removeOnDrawListener(drawListener)
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = originalMode
                    preferredRefreshRate = originalRate
                }
            }
            worker.quitSafely()
        }
    }

    private class DisplayFrameFeeder(private val update: (Int) -> Unit) : Choreographer.FrameCallback {
        val updates = AtomicInteger()
        val callbacks = AtomicInteger()
        private var running = false
        private var lastUpdateNanos = Long.MIN_VALUE
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            callbacks.incrementAndGet()
            // The window requests 60 Hz. On a faster panel, avoid feeding duplicate 120 Hz work.
            if (lastUpdateNanos == Long.MIN_VALUE || frameTimeNanos - lastUpdateNanos >= 15_500_000L) {
                update(updates.getAndIncrement())
                // Publish the write before the same vsync's traversal. The test's
                // virtual composition clock must not defer a draw-only update.
                Snapshot.sendApplyNotifications()
                lastUpdateNanos = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
        fun start() {
            running = true
            lastUpdateNanos = Long.MIN_VALUE
            Choreographer.getInstance().postFrameCallback(this)
        }
        fun stop() {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    private data class RenderedFrame(
        val intendedNanos: Long,
        val totalNanos: Long,
        val gpuNanos: Long,
        val drawNanos: Long,
        val syncNanos: Long,
        val commandNanos: Long,
        val layoutNanos: Long,
        val animationNanos: Long,
        val unknownNanos: Long,
        val swapNanos: Long,
    )

    private fun measuredPcmFrames(): List<AudioVisualizationFrame> {
        val analyzer = AudioFrameAnalyzer(48_000, 2)
        return List(24) { frame ->
            val amplitude = 0.45 + 0.25 * sin(2 * PI * frame / 24)
            val samples = FloatArray(AudioFrameAnalyzer.WINDOW_SIZE * 2) { index ->
                val channel = index % 2
                val sample = index / 2 + frame * 800
                (amplitude * sin(2 * PI * (if (channel == 0) 1_000 else 3_000) * sample / 48_000)).toFloat()
            }
            analyzer.analyze(samples, positionUs = frame * 16_667L, sequence = frame.toLong())
        }
    }

    private fun percentile(values: List<Double>, fraction: Double): Double =
        if (values.isEmpty()) Double.NaN else values.sorted()[(ceil(values.size * fraction).toInt() - 1).coerceIn(0, values.lastIndex)]

    private fun drain(handler: Handler) {
        val drained = CountDownLatch(1)
        handler.post { drained.countDown() }
        assertTrue("Frame metrics finish before analysis", drained.await(2, TimeUnit.SECONDS))
    }

    companion object { private const val TAG = "VisualizationTiming" }
}
