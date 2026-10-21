package com.github.musicyou.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.github.musicyou.downloads.OfflineDownloads
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class VideoExportManager internal constructor(private val context: Context) {
    private val mutableState = MutableStateFlow(VideoExportState())
    val state: StateFlow<VideoExportState> = mutableState.asStateFlow()
    private var pending: VideoExportRequest? = null
    private var activeJob: Job? = null
    private var sourceTitle: String? = null
    private val preferences = context.getSharedPreferences("video_export_work", Context.MODE_PRIVATE)

    init {
        preferences.getString("pending_uri", null)?.let { value ->
            if (Build.VERSION.SDK_INT >= 29) runCatching {
                val uri = Uri.parse(value)
                val pending = context.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.IS_PENDING), null, null, null)?.use {
                    it.moveToFirst() && it.getInt(0) == 1
                } == true
                if (pending) context.contentResolver.delete(uri, null, null)
            }
        }
        preferences.edit().remove("pending_uri").apply()
        @Suppress("DEPRECATION")
        val movies = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OurMusic")
        preferences.getString("pending_legacy_path", null)?.let { path -> runCatching {
            val partial = File(path)
            if (partial.parentFile?.canonicalFile == movies.canonicalFile && partial.name.startsWith(".") && partial.name.endsWith(".mp4.part")) partial.delete()
        } }
        preferences.edit().remove("pending_legacy_path").apply()
        File(context.cacheDir, "video_exports").listFiles()?.forEach { it.deleteRecursively() }
    }

    @Synchronized
    fun start(request: VideoExportRequest) {
        if (mutableState.value.running || activeJob != null) return
        sourceTitle = request.title
        if (OfflineDownloads.get(context).localFile(request.mediaId) == null) {
            mutableState.value = VideoExportState(error = "Download this song before rendering its video.", sourceTitle = sourceTitle)
            return
        }
        if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = VideoExportState(error = "Allow storage access to save videos to Movies.", sourceTitle = sourceTitle)
            return
        }
        pending = request.copy(lyrics = request.lyrics.sortedBy { it.timeMs })
        mutableState.value = VideoExportState(running = true, stage = "Preparing video export", sourceTitle = sourceTitle)
        try { ContextCompat.startForegroundService(context, Intent(context, VideoExportService::class.java)) }
        catch (failure: Exception) {
            pending = null
            mutableState.value = VideoExportState(error = "Open Our Music and start the export again.", sourceTitle = sourceTitle)
        }
    }

    @Synchronized
    fun cancel() {
        pending = null
        activeJob?.cancel(CancellationException("Video export canceled"))
        if (activeJob == null) mutableState.value = VideoExportState(stage = "Export canceled", sourceTitle = sourceTitle)
    }

    fun clearResult() { if (!mutableState.value.running) mutableState.value = VideoExportState() }

    @Synchronized
    internal fun hasPendingRequest(): Boolean = pending != null

    internal suspend fun process() {
        val job = currentCoroutineContext()[Job]
        val request = synchronized(this) { pending?.also { pending = null; activeJob = job } } ?: return
        val directory = File(File(context.cacheDir, "video_exports"), UUID.randomUUID().toString())
        try {
            check(directory.mkdirs()) { "The video workspace could not be created." }
            val source = OfflineDownloads.get(context).localFile(request.mediaId) ?: error("The downloaded song was removed. Download it again before exporting.")
            var lastProgressAt = 0L
            var lastStage = ""
            val output = VideoExporter.render(context, request, source, directory) { value, stage ->
                val now = System.nanoTime()
                if (stage != lastStage || now - lastProgressAt >= 100_000_000L) {
                    mutableState.value = VideoExportState(running = true, progress = value, stage = stage, sourceTitle = sourceTitle)
                    lastProgressAt = now; lastStage = stage
                }
            }
            currentCoroutineContext().ensureActive()
            mutableState.value = VideoExportState(running = true, progress = .99f, stage = "Saving to Movies / OurMusic", sourceTitle = sourceTitle)
            val uri = publish(output, request.title)
            mutableState.value = VideoExportState(progress = 1f, stage = "Saved to Movies / OurMusic", outputUri = uri, sourceTitle = sourceTitle)
        } catch (canceled: CancellationException) {
            mutableState.value = VideoExportState(stage = "Export canceled", sourceTitle = sourceTitle)
            throw canceled
        } catch (failure: Exception) {
            mutableState.value = VideoExportState(error = failure.message?.take(200) ?: "The video could not be rendered. Try again at 720p.", sourceTitle = sourceTitle)
        } finally {
            withContext(NonCancellable) { directory.deleteRecursively() }
            synchronized(this) { if (activeJob === job) activeJob = null }
        }
    }

    private suspend fun publish(source: File, title: String): Uri {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").trim().take(70).ifBlank { "Our Music" }
        val name = "$safeTitle ${System.currentTimeMillis()}.mp4"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/OurMusic")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }) ?: error("The video library could not create a new file.")
            preferences.edit().putString("pending_uri", uri.toString()).commit()
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error("The video library could not open the new file.")
                currentCoroutineContext().ensureActive()
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
                preferences.edit().remove("pending_uri").commit()
                return uri
            } catch (failure: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                preferences.edit().remove("pending_uri").commit()
                throw failure
            }
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "OurMusic")
        check(directory.isDirectory || directory.mkdirs()) { "The Movies folder could not be opened." }
        val destination = File(directory, name)
        val partial = File(directory, ".$name.part")
        preferences.edit().putString("pending_legacy_path", partial.path).commit()
        try {
            source.inputStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(destination)) { "The rendered video could not be saved." }
            @Suppress("DEPRECATION")
            return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DATA, destination.path); put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }) ?: error("The video library could not register the saved video.")
        } catch (failure: Throwable) { destination.delete(); throw failure }
        finally { partial.delete(); preferences.edit().remove("pending_legacy_path").commit() }
    }
}

object VideoExports {
    @Volatile private var manager: VideoExportManager? = null
    fun get(context: Context): VideoExportManager = manager ?: synchronized(this) {
        manager ?: VideoExportManager(context.applicationContext).also { manager = it }
    }
}
