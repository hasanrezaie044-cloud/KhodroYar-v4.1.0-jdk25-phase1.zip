package com.khodroyar.app.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The user's own FastOCR API key (fok_live_…), AES-256 encrypted at rest via
 * Android Keystore (not committed). Entered once by the user; never copied,
 * displayed in full, logged, exported or included in any report/PDF.
 * Used ONLY for HTTPS calls to https://api.fastocr.org with explicit consent.
 */
class FastOcrApiKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context.applicationContext,
        "khodroyar_fastocr_credentials",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun key(): String? = prefs.getString(KEY, null).takeUnless { it.isNullOrBlank() }

    fun save(raw: String): String? {
        val normalized = normalize(raw) ?: return null
        prefs.edit().putString(KEY, normalized).apply()
        return normalized
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val KEY = "api_key"
        fun normalize(raw: String?): String? = raw?.trim()?.takeIf { it.length in 8..256 && it.none(Char::isWhitespace) }
    }
}
