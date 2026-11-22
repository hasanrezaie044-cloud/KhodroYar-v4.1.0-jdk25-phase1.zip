package com.khodroyar.app.ai

import java.time.Instant

/** A documented free quota is not zero-priced inference on a paid account. Never conflate the two. */
enum class AICostEvidence { ZERO_PRICE, FREE_QUOTA, UNKNOWN, PAYMENT_REQUIRED, ACCOUNT_QUOTA }
enum class AIUseRegion { UNKNOWN, IRAN, SUPPORTED_ELSEWHERE }
data class AIProviderDescriptor(val mode: AiMode, val id: String, val title: String, val cost: AICostEvidence,
    val iran: String, val conditions: String, val sources: List<String>, val adapterImplemented: Boolean = true)
object AIProviderRegistry {
    // Explicit policy snapshot: live model discovery alone cannot establish a provider's billing plan.
    const val VERIFIED_ON = "2026-11-10"
    val POLICY_EXPIRES_AT: Long = Instant.parse("2026-12-10T00:00:00Z").toEpochMilli()
    /** Exactly three assistant systems: the deterministic offline assistant, OpenRouter and Groq. */
    val supportedModes = listOf(AiMode.LOCAL, AiMode.OPENROUTER, AiMode.GROQ)
    val providers = listOf(
        AIProviderDescriptor(AiMode.OPENROUTER, "openrouter", "OpenRouter", AICostEvidence.ZERO_PRICE,
            "دسترسی ایران/حساب تضمین نشده؛ پاسخ سرویس ملاک است، نه VPN یا دورزدن محدودیت.",
            "فقط همهٔ قیمت‌های دقیقاً صفر + فهرست مجاز کلید + endpoint تازه؛ سهمیه محدود و نامعلوم تا اعلام معتبر سرویس.",
            listOf("https://openrouter.ai/docs/api_reference/models", "https://openrouter.ai/docs/guides/routing/provider-selection")),
        AIProviderDescriptor(AiMode.GROQ, "groq", "Groq", AICostEvidence.FREE_QUOTA,
            "محدودیت صادرات/تحریم و حساب دارد؛ مجازبودن استفاده در ایران تأیید نشده و در برنامه مسدود است.",
            "فقط حساب Free بدون روش پرداخت؛ Developer پولی است. سقف نمونهٔ مستند GPT-OSS: ۳۰ RPM، ۱۰۰۰ RPD، ۸۰۰۰ TPM و ۲۰۰۰۰۰ TPD؛ حساب ممکن است متفاوت باشد. باقی‌مانده فقط از هدر معتبر همان پاسخ نمایش داده می‌شود.",
            listOf("https://console.groq.com/docs/billing-faqs", "https://console.groq.com/docs/rate-limits", "https://console.groq.com/docs/legal/services-agreement")),
        AIProviderDescriptor(AiMode.LOCAL, "local", "دستیار آفلاین", AICostEvidence.ZERO_PRICE,
            "بدون شبکه", "روی همین دستگاه", emptyList()),
    )
    fun get(mode: AiMode): AIProviderDescriptor = providers.firstOrNull { it.mode == mode } ?: providers.first()
    fun normalizeKey(mode: AiMode, raw: String): String? {
        val key = raw.trim()
        if (key.length !in 24..512 || key.any { it.isWhitespace() || it.isISOControl() || it == '"' || it == '\\' }) return null
        return when (mode) {
            AiMode.OPENROUTER -> OpenRouterFreePolicy.normalizeKey(key)
            AiMode.GROQ -> key.takeIf { Regex("gsk_[A-Za-z0-9_-]{20,}").matches(it) }
            AiMode.LOCAL -> null
        }
    }
    fun barrier(settings: AiSettings, now: Long): String? {
        val p = get(settings.mode)
        if (settings.mode !in supportedModes) return "این اتصال دیگر پشتیبانی نمی‌شود؛ اتصال را انتخاب کنید."
        if (!p.adapterImplemented) return p.conditions
        if (settings.mode in setOf(AiMode.OPENROUTER, AiMode.LOCAL)) return null
        if (now >= POLICY_EXPIRES_AT) return "شواهد قیمت/شرایط این اتصال منقضی شده؛ بدون به‌روزرسانی تأییدشده درخواست نمی‌شود."
        if (settings.useRegion != AIUseRegion.SUPPORTED_ELSEWHERE) return p.iran + " محل استفادهٔ مجاز را صریحاً در تنظیمات مشخص کنید؛ دورزدن محدودیت نداریم."
        if (p.id !in settings.freeAccountConfirmed) return "طرح Free و نداشتن billing/روش پرداخت این حساب هنوز توسط شما تأیید نشده است. برنامه نمی‌تواند طرح حساب را از کلید حدس بزند."
        return null
    }
    /** Known official IDs are intersected with the live, authenticated model list. No suffix guessing or auto substitution. */
    val groqModels = setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b")
    /** Groq's /models list has no modality field; these IDs are documented as text+image input (console.groq.com/docs/vision). */
    val groqVisionModels = setOf("qwen/qwen3.8-27b")
    fun permittedEndpoint(request: AiHttpRequest): Boolean {
        val u = request.url
        if (request.method == "GET" && request.body != null || request.method == "POST" && request.body == null) return false
        return when (u) {
            "https://api.groq.com/openai/v1/models" -> request.method == "GET"
            "https://api.groq.com/openai/v1/chat/completions" -> request.method == "POST"
            else -> false
        }
    }
}
