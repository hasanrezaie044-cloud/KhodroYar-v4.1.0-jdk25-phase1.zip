package com.khodroyar.app.ai

import org.json.JSONObject
import java.math.BigDecimal

/** Only explicit free variants and the documented free-only router; never auto/paid aliases. */
data class VerifiedFreeModel(val id: String, val label: String, val contextLength: Int)

object OpenRouterFreePolicy {
    const val BASE_URL = "https://openrouter.ai/api/v1"
    const val CHAT_URL = "$BASE_URL/chat/completions"
    const val MODELS_URL = "$BASE_URL/models"
    const val INITIAL_MODEL = "openrouter/free"
    const val MIN_CONTEXT = 32768

    fun normalizeKey(raw: String): String? = raw.trim().takeIf {
        it.length in 24..512 && it.startsWith("sk-or-") && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' }
            && !it.contains("YOUR", true) && !it.contains("example", true)
    }
    fun containsCredential(text: String): Boolean = Regex("(?i)sk-(?:or-|[a-z0-9]{16})").containsMatchIn(text)
    fun redactCredentials(text: String): String = Regex("(?i)sk-(?:or-[A-Za-z0-9_-]*|[A-Za-z0-9_-]{16,})").replace(text, "[کلید پنهان شد]")
    fun isFreeId(id: String): Boolean = id == INITIAL_MODEL ||
        (id.endsWith(":free") && Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._:/-]+:free").matches(id) && !id.contains(".."))

    /** Missing, negative, malformed, non-zero, or future unknown non-zero price fields reject the model. */
    fun catalog(body: String): List<VerifiedFreeModel> {
        require(body.length <= 4 * 1024 * 1024)
        val rows = NativeJson.objectValue(body, 4 * 1024 * 1024).getJSONArray("data")
        require(rows.length() in 0..10000)
        val result = mutableListOf<VerifiedFreeModel>()
        val seen = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id")
            require(seen.add(id)) { "Duplicate model identity" }
            if (!isFreeId(id)) continue
            val price = row.optJSONObject("pricing") ?: continue
            if (price.length() > 64) continue
            if (!price.has("prompt") || !price.has("completion") || price.length() == 0) continue
            if (!price.keys().asSequence().all { field ->
                val v = price.opt(field)
                v != null && v != JSONObject.NULL && v.toString().length <= 100 && runCatching { BigDecimal(v.toString()).signum() == 0 }.getOrDefault(false)
            }) continue
            val parameters = row.optJSONArray("supported_parameters") ?: continue
            val supported = (0 until parameters.length()).map { parameters.optString(it) }.toSet()
            if (!supported.containsAll(setOf("tools", "tool_choice", "max_tokens"))) continue
            val context = row.optInt("context_length", 0)
            if (context < MIN_CONTEXT) continue
            result += VerifiedFreeModel(id, row.optString("name", id).take(150), context)
        }
        // A router alone is not proof that any usable free tool model currently exists.
        return result.filter { it.id != INITIAL_MODEL || result.any { candidate -> candidate.id != INITIAL_MODEL } }
            .sortedWith(compareBy<VerifiedFreeModel> { it.id != INITIAL_MODEL }.thenBy { it.id })
    }

    fun routing(): JSONObject = JSONObject().put("allow_fallbacks", false).put("require_parameters", true)
        .put("data_collection", "deny").put("max_price", JSONObject().put("prompt", 0).put("completion", 0).put("request", 0))

    fun checkReturnedModel(actual: String, selected: String, catalog: List<VerifiedFreeModel>) {
        require(catalog.any { it.id == actual }) { "Unverified response model" }
        require(actual == selected || selected == INITIAL_MODEL && actual != INITIAL_MODEL) { "Unexpected model substitution" }
    }

    fun error(status: Int, retryAfter: String? = null): AiResult.Failure {
        val wait = retryAfter?.toLongOrNull()?.coerceIn(1, 86400)?.toInt()
        return when (status) {
            401, 403 -> AiResult.Failure(AiErrorKind.UNAUTHORIZED, "کلید OpenRouter نامعتبر است یا اجازهٔ استفاده ندارد؛ فقط در تنظیمات جایگزینش کنید، نه در گفتگو.")
            402 -> AiResult.Failure(AiErrorKind.CREDIT_EXHAUSTED, "OpenRouter درخواست رایگان را نپذیرفت. برنامه به مدل پولی تغییر نمی‌کند و اعتبار نمی‌خرد؛ محدودیت حساب یا دسترسی مدل رایگان را بررسی کنید.")
            404 -> AiResult.Failure(AiErrorKind.NOT_FOUND, "مدل/مسیر رایگانِ سازگار در OpenRouter در دسترس نیست؛ فهرست مدل‌های رایگان را تازه کنید.")
            408, 504 -> AiResult.Failure(AiErrorKind.TIMEOUT, "پاسخ سرویس رایگان در مهلت تعیین‌شده نرسید؛ عملیات جدیدی اجرا نمی‌شود.")
            429 -> AiResult.Failure(AiErrorKind.RATE_LIMITED, "سهمیه یا ظرفیت رایگان OpenRouter فعلاً محدود است. سقف روزانه و دسترسی را OpenRouter تعیین می‌کند؛ مدل پولی جایگزین نمی‌شود.", wait ?: 60)
            400, 422 -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "مدل رایگان این درخواست/ابزار را نپذیرفت؛ مدل رایگانِ تأییدشدهٔ دیگری انتخاب کنید یا گفتگو را کوتاه کنید.")
            else -> AiResult.Failure(AiErrorKind.SERVER, "OpenRouter یا ارائه‌دهندهٔ مدل رایگان در دسترس نیست. ثبت دستی و امکانات آفلاین برنامه برقرارند؛ تغییر خودکار مدل یا تلاش نامحدود نداریم.")
        }
    }
}
