package com.khodroyar.app.ai

/** An explicit protocol choice, never a guess based on response shape or a hidden fallback. */
class AiProviderRouter(
    private val mode: () -> AiMode,
    private val backend: AIProvider,
    private val direct: AIProvider,
) : AIProvider {
    override val name: String get() = when (mode()) {
        AiMode.OPENROUTER -> "OpenRouter — native tool protocol only"
        AiMode.LOCAL -> "Local — no network"
        AiMode.ONLINE -> backend.name
        AiMode.GAPGPT_DIRECT -> direct.name
        else -> "Selected native provider — no legacy fallback"
    }

    override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult = when (mode()) {
        AiMode.OPENROUTER -> AiResult.Failure(AiErrorKind.DISABLED, "OpenRouter فقط از پروتکل استاندارد ابزار استفاده می‌کند؛ مسیر قدیمی یا مدل پولی جایگزین نمی‌شود." )
        AiMode.LOCAL -> AiResult.Failure(AiErrorKind.DISABLED, "حالت داخلی هیچ درخواست آنلاین نمی‌فرستد.")
        AiMode.ONLINE -> backend.chat(messages, model, maxTokens)
        AiMode.GAPGPT_DIRECT -> direct.chat(messages, model, maxTokens)
        else -> AiResult.Failure(AiErrorKind.DISABLED, "این اتصال فقط مسیر native دارد؛ fallback قدیمی مجاز نیست.")
    }

    override suspend fun status(): AiProviderStatus = when (mode()) {
        AiMode.OPENROUTER -> AiProviderStatus(false, message = "از تست اتصال OpenRouter استفاده کنید.", errorKind = AiErrorKind.DISABLED)
        AiMode.LOCAL -> AiProviderStatus(false, message = "حالت داخلی به اتصال آنلاین نیاز ندارد.", errorKind = AiErrorKind.DISABLED)
        AiMode.ONLINE -> backend.status()
        AiMode.GAPGPT_DIRECT -> direct.status()
        else -> AiProviderStatus(false, message = "از تنظیمات اتصال انتخابی استفاده کنید.", errorKind = AiErrorKind.DISABLED)
    }
}
