package com.downloadstudio.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ShareDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.downloadstudio.android.notification.VIDEO",
            "com.downloadstudio.android.notification.AUDIO" -> ShareDownloadNotification.startDownload(context, intent)
            "com.downloadstudio.android.notification.DISCARD" -> ShareDownloadNotification.discard(context, intent)
            "com.downloadstudio.android.notification.PAUSE",
            "com.downloadstudio.android.notification.RESUME",
            "com.downloadstudio.android.notification.STOP" -> ShareDownloadNotification.performControl(context, intent)
        }
    }
}
