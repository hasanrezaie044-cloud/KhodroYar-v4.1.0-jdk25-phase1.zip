package com.khodroyar.app.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

fun interface AiApiKeyReader {
    fun read(): String?
}

/**
 * Optional, user-entered personal GapGPT key only. Android Keystore-backed encryption, in a
 * separate private preference file. Never in AiSettings, BuildConfig, Room, exports or backups.
 * Failure is propagated to the UI; there is deliberately no plaintext fallback.
 */
class AiApiKeyStore(context: Context) : AiApiKeyReader {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        val master = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext, "khodroyar_gapgpt_credentials", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun read(): String? = prefs.getString("api_key", null)

    fun save(raw: String) {
        val key = requireNotNull(GapGptDirectContract.normalizeApiKey(raw)) { "Invalid API key" }
        check(prefs.edit().putString("api_key", key).commit()) { "Could not persist encrypted key" }
    }

    fun clear() {
        check(prefs.edit().remove("api_key").commit()) { "Could not remove encrypted key" }
    }
}
