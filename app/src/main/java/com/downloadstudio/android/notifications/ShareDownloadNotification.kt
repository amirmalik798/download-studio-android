package com.downloadstudio.android.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.downloadstudio.android.MainActivity
import com.downloadstudio.android.R
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.DownloadStatus
import com.downloadstudio.android.engine.DownloadCoordinator
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object ShareDownloadNotification {
    private const val CHANNEL_ID = "shared_media_downloads"
    private const val PREFIX = "com.downloadstudio.android.notification."
    private const val EXTRA_URL = "share_url"
    private const val EXTRA_TITLE = "share_title"
    private const val EXTRA_THUMBNAIL = "share_thumbnail"
    private const val EXTRA_DURATION = "share_duration"
    private const val EXTRA_SIZE_BYTES = "share_size_bytes"
    private const val EXTRA_ITEM_ID = "download_item_id"
    private const val EXTRA_NOTIFICATION_ID = "notification_id"
    private const val ACTIVE_ID_BASE = 73000
    private val ids = AtomicInteger(72000 + (System.currentTimeMillis() % 90000L).toInt())
    private val executor = Executors.newFixedThreadPool(2)
    private val snapshots = ConcurrentHashMap<String, String>()
    private val dismissedTracking = ConcurrentHashMap.newKeySet<String>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Shared media downloads", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shared link previews and download controls"
            })
        }
    }

    fun nextPreviewId(): Int = ids.incrementAndGet()

    fun showPreview(context: Context, id: Int, url: String, title: String, thumbnail: String, duration: Long, sizeBytes: Long = 0L) {
        ensureChannel(context)
        val safeTitle = title.ifBlank { "Media link" }
        val safeThumbnail = thumbnail.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_studio)
            .setContentTitle(safeTitle)
            .setContentText(previewSummary(duration, sizeBytes))
            .setAutoCancel(false).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(openApp(context))
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewSummary(duration, sizeBytes) + "\n" + url.take(180)))
            .addAction(action(context, id, PREFIX + "VIDEO", "Download video", url, safeTitle, safeThumbnail, duration, sizeBytes))
            .addAction(action(context, id, PREFIX + "AUDIO", "Download audio", url, safeTitle, safeThumbnail, duration, sizeBytes))
            .addAction(action(context, id, PREFIX + "DISCARD", "Discard", url, safeTitle, safeThumbnail, duration, sizeBytes))
        NotificationManagerCompat.from(context).notify(id, builder.build())
        if (safeThumbnail.isNotBlank()) executor.execute {
            val bitmap = loadBitmap(safeThumbnail) ?: return@execute
            if (NotificationManagerCompat.from(context).activeNotifications.none { it.id == id }) return@execute
            val updated = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_studio).setContentTitle(safeTitle)
                .setContentText(previewSummary(duration, sizeBytes))
                .setContentIntent(openApp(context))
                .setLargeIcon(bitmap).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?))
                .setAutoCancel(false).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(action(context, id, PREFIX + "VIDEO", "Download video", url, safeTitle, safeThumbnail, duration, sizeBytes))
                .addAction(action(context, id, PREFIX + "AUDIO", "Download audio", url, safeTitle, safeThumbnail, duration, sizeBytes))
                .addAction(action(context, id, PREFIX + "DISCARD", "Discard", url, safeTitle, safeThumbnail, duration, sizeBytes))
                .build()
            NotificationManagerCompat.from(context).notify(id, updated)
        }
    }

    private fun action(context: Context, id: Int, type: String, label: String, url: String, title: String, thumbnail: String, duration: Long, sizeBytes: Long): NotificationCompat.Action {
        val intent = Intent(context, ShareDownloadActionReceiver::class.java).apply {
            action = type; putExtra(EXTRA_URL, url); putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_THUMBNAIL, thumbnail); putExtra(EXTRA_DURATION, duration); putExtra(EXTRA_SIZE_BYTES, sizeBytes); putExtra(EXTRA_NOTIFICATION_ID, id)
        }
        val pending = PendingIntent.getBroadcast(context, id + type.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val icon = when (type) {
            PREFIX + "VIDEO" -> R.drawable.ic_action_video
            PREFIX + "AUDIO" -> R.drawable.ic_action_audio
            else -> R.drawable.ic_action_discard
        }
        return NotificationCompat.Action.Builder(icon, label, pending).build()
    }

    fun updatePreview(context: Context, id: Int, url: String, title: String, thumbnail: String, duration: Long, sizeBytes: Long) {
        if (NotificationManagerCompat.from(context).activeNotifications.none { it.id == id }) return
        showPreview(context, id, url, title, thumbnail, duration, sizeBytes)
    }

    fun startDownload(context: Context, intent: Intent) {
        DownloadCoordinator.initialize(context.applicationContext)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val thumbnail = intent.getStringExtra(EXTRA_THUMBNAIL).orEmpty()
        val duration = intent.getLongExtra(EXTRA_DURATION, 0L)
        val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val mode = if (intent.action == PREFIX + "AUDIO") DownloadMode.AUDIO else DownloadMode.VIDEO
        runCatching {
            val confirmedTitle = title.takeUnless { it == "Checking media details…" }.orEmpty()
            val id = DownloadCoordinator.enqueue(url, mode, confirmedTitle, thumbnail, duration, sizeBytes)
            NotificationManagerCompat.from(context).cancel(notificationId)
            showTrackingForItem(context, id)
        }.onFailure { showError(context, notificationId, title, it.message ?: "Unable to start download") }
    }

    fun discard(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_ITEM_ID)?.let { dismissedTracking.add(it) }
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
    }

    fun syncQueue(context: Context, items: List<DownloadItem>) {
        val byId = items.associateBy { it.id }
        snapshots.keys.toList().forEach { id ->
            val item = byId[id]
            if (item == null) {
                snapshots.remove(id)
                dismissedTracking.remove(id)
                NotificationManagerCompat.from(context).cancel(activeId(id))
            } else {
                showTrackingForItem(context, id, item)
            }
        }
        items.filter {
            it.status in setOf(DownloadStatus.QUEUED, DownloadStatus.STARTING, DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED, DownloadStatus.PROCESSING)
        }.forEach { showTrackingForItem(context, it.id, it) }
    }

    fun showTrackingForItem(context: Context, id: String, known: DownloadItem? = null) {
        val item = known ?: DownloadCoordinator.items.value.firstOrNull { it.id == id } ?: return
        if (id in dismissedTracking) return
        val signature = trackingSignature(item)
        if (snapshots.put(id, signature) == signature) return

        val notificationId = activeId(id)
        NotificationManagerCompat.from(context).notify(
            notificationId,
            trackingBuilder(context, id, item, notificationId, null).build()
        )

        if (item.thumbnailUrl.isNotBlank()) executor.execute {
            val bitmap = loadBitmap(item.thumbnailUrl) ?: return@execute
            val current = DownloadCoordinator.items.value.firstOrNull { it.id == id } ?: return@execute
            if (id in dismissedTracking || snapshots[id] != trackingSignature(current)) return@execute
            NotificationManagerCompat.from(context).notify(
                notificationId,
                trackingBuilder(context, id, current, notificationId, bitmap).build()
            )
        }
    }

    private fun trackingSignature(item: DownloadItem): String =
        listOf(
            item.status.name, item.progress.toInt(), item.speed, item.eta,
            item.downloaded, item.totalSize, item.processingStage, item.title
        ).joinToString("|")

    private fun trackingBuilder(
        context: Context,
        id: String,
        item: DownloadItem,
        notificationId: Int,
        thumbnail: Bitmap?
    ): NotificationCompat.Builder {
        val active = item.status in setOf(
            DownloadStatus.QUEUED, DownloadStatus.STARTING, DownloadStatus.DOWNLOADING,
            DownloadStatus.PAUSED, DownloadStatus.PROCESSING
        )
        val statusText = when (item.status) {
            DownloadStatus.QUEUED -> "Waiting in queue"
            DownloadStatus.STARTING -> "Starting"
            DownloadStatus.DOWNLOADING -> "Downloading · " + item.progress.toInt() + "%"
            DownloadStatus.PAUSED -> "Paused · " + item.progress.toInt() + "%"
            DownloadStatus.PROCESSING -> item.processingStage.ifBlank { "Processing media" }
            DownloadStatus.COMPLETED -> "Download completed"
            DownloadStatus.FAILED -> "Download failed"
            DownloadStatus.STOPPED -> "Download stopped"
        }
        val details = buildList {
            add(statusText)
            if (item.durationSeconds > 0L) add("Duration: " + formatDuration(item.durationSeconds))
            if (item.totalSize != "—") add("Size: " + item.totalSize)
            if (item.downloaded != "—") add("Downloaded: " + item.downloaded)
            if (item.speed != "—") add(item.speed)
            if (item.eta != "—") add("ETA " + item.eta)
            if (item.error.isNotBlank()) add(item.error)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_studio)
            .setContentTitle(item.title.ifBlank { "Media download" })
            .setContentText(statusText)
            .setContentIntent(openApp(context))
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, (item.progress * 100).toInt().coerceIn(0, 100), item.status == DownloadStatus.PROCESSING)

        if (thumbnail != null) {
            builder.setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(null as Bitmap?)
                        .setSummaryText(details.joinToString(" · "))
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(details.joinToString(" · ")))
        }

        if (item.status == DownloadStatus.PAUSED) {
            builder.addAction(R.drawable.ic_action_resume, "Resume", control(context, id, PREFIX + "RESUME"))
        }
        if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.STARTING) {
            builder.addAction(R.drawable.ic_action_pause, "Pause", control(context, id, PREFIX + "PAUSE"))
        }
        if (active) {
            builder.addAction(R.drawable.ic_action_stop, "Stop", control(context, id, PREFIX + "STOP"))
        }
        builder.addAction(R.drawable.ic_action_discard, "Discard", dismissTracking(context, id, notificationId))
        return builder
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 915, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun dismissTracking(context: Context, id: String, notificationId: Int): PendingIntent {
        val intent = Intent(context, ShareDownloadActionReceiver::class.java).apply {
            action = PREFIX + "DISCARD"
            putExtra(EXTRA_ITEM_ID, id)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, id.hashCode() + 0x51A, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun control(context: Context, id: String, action: String): PendingIntent {
        val intent = Intent(context, ShareDownloadActionReceiver::class.java).apply { this.action = action; putExtra(EXTRA_ITEM_ID, id) }
        return PendingIntent.getBroadcast(context, id.hashCode() + action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun performControl(context: Context, intent: Intent) {
        DownloadCoordinator.initialize(context.applicationContext)
        val id = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        when (intent.action) {
            PREFIX + "PAUSE" -> DownloadCoordinator.pause(id)
            PREFIX + "RESUME" -> DownloadCoordinator.resume(id)
            PREFIX + "STOP" -> DownloadCoordinator.stop(id)
        }
        DownloadCoordinator.items.value.firstOrNull { it.id == id }?.let { showTrackingForItem(context, id, it) }
    }

    private fun showError(context: Context, id: Int, title: String, message: String) {
        val intent = Intent(context, ShareDownloadActionReceiver::class.java).apply { action = PREFIX + "DISCARD"; putExtra(EXTRA_NOTIFICATION_ID, id) }
        val pending = PendingIntent.getBroadcast(context, id + 17, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_download_studio)
            .setContentTitle(title.ifBlank { "Download Studio" }).setContentText(message)
            .setAutoCancel(false).setOngoing(false).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", pending)
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun activeId(id: String): Int = ACTIVE_ID_BASE + (id.hashCode() and 0x7fffffff) % 100000

    private fun loadBitmap(raw: String): Bitmap? = runCatching {
        URL(raw).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }.getInputStream().use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun previewSummary(duration: Long, sizeBytes: Long): String {
        val durationText = if (duration > 0L) formatDuration(duration) else "Duration unavailable"
        val sizeText = if (sizeBytes > 0L) "Approx. " + formatBytes(sizeBytes) else "Size unavailable"
        return "Shared link · " + durationText + " · " + sizeText
    }

    private fun formatBytes(value: Long): String {
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var amount = value.toDouble()
        var index = 0
        while (amount >= 1024.0 && index < units.lastIndex) { amount /= 1024.0; index++ }
        return "%.1f %s".format(amount, units[index])
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
