package com.github.musicyou.downloads

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the same resolver used by PlayerService with a network source that fails on use. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class OfflinePlaybackTest {
    @Test fun verifiedDownloadPlaysWithNoNetworkSourceCreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val manager = OfflineDownloads.get(instrumentation.targetContext)
        val id = "offline-test-${UUID.randomUUID()}"
        val attempts = AtomicInteger()
        var player: ExoPlayer? = null
        val progress = CountDownLatch(1)
        var failure: PlaybackException? = null
        try {
            manager.store.upsert(OfflineDownload(id, "Offline playback test", mimeType = "audio/wav", status = DownloadStatus.DOWNLOADING))
            val file = manager.store.partialFile(id)
            val samples = 44_100 * 3
            val pcm = ByteArray(samples * 2)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(44_100); putInt(88_200); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }.array()
            file.outputStream().use { it.write(header); it.write(pcm) }
            manager.store.complete(id, file.length(), sha256(file))

            val blockedNetwork = DataSource.Factory {
                attempts.incrementAndGet()
                error("Offline playback attempted to construct a network source")
            }
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(offlineFirstDataSourceFactory(instrumentation.targetContext, blockedNetwork)))
                    .build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { failure = error; progress.countDown() }
                        })
                        setMediaItem(MediaItem.Builder().setMediaId(id).setCustomCacheKey(id).setUri(id).build())
                        prepare(); play()
                    }
                val handler = Handler(Looper.getMainLooper())
                val checkProgress = object : Runnable {
                    override fun run() {
                        if ((player?.currentPosition ?: 0) >= 250) progress.countDown()
                        else if (progress.count > 0) handler.postDelayed(this, 50)
                    }
                }
                handler.post(checkProgress)
            }
            assertTrue("Downloaded audio must reach playback progress", progress.await(15, TimeUnit.SECONDS))
            assertNull("Offline audio must decode without a playback error", failure)
            assertEquals("The network factory must never be constructed", 0, attempts.get())
            assertEquals("The media ID stays stable for lyrics and favorites", id, manager.localMediaItem(id)?.mediaId)
        } finally {
            progress.countDown()
            instrumentation.runOnMainSync { player?.release(); player = null }
            manager.store.remove(id)
        }
    }
}
