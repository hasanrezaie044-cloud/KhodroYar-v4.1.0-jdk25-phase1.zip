package com.khodroyar.app.ai

import java.time.Instant

/** A documented free quota is not zero-priced inference on a paid account. Never conflate the two. */
enum class AICostEvidence { ZERO_PRICE, FREE_QUOTA, UNKNOWN, PAYMENT_REQUIRED }
enum class AIUseRegion { UNKNOWN, IRAN, SUPPORTED_ELSEWHERE }
data class AIProviderDescriptor(val mode: AiMode, val id: String, val title: String, val cost: AICostEvidence,
    val iran: String, val conditions: String, val sources: List<String>, val adapterImplemented: Boolean = true)
object AIProviderRegistry {
    // Explicit policy snapshot: live model discovery alone cannot establish a provider's billing plan.
    const val VERIFIED_ON = "2026-11-10"
    val POLICY_EXPIRES_AT: Long = Instant.parse("2026-12-10T00:00:00Z").toEpochMilli()
    val providers = listOf(
        AIProviderDescriptor(AiMode.OPENROUTER, "openrouter", "OpenRouter", AICostEvidence.ZERO_PRICE,
            "دسترسی ایران/حساب تضمین نشده؛ پاسخ سرویس ملاک است، نه VPN یا دورزدن محدودیت.",
            "فقط همهٔ قیمت‌های دقیقاً صفر + فهرست مجاز کلید + endpoint تازه؛ سهمیه محدود و نامعلوم تا اعلام معتبر سرویس.",
            listOf("https://openrouter.ai/docs/api_reference/models", "https://openrouter.ai/docs/guides/routing/provider-selection")),
        AIProviderDescriptor(AiMode.GEMINI, "gemini", "Google Gemini", AICostEvidence.FREE_QUOTA,
            "ایران در فهرست رسمی مناطق مجاز Gemini API نیست؛ در حالت ایران ارسال مسدود است.",
            "فقط پروژهٔ Free بدون billing و مدلِ دارای Free tier؛ تعداد دقیق سهمیه وابسته به پروژه است. دادهٔ Free ممکن است برای بهبود محصولات استفاده شود؛ ابزارهای دادهٔ برنامه در این اتصال غیرفعال‌اند.",
            listOf("https://ai.google.dev/gemini-api/docs/pricing", "https://ai.google.dev/gemini-api/docs/available-regions", "https://ai.google.dev/gemini-api/docs/rate-limits")),
        AIProviderDescriptor(AiMode.GROQ, "groq", "Groq", AICostEvidence.FREE_QUOTA,
            "محدودیت صادرات/تحریم و حساب دارد؛ مجازبودن استفاده در ایران تأیید نشده و در برنامه مسدود است.",
            "فقط حساب Free بدون روش پرداخت؛ Developer پولی است. سقف نمونهٔ مستند GPT-OSS: ۳۰ RPM، ۱۰۰۰ RPD، ۸۰۰۰ TPM و ۲۰۰۰۰۰ TPD؛ حساب ممکن است متفاوت باشد. باقی‌مانده فقط از هدر معتبر همان پاسخ نمایش داده می‌شود.",
            listOf("https://console.groq.com/docs/billing-faqs", "https://console.groq.com/docs/rate-limits", "https://console.groq.com/docs/legal/services-agreement")),
        AIProviderDescriptor(AiMode.CLOUDFLARE, "cloudflare", "Cloudflare Workers AI", AICostEvidence.FREE_QUOTA,
            "دسترسی و مجوز ایران تأیید نشده؛ در حالت ایران ارسال مسدود است.",
            "Workers Free: روزانه ۱۰۰۰۰ Neuron، نه ۱۰۰۰۰ توکن. Workers Paid اضافه‌مصرف پولی دارد. فقط پس از اثبات Workers Free از API اشتراک؛ وضعیت مجهول یا Paid مسدود است.",
            listOf("https://developers.cloudflare.com/workers-ai/platform/pricing/", "https://developers.cloudflare.com/workers-ai/configuration/open-ai-compatibility/", "https://www.cloudflare.com/terms/")),
        AIProviderDescriptor(AiMode.CEREBRAS, "cerebras", "Cerebras", AICostEvidence.PAYMENT_REQUIRED,
            "دسترسی ایران تأیید نشده.", "طبق مستندات فعلی، فعال‌شدن API/اعتبار آزمایشی به روش پرداخت معتبر نیاز دارد؛ تحت سیاست بدون پرداخت برنامه غیرفعال است.",
            listOf("https://inference-docs.cerebras.ai/support/rate-limits"), false),
        AIProviderDescriptor(AiMode.HUGGING_FACE, "huggingface", "Hugging Face Inference", AICostEvidence.PAYMENT_REQUIRED,
            "دسترسی ایران/ارائه‌دهندگان زیرمجموعه تضمین نشده.", "مستندات فعلی برای Free اعتبار ماهانه اعلام نمی‌کند؛ خرید اعتبار یا PRO لازم است. رایگان معرفی یا خودکار استفاده نمی‌شود.",
            listOf("https://huggingface.co/docs/inference-providers/pricing"), false),
        AIProviderDescriptor(AiMode.AVALAI, "avalai", "ایرانی — AvalAI / GapGPT", AICostEvidence.UNKNOWN,
            "API ایرانی معرفی شده، اما رایگان‌بودن و کیفیت/دسترسی زنده در این پروژه تأیید نشده است.",
            "AvalAI API سازگار و قیمت‌گذاری دارد؛ طرح رایگان بدون کارت/پرداختِ قابل اثبات پیدا نشد. اتصال قدیمی GapGPT حفظ شده اما مسیر پولی فعال یا fallback نیست.",
            listOf("https://docs.avalai.ir/fa/", "https://gapgpt.app/"), false),
    )
    fun get(mode: AiMode): AIProviderDescriptor = providers.firstOrNull { it.mode == mode } ?: providers.first()
    fun normalizeKey(mode: AiMode, raw: String): String? {
        val key = raw.trim()
        if (key.length !in 24..512 || key.any { it.isWhitespace() || it.isISOControl() || it == '"' || it == '\\' }) return null
        return when (mode) {
            AiMode.OPENROUTER -> OpenRouterFreePolicy.normalizeKey(key)
            AiMode.GEMINI -> key.takeIf { Regex("AIza[A-Za-z0-9_-]{20,}").matches(it) }
            AiMode.GROQ -> key.takeIf { Regex("gsk_[A-Za-z0-9_-]{20,}").matches(it) }
            else -> key.takeIf { Regex("[A-Za-z0-9_-]{24,512}").matches(it) }
        }
    }
    fun barrier(settings: AiSettings, now: Long): String? {
        val p = get(settings.mode)
        if (!p.adapterImplemented) return p.conditions
        if (settings.mode == AiMode.OPENROUTER) return null
        if (now >= POLICY_EXPIRES_AT) return "شواهد قیمت/شرایط این اتصال منقضی شده؛ بدون به‌روزرسانی تأییدشده درخواست نمی‌شود."
        if (settings.useRegion != AIUseRegion.SUPPORTED_ELSEWHERE) return p.iran + " محل استفادهٔ مجاز را صریحاً در تنظیمات مشخص کنید؛ دورزدن محدودیت نداریم."
        if (p.id !in settings.freeAccountConfirmed) return "طرح Free و نداشتن billing/روش پرداخت این حساب هنوز توسط شما تأیید نشده است. برنامه نمی‌تواند طرح حساب را از کلید حدس بزند."
        if (settings.mode == AiMode.CLOUDFLARE && !Regex("[a-fA-F0-9]{32}").matches(settings.cloudflareAccountId)) return "شناسهٔ معتبر حساب Cloudflare لازم است."
        return null
    }
    /** Known official IDs are intersected with the live, authenticated model list. No suffix guessing or auto substitution. */
    val groqModels = setOf("openai/gpt-oss-20b", "openai/gpt-oss-120b", "qwen/qwen3.8-27b")
    val geminiModels = setOf("gemini-2.5-flash-lite", "gemini-3.5-flash-lite", "gemini-3.8-flash")
    val cloudflareModels = setOf("@cf/meta/llama-3.3-70b-instruct-fp8-fast")
    fun permittedEndpoint(request: AiHttpRequest): Boolean {
        val u = request.url
        if (request.method == "GET" && request.body != null || request.method == "POST" && request.body == null) return false
        return when {
            u == "https://api.groq.com/openai/v1/models" -> request.method == "GET"
            u == "https://api.groq.com/openai/v1/chat/completions" -> request.method == "POST"
            u == "https://generativelanguage.googleapis.com/v1beta/models" -> request.method == "GET"
            u.startsWith("https://generativelanguage.googleapis.com/v1beta/models/") -> request.method == "POST" &&
                geminiModels.any { u == "https://generativelanguage.googleapis.com/v1beta/models/$it:generateContent" || u == "https://generativelanguage.googleapis.com/v1beta/models/$it:streamGenerateContent?alt=sse" }
            else -> Regex("https://api\\.cloudflare\\.com/client/v4/accounts/[a-fA-F0-9]{32}/(subscriptions|ai/models/search|ai/v1/chat/completions)").matches(u) &&
                request.method == if (u.endsWith("/chat/completions")) "POST" else "GET"
        }
    }
}
