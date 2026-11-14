package com.downloadstudio.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ShareDownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ShareDownloadNotification.handleAction(context.applicationContext, intent)
    }
}
