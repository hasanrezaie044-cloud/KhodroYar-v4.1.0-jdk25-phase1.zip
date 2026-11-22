package com.khodroyar.app.ai

import android.content.Context
import org.json.JSONObject

/**
 * Local assistant preferences, kept in their own SharedPreferences file (not the main settings JSON
 * or the database). The full backup carries only the non-secret choices (enabled, mode, models,
 * selection/fallback); consent and account scope stay per device and are asked again after a restore.
 *
 * Nothing here is a provider key: OpenRouter and Groq keys live only in encrypted, device-bound stores
 * and are never part of a backup.
 */
data class AiSettings(
    val enabled: Boolean = true,
    /** OpenRouter is the new online provider; nothing is sent without a key and fresh consent. Explicit local mode is preserved. */
    val mode: AiMode = AiMode.OPENROUTER,
    /** The user has read and accepted what is sent to the server. Nothing is sent before this. */
    val consented: Boolean = false,
    val openRouterModel: String = OpenRouterFreePolicy.INITIAL_MODEL,
    val modelSelectionMode: AISelectionMode = AISelectionMode.AUTOMATIC,
    val modelAccountScope: String = "initial",
    val providerModels: Map<String, String> = emptyMap(),
    val freeAccountConfirmed: Set<String> = emptySet(),
    val accountTermsConfirmed: Set<String> = emptySet(),
    val useRegion: AIUseRegion = AIUseRegion.UNKNOWN,
    val allowModelFallback: Boolean = false,
    val usage: AIUsageLimits = AIUsageLimits(),
) {
    fun modelFor(provider: AiMode = mode): String = if (provider == AiMode.OPENROUTER) openRouterModel else providerModels[AIProviderRegistry.get(provider).id].orEmpty()
    fun withModel(provider: AiMode, id: String): AiSettings = if (provider == AiMode.OPENROUTER) copy(openRouterModel = id) else copy(providerModels = providerModels + (AIProviderRegistry.get(provider).id to id))
    val requestedModel: String? get() = when (mode) {
        AiMode.OPENROUTER -> openRouterModel
        AiMode.GROQ -> modelFor().ifBlank { null }
        AiMode.LOCAL -> null
    }
}

class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("khodroyar_ai", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        enabled = prefs.getBoolean("enabled", true),
        mode = AiMode.fromStored(prefs.getString("mode", null), absent = AiMode.OPENROUTER),
        consented = prefs.getBoolean("provider_consented_v4", false),
        openRouterModel = prefs.getString("openrouter_model", null) ?: OpenRouterFreePolicy.INITIAL_MODEL,
        modelSelectionMode = runCatching { AISelectionMode.valueOf(prefs.getString("model_selection_mode", "AUTOMATIC").orEmpty()) }.getOrDefault(AISelectionMode.AUTOMATIC),
        modelAccountScope = prefs.getString("model_account_scope", "initial") ?: "initial",
        providerModels = runCatching { JSONObject(prefs.getString("provider_models", "{}").orEmpty()).let { j -> j.keys().asSequence().associateWith { j.getString(it) } } }.getOrDefault(emptyMap()),
        freeAccountConfirmed = prefs.getStringSet("free_accounts", emptySet()).orEmpty().toSet(),
        accountTermsConfirmed = prefs.getStringSet("account_terms", emptySet()).orEmpty().toSet(),
        useRegion = runCatching { AIUseRegion.valueOf(prefs.getString("use_region", "UNKNOWN").orEmpty()) }.getOrDefault(AIUseRegion.UNKNOWN),
        allowModelFallback = prefs.getBoolean("model_fallback_opt_in", false),
        // Retired quota preferences are deliberately ignored, not merely hidden in the UI.
        usage = AIUsageLimits(toolsEnabled = prefs.getBoolean("tools_enabled", true), saveHistory = prefs.getBoolean("save_history", true)),
    )

    fun save(s: AiSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("mode", s.mode.name)
            .putBoolean("provider_consented_v4", s.mode != AiMode.LOCAL && s.consented)
            .putBoolean("openrouter_consented_v2", false)
            .putString("provider_models", JSONObject(s.providerModels).toString())
            .putStringSet("free_accounts", s.freeAccountConfirmed.toSet())
            .putStringSet("account_terms", s.accountTermsConfirmed.toSet())
            .putString("use_region", s.useRegion.name)
            .remove("cloudflare_account")
            .putBoolean("model_fallback_opt_in", s.allowModelFallback)
            .remove("input_limit").remove("output_limit").remove("turn_limit").remove("day_request_limit").remove("day_token_limit")
            .remove("history_messages").remove("history_chars")
            .putBoolean("tools_enabled", s.usage.toolsEnabled).putBoolean("save_history", s.usage.saveHistory)
            .putString("model_selection_mode", s.modelSelectionMode.name)
            .putString("model_account_scope", s.modelAccountScope)
            .putString("openrouter_model", s.openRouterModel.trim())
            // Retired connection settings (custom backend / GapGPT) are removed, never migrated.
            .remove("backend_url").remove("model").remove("client_token").remove("gapgpt_base_url").remove("gapgpt_model")
            .apply()
    }

    /** Persist BEFORE changing a key in another preference file; process death cannot reuse old account consent. */
    fun revokeOpenRouterConsent() {
        check(prefs.edit().putBoolean("provider_consented_v4", false).putBoolean("openrouter_consented_v2", false).putBoolean("openrouter_consented_v1", false).commit()) { "Consent revocation failed" }
    }
}

fun AiSettings.effectiveUsage(): AIUsageLimits = usage.bounded()
