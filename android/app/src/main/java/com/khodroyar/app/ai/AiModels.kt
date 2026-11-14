package com.khodroyar.app.ai

/** One message sent to the model. [role] is "system", "user" or "assistant". */
data class AiMessage(val role: String, val content: String)

/** Local is an explicit command assistant, not a downloaded/generative language model. */
enum class AiMode { LOCAL, ONLINE, GAPGPT_DIRECT, OPENROUTER, GEMINI, GROQ, CLOUDFLARE, CEREBRAS, HUGGING_FACE, AVALAI }

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
)

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatEntry>,
    val mode: AiMode = AiMode.ONLINE,
    /** Legacy histories without a scope are view-only, never silently reused with a new key. */
    val accountScope: String = "",
)

object AiLimits {
    /** Longest message the user can send (the backend enforces its own, larger limit). */
    const val MAX_USER_CHARS = 1000
    /** Recent chat entries sent as context; older ones are dropped, never the whole history. */
    const val MAX_HISTORY_ENTRIES = 10
    const val MAX_HISTORY_CHARS = 6000
    const val MAX_TOOL_RESULT_CHARS = 3000
    const val MAX_AGENT_STEPS = 5
    const val MAX_STORED_CONVERSATIONS = 50
    const val MAX_STORED_MESSAGES = 200
    const val MAX_OUTPUT_TOKENS = 700
}
