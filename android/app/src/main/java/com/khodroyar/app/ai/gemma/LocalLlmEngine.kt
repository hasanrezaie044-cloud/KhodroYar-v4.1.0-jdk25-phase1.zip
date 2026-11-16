package com.khodroyar.app.ai.gemma

/**
 * Contract between the local-agent logic and whatever runs the model on-device.
 * Pure JVM so tests can substitute a scripted fake; the production implementation is
 * [LiteRtLmEngine] (LiteRT-LM runtime, Gemma 4 E2B .litertlm).
 */
interface LocalLlmEngine {

    data class Turn(val role: String, val text: String) {
        companion object {
            const val USER = "user"
            const val ASSISTANT = "assistant"
        }
    }

    /**
     * @param imageBytes JPEG/PNG bytes of the user's attached image; only ever sent WITH
     *   the last user turn. Vision content always comes BEFORE text in the content list
     *   (LiteRT-LM's official order).
     */
    data class Request(
        val systemPrompt: String,
        val history: List<Turn>,
        val userText: String,
        val imageBytes: ByteArray? = null,
        val maxOutputTokens: Int,
        val disableThinking: Boolean,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Request && systemPrompt == other.systemPrompt && history == other.history &&
                userText == other.userText && imageBytes.contentEquals(other.imageBytes) &&
                maxOutputTokens == other.maxOutputTokens

        override fun hashCode(): Int = userText.hashCode()
    }

    sealed interface Reply {
        data class Text(val text: String) : Reply
        data class Failure(val error: EngineError, val detail: String) : Reply
    }

    enum class EngineError {
        /** Model file missing / not verified. */ NOT_INSTALLED,
        /** Device reported low memory or not enough free RAM to load the model. */ OUT_OF_MEMORY,
        /** Engine busy or pool busy; try again. */ BUSY,
        /** Model output stream cancelled (by user). */ CANCELLED,
        /** Initialization failed (incompatible model, missing backend...). */ INIT_FAILED,
        /** Anything else during inference. */ FAILED,
    }

    suspend fun complete(request: Request): Reply

    /** Whether the runtime currently holds the model in memory. */
    fun isLoaded(): Boolean
}
