package com.khodroyar.app.ai

/** Compatibility guard only. No native provider or retired preference may route into a legacy proxy. */
@Suppress("UNUSED_PARAMETER")
class AiProviderRouter(mode: () -> AiMode, backend: AIProvider, direct: AIProvider) : AIProvider {
    override val name: String = "Native providers only — no legacy fallback"
    override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult =
        AiResult.Failure(AiErrorKind.DISABLED, "این مسیر قدیمی غیرفعال است؛ از اتصال انتخابی استفاده کنید.")
    override suspend fun status() = AiProviderStatus(false, errorKind = AiErrorKind.DISABLED,
        message = "از تنظیمات اتصال پشتیبانی‌شده استفاده کنید.")
}
