package com.downloadstudio.android.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.downloadstudio.android.MainActivity
import com.downloadstudio.android.R
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.data.SharedLinkState
import com.downloadstudio.android.engine.DownloadCoordinator
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * System notifications are a projection of the persisted DownloadCoordinator queue.
 * This object never stores a second copy of queue status, link payload, progress,
 * or dismissal state. Every action resolves the latest queue item by its ID.
 */
object ShareDownloadNotification {
    private const val TAG = "DownloadStudioNotif"
    private const val CHANNEL_ID = "shared_media_downloads"
    private const val ACTION_PREFIX = "com.downloadstudio.android.notification."
    private const val EXTRA_ITEM_ID = "download_item_id"
    private const val NOTIFICATION_TAG_PREFIX = "download-studio:queue:"
    private const val NOTIFICATION_ID = 1

    private val executor = Executors.newFixedThreadPool(2)
    private val bitmapCache = LruCache<String, Bitmap>(12)
    private val thumbnailRequests = ConcurrentHashMap.newKeySet<String>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Shared media downloads",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shared link previews and live download controls"
                    setShowBadge(true)
                }
            )
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /**
     * Reconcile the full system-notification set against one immutable queue snapshot.
     * Stable notification tags ensure one queue item owns one system notification.
     */
    fun syncQueue(context: Context, items: List<DownloadItem>) {
        ensureChannel(context)
        val expectedTags = items.mapTo(HashSet()) { notificationTag(it.id) }
        items.forEach { item -> publishItem(context.applicationContext, item) }

        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications
                .filter { it.tag?.startsWith(NOTIFICATION_TAG_PREFIX) == true && it.tag !in expectedTags }
                .forEach { manager.cancel(it.tag, it.id) }
        }.onFailure { Log.w(TAG, "Unable to reconcile removed queue notifications", it) }
    }

    private fun publishItem(context: Context, item: DownloadItem) {
        val cachedThumbnail = item.thumbnailUrl.takeIf { it.isNotBlank() }?.let(bitmapCache::get)
        post(context, item, cachedThumbnail)

        val thumbnailUrl = item.thumbnailUrl
        if (thumbnailUrl.isBlank() || cachedThumbnail != null || !thumbnailRequests.add(thumbnailUrl)) return
        executor.execute {
            try {
                val bitmap = loadBitmap(thumbnailUrl) ?: return@execute
                bitmapCache.put(thumbnailUrl, bitmap)
                val current = DownloadCoordinator.items.value.firstOrNull { it.id == item.id } ?: return@execute
                if (current.thumbnailUrl == thumbnailUrl) post(context, current, bitmap)
            } finally {
                thumbnailRequests.remove(thumbnailUrl)
            }
        }
    }

    private fun post(context: Context, item: DownloadItem, thumbnail: Bitmap?) {
        val notification = buildNotification(context, item, thumbnail).build()
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationTag(item.id), NOTIFICATION_ID, notification)
        }.onFailure {
            Log.e(TAG, "Unable to post queue notification for ${item.id}", it)
        }
    }

    private fun buildNotification(
        context: Context,
        item: DownloadItem,
        thumbnail: Bitmap?
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_studio)
            .setContentTitle(item.title.ifBlank { "Media download" })
            .setContentIntent(openApp(context))
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumbnail).bigLargeIcon(null as Bitmap?))
        }

        when (item.shareState) {
            SharedLinkState.PREVIEWING -> {
                builder.setContentText("Checking shared link…")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        "Checking the shared link. The notification will update with the media details."
                    ))
                    .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
            }
            SharedLinkState.AWAITING_ACTION -> {
                val heading = if (item.shareReady) "Choose video or audio to download" else "Shared link needs attention"
                val details = buildString {
                    append(heading)
                    if (item.durationSeconds > 0L) append("\nDuration: ").append(formatDuration(item.durationSeconds))
                    else append("\nDuration: unavailable")
                    if (item.totalSize != "—") append("\nSize: ").append(item.totalSize)
                    else append("\nSize: unavailable")
                    if (item.error.isNotBlank()) append("\n").append(item.error)
                }
                builder.setContentText(heading)
                if (thumbnail == null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(details))
                if (item.shareReady) {
                    builder.addAction(action(context, item.id, ACTION_PREFIX + "VIDEO", R.drawable.ic_action_video, "Download video"))
                    builder.addAction(action(context, item.id, ACTION_PREFIX + "AUDIO", R.drawable.ic_action_audio, "Download audio"))
                }
                builder.addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
            }
            null -> when (item.status) {
                DownloadStatus.QUEUED -> {
                    builder.setContentText("Waiting in download queue")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, "Waiting in download queue")))
                        .setProgress(100, 0, true)
                        .addAction(action(context, item.id, ACTION_PREFIX + "STOP", R.drawable.ic_action_stop, "Stop"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.STARTING -> {
                    builder.setContentText("Starting download…")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, "Starting download…")))
                        .setProgress(100, item.progressPercent(), true)
                        .addAction(action(context, item.id, ACTION_PREFIX + "STOP", R.drawable.ic_action_stop, "Stop"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.DOWNLOADING -> {
                    val state = "Downloading · ${item.progressPercent()}%"
                    builder.setContentText(state)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, state)))
                        .setProgress(100, item.progressPercent(), false)
                        .addAction(action(context, item.id, ACTION_PREFIX + "PAUSE", R.drawable.ic_action_pause, "Pause"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "STOP", R.drawable.ic_action_stop, "Stop"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.PAUSED -> {
                    val state = "Paused · ${item.progressPercent()}%"
                    builder.setContentText(state)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, state)))
                        .setProgress(100, item.progressPercent(), false)
                        .addAction(action(context, item.id, ACTION_PREFIX + "RESUME", R.drawable.ic_action_resume, "Resume"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "STOP", R.drawable.ic_action_stop, "Stop"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.PROCESSING -> {
                    val state = item.processingStage.ifBlank { "Processing media…" }
                    builder.setContentText(state)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, state)))
                        .setProgress(100, item.progressPercent(), true)
                        .addAction(action(context, item.id, ACTION_PREFIX + "STOP", R.drawable.ic_action_stop, "Stop"))
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.COMPLETED -> {
                    builder.setContentText("Download completed")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, "Download completed")))
                        .setProgress(100, 100, false)
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.FAILED -> {
                    builder.setContentText("Download failed")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, "Download failed")))
                        .setProgress(0, 0, false)
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
                DownloadStatus.STOPPED -> {
                    builder.setContentText("Download stopped")
                        .setStyle(NotificationCompat.BigTextStyle().bigText(downloadDetails(item, "Download stopped")))
                        .setProgress(0, 0, false)
                        .addAction(action(context, item.id, ACTION_PREFIX + "DISCARD", R.drawable.ic_action_discard, "Discard"))
                }
            }
        }
        return builder
    }

    private fun downloadDetails(item: DownloadItem, state: String): String = buildString {
        append(state)
        if (item.durationSeconds > 0L) append("\nDuration: ").append(formatDuration(item.durationSeconds))
        if (item.totalSize != "—") append("\nSize: ").append(item.totalSize)
        if (item.downloaded != "—") append("\nDownloaded: ").append(item.downloaded)
        if (item.speed != "—") append("\nSpeed: ").append(item.speed)
        if (item.eta != "—") append("\nETA: ").append(item.eta)
        if (item.error.isNotBlank()) append("\n").append(item.error)
    }

    private fun DownloadItem.progressPercent(): Int = (progress * 100f).toInt().coerceIn(0, 100)

    private fun action(
        context: Context,
        itemId: String,
        action: String,
        icon: Int,
        label: String
    ): NotificationCompat.Action {
        val intent = Intent(context, ShareDownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            31 * itemId.hashCode() + action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }

    /** Every action is validated against the latest persisted queue item. */
    fun handleAction(context: Context, intent: Intent) {
        DownloadCoordinator.initialize(context.applicationContext)
        val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        when (intent.action) {
            ACTION_PREFIX + "VIDEO" -> DownloadCoordinator.startSharedDownload(id, DownloadMode.VIDEO)
            ACTION_PREFIX + "AUDIO" -> DownloadCoordinator.startSharedDownload(id, DownloadMode.AUDIO)
            ACTION_PREFIX + "PAUSE" -> DownloadCoordinator.pause(id)
            ACTION_PREFIX + "RESUME" -> DownloadCoordinator.resume(id)
            ACTION_PREFIX + "STOP" -> DownloadCoordinator.stop(id)
            ACTION_PREFIX + "DISCARD" -> DownloadCoordinator.remove(id)
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 915, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationTag(itemId: String): String = NOTIFICATION_TAG_PREFIX + itemId

    private fun loadBitmap(raw: String): Bitmap? = runCatching {
        URL(raw).openConnection().apply {
            connectTimeout = 5000
            readTimeout = 5000
        }.getInputStream().use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.w(TAG, "Unable to load notification thumbnail", it) }.getOrNull()

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
        else "%d:%02d".format(minutes, remainder)
    }
}
