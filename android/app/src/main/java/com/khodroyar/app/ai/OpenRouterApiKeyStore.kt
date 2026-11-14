package com.khodroyar.app.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Separate account/Keystore domain. No plaintext fallback or migration from another provider. */
class OpenRouterApiKeyStore(context: Context) : AiApiKeyReader {
    private val app = context.applicationContext
    private val preferences by lazy {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, "khodroyar_openrouter_credentials", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    override fun read(): String? = preferences.getString("api_key", null)
    fun save(raw: String) {
        val key = requireNotNull(OpenRouterFreePolicy.normalizeKey(raw)) { "Invalid OpenRouter key" }
        check(preferences.edit().putString("api_key", key).commit()) { "Encrypted persistence failed" }
    }
    fun clear() { check(preferences.edit().remove("api_key").commit()) { "Encrypted key deletion failed" } }
}
