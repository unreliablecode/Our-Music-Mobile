package com.github.musicyou.audio.visualization

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Analysis stays off the playback thread; queued work and presentation history are bounded. */
class RealtimeAudioVisualization : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val observers = AtomicInteger(0)
    private val epochCounter = AtomicLong(0L)
    private val sequenceCounter = AtomicLong(0L)
    private val windows = Channel<PcmWindow>(Channel.CONFLATED)
    private val frames = ArrayDeque<AudioVisualizationFrame>()
    private val frameLock = Any()
    private val mutableFrames = MutableStateFlow(AudioVisualizationFrame())
    val visualization: StateFlow<AudioVisualizationFrame> = mutableFrames.asStateFlow()
    @Volatile private var playing = false
    @Volatile private var closed = false
    val capturing get() = !closed && playing && observers.get() > 0
    val hasObservers get() = observers.get() > 0
    val epoch get() = epochCounter.get()

    init {
        scope.launch {
            var analyzer: AudioFrameAnalyzer? = null
            var format = 0 to 0
            for (window in windows) {
                if (!capturing || window.epoch != epoch) continue
                val nextFormat = window.sampleRate to window.channelCount
                if (format != nextFormat) {
                    analyzer = AudioFrameAnalyzer(window.sampleRate, window.channelCount)
                    format = nextFormat
                }
                val frame = analyzer!!.analyze(window.samples, window.positionUs, sequenceCounter.incrementAndGet())
                synchronized(frameLock) {
                    if (capturing && window.epoch == epoch) {
                        frames.addLast(frame)
                        while (frames.size > 160) frames.removeFirst()
                    }
                }
            }
        }
    }

    fun acquire(): AutoCloseable {
        check(!closed)
        if (observers.incrementAndGet() == 1) clear()
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true) && observers.decrementAndGet() == 0) clear()
        }
    }

    fun setPlaying(value: Boolean) {
        if (playing != value) {
            playing = value
            clear()
        }
    }

    /** Select decoded audio at the audible player position rather than the decoder's lead. */
    fun present(positionUs: Long) {
        if (!capturing) return
        val frame = synchronized(frameLock) {
            while (frames.size > 1 && frames.elementAt(1).positionUs <= positionUs) frames.removeFirst()
            frames.firstOrNull()?.takeIf { it.positionUs <= positionUs && positionUs - it.positionUs <= 250_000L }
        }
        mutableFrames.value = frame ?: AudioVisualizationFrame()
    }

    fun clear() {
        epochCounter.incrementAndGet()
        synchronized(frameLock) { frames.clear() }
        mutableFrames.value = AudioVisualizationFrame()
    }

    internal fun offer(window: PcmWindow) {
        if (capturing && window.epoch == epoch) windows.trySend(window)
    }

    override fun close() {
        closed = true
        clear()
        windows.close()
        scope.cancel()
    }
}

internal data class PcmWindow(
    val epoch: Long,
    val positionUs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val samples: FloatArray,
)
