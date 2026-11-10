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
    /** The user has read and accepted what is sent to the server. Nothing is sent before this. */
    val consented: Boolean = false,
    val backendUrl: String = "",
    /** Empty = let the backend use its own default model. */
    val model: String = "",
    val clientToken: String = "",
)

class AiSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("khodroyar_ai", Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        enabled = prefs.getBoolean("enabled", true),
        consented = prefs.getBoolean("consented", false),
        backendUrl = prefs.getString("backend_url", null) ?: BuildConfig.AI_BACKEND_URL,
        model = prefs.getString("model", "") ?: "",
        clientToken = prefs.getString("client_token", "") ?: "",
    )

    fun save(s: AiSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putBoolean("consented", s.consented)
            .putString("backend_url", s.backendUrl.trim())
            .putString("model", s.model.trim())
            .putString("client_token", s.clientToken.trim())
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
        fun isValidBackendUrl(url: String): Boolean {
            val u = url.trim()
            return u.startsWith("https://", ignoreCase = true) && u.length > 10 && !u.contains(' ')
        }
    }
}
