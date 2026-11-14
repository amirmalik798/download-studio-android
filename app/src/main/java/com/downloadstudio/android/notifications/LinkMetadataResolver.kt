package com.downloadstudio.android.notifications

import android.content.Context
import android.net.Uri
import com.downloadstudio.android.engine.DownloadCoordinator
import com.downloadstudio.android.engine.TermuxBridge
import com.downloadstudio.android.util.StorageAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * Best-effort metadata enrichment for a queue item that already exists.
 * Queue acceptance is synchronous; metadata never delays or duplicates it.
 */
object LinkMetadataResolver {
    private data class Metadata(
        val title: String = "",
        val thumbnailUrl: String = "",
        val durationSeconds: Long = 0L,
        val sizeBytes: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun siteName(rawUrl: String): String {
        val host = runCatching {
            Uri.parse(rawUrl).host?.lowercase(Locale.ROOT)?.removePrefix("www.")?.removePrefix("m.")
        }.getOrNull().orEmpty()
        return when {
            host == "youtu.be" || host.endsWith("youtube.com") -> "YouTube"
            host == "facebook.com" || host.endsWith(".facebook.com") || host.endsWith("fb.watch") -> "Facebook"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
            host == "x.com" || host == "twitter.com" || host == "t.co" -> "X"
            host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
            host == "reddit.com" || host.endsWith(".reddit.com") -> "Reddit"
            host == "pinterest.com" || host.endsWith(".pinterest.com") -> "Pinterest"
            host.isBlank() -> "Website"
            else -> host.replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    fun resolve(context: Context, itemId: String, rawUrl: String, updateNotification: Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            val site = siteName(rawUrl)
            val extracted = resolveWithDownloader(appContext, rawUrl)
            val page = if (
                extracted.title.isBlank() || extracted.thumbnailUrl.isBlank() ||
                extracted.durationSeconds <= 0L || extracted.sizeBytes <= 0L
            ) resolvePage(rawUrl) else Metadata()

            val merged = Metadata(
                title = extracted.title.ifBlank { page.title },
                thumbnailUrl = extracted.thumbnailUrl.ifBlank { page.thumbnailUrl },
                durationSeconds = extracted.durationSeconds.takeIf { it > 0L } ?: page.durationSeconds,
                sizeBytes = extracted.sizeBytes.takeIf { it > 0L } ?: page.sizeBytes
            )

            val updated = DownloadCoordinator.updateLinkMetadata(
                id = itemId,
                title = merged.title,
                thumbnailUrl = merged.thumbnailUrl,
                durationSeconds = merged.durationSeconds,
                estimatedSizeBytes = merged.sizeBytes,
                sourceName = site
            )
            if (updated && updateNotification) {
                LinkStatusNotification.show(appContext, itemId, alert = false)
            }
        }
    }

    private suspend fun resolveWithDownloader(context: Context, rawUrl: String): Metadata {
        if (!StorageAccess.isDownloadLocationConfigured(context) ||
            !TermuxBridge.isInstalled(context) || !TermuxBridge.hasRunPermission(context) ||
            !isPublicHttpsUrl(rawUrl)
        ) return Metadata()

        val destination = StorageAccess.destinationPath(context) ?: return Metadata()
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val statePath = "DownloadStudioState/preview-$requestId.txt"
        val script = runCatching { TermuxBridge.previewMetadataScript(requestId, rawUrl, destination) }
            .getOrNull() ?: return Metadata()

        try {
            val started = withContext(Dispatchers.IO) {
                TermuxBridge.runShell(context, script, "Download Studio · link preview", true)
            }
            if (started.isFailure) return Metadata()

            repeat(65) {
                delay(400)
                val state = withContext(Dispatchers.IO) { StorageAccess.readState(context, statePath) }
                if (!state.isNullOrBlank() && state.contains("DS_DONE")) return parseResult(state)
            }
            return Metadata()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                StorageAccess.deleteState(context, statePath)
            }
        }
    }

    private fun parseResult(raw: String): Metadata {
        val lines = raw.lineSequence().toList()
        fun value(key: String) = lines.lastOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim().orEmpty()
        val thumb = safeImageUrl(value("DS_THUMBNAIL"))
        return Metadata(
            title = value("DS_TITLE").take(300),
            thumbnailUrl = thumb,
            durationSeconds = value("DS_DURATION").toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L,
            sizeBytes = value("DS_SIZE").toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
        )
    }

    private fun resolvePage(rawUrl: String): Metadata = runCatching {
        var current = rawUrl
        repeat(5) {
            if (!isPublicHttpsUrl(current)) return Metadata()
            val response = Jsoup.connect(current)
                .userAgent("Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .timeout(7_000)
                .maxBodySize(1_000_000)
                .followRedirects(false)
                .ignoreHttpErrors(true)
                .execute()
            if (response.statusCode() in 300..399) {
                val location = response.header("location") ?: return Metadata()
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            if (response.statusCode() !in 200..299) return Metadata()
            val document = response.parse()
            val title = listOf(
                document.selectFirst("meta[property=og:title]")?.attr("content"),
                document.selectFirst("meta[name=twitter:title]")?.attr("content"),
                document.title()
            ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

            val imageCandidates = listOf(
                document.selectFirst("meta[property=og:image]")?.attr("content"),
                document.selectFirst("meta[property=og:image:secure_url]")?.attr("content"),
                document.selectFirst("meta[name=twitter:image]")?.attr("content"),
                document.selectFirst("meta[itemprop=image]")?.attr("content"),
                document.selectFirst("video[poster]")?.attr("poster")
            )
            val thumbnail = imageCandidates.firstNotNullOfOrNull { candidate ->
                candidate?.takeIf { it.isNotBlank() }?.let {
                    val resolved = URI(current).resolve(it.trim()).toString()
                    safeImageUrl(resolved).takeIf { safe -> safe.isNotBlank() }
                }
            }.orEmpty()
            val duration = listOf(
                document.selectFirst("meta[property=video:duration]")?.attr("content"),
                document.selectFirst("meta[itemprop=duration]")?.attr("content")
            ).firstNotNullOfOrNull { it?.trim()?.toDoubleOrNull()?.toLong() }?.coerceAtLeast(0L) ?: 0L
            return Metadata(title.take(300), thumbnail, duration, 0L)
        }
        Metadata()
    }.getOrDefault(Metadata())

    private fun safeImageUrl(raw: String): String {
        val candidate = runCatching {
            val value = if (raw.startsWith("//")) "https:$raw" else raw
            URI(value).normalize().toString()
        }.getOrNull() ?: return ""
        return candidate.takeIf { isPublicHttpsUrl(it) }.orEmpty()
    }

    private fun isPublicHttpsUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.userInfo != null ||
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")
        ) return false
        InetAddress.getAllByName(host).isNotEmpty() &&
            InetAddress.getAllByName(host).all { address ->
                val bytes = address.address
                val privateIpv6 = bytes.size == 16 &&
                    ((bytes[0].toInt() and 0xFE) == 0xFC ||
                        ((bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xC0) == 0x80))
                val privateIpv4 = bytes.size == 4 && (
                    (bytes[0].toInt() and 0xFF) == 10 ||
                        (bytes[0].toInt() and 0xFF) == 127 ||
                        (bytes[0].toInt() and 0xFF) == 0 ||
                        ((bytes[0].toInt() and 0xFF) == 169 && (bytes[1].toInt() and 0xFF) == 254) ||
                        ((bytes[0].toInt() and 0xFF) == 172 && (bytes[1].toInt() and 0xF0) == 16) ||
                        ((bytes[0].toInt() and 0xFF) == 192 && (bytes[1].toInt() and 0xFF) == 168)
                )
                !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                    !address.isMulticastAddress && !privateIpv4 && !privateIpv6
            }
    }.getOrDefault(false)
}
