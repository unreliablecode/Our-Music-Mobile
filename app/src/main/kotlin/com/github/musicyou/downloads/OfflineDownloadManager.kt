package com.github.musicyou.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.github.innertube.Innertube
import com.github.innertube.requests.player
import com.github.innertube.requests.StreamAccessException
import com.github.musicyou.database
import com.github.musicyou.utils.fetchAndSaveLyrics
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

private class SongUnavailableException(message: String) : IOException(message)

class OfflineDownloadManager internal constructor(private val context: Context) {
    internal val store = OfflineStore(File(context.filesDir, "offline_music"))
    val downloads: StateFlow<List<OfflineDownload>> = store.downloads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()

    fun download(mediaItem: MediaItem) {
        if (mediaItem.mediaId.isBlank()) return
        scope.launch {
            val previous = store.record(mediaItem.mediaId)
            if (previous?.status == DownloadStatus.COMPLETE && store.completedFile(mediaItem.mediaId) != null) return@launch
            val metadata = mediaItem.mediaMetadata
            store.upsert(
                (previous ?: OfflineDownload(
                    mediaId = mediaItem.mediaId,
                    title = metadata.title?.toString().orEmpty().ifBlank { "Untitled song" },
                    artist = metadata.artist?.toString().orEmpty(), album = metadata.albumTitle?.toString().orEmpty(),
                    artworkUri = metadata.artworkUri?.toString(), durationText = metadata.extras?.getString("durationText"),
                )).copy(status = DownloadStatus.QUEUED, error = null),
            )
            database.insert(mediaItem)
            startService()
        }
    }

    fun retry(mediaId: String) {
        scope.launch {
            if (store.record(mediaId) == null) return@launch
            store.update(mediaId) { it.copy(status = DownloadStatus.QUEUED, error = null) }
            startService()
        }
    }

    fun cancel(mediaId: String) {
        scope.launch {
            store.update(mediaId) {
                if (it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING) it.copy(status = DownloadStatus.CANCELED, error = null) else it
            }
            jobs[mediaId]?.cancel()
            connections.remove(mediaId)?.disconnect()
        }
    }

    fun delete(mediaId: String) {
        scope.launch {
            jobs[mediaId]?.cancel()
            connections.remove(mediaId)?.disconnect()
            store.remove(mediaId)
        }
    }

    fun refresh() { scope.launch { store.refresh() } }
    fun resumePending() {
        scope.launch {
            if (downloads.value.any { it.status == DownloadStatus.QUEUED }) startService()
        }
    }
    fun localFile(mediaId: String): File? = store.completedFile(mediaId)

    @androidx.annotation.OptIn(UnstableApi::class)
    fun localMediaItem(mediaId: String): MediaItem? {
        val file = localFile(mediaId) ?: return null
        val record = store.record(mediaId) ?: return null
        val artwork = store.artworkFile(mediaId).takeIf { it.isFile }?.let(Uri::fromFile)
        return MediaItem.Builder()
            .setMediaId(mediaId).setUri(Uri.fromFile(file)).setCustomCacheKey(mediaId)
            .setMimeType(record.mimeType?.substringBefore(';'))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(record.title).setArtist(record.artist).setAlbumTitle(record.album)
                    .setArtworkUri(artwork)
                    .setExtras(Bundle().apply { putString("durationText", record.durationText) }).build(),
            ).build()
    }

    private suspend fun startService() = withContext(Dispatchers.Main) {
        runCatching { ContextCompat.startForegroundService(context, Intent(context, OfflineDownloadService::class.java)) }
            .onFailure {
                scope.launch {
                    downloads.value.filter { it.status == DownloadStatus.QUEUED }.forEach { record ->
                        store.update(record.mediaId) { it.copy(status = DownloadStatus.FAILED, error = "Open Our Music and tap Retry to continue.") }
                    }
                }
            }
    }

    internal suspend fun processQueue() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val record = downloads.value.lastOrNull { it.status == DownloadStatus.QUEUED } ?: return
            supervisorScope {
                val task = launch {
                    try { downloadWithRetries(record.mediaId) }
                    finally {
                        jobs.remove(record.mediaId)
                        connections.remove(record.mediaId)?.disconnect()
                    }
                }
                jobs[record.mediaId] = task
                task.join()
            }
        }
    }

    private suspend fun downloadWithRetries(id: String) {
        repeat(3) { attempt ->
            try {
                downloadAudio(id)
                return
            } catch (canceled: CancellationException) {
                store.update(id) { if (it.status == DownloadStatus.DOWNLOADING) it.copy(status = DownloadStatus.QUEUED) else it }
                throw canceled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (store.record(id)?.status == DownloadStatus.CANCELED) return
                val unavailable = failure is SongUnavailableException ||
                    (failure is StreamAccessException && failure.statusCode in listOf(403, 404, 410, 429))
                if (attempt == 2 || unavailable) {
                    store.update(id) { it.copy(status = DownloadStatus.FAILED, error = failure.message?.take(180) ?: "Download failed. Tap Retry.") }
                    return
                } else {
                    store.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, error = "Connection interrupted. Retrying…") }
                    delay((attempt + 1) * 2_000L)
                }
            }
        }
    }

    private suspend fun downloadAudio(id: String) {
        val record = store.record(id) ?: return
        if (record.status == DownloadStatus.CANCELED) return
        store.update(id) { it.copy(status = DownloadStatus.DOWNLOADING, error = null) }
        val response = Innertube.player(id)?.getOrThrow() ?: error("The music service did not respond. Tap Retry.")
        if (response.playabilityStatus?.status != "OK") {
            throw SongUnavailableException(response.playabilityStatus?.reason ?: "The music service does not make this song available for download.")
        }
        check(response.videoDetails?.videoId == id) { "The music service returned a different song." }
        val format = response.streamingData?.highestQualityFormat ?: throw SongUnavailableException("No downloadable audio was found.")
        val source = format.url ?: throw SongUnavailableException("No downloadable audio was found.")
        val partial = store.partialFile(id)
        if (record.sourceItag != null && record.sourceItag != format.itag) partial.delete()
        val duration = format.approxDurationMs ?: record.durationMs
        val remaining = ((format.contentLength ?: 0) - partial.length()).coerceAtLeast(0)
        check(context.filesDir.usableSpace > remaining + 5 * 1024 * 1024) { "There is not enough free space for this song." }
        check((format.contentLength ?: 0) <= 512L * 1024 * 1024) { "This song exceeds the download size limit." }
        store.update(id) { it.copy(mimeType = format.mimeType, sourceItag = format.itag, durationMs = duration, totalBytes = format.contentLength ?: 0) }
        transfer(id, source, partial, format.contentLength ?: 0, format.requestHeaders)
        currentCoroutineContext().ensureActive()
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(partial.path)
            check((0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }) { "The download does not contain playable audio." }
        } finally { extractor.release() }
        val digest = sha256(partial)
        currentCoroutineContext().ensureActive()
        store.complete(id, store.record(id)?.totalBytes ?: 0, digest)
        // Audio remains available even if optional artwork or lyric providers fail.
        runCatching { saveArtwork(id, record.artworkUri) }
        runCatching { localMediaItem(id)?.let { fetchAndSaveLyrics(it, duration) } }
    }

    private suspend fun transfer(id: String, source: String, destination: File, expectedBytes: Long, headers: Map<String, String>) {
        var offset = destination.takeIf { it.isFile }?.length() ?: 0
        if (expectedBytes > 0 && offset > expectedBytes) { destination.delete(); offset = 0 }
        if (expectedBytes > 0 && offset == expectedBytes) return
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 20_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        connections[id] = connection
        try {
            val code = connection.responseCode
            check(code == 200 || code == 206) { "Download connection failed ($code). Tap Retry." }
            if (code == 206) {
                val range = connection.getHeaderField("Content-Range").orEmpty()
                check(range.startsWith("bytes $offset-")) { "The server could not resume this download." }
            } else if (offset > 0) offset = 0
            val headerTotal = if (code == 206) connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                else connection.getHeaderFieldLong("Content-Length", -1).takeIf { it > 0 }
            val total = expectedBytes.takeIf { it > 0 } ?: headerTotal ?: 0
            store.update(id) { it.copy(downloadedBytes = offset, totalBytes = total) }
            var written = offset
            var lastUpdate = System.currentTimeMillis()
            connection.inputStream.use { input ->
                FileOutputStream(destination, offset > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (store.record(id)?.status == DownloadStatus.CANCELED) throw CancellationException("Download canceled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        check(written <= 512L * 1024 * 1024) { "This song exceeds the download size limit." }
                        if (System.currentTimeMillis() - lastUpdate >= 500) {
                            store.update(id) { if (it.status == DownloadStatus.DOWNLOADING) it.copy(downloadedBytes = written) else it }
                            lastUpdate = System.currentTimeMillis()
                        }
                    }
                    output.fd.sync()
                }
            }
            check(written > 0 && (total <= 0 || written == total)) { "The download was interrupted. Tap Retry." }
            store.update(id) { it.copy(downloadedBytes = written, totalBytes = written) }
        } finally {
            connections.remove(id, connection)
            connection.disconnect()
        }
    }

    private suspend fun saveArtwork(id: String, source: String?) {
        if (source.isNullOrBlank()) return
        val destination = store.artworkFile(id)
        val partial = File(destination.path + ".part")
        val connection = (URL(source).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 8_000 }
        try {
            check(connection.responseCode == 200)
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytes = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        check(bytes <= 8 * 1024 * 1024)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(partial.path, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0)
            check(partial.renameTo(destination))
            store.update(id) { it.copy(localArtwork = destination.path) }
        } finally { connection.disconnect(); partial.delete() }
    }
}

object OfflineDownloads {
    @Volatile private var manager: OfflineDownloadManager? = null
    fun get(context: Context): OfflineDownloadManager = manager ?: synchronized(this) {
        manager ?: OfflineDownloadManager(context.applicationContext).also { manager = it }
    }
}

@Composable
fun rememberDownloadManager(): OfflineDownloadManager {
    val context = LocalContext.current.applicationContext
    val manager = remember(context) { OfflineDownloads.get(context) }
    LaunchedEffect(manager) { manager.resumePending() }
    return manager
}
