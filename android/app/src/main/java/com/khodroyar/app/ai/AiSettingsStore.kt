package com.khodroyar.app.ai

import android.content.Context
import com.khodroyar.app.BuildConfig
import java.util.UUID

/**
 * Local assistant preferences. Kept in their own SharedPreferences file so they never
 * touch the main settings JSON, the database or backups.
 *
 * Nothing here is a secret provider key. The optional [clientToken] only identifies this
 * app to YOUR backend and is typed in by the owner at runtime, never shipped in the APK.
 */
data class AiSettings(
    val enabled: Boolean = true,
    /** OpenRouter is the new online provider; nothing is sent without a key and fresh consent. Explicit local mode is preserved. */
    val mode: AiMode = AiMode.OPENROUTER,
    /** The user has read and accepted what is sent to the server. Nothing is sent before this. */
    val consented: Boolean = false,
    val backendUrl: String = "",
    /** Empty = let the backend use its own default model. */
    val model: String = "",
    val clientToken: String = "",
    /** Public URL/model for the explicitly selected direct connection. No API key here. */
    val gapGptBaseUrl: String = GapGptDirectContract.PRIMARY_BASE_URL,
    val gapGptModel: String = GapGptDirectContract.DEFAULT_MODEL,
    val openRouterModel: String = OpenRouterFreePolicy.INITIAL_MODEL,
) {
    val requestedModel: String? get() = when (mode) {
        AiMode.OPENROUTER -> openRouterModel
        AiMode.GAPGPT_DIRECT -> GapGptDirectContract.selectedModel(this)
        else -> model.ifBlank { null }
    }
}

class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("khodroyar_ai", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        enabled = prefs.getBoolean("enabled", true),
        mode = runCatching { AiMode.valueOf(prefs.getString("mode", null).orEmpty()) }.getOrDefault(AiMode.OPENROUTER).let { if (it in setOf(AiMode.ONLINE, AiMode.GAPGPT_DIRECT)) AiMode.OPENROUTER else it },
        consented = prefs.getBoolean("openrouter_consented_v1", false),
        backendUrl = prefs.getString("backend_url", null) ?: BuildConfig.AI_BACKEND_URL,
        model = prefs.getString("model", "") ?: "",
        clientToken = prefs.getString("client_token", "") ?: "",
        gapGptBaseUrl = prefs.getString("gapgpt_base_url", null) ?: GapGptDirectContract.PRIMARY_BASE_URL,
        gapGptModel = prefs.getString("gapgpt_model", null) ?: GapGptDirectContract.DEFAULT_MODEL,
        openRouterModel = prefs.getString("openrouter_model", null) ?: OpenRouterFreePolicy.INITIAL_MODEL,
    )

    fun save(s: AiSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("mode", s.mode.name)
            .putBoolean("openrouter_consented_v1", s.mode == AiMode.OPENROUTER && s.consented)
            .putString("openrouter_model", s.openRouterModel.trim())
            .putString("backend_url", AiBackendContract.normalizeRoot(s.backendUrl) ?: s.backendUrl.trim())
            .putString("model", s.model.trim())
            .putString("client_token", s.clientToken.trim())
            .putString("gapgpt_base_url", GapGptDirectContract.normalizeBaseUrl(s.gapGptBaseUrl) ?: s.gapGptBaseUrl.trim())
            .putString("gapgpt_model", s.gapGptModel.trim())
            .apply()
    }

    /** Random per-install id used only for server-side rate limiting. Not tied to the person. */
    fun installId(): String {
        val existing = prefs.getString("install_id", null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", fresh).apply()
        return fresh
    }

    companion object {
        /** Only https is accepted; plain http would expose conversations on the network. */
        fun isValidBackendUrl(url: String): Boolean = AiBackendContract.normalizeRoot(url) != null && !GapGptDirectContract.isProviderHost(url)
    }
}
