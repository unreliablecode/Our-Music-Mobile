package com.github.musicyou.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.musicyou.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Continues queued downloads while the app is closed and resumes after process restart. */
class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queue: Job? = null
    private lateinit var manager: OfflineDownloadManager

    override fun onCreate() {
        super.onCreate()
        manager = OfflineDownloads.get(this)
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Music downloads", NotificationManager.IMPORTANCE_LOW))
        }
        startForeground(NOTIFICATION, notification(null))
        scope.launch {
            manager.downloads.collectLatest { records ->
                val active = records.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                    ?: records.lastOrNull { it.status == DownloadStatus.QUEUED }
                notifications.notify(NOTIFICATION, notification(active))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("mediaId")?.let { id -> if (intent.action == CANCEL) manager.cancel(id) }
        if (queue?.isActive != true) {
            queue = scope.launch {
                try { manager.processQueue() }
                finally {
                    withContext(NonCancellable + Dispatchers.Main) {
                        if (manager.downloads.value.none { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }) {
                            removeForeground()
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        scope.cancel()
        removeForeground()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun removeForeground() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    private fun notification(record: OfflineDownload?): Notification {
        val open = PendingIntent.getActivity(this, 3200, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(record?.title ?: "Preparing music downloads")
            .setContentText(record?.let {
                if (it.totalBytes > 0) "${(it.progress * 100).toInt()}% · ${it.artist}" else "Downloading for offline listening"
            } ?: "Your music will be saved on this device")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, record?.let { (it.progress * 100).toInt() } ?: 0, record == null || record.totalBytes <= 0)
        if (record != null) {
            val cancel = PendingIntent.getService(
                this, record.mediaId.hashCode(),
                Intent(this, OfflineDownloadService::class.java).setAction(CANCEL).putExtra("mediaId", record.mediaId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
        }
        return builder.build()
    }

    private companion object {
        const val CHANNEL = "offline_music_downloads"
        const val NOTIFICATION = 3200
        const val CANCEL = "com.github.musicyou.downloads.CANCEL"
    }
}
