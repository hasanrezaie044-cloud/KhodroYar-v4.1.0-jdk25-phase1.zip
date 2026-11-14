package com.khodroyar.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Custom KhodroYar BACKEND mode only, not the public GapGPT/OpenAI Chat Completions API. */
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

    private fun connect(url: String, method: String, s: AiSettings): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            // Do not forward the client token/conversation to a redirected host.
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Client-Id", settings.installId())
            if (s.clientToken.isNotBlank()) setRequestProperty("Authorization", "Bearer ${s.clientToken}")
        }

    override suspend fun chat(messages: List<AiMessage>, model: String?, maxTokens: Int?): AiResult =
        withContext(Dispatchers.IO) {
            val s = settings.load()
            if (!s.enabled || s.mode != AiMode.ONLINE || !s.consented) {
                return@withContext AiResult.Failure(AiErrorKind.DISABLED, "ارسال آنلاین فعال نیست یا رضایت ارسال اطلاعات داده نشده است.")
            }
            val url = AiBackendContract.endpoint(s.backendUrl, "chat") ?: return@withContext AiResult.Failure(
                AiErrorKind.NOT_CONFIGURED, "این حالت آدرس بک‌اند خودرویار را می‌خواهد. برای api.gapgpt.app یا api.gapapi.com گزینهٔ «اتصال مستقیم GapGPT» و API Key حساب را انتخاب کنید.",
            )
            if (!online()) return@withContext AiResult.Failure(AiErrorKind.OFFLINE, "اینترنت برقرار نیست. می‌توانید از حالت داخلی بدون API استفاده کنید.")
            val payload = JSONObject().apply {
                put("messages", JSONArray().also { arr ->
                    messages.forEach { m -> arr.put(JSONObject().put("role", m.role).put("content", m.content)) }
                })
                if (!model.isNullOrBlank()) put("model", model)
                if (maxTokens != null) put("max_tokens", maxTokens)
                put("temperature", 0.2)
                put("json", true)
            }
            val conn = connect(url, "POST", s).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                AiBackendContract.chatResponse(code, text, conn.getHeaderField("Retry-After"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: SocketTimeoutException) {
                AiResult.Failure(AiErrorKind.TIMEOUT, "زمان انتظار سرور تمام شد. دوباره تلاش کنید یا از حالت داخلی استفاده کنید.")
            } catch (_: UnknownHostException) {
                AiResult.Failure(AiErrorKind.OFFLINE, "به سرور دسترسی نیست. اینترنت یا آدرس سرور را بررسی کنید؛ حالت داخلی بدون اینترنت کار می‌کند.")
            } catch (_: IOException) {
                AiResult.Failure(AiErrorKind.OFFLINE, "ارتباط با سرور برقرار نشد. می‌توانید از حالت داخلی استفاده کنید.")
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun status(): AiProviderStatus = withContext(Dispatchers.IO) {
        val s = settings.load()
        if (s.mode != AiMode.ONLINE || !s.enabled || !s.consented) {
            return@withContext AiProviderStatus(false, message = "برای آزمایش آنلاین ابتدا رضایت ارسال داده را تأیید کنید.", errorKind = AiErrorKind.DISABLED)
        }
        val url = AiBackendContract.endpoint(s.backendUrl, "status") ?: return@withContext AiProviderStatus(
            false, message = "آدرس بک‌اند خودرویار معتبر نیست؛ آدرس‌های خود GapGPT باید در حالت «اتصال مستقیم GapGPT» استفاده شوند، نه در حالت بک‌اند.", errorKind = AiErrorKind.NOT_CONFIGURED,
        )
        if (!online()) return@withContext AiProviderStatus(false, message = "اینترنت قطع است.", errorKind = AiErrorKind.OFFLINE)
        try {
            val conn = connect(url, "GET", s)
            try {
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                AiBackendContract.statusResponse(code, text)
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            AiProviderStatus(false, message = "زمان انتظار سرور تمام شد.", errorKind = AiErrorKind.TIMEOUT)
        } catch (_: IOException) {
            AiProviderStatus(false, message = "اتصال به سرور برقرار نشد.", errorKind = AiErrorKind.OFFLINE)
        }
    }

    /** Checks the selected model only after consent and only in online mode. */
    suspend fun checkModel(): Boolean? = withContext(Dispatchers.IO) {
        val s = settings.load()
        if (s.mode != AiMode.ONLINE || !s.enabled || !s.consented) return@withContext null
        val url = AiBackendContract.endpoint(s.backendUrl, "models") ?: return@withContext null
        try {
            val conn = connect(url, "GET", s)
            try {
                if (conn.responseCode !in 200..299) return@withContext null
                val body = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                val wanted = s.model.ifBlank { body.optString("defaultModel", "") }
                val models = body.optJSONArray("models") ?: return@withContext null
                (0 until models.length()).any { models.optString(it) == wanted }
            } finally {
                conn.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
