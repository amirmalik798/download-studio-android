package com.downloadstudio.android.auth

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.downloadstudio.android.R

/**
 * A dedicated in-app browser. The user signs in directly with the platform;
 * Download Studio never asks for or handles the user's password.
 */
class BrowserSessionActivity : Activity() {
    private lateinit var platform: BrowserSessionStore.Platform
    private lateinit var webView: WebView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = runCatching {
            BrowserSessionStore.Platform.valueOf(intent.getStringExtra(EXTRA_PLATFORM).orEmpty())
        }.getOrDefault(BrowserSessionStore.Platform.FACEBOOK)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 14, 25))
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val heading = TextView(this).apply {
            text = "Connect ${platform.label}"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        status = TextView(this).apply {
            text = "Sign in using the official ${platform.label} page. Download Studio never sees your password."
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
            if (Build.VERSION.SDK_INT >= 26) settings.safeBrowsingEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !isAllowedPlatformUrl(request.url)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                    !isAllowedPlatformUrl(Uri.parse(url))

                override fun onPageFinished(view: WebView, url: String) {
                    status.text = "When you are signed in, tap Save session. You can remove it later in Settings."
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        status.text = "The login page could not be loaded. Check your connection and try again."
                    }
                }
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }
        val save = Button(this).apply {
            text = "Save session"
            setOnClickListener { saveSession() }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(heading)
        root.addView(status)
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(actions)
        setContentView(root)

        webView.loadUrl(loginUrl())
    }

    private fun loginUrl(): String = when (platform) {
        BrowserSessionStore.Platform.FACEBOOK -> "https://www.facebook.com/login/"
        BrowserSessionStore.Platform.INSTAGRAM -> "https://www.instagram.com/accounts/login/"
    }

    private fun isAllowedPlatformUrl(uri: Uri): Boolean {
        if (uri.scheme != "https" && uri.scheme != "http") return true
        val host = uri.host?.lowercase().orEmpty()
        return when (platform) {
            BrowserSessionStore.Platform.FACEBOOK ->
                host == "facebook.com" || host.endsWith(".facebook.com")
            BrowserSessionStore.Platform.INSTAGRAM ->
                host == "instagram.com" || host.endsWith(".instagram.com")
        }.not().also { blocked ->
            if (blocked) runOnUiThread {
                Toast.makeText(this, "For your security, this login browser stays on ${platform.label}.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSession() {
        val manager = CookieManager.getInstance()
        manager.flush()
        val urls = when (platform) {
            BrowserSessionStore.Platform.FACEBOOK -> listOf(
                "https://www.facebook.com/",
                "https://m.facebook.com/",
                "https://web.facebook.com/"
            )
            BrowserSessionStore.Platform.INSTAGRAM -> listOf(
                "https://www.instagram.com/",
                "https://i.instagram.com/"
            )
        }
        val cookies = linkedMapOf<String, String>()
        urls.forEach { url ->
            manager.getCookie(url)?.split(';')?.forEach { part ->
                val trimmed = part.trim()
                val split = trimmed.indexOf('=')
                if (split > 0) {
                    val name = trimmed.substring(0, split).trim()
                    val value = trimmed.substring(split + 1).trim()
                    if (name.matches(Regex("[A-Za-z0-9_]+")) && value.isNotEmpty()) {
                        cookies[name] = value
                    }
                }
            }
        }
        val required = when (platform) {
            BrowserSessionStore.Platform.FACEBOOK -> setOf("c_user", "xs")
            BrowserSessionStore.Platform.INSTAGRAM -> setOf("sessionid")
        }
        if (!cookies.keys.containsAll(required)) {
            status.text = "A complete signed-in session was not detected. Finish signing in, then try again."
            Toast.makeText(this, "Please sign in to ${platform.label} first.", Toast.LENGTH_LONG).show()
            return
        }
        val domain = platform.domain
        val lines = cookies.map { (name, value) ->
            "$domain\tTRUE\t/\tTRUE\t0\t$name\t$value"
        }
        val jar = (listOf("# Netscape HTTP Cookie File", "# Saved by Download Studio with your approval") + lines)
            .joinToString("\n", postfix = "\n")
        runCatching { BrowserSessionStore.save(this, platform, jar) }
            .onSuccess {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_PLATFORM, platform.name))
                Toast.makeText(this, "${platform.label} session saved securely on this device.", Toast.LENGTH_LONG).show()
                finish()
            }
            .onFailure {
                status.text = it.message ?: "Could not save the encrypted session."
            }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PLATFORM = "browser_session_platform"
        fun intent(context: android.content.Context, platform: BrowserSessionStore.Platform) =
            Intent(context, BrowserSessionActivity::class.java).putExtra(EXTRA_PLATFORM, platform.name)
    }
}
