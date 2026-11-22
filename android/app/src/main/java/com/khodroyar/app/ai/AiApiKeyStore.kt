package com.khodroyar.app.ai

import android.content.Context

fun interface AiApiKeyReader {
    fun read(): String?
}

/**
 * Removes encrypted credential files of retired connections (custom backend / GapGPT direct
 * key). Their keys are never read, migrated or exported; deleting the file is the only safe
 * handling of a secret the app no longer uses. OpenRouter and Groq keys are untouched.
 */
object RetiredAiCredentials {
    private const val GAPGPT_FILE = "khodroyar_gapgpt_credentials"
    private val retiredProviderIds = listOf("gemini", "claude", "fireworks", "gpt", "cloudflare", "cerebras", "huggingface", "avalai")

    fun purge(context: Context, providerKeys: (List<String>) -> Unit = {}) {
        val app = context.applicationContext
        runCatching { app.deleteSharedPreferences(GAPGPT_FILE) }
        runCatching { providerKeys(retiredProviderIds) }
    }
}
