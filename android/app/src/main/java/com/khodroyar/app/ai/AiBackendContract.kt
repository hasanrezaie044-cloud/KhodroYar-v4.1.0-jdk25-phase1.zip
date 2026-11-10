package com.khodroyar.app.ai

import org.json.JSONObject
import java.net.URI

/** Pure JVM contract: one canonical backend root and actionable, testable HTTP errors. */
object AiBackendContract {
    private val suffixes = listOf(
        "/v1/chat/completions", "/v1/chat", "/v1/status", "/v1/models", "/health", "/v1",
    )

    /**
     * Accept the backend root, its /v1 URL, or one of its full endpoints. Keep reverse-proxy
     * prefixes (e.g. /assistant). Never turn an invalid/insecure URL into a network request.
     */
    fun normalizeRoot(input: String): String? {
        val text = input.trim()
        if (text.any { it.isWhitespace() || it.isISOControl() }) return null
        return try {
            val uri = URI(text)
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.port !in -1..65535 || uri.port == 0
            ) return null
            var path = uri.rawPath.orEmpty().trimEnd('/')
            // Reject dot segments and encoded separators rather than changing where a token is sent.
            if (path.split('/').any { it == "." || it == ".." } ||
                Regex("%2f|%5c|%2e", RegexOption.IGNORE_CASE).containsMatchIn(path)
            ) return null
            val suffix = suffixes.firstOrNull { path.endsWith(it) }
            if (suffix != null) path = path.removeSuffix(suffix).trimEnd('/')
            "https://${uri.rawAuthority}$path"
        } catch (_: Exception) {
            null
        }
    }

    fun endpoint(input: String, resource: String): String? {
        require(resource in setOf("chat", "status", "models"))
        return normalizeRoot(input)?.let { "$it/v1/$resource" }
    }

    fun chatResponse(code: Int, text: String, retryHeader: String? = null): AiResult {
        if (code !in 200..299) return error(code, text, retryHeader)
        return try {
            val body = JSONObject(text)
            val content = body.opt("content") as? String
            if (content.isNullOrBlank()) {
                AiResult.Failure(AiErrorKind.SERVER, "پاسخ با قرارداد بک‌اند خودرویار سازگار نیست. آدرس باید سرور خودرویار باشد، نه آدرس مستقیم سرویس مدل.")
            } else {
                AiResult.Success(content, body.optString("model", ""))
            }
        } catch (_: Exception) {
            AiResult.Failure(AiErrorKind.SERVER, "سرور به‌جای پاسخ دستیار، پاسخ نامعتبر فرستاد. آدرس بک‌اند خودرویار را بررسی کنید.")
        }
    }

    fun statusResponse(code: Int, text: String): AiProviderStatus {
        if (code !in 200..299) {
            val failure = error(code, text)
            return AiProviderStatus(reachable = true, message = failure.message, errorKind = failure.kind)
        }
        return try {
            val body = JSONObject(text)
            if (body.opt("enabled") !is Boolean || body.opt("configured") !is Boolean) {
                return AiProviderStatus(true, message = "این آدرس، بک‌اند سازگار خودرویار نیست. برای استفاده بدون سرور، حالت داخلی را انتخاب کنید.", errorKind = AiErrorKind.SERVER)
            }
            AiProviderStatus(
                reachable = true,
                enabled = body.getBoolean("enabled"),
                configured = body.getBoolean("configured"),
                defaultModel = body.optString("defaultModel", ""),
            )
        } catch (_: Exception) {
            AiProviderStatus(true, message = "پاسخ این آدرس، JSON معتبرِ بک‌اند خودرویار نیست.", errorKind = AiErrorKind.SERVER)
        }
    }

    fun error(code: Int, text: String, retryHeader: String? = null): AiResult.Failure {
        val err = try { JSONObject(text).optJSONObject("error") } catch (_: Exception) { null }
        val name = err?.optString("code", "").orEmpty()
        val retry = retryHeader?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: err?.optInt("retryAfterSec", 0)?.takeIf { it > 0 }
        return when {
            code == 404 || name == "not_found" -> AiResult.Failure(
                AiErrorKind.NOT_FOUND,
                "مسیر دستیار روی این آدرس پیدا نشد (۴۰۴). آدرس باید بک‌اند خودرویار باشد، نه صفحهٔ سایت یا آدرس مستقیم GapGPT. آدرس ریشهٔ سرور را بررسی کنید یا حالت «داخلی — بدون API» را انتخاب کنید.",
            )
            name == "upstream_not_found" -> AiResult.Failure(
                AiErrorKind.NOT_FOUND,
                "بک‌اند وصل است ولی مسیر سرویس مدل یا نام مدل پیدا نشد (۴۰۴ سرویس مدل). مدیر سرور باید GAPGPT_BASE_URL و نام مدل را بررسی کند. حالت داخلی به سرور نیاز ندارد.",
            )
            name == "assistant_disabled" -> AiResult.Failure(AiErrorKind.DISABLED, "دستیار آنلاین از سمت سرور غیرفعال شده است. می‌توانید از حالت داخلی استفاده کنید.")
            name == "not_configured" -> AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "کلید سرویس آنلاین در سرور تنظیم نشده است. برای استفاده بدون کلید، حالت داخلی را انتخاب کنید.")
            code == 401 || code == 403 || name == "unauthorized" -> AiResult.Failure(AiErrorKind.UNAUTHORIZED, "دسترسی به سرور رد شد. توکن برنامه را در تنظیمات دستیار بررسی کنید.")
            name == "provider_auth_failed" -> AiResult.Failure(AiErrorKind.UNAUTHORIZED, "کلید سرویس مدل روی سرور نامعتبر است. حالت داخلی بدون کلید کار می‌کند.")
            code == 402 || name in setOf("credit_exhausted", "budget_exceeded") -> AiResult.Failure(AiErrorKind.CREDIT_EXHAUSTED, "اعتبار یا سقف مصرف سرویس آنلاین تمام شده است. می‌توانید از حالت داخلی استفاده کنید.")
            code == 429 -> AiResult.Failure(AiErrorKind.RATE_LIMITED, "تعداد درخواست‌ها زیاد است. ${retry ?: 30} ثانیه دیگر تلاش کنید یا از حالت داخلی استفاده کنید.", retry)
            name in setOf("model_unavailable", "model_not_allowed") -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "مدل انتخاب‌شده در دسترس نیست. نام مدل را در تنظیمات بررسی کنید.")
            code == 413 || name in setOf("message_too_long", "conversation_too_long", "too_many_messages") -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "پیام یا مکالمه بیش از حد طولانی است. مکالمهٔ جدید شروع کنید یا پیام را کوتاه‌تر کنید.")
            code == 400 -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "درخواست نامعتبر بود. تنظیمات سرور و مدل را بررسی کنید.")
            code == 504 || name == "upstream_timeout" -> AiResult.Failure(AiErrorKind.TIMEOUT, "زمان انتظار سرویس آنلاین تمام شد. دوباره تلاش کنید یا از حالت داخلی استفاده کنید.")
            code >= 500 -> AiResult.Failure(AiErrorKind.SERVER, "سرویس آنلاین موقتاً در دسترس نیست (کد $code). می‌توانید از حالت داخلی استفاده کنید.")
            else -> AiResult.Failure(AiErrorKind.UNKNOWN, "ارتباط با دستیار آنلاین ناموفق بود (کد $code)." )
        }
    }
}
