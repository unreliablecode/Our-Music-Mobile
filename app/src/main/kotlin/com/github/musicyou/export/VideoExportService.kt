package com.github.musicyou.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.github.musicyou.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var notificationUpdates: Job? = null
    private var latestStartId = 0
    private lateinit var manager: VideoExportManager

    override fun onCreate() {
        super.onCreate()
        manager = VideoExports.get(this)
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Music video exports", NotificationManager.IMPORTANCE_LOW))
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(NOTIFICATION, notification(manager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> startForeground(NOTIFICATION, notification(manager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(NOTIFICATION, notification(manager.state.value))
        }
        notificationUpdates = scope.launch(Dispatchers.Main.immediate) {
            manager.state.collectLatest { notifications.notify(NOTIFICATION, notification(it)) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == CANCEL) manager.cancel()
        startWork()
        return START_NOT_STICKY
    }

    private fun startWork() {
        // Cancellation makes a job inactive before its files and codec resources are released.
        if (work != null) return
        work = scope.launch {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:VideoExport").apply { setReferenceCounted(false) }
            try {
                lock.acquire(6L * 60 * 60 * 1000)
                manager.process()
            } finally {
                if (lock.isHeld) lock.release()
                withContext(NonCancellable + Dispatchers.Main) {
                    work = null
                    if (manager.hasPendingRequest()) startWork()
                    else if (stopSelfResult(latestStartId)) {
                        notificationUpdates?.cancel()
                        removeForeground()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onTimeout(startId: Int, fgsType: Int) { manager.cancel(); scope.cancel(); removeForeground(); stopSelf() }

    @Suppress("DEPRECATION")
    private fun removeForeground() { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true) }

    private fun notification(state: VideoExportState): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, NOTIFICATION, Intent(this, VideoExportService::class.java).setAction(CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Rendering music video").setContentText(state.stage).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setProgress(100, (state.progress * 100).toInt(), state.progress <= 0f)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel).build()
    }

    private companion object {
        const val CHANNEL = "music_video_exports"
        const val NOTIFICATION = 3300
        const val CANCEL = "com.github.musicyou.export.CANCEL"
    }
}
