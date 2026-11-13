package com.khodroyar.app.workreport

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * Optional FastOCR (api.fastocr.org) document upload, explicitly user-consented.
 * Official flow: create document job -> PUT bytes to the presigned upload URL
 * -> start -> poll status (awaiting_upload/processing/completed/partial/failed)
 * -> presigned text download URL -> UTF-8 text.
 * The Bearer key goes ONLY to api.fastocr.org; presigned S3 URLs are already
 * authorized and must never receive it. Bodies/keys are never logged or stored.
 */
class FastOcrClient(
    private val transport: Transport = UrlConnectionTransport(),
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    companion object {
        const val BASE = "https://api.fastocr.org"
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_TEXT_BYTES = 2_000_000
        const val MAX_POLLS = 45
        const val POLL_MS = 2_000L
    }

    /** Intentionally not a data class: toString must never print Authorization or bodies. */
    class Request(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray? = null) {
        override fun toString(): String = "$method $url (headers and body redacted)"
    }

    class Response(val status: Int, val body: ByteArray) {
        override fun toString(): String = "FastOCR HTTP $status (body redacted)"
    }

    fun interface Transport {
        fun execute(request: Request): Response
    }

    /** HTTPS-only; no redirects, cookies or logging. Presigned upload/download URLs included. */
    class UrlConnectionTransport : Transport {
        override fun execute(request: Request): Response {
            val uri = URI(request.url)
            require(uri.scheme == "https" && uri.userInfo == null)
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 120_000
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                doOutput = request.body != null
            }
            try {
                request.body?.let { connection.outputStream.use { out -> out.write(it) } }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= MAX_TEXT_BYTES + 1_000_000) { "پاسخ بیش از حد بزرگ است." }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: ByteArray(0)
                return Response(status, bytes)
            } finally {
                connection.disconnect()
            }
        }
    }

    data class Created(val id: String, val uploadUrl: String)
    data class Status(val status: String, val pagesProcessed: Int, val pagesTotal: Int, val truncated: Boolean, val error: String?)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun call(request: Request): JSONObject {
        val response = transport.execute(request)
        val text = String(response.body, Charsets.UTF_8)
        if (response.status == 401 || response.status == 403) throw IllegalStateException("کلید API پذیرفته نشد؛ آن را از حساب FastOCR بررسی کنید.")
        if (response.status == 402 || response.status == 429) throw IllegalStateException("سهمیه/محدودیت FastOCR؛ بعداً دوباره تلاش کنید.")
        require(response.status in 200..299) { "FastOCR خطای HTTP ${response.status} داد." }
        // Empty 2xx bodies (e.g. /start) are valid; org.json rejects blank strings.
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    fun create(apiKey: String, filename: String, bytes: ByteArray): Created {
        val body = JSONObject()
            .put("filename", filename)
            .put("size_bytes", bytes.size)
            .put("sha256", sha256(bytes))
        val json = call(Request("POST", "$BASE/v1/documents", mapOf(
            "Authorization" to "Bearer $apiKey",
            "Idempotency-Key" to UUID.randomUUID().toString(),
            "Content-Type" to "application/json; charset=utf-8",
        ), body.toString().toByteArray(Charsets.UTF_8)))
        return Created(json.getString("id"), json.getString("upload_url"))
    }

    fun upload(created: Created, bytes: ByteArray, mime: String) {
        // Presigned storage URL: NO Authorization header here.
        val response = transport.execute(Request("PUT", created.uploadUrl, mapOf("Content-Type" to mime), bytes))
        require(response.status in 200..299) { "بارگذاری فایل به مقصد امضاشده انجام نشد (HTTP ${response.status})." }
    }

    fun start(apiKey: String, id: String) {
        call(Request("POST", "$BASE/v1/documents/$id/start", mapOf("Authorization" to "Bearer $apiKey")))
    }

    fun status(apiKey: String, id: String): Status {
        val json = call(Request("GET", "$BASE/v1/documents/$id", mapOf("Authorization" to "Bearer $apiKey")))
        val error = json.optJSONObject("error")?.let { it.optString("code", "") + " " + it.optString("message", "") }?.trim()?.ifBlank { null }
        return Status(json.getString("status"), json.optInt("pages_processed", -1), json.optInt("pages_total", -1),
            json.optBoolean("is_truncated", false), error)
    }

    /** Bounded polling: a stalled job must not hang the screen forever. */
    suspend fun await(apiKey: String, id: String, checkCancelled: () -> Unit = {}): Status {
        repeat(MAX_POLLS) {
            checkCancelled()
            sleeper(POLL_MS)
            val status = status(apiKey, id)
            when (status.status) {
                "completed", "partial" -> return status
                "failed" -> throw IllegalStateException("پردازش FastOCR ناموفق بود: ${status.error ?: "بدون توضیح"}")
            }
        }
        throw IllegalStateException("FastOCR در بازهٔ زمانی پاسخ نداد؛ بعداً دوباره تلاش کنید.")
    }

    fun downloadText(apiKey: String, id: String): String {
        val output = call(Request("GET", "$BASE/v1/documents/$id/output?format=text", mapOf("Authorization" to "Bearer $apiKey")))
        val response = transport.execute(Request("GET", output.getString("url"), emptyMap())) // presigned URL: no key
        require(response.status in 200..299) { "دریافت متن نتیجه انجام نشد (HTTP ${response.status})." }
        require(response.body.size <= MAX_TEXT_BYTES) { "متن نتیجه بیش از حد بزرگ است." }
        return String(response.body, Charsets.UTF_8)
    }

    /** One JPEG page through the whole published flow. Nothing is cached or retried blindly. */
    suspend fun ocrPageJpeg(apiKey: String, filename: String, jpeg: ByteArray, checkCancelled: () -> Unit = {}): Pair<String, Status> {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_IMAGE_BYTES) { "حجم هر تصویر حداکثر ۲۰ مگابایت است." }
        require(apiKey.isNotBlank() && apiKey.length <= 256 && apiKey.none { it.isWhitespace() }) { "کلید API نامعتبر است." }
        val created = create(apiKey, filename, jpeg)
        checkCancelled()
        upload(created, jpeg, "image/jpeg")
        start(apiKey, created.id)
        val status = await(apiKey, created.id, checkCancelled)
        return downloadText(apiKey, created.id) to status
    }
}
