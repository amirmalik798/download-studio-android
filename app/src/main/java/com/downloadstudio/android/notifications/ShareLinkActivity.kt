package com.downloadstudio.android.notifications

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.downloadstudio.android.data.DownloadMode
import com.downloadstudio.android.engine.DownloadCoordinator

/**
 * Transparent share target. It only accepts the URL, persists a queue entry,
 * and posts a system notification; it never launches the main workspace.
 */
class ShareLinkActivity : ComponentActivity() {
    private var acceptedIds: ArrayList<String> = arrayListOf()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) acceptedIds.forEach { LinkStatusNotification.show(applicationContext, it) }
        finishQuietly()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)

        DownloadCoordinator.initialize(applicationContext)
        LinkStatusNotification.ensureChannel(applicationContext)

        acceptedIds = savedInstanceState?.getStringArrayList(STATE_ACCEPTED_IDS) ?: arrayListOf()
        if (savedInstanceState == null) {
            val urls = extractSharedUrls(intent)
            urls.forEach { url ->
                runCatching { DownloadCoordinator.enqueueForChoice(url, DownloadMode.VIDEO) }
                    .onSuccess { id ->
                        acceptedIds.add(id)
                        LinkMetadataResolver.resolve(applicationContext, id, url, updateNotification = true)
                    }
            }
        }

        if (acceptedIds.isEmpty()) {
            finishQuietly()
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            acceptedIds.forEach { LinkStatusNotification.show(applicationContext, it) }
            finishQuietly()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_ACCEPTED_IDS, acceptedIds)
        super.onSaveInstanceState(outState)
    }

    private fun finishQuietly() {
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun extractSharedUrls(intent: Intent?): List<String> {
        if (intent == null) return emptyList()
        val candidates = linkedSetOf<String>()
        fun addText(raw: String?) {
            if (raw.isNullOrBlank()) return
            URL_PATTERN.findAll(raw).forEach { match ->
                val candidate = match.value.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
                val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return@forEach
                if ((uri.scheme == "https" || uri.scheme == "http") &&
                    !uri.host.isNullOrBlank() && uri.userInfo == null && candidate.length <= 4096
                ) candidates.add(candidate)
            }
        }

        addText(intent.getStringExtra(Intent.EXTRA_TEXT))
        addText(intent.getStringExtra(Intent.EXTRA_SUBJECT))
        addText(intent.dataString)
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                addText(item.text?.toString())
                addText(item.uri?.toString())
            }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }.getOrNull()?.forEach { addText(it.toString()) }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }.getOrNull()?.let { addText(it.toString()) }
        }
        return candidates.take(MAX_SHARED_LINKS)
    }

    companion object {
        private const val STATE_ACCEPTED_IDS = "accepted_queue_item_ids"
        private const val MAX_SHARED_LINKS = 10
        private val URL_PATTERN = Regex("""https?://[^\\s<>"']+""", RegexOption.IGNORE_CASE)
    }
}
