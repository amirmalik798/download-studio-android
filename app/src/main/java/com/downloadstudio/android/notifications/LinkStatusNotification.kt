package com.downloadstudio.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Log
import java.io.ByteArrayOutputStream
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.downloadstudio.android.MainActivity
import com.downloadstudio.android.R
import com.downloadstudio.android.data.DownloadItem
import com.downloadstudio.android.engine.DownloadCoordinator
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Presentation-only notification projection. DownloadCoordinator remains the
 * sole source of truth for whether a link exists and what metadata it contains.
 */
object LinkStatusNotification {
    private const val CHANNEL_ID = "shared_link_acceptance_v2"
    private const val TAG = "DownloadStudioNotice"
    private const val TAG_PREFIX = "download-studio:shared-link:"
    private const val PREFS = "download_studio_notification_state"
    private const val DISMISSED_IDS = "dismissed_link_notifications"

    private val executor = Executors.newFixedThreadPool(2)
    private val pendingImages = ConcurrentHashMap.newKeySet<String>()
    // Bound cache memory by decoded bitmap size instead of entry count.
    private val bitmapCache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Shared link status",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Confirms when a shared media link is accepted into the Download Studio queue"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                enableLights(true)
                lightColor = android.graphics.Color.rgb(91, 224, 245)
            }
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    /** Publish links accepted while Android notification permission was unavailable. */
    fun showPending(context: Context) {
        DownloadCoordinator.items.value
            .filter { it.awaitingChoice && it.notificationEligible }
            .forEach { show(context, it.id, alert = true) }
    }

    fun show(context: Context, itemId: String, alert: Boolean = true) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPost(appContext)) {
            Log.w(TAG, "Cannot post $itemId: notification permission or app/channel notifications are disabled")
            return
        }
        if (isDismissed(appContext, itemId)) {
            Log.i(TAG, "Notification for $itemId was already dismissed")
            return
        }
        val item = DownloadCoordinator.items.value.firstOrNull {
            it.id == itemId && it.awaitingChoice && it.notificationEligible
        }
        if (item == null) {
            Log.w(TAG, "Queue item $itemId is unavailable for notification delivery")
            return
        }
        val image = item.thumbnailUrl.takeIf { it.isNotBlank() }?.let(bitmapCache::get)
        post(appContext, item, image, alert)

        val thumbnailUrl = item.thumbnailUrl
        if (thumbnailUrl.isBlank() || image != null || !pendingImages.add(itemId)) return
        executor.execute {
            try {
                val bitmap = loadThumbnail(thumbnailUrl) ?: return@execute
                bitmapCache.put(thumbnailUrl, bitmap)
                if (isDismissed(appContext, itemId)) return@execute
                val current = DownloadCoordinator.items.value.firstOrNull {
                    it.id == itemId && it.awaitingChoice && it.notificationEligible && it.thumbnailUrl == thumbnailUrl
                } ?: return@execute
                post(appContext, current, bitmap, alert = false)
            } finally {
                pendingImages.remove(itemId)
            }
        }
    }

    fun cancel(context: Context, itemId: String) {
        if (itemId.isBlank()) return
        NotificationManagerCompat.from(context.applicationContext)
            .cancel(notificationTag(itemId), notificationId(itemId))
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(DISMISSED_IDS, emptySet()).orEmpty().toMutableSet()
        if (ids.remove(itemId)) prefs.edit().putStringSet(DISMISSED_IDS, ids).apply()
    }

    /** Called by the notification delete receiver. Dismissal never removes queue data. */
    fun markDismissed(context: Context, itemId: String) {
        if (itemId.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(DISMISSED_IDS, emptySet()).orEmpty().toMutableSet()
        ids.add(itemId)
        // Keep this presentation-only set bounded if the user routinely dismisses notices.
        val bounded = ids.toList().takeLast(128).toSet()
        prefs.edit().putStringSet(DISMISSED_IDS, bounded).apply()
    }

    private fun isDismissed(context: Context, itemId: String): Boolean =
        itemId in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(DISMISSED_IDS, emptySet()).orEmpty()

    private fun post(context: Context, item: DownloadItem, thumbnail: Bitmap?, alert: Boolean) {
        val source = item.sourceName.ifBlank { LinkMetadataResolver.siteName(item.url) }
        val title = item.title.ifBlank { "New media link accepted" }
        val durationLabel = formatDuration(item.durationSeconds)
        val sizeLabel = formatBytes(item.estimatedSizeBytes)
        val statusText = "$source · Added to your queue"
        val details = "$source · Added to your download queue\nLength: $durationLabel · Approx. size: $sizeLabel\nChoose Download Video or Download Audio in Download Studio."
        val itemId = item.id

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(statusText)
            .setSubText("DOWNLOAD STUDIO")
            .setContentIntent(openApp(context, itemId))
            .setDeleteIntent(dismissIntent(context, itemId))
            .setColor(android.graphics.Color.rgb(91, 224, 245))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(!alert)
            .setAutoCancel(true)
            .setOngoing(false)
            .setShowWhen(true)

        if (thumbnail != null) {
            builder
                .setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(null as Bitmap?)
                        .setBigContentTitle(title)
                        .setSummaryText("$source · $durationLabel · Approx. $sizeLabel")
                )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(details))
        }

        try {
            NotificationManagerCompat.from(context).notify(
                notificationTag(itemId),
                notificationId(itemId),
                builder.build()
            )
            Log.i(TAG, "Posted shared-link notification for $itemId from $source")
        } catch (error: SecurityException) {
            Log.e(TAG, "Android denied notification delivery for $itemId", error)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Android failed to render or post notification for $itemId", error)
        }
    }

    private fun compactView(context: Context, item: DownloadItem, thumbnail: Bitmap?): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_link_collapsed).apply {
            setTextViewText(R.id.notification_source_small, item.sourceName.ifBlank { LinkMetadataResolver.siteName(item.url) }.uppercase())
            setTextViewText(R.id.notification_title_small, item.title.ifBlank { "New link added to your queue" })
            setTextViewText(R.id.notification_state_small, "Accepted · Length ${formatDuration(item.durationSeconds)} · Approx. size ${formatBytes(item.estimatedSizeBytes)}")
            if (thumbnail != null) setImageViewBitmap(R.id.notification_thumbnail_small, thumbnail)
            else setImageViewResource(R.id.notification_thumbnail_small, R.drawable.ic_notification_placeholder)
        }

    private fun expandedView(context: Context, item: DownloadItem, thumbnail: Bitmap?): RemoteViews =
        RemoteViews(context.packageName, R.layout.notification_link_expanded).apply {
            setTextViewText(R.id.notification_source_large, item.sourceName.ifBlank { LinkMetadataResolver.siteName(item.url) }.uppercase())
            setTextViewText(R.id.notification_title_large, item.title.ifBlank { "New link added to your queue" })
            setTextViewText(R.id.notification_state_large, "ACCEPTED · WAITING FOR YOUR CHOICE")
            setTextViewText(R.id.notification_duration, "Length · ${formatDuration(item.durationSeconds)}")
            setTextViewText(R.id.notification_size, "Approx. size · ${formatBytes(item.estimatedSizeBytes)}")
            if (thumbnail != null) setImageViewBitmap(R.id.notification_thumbnail_large, thumbnail)
            else setImageViewResource(R.id.notification_thumbnail_large, R.drawable.ic_notification_placeholder)
        }

    private fun openApp(context: Context, itemId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("downloadstudio://queue/$itemId")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            context,
            19 * itemId.hashCode() + 7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dismissIntent(context: Context, itemId: String): PendingIntent {
        val intent = Intent(context, LinkNotificationDismissReceiver::class.java).apply {
            data = Uri.parse("downloadstudio://notification/dismiss/$itemId")
            putExtra(LinkNotificationDismissReceiver.EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context,
            19 * itemId.hashCode() + 11,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun loadThumbnail(rawUrl: String): Bitmap? = runCatching {
        var currentUrl = rawUrl
        repeat(4) {
            val uri = Uri.parse(currentUrl)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
                !isPublicImageHost(uri.host!!)
            ) return null

            val connection = URL(currentUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "DownloadStudio/1.0")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                if (connection.contentLengthLong > 3L * 1024L * 1024L) return null

                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 3 * 1024 * 1024) return null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

                val maxDimension = 640
                var sampleSize = 1
                while (bounds.outWidth / sampleSize > maxDimension ||
                    bounds.outHeight / sampleSize > maxDimension
                ) {
                    sampleSize *= 2
                }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
                if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap

                val scale = minOf(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
                return Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { bitmap.recycle() }
            } finally {
                connection.disconnect()
            }
        }
        null
    }.getOrNull()

    private fun isPublicImageHost(host: String): Boolean = runCatching {
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all { address ->
            val bytes = address.address
            val privateIpv4 = bytes.size == 4 && (
                (bytes[0].toInt() and 0xFF) == 10 ||
                    (bytes[0].toInt() and 0xFF) == 127 ||
                    (bytes[0].toInt() and 0xFF) == 0 ||
                    ((bytes[0].toInt() and 0xFF) == 169 && (bytes[1].toInt() and 0xFF) == 254) ||
                    ((bytes[0].toInt() and 0xFF) == 172 && (bytes[1].toInt() and 0xF0) == 16) ||
                    ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 168)
            )
            val privateIpv6 = bytes.size == 16 &&
                ((bytes[0].toInt() and 0xFE) == 0xFC ||
                    ((bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80))
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress && !privateIpv4 && !privateIpv6
        }
    }.getOrDefault(false)

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0L) return "Unavailable"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainder = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
        else "%d:%02d".format(minutes, remainder)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "Unavailable"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var amount = bytes.toDouble()
        var index = 0
        while (amount >= 1024 && index < units.lastIndex) {
            amount /= 1024
            index++
        }
        return "%.1f %s".format(amount, units[index])
    }

    private fun notificationTag(itemId: String) = TAG_PREFIX + itemId
    private fun notificationId(itemId: String) = itemId.hashCode().takeIf { it != 0 } ?: 1
}
