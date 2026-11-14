package com.downloadstudio.android.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.downloadstudio.android.R
import com.downloadstudio.android.data.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps Download Studio's Termux job monitors alive while a user-started
 * download is active. The actual yt-dlp transfer remains in Termux.
 */
class DownloadMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(0, 0), foregroundType())
            foregroundStarted = true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to enter foreground state for download monitoring", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        DownloadCoordinator.onMonitorServiceStarted()
        DownloadCoordinator.initialize(applicationContext)
        observation?.cancel()
        observation = serviceScope.launch {
            DownloadCoordinator.items.collect { items ->
                val monitored = items.filter { item ->
                    !item.awaitingChoice && item.status in setOf(
                        DownloadStatus.QUEUED,
                        DownloadStatus.STARTING,
                        DownloadStatus.DOWNLOADING,
                        DownloadStatus.PAUSED,
                        DownloadStatus.PROCESSING
                    )
                }

                if (monitored.isEmpty()) {
                    stopSelf(startId)
                } else {
                    val active = monitored.count {
                        it.status in setOf(DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PROCESSING)
                    }
                    val waiting = monitored.size - active
                    val downloading = monitored.filter { it.status == DownloadStatus.DOWNLOADING }
                    val determinate = downloading.isNotEmpty()
                    val progress = if (determinate) {
                        (downloading.map { it.progress.coerceIn(0f, 1f) }.average() * 100).toInt()
                    } else 0

                    try {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(active, waiting, progress, determinate))
                    } catch (error: Exception) {
                        Log.w(TAG, "Could not refresh the download progress notification", error)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while Download Studio downloads continue in the background."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        active: Int,
        waiting: Int,
        percent: Int = 0,
        determinate: Boolean = false
    ): Notification {
        val message = when {
            active > 0 && waiting > 0 -> "$active active · $waiting waiting"
            active > 0 -> "$active download(s) in progress"
            waiting > 0 -> "$waiting download(s) waiting"
            else -> "Preparing downloads"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle("Downloads running")
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(100, percent.coerceIn(0, 100), !determinate)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ provides only a short grace period after the dataSync quota.
        Log.w(TAG, "Android dataSync foreground-service time limit reached; stopping monitor safely.")
        observation?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        observation?.cancel()
        serviceScope.cancel()
        DownloadCoordinator.onMonitorServiceStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DownloadStudioMonitor"
        private const val CHANNEL_ID = "download_monitor_v1"
        private const val NOTIFICATION_ID = 4107
    }
}
