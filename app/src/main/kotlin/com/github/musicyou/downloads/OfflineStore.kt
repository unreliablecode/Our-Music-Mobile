package com.github.musicyou.downloads

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETE, FAILED, CANCELED }

@Serializable
data class OfflineDownload(
    val mediaId: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    val localArtwork: String? = null,
    val durationMs: Long = 0,
    val durationText: String? = null,
    val mimeType: String? = null,
    val sourceItag: Int? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val sha256: String? = null,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** Durable downloads are stored independently of the player's evictable cache. */
internal class OfflineStore(val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val index = File(directory, "index.json")
    private val backup = File(directory, "index.json.bak")
    private val lock = Any()
    private val verified = mutableMapOf<String, Pair<Long, Long>>()
    private val _downloads = MutableStateFlow<List<OfflineDownload>>(emptyList())
    val downloads: StateFlow<List<OfflineDownload>> = _downloads

    init {
        directory.mkdirs()
        val loaded = sequenceOf(index, backup).mapNotNull { file ->
            runCatching { json.decodeFromString<List<OfflineDownload>>(file.readText()) }.getOrNull()
        }.firstOrNull().orEmpty()
        _downloads.value = loaded.map { record ->
            when {
                record.status == DownloadStatus.COMPLETE && !hasCompleteFile(record) -> record.copy(
                    status = DownloadStatus.FAILED, error = "The downloaded file is missing. Download it again.", sha256 = null,
                )
                record.status == DownloadStatus.DOWNLOADING -> record.copy(status = DownloadStatus.QUEUED)
                else -> record
            }
        }
        if (_downloads.value != loaded) persist()
    }

    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    fun audioFile(id: String) = File(directory, "${key(id)}.audio")
    fun partialFile(id: String) = File(directory, "${key(id)}.part")
    fun artworkFile(id: String) = File(directory, "${key(id)}.image")

    fun record(id: String): OfflineDownload? = synchronized(lock) { _downloads.value.firstOrNull { it.mediaId == id } }

    fun upsert(record: OfflineDownload) = synchronized(lock) {
        _downloads.value = (_downloads.value.filterNot { it.mediaId == record.mediaId } + record).sortedByDescending { it.addedAt }
        persist()
    }

    fun update(id: String, change: (OfflineDownload) -> OfflineDownload) = synchronized(lock) {
        val existing = _downloads.value.firstOrNull { it.mediaId == id } ?: return@synchronized
        upsert(change(existing))
    }

    fun remove(id: String) = synchronized(lock) {
        partialFile(id).delete()
        audioFile(id).delete()
        artworkFile(id).delete()
        verified.remove(id)
        _downloads.value = _downloads.value.filterNot { it.mediaId == id }
        persist()
    }

    fun refresh() = synchronized(lock) {
        _downloads.value = _downloads.value.map { record ->
            if (record.status == DownloadStatus.COMPLETE && !hasCompleteFile(record)) record.copy(
                status = DownloadStatus.FAILED, sha256 = null, error = "The downloaded file is missing. Download it again.",
            ) else record
        }
        persist()
    }

    /** A file is never advertised as complete until size, digest and rename succeed. */
    fun complete(id: String, expectedBytes: Long, expectedDigest: String): OfflineDownload = synchronized(lock) {
        val record = record(id) ?: error("Download was removed")
        val partial = partialFile(id)
        check(partial.isFile && partial.length() > 0) { "The download is empty" }
        check(expectedBytes <= 0 || partial.length() == expectedBytes) { "The download was interrupted" }
        check(sha256(partial) == expectedDigest) { "The downloaded file did not pass verification" }
        val final = audioFile(id)
        check(!final.exists() || final.delete()) { "The previous download could not be replaced" }
        check(partial.renameTo(final)) { "The verified download could not be saved" }
        val complete = record.copy(
            status = DownloadStatus.COMPLETE, downloadedBytes = final.length(), totalBytes = final.length(),
            sha256 = expectedDigest, error = null,
        )
        verified[id] = final.length() to final.lastModified()
        upsert(complete)
        complete
    }

    /** Verifies once per file fingerprint, including after restarting the process. */
    fun completedFile(id: String): File? = synchronized(lock) {
        val record = record(id)?.takeIf { it.status == DownloadStatus.COMPLETE } ?: return@synchronized null
        val file = audioFile(id)
        if (!hasCompleteFile(record)) {
            update(id) { it.copy(status = DownloadStatus.FAILED, sha256 = null, error = "The downloaded file is missing. Download it again.") }
            return@synchronized null
        }
        val fingerprint = file.length() to file.lastModified()
        if (verified[id] != fingerprint) {
            if (sha256(file) != record.sha256) {
                update(id) { it.copy(status = DownloadStatus.FAILED, sha256 = null, error = "The downloaded file is damaged. Download it again.") }
                return@synchronized null
            }
            verified[id] = fingerprint
        }
        file
    }

    private fun hasCompleteFile(record: OfflineDownload): Boolean {
        val file = audioFile(record.mediaId)
        return record.sha256 != null && file.isFile && file.length() > 0 && file.length() == record.totalBytes
    }

    private fun persist() {
        val temporary = File(directory, "index.json.new")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(_downloads.value).toByteArray())
            output.fd.sync()
        }
        if (index.exists()) {
            if (backup.exists()) check(backup.delete())
            check(index.renameTo(backup))
        }
        check(temporary.renameTo(index))
        backup.delete()
    }
}

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
