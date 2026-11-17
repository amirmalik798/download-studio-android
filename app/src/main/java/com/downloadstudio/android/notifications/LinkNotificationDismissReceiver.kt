package com.downloadstudio.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LinkNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        LinkStatusNotification.markDismissed(context, itemId)
    }

    companion object {
        const val EXTRA_ITEM_ID = "download_studio_notification_item_id"
    }
}
