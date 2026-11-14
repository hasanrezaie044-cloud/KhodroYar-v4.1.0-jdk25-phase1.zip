package com.khodroyar.app.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Intentionally not a data class: toString must never print Authorization or message bodies. */
class AiHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String? = null,
) {
    override fun toString(): String = "$method $url (headers and body redacted)"
}

class AiHttpResponse(val status: Int, val body: String, val retryAfter: String? = null, val headers: Map<String, String> = emptyMap()) {
    override fun toString(): String = "AI HTTP $status (body redacted)"
}

fun interface AiHttpTransport {
    fun execute(request: AiHttpRequest): AiHttpResponse
}

/** Executed on Dispatchers.IO by the provider. No redirects, logging or plaintext HTTP. */
class UrlConnectionAiTransport : AiHttpTransport {
    override fun execute(request: AiHttpRequest): AiHttpResponse {
        val url = URL(request.url)
        require(url.protocol == "https")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 45_000
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            doOutput = request.body != null
        }
        try {
            request.body?.let { body ->
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (result.length + count > 128 * 1024) throw IOException("AI response too large")
                    result.append(buffer, 0, count)
                }
                result.toString()
            }.orEmpty()
            return AiHttpResponse(status, body, connection.getHeaderField("Retry-After"))
        } finally {
            connection.disconnect()
        }
    }
}
