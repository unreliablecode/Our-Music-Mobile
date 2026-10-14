package com.github.innertube.requests

import com.github.innertube.Innertube
import com.github.innertube.models.PlayerResponse
import com.github.innertube.models.YouTubeClient
import com.github.innertube.models.bodies.PlayerBody
import com.github.innertube.utils.runCatchingNonCancellable
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** Headers stay consistent from the player request through the audio transfer. */
val Innertube.streamingHeaders: Map<String, String>
    get() = mapOf(
        "User-Agent" to YouTubeClient.VISION_OS.userAgent,
        "Accept-Encoding" to "identity",
        "Origin" to "https://www.youtube.com",
        "Referer" to "https://www.youtube.com/",
    )

class StreamAccessException(val statusCode: Int?, message: String) : IOException(message)

/** Signed links must not survive their server expiry in playback's in-memory URL cache. */
fun streamUrlExpiresAt(url: String): Long? = runCatching {
    URI(url).rawQuery?.split('&')?.firstOrNull { it.startsWith("expire=") }
        ?.substringAfter('=')?.toLongOrNull()?.times(1_000)
}.getOrNull()

/** Only accepts real readable media responses, never a successful HTML error page. */
fun isUsableStreamResponse(status: Int, contentType: String?, firstByte: Int): Boolean =
    status in listOf(200, 206) && firstByte >= 0 &&
        contentType?.substringBefore(';')?.trim()?.lowercase()?.let {
            it.startsWith("audio/") || it.startsWith("video/") || it == "application/octet-stream"
        } != false

suspend fun Innertube.player(videoId: String) = runCatchingNonCancellable {
    withContext(Dispatchers.IO) {
        var rejection: StreamAccessException? = null
        repeat(2) {
            currentCoroutineContext().ensureActive()
            val response = client.post("https://www.youtube.com$PLAYER") {
                setBody(PlayerBody(context = YouTubeClient.VISION_OS.toContext(visitorData = visitorData), videoId = videoId))
                streamingHeaders.forEach { (name, value) -> header(name, value) }
                header("X-YouTube-Client-Name", YouTubeClient.VISION_OS.clientId)
                header("X-YouTube-Client-Version", YouTubeClient.VISION_OS.clientVersion)
                mask("playabilityStatus.status,playabilityStatus.reason,playerConfig.audioConfig,streamingData.adaptiveFormats,streamingData.formats,streamingData.expiresInSeconds,videoDetails.videoId")
            }.body<PlayerResponse>()
            // Respect service availability, account, age and regional restrictions.
            if (response.playabilityStatus?.status != "OK" || response.videoDetails?.videoId != videoId) return@withContext response
            val data = response.streamingData ?: return@withContext response
            val audio = (data.adaptiveFormats.orEmpty() + data.formats.orEmpty()).filter { it.mimeType.startsWith("audio/") }
            val progressive = data.formats.orEmpty().filter {
                it.mimeType.startsWith("video/") && (it.audioQuality != null || it.itag == 18 || it.itag == 22)
            }
            val formats = (audio + progressive)
                .filter { !it.url.isNullOrBlank() }
                .sortedWith(compareBy<PlayerResponse.StreamingData.AdaptiveFormat> {
                    if (it.mimeType.startsWith("audio/")) 0 else 1
                }.thenBy {
                    when (it.itag) { 251 -> 0; 140 -> 1; 250 -> 2; 249 -> 3; 139 -> 4; else -> 5 }
                }.thenByDescending { it.bitrate ?: 0 })
                .distinctBy { it.url }
            if (formats.isEmpty()) return@withContext response
            val candidates = formats.filter { it.mimeType.startsWith("audio/") }.take(3) +
                formats.filter { it.mimeType.startsWith("video/") }.take(1)
            for (format in candidates) {
                currentCoroutineContext().ensureActive()
                val source = format.url ?: continue
                try {
                    probeStream(source, streamingHeaders)
                    return@withContext response.copy(streamingData = data.withVerifiedFormat(format, streamingHeaders))
                } catch (canceled: CancellationException) {
                    throw canceled
                } catch (failure: StreamAccessException) {
                    if (failure.statusCode == null || failure.statusCode == 429) throw failure
                    rejection = failure
                }
            }
            // Fetch a newly signed link once; never repeatedly reuse a rejected URL.
        }
        throw rejection ?: StreamAccessException(null, "The music service did not provide readable audio. Try again later.")
    }
}

private fun probeStream(source: String, headers: Map<String, String>) {
    val url = URL(source)
    if (url.protocol != "https" || !(url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com"))) {
        throw StreamAccessException(null, "The music service returned an unsupported audio address.")
    }
    val expiry = streamUrlExpiresAt(source)
    if (expiry != null && expiry <= System.currentTimeMillis() + 30_000) {
        throw StreamAccessException(403, "The music service returned an expired audio link. Try again later.")
    }
    val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
        setRequestProperty("Range", "bytes=0-0")
    }
    try {
        val status = connection.responseCode
        val firstByte = if (status == 200 || status == 206) connection.inputStream.use { it.read() } else -1
        if (!isUsableStreamResponse(status, connection.contentType, firstByte)) {
            throw StreamAccessException(status, when (status) {
                403 -> "The music service rejected this song's audio (403). Try again later."
                429 -> "The music service is receiving too many requests. Try again in a few minutes."
                else -> "The music service could not deliver this song's audio ($status). Try again later."
            })
        }
    } catch (failure: StreamAccessException) {
        throw failure
    } catch (failure: IOException) {
        throw StreamAccessException(null, "The audio connection was interrupted. Check your connection and try again.")
    } finally {
        connection.disconnect()
    }
}
