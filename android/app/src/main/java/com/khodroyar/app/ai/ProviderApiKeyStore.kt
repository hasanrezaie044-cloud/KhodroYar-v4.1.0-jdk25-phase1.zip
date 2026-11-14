package com.khodroyar.app.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Existing OpenRouter store is retained byte-for-byte; other providers have separate encrypted namespaces. */
class ProviderApiKeyStore(context: Context, private val mode: AiMode) : AiApiKeyReader {
    private val app = context.applicationContext
    private val original by lazy { OpenRouterApiKeyStore(app) }
    private val prefs by lazy {
        val master = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(app, "khodroyar_provider_credentials", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    override fun read(): String? = if (mode == AiMode.OPENROUTER) original.read() else prefs.getString(AIProviderRegistry.get(mode).id, null)
    fun save(raw: String) {
        val value = requireNotNull(AIProviderRegistry.normalizeKey(mode, raw)) { "Invalid provider key" }
        if (mode == AiMode.OPENROUTER) original.save(value)
        else check(prefs.edit().putString(AIProviderRegistry.get(mode).id, value).commit()) { "Encrypted persistence failed" }
    }
    fun clear() { if (mode == AiMode.OPENROUTER) original.clear() else check(prefs.edit().remove(AIProviderRegistry.get(mode).id).commit()) }
}

/** Install-wide budgets do not reset when provider, model, account, consent or history changes. */
class AndroidAIUsageStore(context: Context) : AIUsageStore {
    private val prefs = context.applicationContext.getSharedPreferences("khodroyar_ai_usage", Context.MODE_PRIVATE)
    override fun read(): AIUsageLedger = synchronized(lock) {
        AIUsageLedger(prefs.getLong("day", -1), prefs.getInt("requests", 0), prefs.getLong("tokens", 0), prefs.getInt("metadata", 0),
            prefs.getLong("minute", -1), prefs.getInt("minute_requests", 0), prefs.getString("blocked_scope", "").orEmpty(),
            prefs.getLong("blocked_until", 0), prefs.getString("blocked_kind", "").orEmpty())
    }
    override fun write(value: AIUsageLedger) = synchronized(lock) {
        check(prefs.edit().putLong("day", value.day).putInt("requests", value.requests).putLong("tokens", value.reservedTokens)
            .putInt("metadata", value.metadataRequests).putLong("minute", value.minute).putInt("minute_requests", value.minuteRequests)
            .putString("blocked_scope", value.blockedScope).putLong("blocked_until", value.blockedUntil).putString("blocked_kind", value.blockedKind).commit()) { "Usage reservation failed" }
    }
    companion object { private val lock = Any() }
}
