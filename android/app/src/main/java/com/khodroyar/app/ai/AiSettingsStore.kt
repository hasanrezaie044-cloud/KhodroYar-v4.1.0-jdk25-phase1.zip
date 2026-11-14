package com.khodroyar.app.ai

import android.content.Context
import com.khodroyar.app.BuildConfig
import java.util.UUID
import org.json.JSONObject

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
    val modelSelectionMode: AISelectionMode = AISelectionMode.AUTOMATIC,
    val modelAccountScope: String = "initial",
    val providerModels: Map<String, String> = emptyMap(),
    val freeAccountConfirmed: Set<String> = emptySet(),
    val useRegion: AIUseRegion = AIUseRegion.UNKNOWN,
    val cloudflareAccountId: String = "",
    val allowModelFallback: Boolean = false,
    val usage: AIUsageLimits = AIUsageLimits(),
) {
    fun modelFor(provider: AiMode = mode): String = if (provider == AiMode.OPENROUTER) openRouterModel else providerModels[AIProviderRegistry.get(provider).id].orEmpty()
    fun withModel(provider: AiMode, id: String): AiSettings = if (provider == AiMode.OPENROUTER) copy(openRouterModel = id) else copy(providerModels = providerModels + (AIProviderRegistry.get(provider).id to id))
    val requestedModel: String? get() = when (mode) {
        AiMode.OPENROUTER -> openRouterModel
        AiMode.GAPGPT_DIRECT -> GapGptDirectContract.selectedModel(this)
        AiMode.ONLINE -> model.ifBlank { null }
        AiMode.LOCAL -> null
        else -> modelFor().ifBlank { null }
    }
}

class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("khodroyar_ai", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        enabled = prefs.getBoolean("enabled", true),
        mode = runCatching { AiMode.valueOf(prefs.getString("mode", null).orEmpty()) }.getOrDefault(AiMode.OPENROUTER).let { if (it in setOf(AiMode.ONLINE, AiMode.GAPGPT_DIRECT)) AiMode.OPENROUTER else it },
        consented = prefs.getBoolean("provider_consented_v3", false),
        backendUrl = prefs.getString("backend_url", null) ?: BuildConfig.AI_BACKEND_URL,
        model = prefs.getString("model", "") ?: "",
        clientToken = prefs.getString("client_token", "") ?: "",
        gapGptBaseUrl = prefs.getString("gapgpt_base_url", null) ?: GapGptDirectContract.PRIMARY_BASE_URL,
        gapGptModel = prefs.getString("gapgpt_model", null) ?: GapGptDirectContract.DEFAULT_MODEL,
        openRouterModel = prefs.getString("openrouter_model", null) ?: OpenRouterFreePolicy.INITIAL_MODEL,
        modelSelectionMode = runCatching { AISelectionMode.valueOf(prefs.getString("model_selection_mode", "AUTOMATIC").orEmpty()) }.getOrDefault(AISelectionMode.AUTOMATIC),
        modelAccountScope = prefs.getString("model_account_scope", "initial") ?: "initial",
        providerModels = runCatching { JSONObject(prefs.getString("provider_models", "{}").orEmpty()).let { j -> j.keys().asSequence().associateWith { j.getString(it) } } }.getOrDefault(emptyMap()),
        freeAccountConfirmed = prefs.getStringSet("free_accounts", emptySet()).orEmpty().toSet(),
        useRegion = runCatching { AIUseRegion.valueOf(prefs.getString("use_region", "UNKNOWN").orEmpty()) }.getOrDefault(AIUseRegion.UNKNOWN),
        cloudflareAccountId = prefs.getString("cloudflare_account", "").orEmpty(),
        allowModelFallback = prefs.getBoolean("model_fallback_opt_in", false),
        usage = AIUsageLimits(prefs.getInt("input_limit", 12000), prefs.getInt("output_limit", 512), prefs.getInt("turn_limit", 5),
            prefs.getInt("day_request_limit", 40), prefs.getInt("day_token_limit", 80000), prefs.getInt("history_messages", 4),
            prefs.getInt("history_chars", 2400), prefs.getBoolean("tools_enabled", true), prefs.getBoolean("save_history", true)).bounded(),
    )

    fun save(s: AiSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("mode", s.mode.name)
            .putBoolean("provider_consented_v3", s.mode != AiMode.LOCAL && s.consented)
            .putBoolean("openrouter_consented_v2", false)
            .putString("provider_models", JSONObject(s.providerModels).toString())
            .putStringSet("free_accounts", s.freeAccountConfirmed.toSet())
            .putString("use_region", s.useRegion.name)
            .putString("cloudflare_account", s.cloudflareAccountId.trim())
            .putBoolean("model_fallback_opt_in", s.allowModelFallback)
            .putInt("input_limit", s.usage.bounded().inputTokens).putInt("output_limit", s.usage.bounded().outputTokens)
            .putInt("turn_limit", s.usage.bounded().requestsPerTurn).putInt("day_request_limit", s.usage.bounded().requestsPerDay)
            .putInt("day_token_limit", s.usage.bounded().tokensPerDay).putInt("history_messages", s.usage.bounded().historyMessages)
            .putInt("history_chars", s.usage.bounded().historyChars).putBoolean("tools_enabled", s.usage.toolsEnabled).putBoolean("save_history", s.usage.saveHistory)
            .putString("model_selection_mode", s.modelSelectionMode.name)
            .putString("model_account_scope", s.modelAccountScope)
            .putString("openrouter_model", s.openRouterModel.trim())
            .putString("backend_url", AiBackendContract.normalizeRoot(s.backendUrl) ?: s.backendUrl.trim())
            .putString("model", s.model.trim())
            .putString("client_token", s.clientToken.trim())
            .putString("gapgpt_base_url", GapGptDirectContract.normalizeBaseUrl(s.gapGptBaseUrl) ?: s.gapGptBaseUrl.trim())
            .putString("gapgpt_model", s.gapGptModel.trim())
            .apply()
    }

    /** Persist BEFORE changing a key in another preference file; process death cannot reuse old account consent. */
    fun revokeOpenRouterConsent() {
        check(prefs.edit().putBoolean("provider_consented_v3", false).putBoolean("openrouter_consented_v2", false).putBoolean("openrouter_consented_v1", false).commit()) { "Consent revocation failed" }
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

/** Free Gemini content can be used for training: no application-data tools in this provider. */
fun AiSettings.effectiveUsage(): AIUsageLimits = usage.bounded().let { if (mode == AiMode.GEMINI) it.copy(toolsEnabled = false) else it }
