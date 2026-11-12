package com.downloadstudio.android.auth

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only sessions explicitly saved by the user from Download Studio's login browser.
 * Cookie jars are encrypted with an Android Keystore AES-GCM key and never backed up.
 */
object BrowserSessionStore {
    enum class Platform(val key: String, val label: String, val domain: String) {
        FACEBOOK("facebook", "Facebook", ".facebook.com"),
        INSTAGRAM("instagram", "Instagram", ".instagram.com")
    }

    private const val PREFS = "browser_media_sessions"
    private const val KEY_ALIAS = "download_studio_browser_sessions_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12

    fun save(context: Context, platform: Platform, cookieJar: String) {
        require(cookieJar.startsWith("# Netscape HTTP Cookie File")) { "The browser did not return a valid cookie session." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(cookieJar.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        check(prefs(context).edit().putString(platform.key, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) { "Could not save the encrypted session." } { "Could not save the encrypted session." }
    }

    fun cookieJarForUrl(context: Context, rawUrl: String): String? {
        val host = runCatching { Uri.parse(rawUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull() ?: return null
        val platform = when {
            host == "facebook.com" || host.endsWith(".facebook.com") -> Platform.FACEBOOK
            host == "instagram.com" || host.endsWith(".instagram.com") -> Platform.INSTAGRAM
            else -> return null
        }
        return read(context, platform)
    }

    fun isConnected(context: Context, platform: Platform): Boolean = !read(context, platform).isNullOrBlank()

    fun remove(context: Context, platform: Platform) {
        prefs(context).edit().remove(platform.key).commit()
    }

    private fun read(context: Context, platform: Platform): String? = runCatching {
        val encoded = prefs(context).getString(platform.key, null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_LENGTH)
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            .takeIf { it.startsWith("# Netscape HTTP Cookie File") }
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
