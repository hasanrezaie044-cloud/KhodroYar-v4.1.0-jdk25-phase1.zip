package com.khodroyar.app.ai

/** An explicit protocol choice, never a guess based on response shape or a hidden fallback. */
class AiProviderRouter(
    private val mode: () -> AiMode,
    private val backend: AIProvider,
    private val direct: AIProvider,
) : AIProvider {
    override val name: String get() = when (mode()) {
        AiMode.LOCAL -> "Local — no network"
        AiMode.ONLINE -> backend.name
        AiMode.GAPGPT_DIRECT -> direct.name
    }

    override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult = when (mode()) {
        AiMode.LOCAL -> AiResult.Failure(AiErrorKind.DISABLED, "حالت داخلی هیچ درخواست آنلاین نمی‌فرستد.")
        AiMode.ONLINE -> backend.chat(messages, model, maxTokens)
        AiMode.GAPGPT_DIRECT -> direct.chat(messages, model, maxTokens)
    }

    override suspend fun status(): AiProviderStatus = when (mode()) {
        AiMode.LOCAL -> AiProviderStatus(false, message = "حالت داخلی به اتصال آنلاین نیاز ندارد.", errorKind = AiErrorKind.DISABLED)
        AiMode.ONLINE -> backend.status()
        AiMode.GAPGPT_DIRECT -> direct.status()
    }
}
