package com.downloadstudio.android.auth

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Uses the app-private WebView session to resolve a media-specific CDN URL.
 * Only that short-lived media URL leaves the WebView; account cookies are never exported.
 */
class BrowserMediaResolverActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var sourceUrl: String
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var resolving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty()
        if (sourceUrl.isBlank() || !isSupportedPageUrl(Uri.parse(sourceUrl))) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 25))
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val heading = TextView(this).apply {
            text = "Prepare secure media download"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        status = TextView(this).apply {
            text = "Opening the shared link in your private browser. Your account session stays inside Download Studio."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), 0, dp(4), dp(10))
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (android.os.Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val allowed = isAllowedNavigation(uri)
                    if (!allowed) runOnUiThread {
                        status.text = "The page requested an external sign-in or redirect. For safety, this browser only follows Facebook/Instagram pages and their media CDNs."
                    }
                    return !allowed
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !isAllowedNavigation(Uri.parse(url))

                override fun onPageFinished(view: WebView, url: String) {
                    attempts = 0
                    status.text = "Looking for the video. For some posts, press Play in the page, then tap Detect media."
                    scheduleDetection()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        status.text = "Could not open this page. Check your connection or sign in, then try again."
                    }
                }
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val detect = Button(this).apply {
            text = "Detect media"
            setOnClickListener { attempts = 0; detectMedia() }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(detect, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(heading)
        root.addView(status)
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buttons)
        setContentView(root)
        webView.loadUrl(sourceUrl)
    }

    private fun scheduleDetection() {
        if (resolving) return
        handler.postDelayed({ detectMedia() }, 1_200L)
    }

    private fun detectMedia() {
        if (resolving || !::webView.isInitialized) return
        attempts += 1
        val script = """
            (function() {
              const videos = Array.from(document.querySelectorAll('video'));
              const video = videos.find(v => v.currentSrc && !v.currentSrc.startsWith('blob:')) ||
                            videos.find(v => v.src && !v.src.startsWith('blob:')) || null;
              const perf = performance.getEntriesByType('resource').map(e => e.name)
                .filter(u => /\.(mp4|m4v|m3u8|mpd)(\?|$)/i.test(u));
              const meta = document.querySelector('meta[property="og:video"]')?.content ||
                           document.querySelector('meta[property="og:video:url"]')?.content || '';
              const url = (video && (video.currentSrc || video.src)) || meta || perf[perf.length - 1] || '';
              const title = document.querySelector('meta[property="og:title"]')?.content ||
                            document.title || 'Social media video';
              const thumbnail = (video && video.poster) ||
                                document.querySelector('meta[property="og:image"]')?.content || '';
              return { url: url, title: title, thumbnail: thumbnail };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            val payload = runCatching { JSONObject(raw ?: "null") }.getOrNull()
            val mediaUrl = payload?.optString("url").orEmpty()
            if (isAllowedMediaUrl(Uri.parse(mediaUrl))) {
                completeResolution(
                    mediaUrl,
                    payload?.optString("title").orEmpty().take(160).ifBlank { "Social media video" },
                    payload?.optString("thumbnail").orEmpty().takeIf {
                        isAllowedThumbnailUrl(Uri.parse(it))
                    }.orEmpty()
                )
            } else {
                status.text = when {
                    mediaUrl.startsWith("blob:", true) ->
                        "The page is using a protected or segmented browser stream. Try playing the video and detecting again; this stream type may not be exportable."
                    attempts < MAX_ATTEMPTS ->
                        "Waiting for the video player. Play the video if needed, then tap Detect media."
                    else ->
                        "Could not find a downloadable media URL. This post may require a format that the current resolver does not support."
                }
                if (attempts < MAX_ATTEMPTS) scheduleDetection()
            }
        }
    }

    private fun completeResolution(mediaUrl: String, title: String, thumbnail: String) {
        if (resolving) return
        resolving = true
        handler.removeCallbacksAndMessages(null)
        setResult(RESULT_OK, Intent().apply {
            putExtra(EXTRA_MEDIA_URL, mediaUrl)
            putExtra(EXTRA_SOURCE_URL, sourceUrl)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_THUMBNAIL, thumbnail)
        })
        Toast.makeText(this, "Media link resolved. Your account session was not shared with Termux.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun isSupportedPageUrl(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "fb.watch" || host.endsWith(".fb.watch") ||
            host == "instagram.com" || host.endsWith(".instagram.com")
    }

    private fun isAllowedNavigation(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase().orEmpty()
        return isSupportedPageUrl(uri) ||
            host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
            host == "fbsbx.com" || host.endsWith(".fbsbx.com") ||
            host == "cdninstagram.com" || host.endsWith(".cdninstagram.com")
    }

    private fun isAllowedMediaUrl(uri: Uri): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null) return false
        val host = uri.host?.lowercase().orEmpty()
        val allowedHost = host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
            host == "fbsbx.com" || host.endsWith(".fbsbx.com") ||
            host == "cdninstagram.com" || host.endsWith(".cdninstagram.com")
        val path = uri.path.orEmpty().lowercase()
        return allowedHost && (path.contains(".mp4") || path.contains(".m4v") ||
            path.contains(".m3u8") || path.contains(".mpd"))
    }

    private fun isAllowedThumbnailUrl(uri: Uri): Boolean {
        if (uri.scheme != "https" || uri.userInfo != null) return false
        val host = uri.host?.lowercase().orEmpty()
        return host == "fbcdn.net" || host.endsWith(".fbcdn.net") ||
            host == "fbsbx.com" || host.endsWith(".fbsbx.com") ||
            host == "cdninstagram.com" || host.endsWith(".cdninstagram.com")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SOURCE_URL = "resolver_source_url"
        const val EXTRA_MEDIA_URL = "resolver_media_url"
        const val EXTRA_TITLE = "resolver_title"
        const val EXTRA_THUMBNAIL = "resolver_thumbnail"
        private const val MAX_ATTEMPTS = 30

        fun intent(context: android.content.Context, sourceUrl: String) =
            Intent(context, BrowserMediaResolverActivity::class.java)
                .putExtra(EXTRA_SOURCE_URL, sourceUrl)
    }
}
