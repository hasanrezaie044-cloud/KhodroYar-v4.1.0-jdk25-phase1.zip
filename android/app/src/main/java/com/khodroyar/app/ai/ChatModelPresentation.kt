package com.khodroyar.app.ai

import java.util.Locale

/** Presentation is independent of billing, selection, network and the tool executor. */
data class ChatModelPack(val brand: String, val title: String, val models: List<AIModelView>)

object ChatModelPresentation {
    fun brand(model: AIModel): Pair<String, String> {
        val publisher = model.publisher.lowercase(Locale.ROOT)
        val id = model.key.id.lowercase(Locale.ROOT)
        return when {
            publisher.contains("nvidia") || id.startsWith("nvidia/") -> "nvidia" to "NVIDIA"
            publisher.contains("anthropic") || id.contains("claude") -> "anthropic" to "Anthropic"
            publisher.contains("google") || id.contains("gemini") -> "google" to "Google"
            publisher.contains("openai") || id.startsWith("gpt-") || id.contains("/gpt-") -> "openai" to "OpenAI"
            publisher.contains("meta") || id.contains("/llama") -> "meta" to "Meta"
            publisher.contains("qwen") || id.contains("/qwen") -> "qwen" to "Qwen"
            publisher.contains("deepseek") || id.contains("deepseek") -> "deepseek" to "DeepSeek"
            publisher.contains("mistral") || id.contains("mistral") -> "mistral" to "Mistral"
            else -> model.key.provider to model.publisher.ifBlank { model.providerName }
        }
    }
    fun packs(models: List<AIModelView>): List<ChatModelPack> = models.groupBy { brand(it.model) }
        .map { (brand, rows) -> ChatModelPack(brand.first, brand.second, rows.sortedBy { it.model.name.lowercase(Locale.ROOT) }) }
        .sortedBy { it.title.lowercase(Locale.ROOT) }

    /** Unknown metadata is a dash, never an invented context allowance. This is not an app quota. */
    fun capacity(tokens: Int): String = when {
        tokens <= 0 -> "—"
        tokens >= 1_000_000 -> compact(tokens / if (tokens % 1_048_576 == 0) 1_048_576.0 else 1_000_000.0) + "M"
        tokens >= 1000 -> compact(tokens / if (tokens % 1024 == 0) 1024.0 else 1000.0) + "k"
        else -> tokens.toString()
    }
    private fun compact(n: Double): String = if (n == n.toInt().toDouble()) n.toInt().toString() else String.format(Locale.ROOT, "%.1f", n)
}
