package com.khodroyar.app.ai

/**
 * Compatibility shell for retired JSON-in-prose providers. Production uses PersianToolAgent's
 * native, identity-checked calls and single-use approval tickets. Old paths cannot send, execute
 * a stale ticket, loop, truncate chat or enforce their former token/history/round allowances.
 */
@Suppress("UNUSED_PARAMETER")
class AgentOrchestrator(provider: AIProvider, executor: AgentToolRunner, settings: AiSettingsStore) {
    suspend fun run(history: List<ChatEntry>): AgentStep = AgentStep.Failure(AiResult.Failure(
        AiErrorKind.DISABLED, "این مسیر قدیمی غیرفعال است؛ اتصال پشتیبانی‌شده را انتخاب کنید."))
    suspend fun execute(pending: PendingAction): ToolOutcome = ToolOutcome(false,
        "پیش‌نمایش مسیر قدیمی معتبر نیست؛ درخواست و تأیید تازه لازم است.")
}
