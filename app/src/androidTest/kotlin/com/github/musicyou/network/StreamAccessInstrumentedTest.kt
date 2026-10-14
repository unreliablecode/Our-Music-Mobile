package com.github.musicyou.network

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.github.innertube.Innertube
import com.github.innertube.requests.StreamAccessException
import com.github.innertube.requests.isUsableStreamResponse
import com.github.innertube.requests.player
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in network diagnostic using the phone's connection and the application's resolver. */
class StreamAccessInstrumentedTest {
    @Test fun publicAudioResolvesTransfersAndDecodesWithMatchingHeaders() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Run this network diagnostic explicitly by class.", arguments.getString("class")?.contains(StreamAccessInstrumentedTest::class.java.name) == true)
        val ids = arguments.getString("streamVideoIds")?.split(',')?.filter { it.isNotBlank() }
            ?: listOf("gOsM-DYAEhY", "mWRsgZuwf_8", "fJ9rUzIMcZQ")
        var playable = 0
        val failures = mutableListOf<String>()
        for (id in ids.take(3)) {
            try {
                val response = Innertube.player(id)?.getOrThrow() ?: error("No player response")
                Log.i("StreamDiagnostics", "$id status=${response.playabilityStatus?.status} reason=${response.playabilityStatus?.reason.orEmpty()}")
                check(response.playabilityStatus?.status == "OK") { response.playabilityStatus?.reason ?: "The service did not make this song available." }
                check(response.videoDetails?.videoId == id) { "Video id mismatch" }
                val selected = response.streamingData?.highestQualityFormat ?: error("No readable audio")
                fun connection(range: Boolean) = (URL(requireNotNull(selected.url)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    selected.requestHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
                    if (range) setRequestProperty("Range", "bytes=0-1023")
                }
                val range = connection(true)
                try {
                    val status = range.responseCode
                    val bytes = if (status == 200 || status == 206) range.inputStream.use { it.readBytesUpTo(1024) } else byteArrayOf()
                    Log.i("StreamDiagnostics", "$id itag=${selected.itag} rangeStatus=$status readableBytes=${bytes.size}")
                    check(isUsableStreamResponse(status, range.contentType, bytes.firstOrNull()?.toInt()?.and(255) ?: -1)) { "Range audio response $status" }
                } finally { range.disconnect() }
                val destination = File(instrumentation.targetContext.cacheDir, "stream-diagnostic-$id.bin")
                val full = connection(false)
                try {
                    val status = full.responseCode
                    Log.i("StreamDiagnostics", "$id itag=${selected.itag} fullTransferStatus=$status expectedBytes=${selected.contentLength}")
                    check(status == 200 || status == 206) { "Full audio response $status" }
                    full.inputStream.use { input -> destination.outputStream().use { input.copyTo(it) } }
                    check(destination.length() > 0)
                    selected.contentLength?.let { check(it == destination.length()) { "Incomplete audio transfer" } }
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(destination.path)
                        check((0 until extractor.trackCount).any {
                            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        }) { "Audio track missing" }
                        Log.i("StreamDiagnostics", "$id downloadedBytes=${destination.length()} audioTrackFound=true")
                        playable++
                    } finally { extractor.release() }
                } finally { full.disconnect(); destination.delete() }
            } catch (canceled: CancellationException) {
                throw canceled
            } catch (failure: Exception) {
                val code = (failure as? StreamAccessException)?.statusCode
                val reason = failure.message.orEmpty().lineSequence().firstOrNull().orEmpty()
                    .replace(Regex("https?://[^ ]+"), "[address]").take(180)
                val detail = "$id failure=${failure.javaClass.simpleName} httpStatus=$code reason=$reason"
                Log.e("StreamDiagnostics", detail)
                failures.add(detail)
            }
        }
        Log.i("StreamDiagnostics", "playable=$playable requested=${ids.take(3).size}")
        assertTrue("No public song resolved, transferred and exposed an audio track: ${failures.joinToString("; ")}", playable > 0)
    }

    private fun java.io.InputStream.readBytesUpTo(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = read(buffer, count, limit - count)
            if (read < 0) break
            count += read
        }
        return buffer.copyOf(count)
    }
}
