package com.github.musicyou.ui.visualization

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.musicyou.audio.visualization.AudioVisualizationFrame
import com.github.musicyou.audio.visualization.RealtimeAudioVisualization
import com.github.musicyou.audio.visualization.VisualizationAudioProcessor
import com.github.musicyou.audio.visualization.VisualizationAudioSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the PCM tap in the real ExoPlayer and device AudioTrack pipeline. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class AudioVisualizationPlaybackTest {
    @Test
    fun decodedStereoReachesTheAnalyzerAtAudibleTimeAndClearsOnPauseAndSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("visualization-playback-", ".wav", context.cacheDir)
        val pcm = stereoPcm(sampleRate = 48_000, durationSeconds = 6)
        val visualization = RealtimeAudioVisualization()
        var subscription: AutoCloseable? = null
        var player: ExoPlayer? = null
        val failure = AtomicReference<PlaybackException?>(null)
        val firstDecodedBytes = AtomicReference<ByteArray?>(null)
        val observed = AtomicReference<PresentedFrame?>(null)
        val resumed = AtomicReference<PresentedFrame?>(null)
        val initialFrames = CountDownLatch(3)
        val resumedFrame = CountDownLatch(1)
        val phase = AtomicInteger(0)
        val futureFrame = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        var lastSequence = 0L
        val poll = object : Runnable {
            override fun run() {
                if (stopped.get()) return
                val activePlayer = player ?: return
                val positionUs = activePlayer.currentPosition.coerceAtLeast(0L) * 1_000L
                visualization.setPlaying(activePlayer.isPlaying)
                visualization.present(positionUs)
                val frame = visualization.visualization.value
                if (frame.ready) {
                    if (frame.positionUs > positionUs) futureFrame.set(true)
                    if (phase.get() == 0 && frame.sequence != lastSequence) {
                        lastSequence = frame.sequence
                        observed.set(PresentedFrame(frame, positionUs))
                        initialFrames.countDown()
                    } else if (phase.get() == 1 && positionUs >= 2_000_000L && frame.positionUs >= 1_950_000L) {
                        resumed.set(PresentedFrame(frame, positionUs))
                        resumedFrame.countDown()
                    }
                }
                handler.postDelayed(this, 33L)
            }
        }

        try {
            assertEquals("The fixture must live only in the test's private cache", context.cacheDir.canonicalFile, file.canonicalFile.parentFile)
            writeStereoWav(file, pcm, 48_000)
            instrumentation.runOnMainSync {
                val processor = VisualizationAudioProcessor(visualization)
                val defaultSink = DefaultAudioSink.Builder(context)
                    .setAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build())
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            arrayOf(processor),
                            SilenceSkippingAudioProcessor(2_000_000, 0.01f, 2_000_000, 0, 256),
                            SonicAudioProcessor(),
                        ),
                    )
                    .build()
                val observedSink = object : ForwardingAudioSink(VisualizationAudioSink(defaultSink, processor)) {
                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        if (buffer.hasRemaining() && firstDecodedBytes.get() == null) {
                            val duplicate = buffer.duplicate()
                            val bytes = ByteArray(duplicate.remaining().coerceAtMost(2_048))
                            duplicate.get(bytes)
                            firstDecodedBytes.compareAndSet(null, bytes)
                        }
                        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    }
                }
                val renderers = RenderersFactory { eventHandler, _, audioListener, _, _ ->
                    arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, eventHandler, audioListener, observedSink))
                }
                subscription = visualization.acquire()
                player = ExoPlayer.Builder(context, renderers).build().apply {
                    // Hardware playback remains active; gain is applied after the PCM tap.
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) { visualization.setPlaying(isPlaying) }
                        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                            visualization.clear()
                        }
                        override fun onPlayerError(error: PlaybackException) {
                            failure.set(error)
                            while (initialFrames.count > 0) initialFrames.countDown()
                            resumedFrame.countDown()
                        }
                    })
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                    play()
                }
                handler.post(poll)
            }
            assertTrue("The actual audio sink must produce several audible-time frames", initialFrames.await(15L, TimeUnit.SECONDS))
            assertNull("The generated local audio must decode without errors", failure.get())
            val initial = requireNotNull(observed.get()) { "No measured audio was presented" }
            assertMeasuredStereo(initial)
            val decoded = requireNotNull(firstDecodedBytes.get()) { "The real sink did not receive decoded PCM" }
            assertArrayEquals("The sink receives the exact PCM bytes from the local WAV", pcm.copyOf(decoded.size), decoded)
            assertFalse("Decoder lead must never be presented as future audible audio", futureFrame.get())

            instrumentation.runOnMainSync {
                player!!.pause()
                visualization.setPlaying(false)
                visualization.present(10_000_000L)
                assertFalse("Pausing removes the previously presented audio immediately", visualization.visualization.value.ready)
            }
            assertFalse("Paused capture is inactive", visualization.capturing)

            instrumentation.runOnMainSync {
                phase.set(1)
                visualization.clear()
                player!!.seekTo(2_000L)
                player!!.play()
            }
            assertTrue("A positive seek must produce frames with the new audible timestamp", resumedFrame.await(15L, TimeUnit.SECONDS))
            assertNull("Resumed local playback must remain error free", failure.get())
            val afterSeek = requireNotNull(resumed.get()) { "No audio was presented after the seek" }
            assertMeasuredStereo(afterSeek)
            assertTrue("Seek timestamps stay in the song's timeline", afterSeek.frame.positionUs >= 1_950_000L)
            assertFalse("Future decoded audio is still withheld after the seek", futureFrame.get())

            instrumentation.runOnMainSync {
                subscription!!.close()
                subscription = null
                assertFalse("Closing the last visualizer removes its frame", visualization.visualization.value.ready)
                assertFalse("Closing the last visualizer stops capture", visualization.capturing)
            }
        } finally {
            stopped.set(true)
            instrumentation.runOnMainSync {
                handler.removeCallbacks(poll)
                player?.release()
                player = null
                subscription?.close()
            }
            visualization.close()
            file.delete()
        }
    }

    private fun assertMeasuredStereo(presented: PresentedFrame) {
        val frame = presented.frame
        assertEquals("The analyzer sees the decoded sample rate", 48_000, frame.sampleRate)
        assertEquals("The analyzer retains both actual channels", 2, frame.waveformChannels.size)
        assertTrue("The left tone has measured energy around 1 kHz", frame.frequenciesHz.indices.any { frame.frequenciesHz[it] in 750f..1_250f && frame.spectrum[it] > 0.8f })
        assertTrue("The right tone has measured energy around 3 kHz", frame.frequenciesHz.indices.any { frame.frequenciesHz[it] in 2_500f..3_500f && frame.spectrum[it] > 0.8f })
        assertTrue("Unrelated bass bands do not acquire invented energy", frame.frequenciesHz.indices.filter { frame.frequenciesHz[it] in 150f..350f }.all { frame.spectrum[it] < 0.3f })
        assertTrue("RMS matches the generated 0.8-amplitude tones", frame.rms in 0.53f..0.60f)
        assertTrue("Peak amplitude remains the decoded PCM amplitude", abs(frame.peak - 0.8f) < 0.002f)
        frame.waveformChannels.forEach { channel ->
            assertTrue("Actual positive samples are present", channel.maximum.max() > 0.79f)
            assertTrue("Actual negative samples are present", channel.minimum.min() < -0.79f)
        }
        assertTrue("A frame never leads audible time", frame.positionUs <= presented.playerPositionUs)
        assertTrue("The visualizer stays within a quarter second of audible time", presented.playerPositionUs - frame.positionUs <= 250_000L)
    }

    private fun stereoPcm(sampleRate: Int, durationSeconds: Int): ByteArray =
        ByteBuffer.allocate(sampleRate * durationSeconds * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(sampleRate * durationSeconds) { sample ->
                putShort((sin(2.0 * PI * 1_000.0 * sample / sampleRate) * 0.8 * 32_767).toInt().toShort())
                putShort((sin(2.0 * PI * 3_000.0 * sample / sampleRate) * 0.8 * 32_767).toInt().toShort())
            }
        }.array()

    private fun writeStereoWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(sampleRate); putInt(sampleRate * 4)
            putShort(4); putShort(16); put("data".toByteArray()); putInt(pcm.size)
        }.array()
        file.outputStream().use { stream -> stream.write(header); stream.write(pcm) }
    }

    private data class PresentedFrame(val frame: AudioVisualizationFrame, val playerPositionUs: Long)
}
