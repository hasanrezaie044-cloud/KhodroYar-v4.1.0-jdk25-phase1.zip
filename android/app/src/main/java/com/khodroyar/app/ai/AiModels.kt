package com.khodroyar.app.ai

/** One message sent to the model. [role] is "system", "user" or "assistant". */
data class AiMessage(val role: String, val content: String)

/** Local is an explicit command assistant, not a downloaded/generative language model. */
/**
 * Exactly three assistant systems. Values written by older versions for retired connections
 * (ONLINE, GAPGPT_DIRECT, GEMINI, CLAUDE, ...) are read back as [LOCAL]: the offline assistant
 * never sends anything, so a retired connection can never silently become a different network
 * provider.
 */
enum class AiMode {
    LOCAL, OPENROUTER, GROQ;

    companion object {
        val RETIRED = setOf("ONLINE", "GAPGPT_DIRECT", "GEMINI", "CLOUDFLARE", "CEREBRAS", "HUGGING_FACE", "AVALAI", "CLAUDE", "FIREWORKS", "GPT")
        fun fromStored(raw: String?, absent: AiMode): AiMode = when {
            raw.isNullOrBlank() -> absent
            raw in RETIRED -> LOCAL
            else -> entries.firstOrNull { it.name == raw } ?: LOCAL
        }
    }
}

enum class AiErrorKind {
    OFFLINE, TIMEOUT, NOT_CONFIGURED, DISABLED, BAD_REQUEST, UNAUTHORIZED,
    CREDIT_EXHAUSTED, RATE_LIMITED, NOT_FOUND, SERVER, UNKNOWN, MODEL_INVALID, GUARDRAIL_REJECTED,
}

sealed interface AiResult {
    data class Success(val content: String, val model: String) : AiResult
    data class Failure(
        val kind: AiErrorKind,
        val message: String,
        val retryAfterSec: Int? = null,
    ) : AiResult
}

/** Result of the connection test shown in the assistant settings. */
data class AiProviderStatus(
    val reachable: Boolean,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val defaultModel: String = "",
    /** null = unknown (could not be checked), true/false = real answer from the provider's model list or a successful completion. */
    val modelAvailable: Boolean? = null,
    val message: String = "",
    val errorKind: AiErrorKind? = null,
)

/**
 * Online provider abstraction. The custom backend and direct OpenAI-compatible service
 * have DIFFERENT endpoint/response contracts. Local mode never calls either one.
 */
interface AIProvider {
    val name: String
    suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult
    suspend fun status(): AiProviderStatus
}

/** A message in the visible chat. */
enum class ChatRole { USER, ASSISTANT, RESULT, ERROR }

data class ChatEntry(
    val id: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Long,
    val image: ChatImageRef? = null,
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatEntry>,
    val mode: AiMode = AiMode.LOCAL,
    /** Legacy histories without a scope are view-only, never silently reused with a new key. */
    val accountScope: String = "",
    /** Earlier visible messages belong to a different connection/key and are never sent implicitly. */
    val contextStart: Int = 0,
)

/** Record-summary minimisation only, never a chat/input/output/token usage allowance. */
object AiLimits { const val MAX_TOOL_RESULT_CHARS = 3000 }
