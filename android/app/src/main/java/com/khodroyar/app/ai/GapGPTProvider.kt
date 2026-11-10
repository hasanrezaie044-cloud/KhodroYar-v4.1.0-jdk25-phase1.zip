package com.khodroyar.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Talks ONLY to the owner's own backend (which holds the GapGPT key). The Android app never
 * sees, stores or sends a GapGPT key. Base URL, model and token come from [AiSettingsStore].
 */
class GapGPTProvider(
    private val context: Context,
    private val settings: AiSettingsStore,
) : AIProvider {

    override val name: String = "GapGPT (via KhodroYar backend)"

    private fun online(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun base(): String? {
        val s = settings.load()
        return if (AiSettingsStore.isValidBackendUrl(s.backendUrl)) s.backendUrl.trim().trimEnd('/') else null
    }

    private fun connect(url: String, method: String): HttpURLConnection {
        val s = settings.load()
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Client-Id", settings.installId())
            if (s.clientToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${s.clientToken}")
        }
    }

    override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult =
        withContext(Dispatchers.IO) {
            val root = base() ?: return@withContext AiResult.Failure(
                AiErrorKind.NOT_CONFIGURED, "آدرس سرور دستیار تنظیم نشده است. از تنظیمات دستیار آن را وارد کنید.",
            )
            if (!online()) {
                return@withContext AiResult.Failure(AiErrorKind.OFFLINE, "اتصال اینترنت برقرار نیست. اینترنت را بررسی و دوباره تلاش کنید.")
            }
            val payload = JSONObject().apply {
                put("messages", JSONArray().also { arr ->
                    messages.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content)) }
                })
                if (!model.isNullOrBlank()) put("model", model)
                if (maxTokens != null) put("max_tokens", maxTokens)
                put("temperature", 0.2)
                put("json", true)
            }
            val conn = try {
                connect("$root/v1/chat", "POST").apply {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            } catch (e: Exception) {
                return@withContext AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "آدرس سرور دستیار نامعتبر است.")
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code in 200..299) parseSuccess(text) else parseError(code, text, conn.getHeaderField("Retry-After"))
            } catch (e: SocketTimeoutException) {
                AiResult.Failure(AiErrorKind.TIMEOUT, "پاسخی از سرور دریافت نشد (زمان انتظار تمام شد). دوباره تلاش کنید.")
            } catch (e: UnknownHostException) {
                AiResult.Failure(AiErrorKind.OFFLINE, "به سرور دستیار دسترسی نیست. اینترنت یا آدرس سرور را بررسی کنید.")
            } catch (e: IOException) {
                AiResult.Failure(AiErrorKind.OFFLINE, "ارتباط با سرور برقرار نشد. اینترنت خود را بررسی کنید.")
            } catch (e: Exception) {
                AiResult.Failure(AiErrorKind.UNKNOWN, "خطای ناشناخته در ارتباط با دستیار.")
            } finally {
                conn.disconnect()
            }
        }

    private fun parseSuccess(text: String): AiResult = try {
        val o = JSONObject(text)
        val content = o.optString("content", "")
        if (content.isBlank()) {
            AiResult.Failure(AiErrorKind.SERVER, "پاسخ خالی از سرور دریافت شد.")
        } else {
            AiResult.Success(content, o.optString("model", ""))
        }
    } catch (e: Exception) {
        AiResult.Failure(AiErrorKind.SERVER, "پاسخ سرور قابل‌خواندن نبود.")
    }

    private fun parseError(code: Int, text: String, retryHeader: String?): AiResult {
        val err = try { JSONObject(text).optJSONObject("error") } catch (e: Exception) { null }
        val errCode = err?.optString("code", "") ?: ""
        val retry = retryHeader?.trim()?.toIntOrNull() ?: err?.optInt("retryAfterSec", 0)?.takeIf { it > 0 }
        return when {
            errCode == "assistant_disabled" || code == 503 && errCode != "not_configured" ->
                AiResult.Failure(AiErrorKind.DISABLED, "دستیار هوشمند فعلاً از سمت سرور غیرفعال شده است.")
            errCode == "not_configured" ->
                AiResult.Failure(AiErrorKind.NOT_CONFIGURED, "سرور دستیار هنوز به سرویس هوش مصنوعی متصل نشده است (کلید API در سرور تنظیم نشده).")
            code == 401 || errCode == "unauthorized" ->
                AiResult.Failure(AiErrorKind.UNAUTHORIZED, "دسترسی به سرور دستیار رد شد. توکن برنامه را در تنظیمات دستیار بررسی کنید.")
            errCode == "provider_auth_failed" ->
                AiResult.Failure(AiErrorKind.UNAUTHORIZED, "سرور دستیار اجازهٔ استفاده از سرویس هوش مصنوعی را ندارد. مدیر سرور باید کلید API را بررسی کند.")
            code == 402 || errCode == "credit_exhausted" || errCode == "budget_exceeded" ->
                AiResult.Failure(AiErrorKind.CREDIT_EXHAUSTED, "اعتبار یا سقف مصرف هوش مصنوعی تمام شده است. بعداً تلاش کنید یا اعتبار را شارژ کنید.")
            code == 429 -> AiResult.Failure(
                AiErrorKind.RATE_LIMITED,
                "تعداد درخواست‌ها زیاد است. ${retry ?: 30} ثانیه دیگر دوباره تلاش کنید.",
                retry,
            )
            errCode == "model_unavailable" || errCode == "model_not_allowed" ->
                AiResult.Failure(AiErrorKind.BAD_REQUEST, "مدل انتخاب‌شده در دسترس نیست. نام مدل را در تنظیمات دستیار بررسی کنید.")
            errCode == "message_too_long" || errCode == "conversation_too_long" || errCode == "too_many_messages" ->
                AiResult.Failure(AiErrorKind.BAD_REQUEST, "پیام یا مکالمه بیش از حد طولانی است. مکالمهٔ جدید شروع کنید یا پیام را کوتاه‌تر کنید.")
            code == 400 -> AiResult.Failure(AiErrorKind.BAD_REQUEST, "درخواست نامعتبر بود.")
            code >= 500 -> AiResult.Failure(AiErrorKind.SERVER, "خطای سرور دستیار. کمی بعد دوباره تلاش کنید.")
            else -> AiResult.Failure(AiErrorKind.UNKNOWN, "خطای ناشناخته ($code).")
        }
    }

    override suspend fun status(): AiProviderStatus = withContext(Dispatchers.IO) {
        val root = base() ?: return@withContext AiProviderStatus(false, message = "آدرس سرور دستیار تنظیم نشده است.")
        if (!online()) return@withContext AiProviderStatus(false, message = "اینترنت قطع است.")
        try {
            val conn = connect("$root/v1/status", "GET")
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code == 401) return@withContext AiProviderStatus(true, message = "توکن برنامه نامعتبر است.")
                if (code !in 200..299) return@withContext AiProviderStatus(false, message = "پاسخ سرور: $code")
                val o = JSONObject(text)
                AiProviderStatus(
                    reachable = true,
                    enabled = o.optBoolean("enabled", false),
                    configured = o.optBoolean("configured", false),
                    defaultModel = o.optString("defaultModel", ""),
                    message = "",
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            AiProviderStatus(false, message = "زمان انتظار تمام شد.")
        } catch (e: Exception) {
            AiProviderStatus(false, message = "اتصال به سرور برقرار نشد.")
        }
    }

    /** Asks the backend whether the chosen/default model really exists on the account. */
    suspend fun checkModel(): Boolean? = withContext(Dispatchers.IO) {
        val root = base() ?: return@withContext null
        try {
            val conn = connect("$root/v1/models", "GET")
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val o = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val wanted = settings.load().model.ifBlank { o.optString("defaultModel", "") }
                val arr = o.optJSONArray("models") ?: return@withContext null
                var found = false
                for (i in 0 until arr.length()) if (arr.optString(i) == wanted) found = true
                found
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
