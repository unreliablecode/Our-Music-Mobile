package com.github.musicyou.utils

import androidx.media3.common.MediaItem
import com.github.innertube.Innertube
import com.github.innertube.requests.lyrics
import com.github.kugou.KuGou
import com.github.musicyou.database
import com.github.musicyou.models.Lyrics
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class LyricsFetchResult(
    val lyrics: Lyrics?,
    val source: String? = null,
    val networkError: Boolean = false,
    val instrumental: Boolean = false,
)

private val lyricsLocks = ConcurrentHashMap<String, Mutex>()

/** Stored words always remain available when a later refresh fails or the network is offline. */
suspend fun fetchAndSaveLyrics(media: MediaItem, duration: Long, force: Boolean = false): LyricsFetchResult =
    lyricsLocks.getOrPut(media.mediaId) { Mutex() }.withLock {
        withContext(Dispatchers.IO) {
            val previous = database.lyrics(media.mediaId).first()
            if (!force && parseLrc(previous?.synced).lines.any { it.text.isNotBlank() }) {
                return@withContext LyricsFetchResult(previous, "Saved lyrics")
            }
            val metadata = media.mediaMetadata
            val title = metadata.title?.toString().orEmpty().trim()
            val artist = metadata.artist?.toString().orEmpty().trim()
            val album = metadata.albumTitle?.toString().orEmpty().trim()
            val durationMs = duration.takeIf { it > 0 } ?: durationTextMillis(metadata.extras?.getString("durationText"))
            var synced: String? = null
            var plain: String? = previous?.fixed?.takeIf { it.isNotBlank() }
            var source: String? = if (plain != null) "Saved lyrics" else null
            var networkError = false
            var instrumental = false

            if (title.isNotBlank() && artist.isNotBlank()) {
                try {
                    val record = withTimeoutOrNull(16_000) {
                        getLrcLibRecord(title, artist, album, durationMs)
                            ?: if (album.isNotBlank()) getLrcLibRecord(title, artist, "", durationMs) else null
                    }
                    if (record != null) {
                        synced = record.optString("syncedLyrics").takeUnless { it.isBlank() || it == "null" }
                            ?.takeIf { parseLrc(it).lines.any { cue -> cue.text.isNotBlank() } }
                        plain = record.optString("plainLyrics").takeUnless { it.isBlank() || it == "null" } ?: plain
                        instrumental = record.optBoolean("instrumental", false)
                        if (synced != null || plain != null) source = "LRCLIB"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    networkError = true
                }
                if (synced == null && !instrumental && durationMs > 0) {
                    val kugou = withTimeoutOrNull(14_000) { KuGou.lyrics(artist, title, durationMs / 1000) }
                    if (kugou?.isFailure == true) networkError = true
                    kugou?.getOrNull()?.value?.takeIf { parseLrc(it).lines.any { cue -> cue.text.isNotBlank() } }?.let {
                        synced = it
                        source = "KuGou"
                        if (plain == null) plain = parseLrc(it).lines.map { cue -> cue.text }.filter(String::isNotBlank).joinToString("\n")
                    }
                }
            }
            if (plain == null && !instrumental) {
                val fallback = withTimeoutOrNull(12_000) { Innertube.lyrics(videoId = media.mediaId) }
                if (fallback?.isFailure == true) networkError = true
                fallback?.getOrNull()?.takeIf { it.isNotBlank() }?.let { plain = it; if (source == null) source = "YouTube Music" }
            }
            if (synced == null && !previous?.synced.isNullOrBlank()) synced = previous?.synced
            val updated = if (synced != null || plain != null) Lyrics(media.mediaId, plain, synced) else previous
            if (updated != null) {
                database.insert(media)
                database.upsert(updated)
            }
            LyricsFetchResult(updated, source, networkError, instrumental)
        }
    }

private fun getLrcLibRecord(title: String, artist: String, album: String, durationMs: Long): JSONObject? {
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    val query = buildString {
        append("track_name=${encode(title)}&artist_name=${encode(artist)}")
        if (album.isNotBlank()) append("&album_name=${encode(album)}")
        if (durationMs > 0) append("&duration=${durationMs / 1000.0}")
    }
    val connection = URI("https://lrclib.net/api/get?$query").toURL().openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "OurMusic/1.0 (net.omusic.unreliablecode)")
        when (connection.responseCode) {
            200 -> connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            404 -> null
            else -> error("Lyrics service unavailable")
        }
    } finally {
        connection.disconnect()
    }
}

fun durationTextMillis(value: String?): Long {
    if (value.isNullOrBlank()) return 0L
    val segments = value.split(':').map { it.toLongOrNull() ?: return 0L }
    if (segments.size !in 2..3 || segments.any { it < 0L }) return 0L
    return segments.fold(0L) { total, segment -> total * 60 + segment } * 1000
}
