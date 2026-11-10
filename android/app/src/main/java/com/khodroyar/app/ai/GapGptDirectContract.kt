package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** GapGPT's documented OpenAI Chat Completions contract; NOT the KhodroYar proxy contract. */
object GapGptDirectContract {
    const val PRIMARY_BASE_URL = "https://api.gapgpt.app/v1"
    const val CDN_BASE_URL = "https://api.gapapi.com/v1"
    const val DEFAULT_MODEL = "gpt-4o"
    private val hosts = setOf("api.gapgpt.app", "api.gapapi.com")

    /** Explicit allow-list: never send a user's provider key to an arbitrary pasted URL. */
    fun normalizeBaseUrl(input: String): String? {
        val text = input.trim()
        if (text.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(text)
            val host = uri.host?.lowercase() ?: return null
            if (!uri.scheme.equals("https", true) || host !in hosts || uri.port !in setOf(-1, 443) ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
            ) return null
            if (uri.rawPath.orEmpty().trimEnd('/') !in setOf("", "/v1", "/v1/chat/completions", "/v1/models")) return null
            "https://$host/v1"
        } catch (_: Exception) {
            null
        }
    }

    /** Also detects a provider URL mistakenly entered as a KhodroYar backend URL. */
    fun isProviderHost(input: String): Boolean = try {
        URI(input.trim()).host?.lowercase() in hosts
    } catch (_: Exception) {
        false
    }

    fun chatUrl(input: String): String? = normalizeBaseUrl(input)?.let { "$it/chat/completions" }

    fun normalizeApiKey(raw: String): String? {
        val text = raw.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
        if (text.length !in 1..2048 || text.any { it.code !in 33..126 }) return null
        if (text.uppercase() in setOf("YOUR_GAPGPT_API_KEY", "YOUR_API_KEY", "GAPGPT_API_KEY", "REPLACE_WITH_YOUR_KEY")) return null
        return text
    }

    fun selectedModel(s: AiSettings): String = s.gapGptModel.trim().ifBlank { DEFAULT_MODEL }

    fun chatRequest(baseUrl: String, key: String, messages: List<AiMessage>, model: String, maxTokens: Int): AiHttpRequest {
        val url = requireNotNull(chatUrl(baseUrl)) { "Invalid direct GapGPT URL" }
        val apiKey = requireNotNull(normalizeApiKey(key)) { "Invalid provider key" }
        require(model.isNotBlank() && model.length <= 100 && model.none { it.isISOControl() })
        require(messages.isNotEmpty())
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().also { array ->
                messages.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
            .put("max_tokens", maxTokens.coerceIn(1, AiLimits.MAX_OUTPUT_TOKENS))
            .put("temperature", 0.2)
            .put("stream", false)
        // No proxy-only `json` field, no /v1/status, no forced response_format (model compatibility).
        return AiHttpRequest(url, "POST", mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
        ), body.toString())
    }

    fun chatResponse(response: AiHttpResponse, requestedModel: String): AiResult {
        if (response.status !in 200..299) return error(response, requestedModel)
        return try {
            val body = JSONObject(response.body)
            val choice = body.optJSONArray("choices")?.optJSONObject(0)
            val content = choice?.optJSONObject("message")?.opt("content") as? String
            if (content.isNullOrBlank()) {
                AiResult.Failure(AiErrorKind.SERVER, "پاسخ GapGPT فاقد choices[0].message.content است؛ پاسخ خالی یا با Chat Completions ناسازگار است.")
            } else {
                AiResult.Success(content, body.optString("model", requestedModel))
            }
        } catch (_: Exception) {
            AiResult.Failure(AiErrorKind.SERVER, "GapGPT به‌جای JSON معتبر Chat Completions پاسخ دیگری فرستاد. آدرس و وضعیت سرویس را بررسی کنید.")
        }
    }

    private fun error(response: AiHttpResponse, requestedModel: String): AiResult.Failure {
        val error = try { JSONObject(response.body).optJSONObject("error") } catch (_: Exception) { null }
        val code = error?.optString("code", "").orEmpty().lowercase()
        val safeModel = requestedModel.filter { it.isLetterOrDigit() || it in "-._/:" }.take(100)
        val retry = response.retryAfter?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        // Never echo raw provider errors: they can contain request text, headers or secrets.
        return when {
            code in setOf("model_not_found", "model_unavailable", "model_not_available", "model_not_allowed") ->
                AiResult.Failure(AiErrorKind.BAD_REQUEST, "مدل «$safeModel» در حساب GapGPT در دسترس نیست. نام مدل و دسترسی حساب را بررسی کنید؛ انتخاب نام مدل به‌تنهایی دسترسی ایجاد نمی‌کند.")
            response.status == 404 -> AiResult.Failure(
                AiErrorKind.NOT_FOUND,
                "GapGPT برای مسیر رسمی /v1/chat/completions پاسخ ۴۰۴ داد. نام مدل «$safeModel»، دسترسی حساب و وضعیت سرویس را بررسی کنید. می‌توانید در تنظیمات، آدرس اصلی یا CDN خارجی معرفی‌شده را خودتان انتخاب کنید.",
            )
            response.status == 401 || response.status == 403 -> AiResult.Failure(AiErrorKind.UNAUTHORIZED, "کلید GapGPT نامعتبر است یا مجوز این درخواست را ندارد. کلید واقعی حساب را در فیلد API Key وارد کنید، نه YOUR_GAPGPT_API_KEY و نه توکن بک‌اند.")
            response.status == 402 || code in setOf("insufficient_quota", "insufficient_balance", "credit_exhausted") ->
                AiResult.Failure(AiErrorKind.CREDIT_EXHAUSTED, "اعتبار حساب GapGPT کافی نیست. سرویس آنلاین به کلید معتبر و اعتبار نیاز دارد؛ حالت داخلی بدون API همچنان قابل استفاده است.")
            response.status == 429 -> AiResult.Failure(AiErrorKind.RATE_LIMITED, "محدودیت درخواست GapGPT فعال شده است؛ ${retry ?: 30} ثانیه دیگر تلاش کنید.", retry)
            response.status in 300..399 -> AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "این آدرس تغییرمسیر می‌دهد. برای جلوگیری از افشای کلید، Redirect دنبال نمی‌شود؛ آدرس اصلی یا CDN رسمی را در تنظیمات انتخاب کنید.")
            response.status == 400 -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "GapGPT درخواست را نپذیرفت. نام مدل و سازگاری آن با Chat Completions را بررسی کنید.")
            response.status == 504 -> AiResult.Failure(AiErrorKind.TIMEOUT, "زمان انتظار GapGPT تمام شد. دوباره تلاش کنید یا از حالت داخلی استفاده کنید.")
            response.status >= 500 -> AiResult.Failure(AiErrorKind.SERVER, "سرویس GapGPT موقتاً خطا داد (کد ${response.status}).")
            else -> AiResult.Failure(AiErrorKind.UNKNOWN, "درخواست مستقیم GapGPT ناموفق بود (کد ${response.status}).")
        }
    }
}
