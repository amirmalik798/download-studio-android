package com.downloadstudio.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.downloadstudio.android.MainActivity
import com.downloadstudio.android.R
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.data.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = true
    private var showing = false
    private var lastForegroundPackage: String? = null
    private var lastUsageQueryTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_studio)
            .setContentTitle("Download Studio floating controls")
            .setContentText("Floating video actions are enabled for YouTube")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        monitor = scope.launch {
            while (isActive) {
                if (Settings.canDrawOverlays(this@OverlayService) && isYoutubeForeground()) showPanel() else hidePanel()
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun isYoutubeForeground(): Boolean {
        return runCatching {
            val usage = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usage == null) {
                false
            } else {
                val now = System.currentTimeMillis()
                // Query the initial recent history once, then consume only new events.
                // Do not expire the foreground package after a short timer: YouTube may
                // stay open for hours without emitting another ACTIVITY_RESUMED event.
                val begin = if (lastUsageQueryTime == 0L) now - 60 * 60 * 1000L else lastUsageQueryTime
                val events = usage.queryEvents(begin, now)
                if (events != null) {
                    val event = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                            lastForegroundPackage = event.packageName
                        } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED || event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                            if (event.packageName == lastForegroundPackage) lastForegroundPackage = null
                        }
                    }
                }
                lastUsageQueryTime = now
                lastForegroundPackage == YOUTUBE_PACKAGE
            }
        }.getOrDefault(false)
    }

    private fun showPanel() {
        if (showing) return
        val wm = windowManager ?: return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded("#111C31", 18)
            elevation = dp(14).toFloat()
        }
        val header = TextView(this).apply {
            text = "DS  ·  DOWNLOAD STUDIO  ·  TAP TO COLLAPSE"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(8))
            setOnClickListener { toggleExpanded(root) }
        }
        root.addView(header)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = if (expanded) View.VISIBLE else View.GONE }
        actions.addView(actionButton("▶  Video", "#2563EB") {
            chooseMode(DownloadMode.VIDEO)
            Toast.makeText(this, "Video selected. In YouTube, tap Share → Download Studio to send the current link.", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(actionButton("♫  Audio", "#087968") {
            chooseMode(DownloadMode.AUDIO)
            Toast.makeText(this, "Audio selected. In YouTube, tap Share → Download Studio to send the current link.", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(5) })
        root.addView(actions)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            dp(if (expanded) 238 else 174), WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12); y = dp(205)
        }
        header.setOnTouchListener(DragTouchListener(wm, lp))
        try {
            wm.addView(root, lp)
            panel = root
            params = lp
            showing = true
        } catch (_: Exception) {
            showing = false
        }
    }

    private fun toggleExpanded(root: LinearLayout) {
        expanded = !expanded
        val actions = root.getChildAt(1)
        actions.visibility = if (expanded) View.VISIBLE else View.GONE
        params?.let {
            it.width = dp(if (expanded) 238 else 174)
            runCatching { windowManager?.updateViewLayout(root, it) }
        }
    }

    private fun chooseMode(mode: DownloadMode) {
        QueueStore.setPendingMode(this, mode)
        Toast.makeText(this, if (mode == DownloadMode.VIDEO) "Video mode selected. Now use YouTube Share → Download Studio." else "Audio mode selected. Now use YouTube Share → Download Studio.", Toast.LENGTH_LONG).show()
        // A notification makes the next action obvious without trying to inspect YouTube's private player URL.
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = android.app.PendingIntent.getActivity(this, 90, launch, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(91, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_studio)
            .setContentTitle(if (mode == DownloadMode.VIDEO) "Video download selected" else "Audio download selected")
            .setContentText("Mode selected. Use YouTube Share → Download Studio to send the current video URL.")
            .setContentIntent(pi).setAutoCancel(true).build())
    }

    private fun actionButton(textValue: String, color: String, onClick: () -> Unit) = Button(this).apply {
        text = textValue; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
        background = rounded(color, 12); setOnClickListener { onClick() }
        minHeight = dp(40); minimumHeight = dp(40); stateListAnimator = null
    }

    private fun hidePanel() {
        if (!showing) return
        runCatching { windowManager?.removeView(panel) }
        panel = null; params = null; showing = false; expanded = true
    }

    private fun rounded(color: String, radius: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Floating download controls", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitor?.cancel(); hidePanel(); scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class DragTouchListener(
        private val wm: WindowManager,
        private val layout: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var moved = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; moved = false; return false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (downX - event.rawX).toInt(); val dy = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 8) moved = true
                    if (moved) { layout.x = (startX + dx).coerceAtLeast(0); layout.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(view, layout) }; return true }
                }
                MotionEvent.ACTION_UP -> if (moved) return true
            }
            return false
        }
    }

    companion object {
        private const val CHANNEL_ID = "download_studio_overlay"
        private const val NOTIFICATION_ID = 90
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}
