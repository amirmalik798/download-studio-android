package com.downloadstudio.android.auth

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * The authenticated browser session stays in Android WebView's app-private cookie store.
 * No cookie value or cookie jar is exported to Termux, shared storage, logs, or preferences.
 */
object BrowserSessionStore {
    enum class Platform(val label: String) {
        FACEBOOK("Facebook"),
        INSTAGRAM("Instagram")
    }

    private fun urls(platform: Platform): List<String> = when (platform) {
        Platform.FACEBOOK -> listOf(
            "https://www.facebook.com/",
            "https://m.facebook.com/",
            "https://web.facebook.com/"
        )
        Platform.INSTAGRAM -> listOf(
            "https://www.instagram.com/",
            "https://i.instagram.com/"
        )
    }

    private fun requiredCookies(platform: Platform): Set<String> = when (platform) {
        Platform.FACEBOOK -> setOf("c_user", "xs")
        Platform.INSTAGRAM -> setOf("sessionid")
    }

    /** Checks that the user is signed in. Cookie values are never returned to callers. */
    fun isConnected(context: Context, platform: Platform): Boolean = runCatching {
        val manager = CookieManager.getInstance()
        val names = urls(platform).flatMap { url ->
            manager.getCookie(url).orEmpty().split(';').mapNotNull { part ->
                part.trim().substringBefore('=').takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
            }
        }.toSet()
        names.containsAll(requiredCookies(platform))
    }.getOrDefault(false)

    /** Explicit Save action is a confirmation only; WebView already persists its own cookies. */
    fun save(context: Context, platform: Platform) {
        require(isConnected(context, platform)) {
            "A signed-in ${platform.label} browser session was not detected. Finish signing in first."
        }
        CookieManager.getInstance().flush()
    }

    /** Removes cookies and Web Storage only for the selected platform's WebView origins. */
    fun remove(context: Context, platform: Platform) {
        runCatching {
            val manager = CookieManager.getInstance()
            urls(platform).forEach { url ->
                val cookieNames = manager.getCookie(url).orEmpty().split(';').mapNotNull { part ->
                    part.trim().substringBefore('=').takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }
                }.distinct()
                cookieNames.forEach { name ->
                    manager.setCookie(url, "$name=; Max-Age=0; path=/")
                }
            }
            manager.flush()
            urls(platform).map { android.net.Uri.parse(it).origin }.distinct().forEach { origin ->
                WebStorage.getInstance().deleteOrigin(origin)
            }
        }
    }
}
