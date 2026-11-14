package com.khodroyar.app.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.InterruptedIOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun interface AiStreamingTransport {
    fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse
}

/** Finite HTTPS/SSE transport. Cancellation actively disconnects; redirects and arbitrary hosts are forbidden. */
class OpenRouterHttp : AiHttpTransport, AiStreamingTransport {
    private class Flight {
        @Volatile var cancelled = false
        @Volatile var expired = false
        @Volatile var connection: HttpURLConnection? = null
        fun cancel(timeout: Boolean = false) {
            expired = timeout; cancelled = true; connection?.disconnect()
        }
        fun check() {
            if (expired) throw SocketTimeoutException("Request deadline exceeded")
            if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("Request cancelled")
        }
    }

    /** Only the production transport needs this bridge; test transports remain deterministic and key-free. */
    suspend fun await(request: AiHttpRequest, onEvent: ((String) -> Unit)? = null): AiHttpResponse = suspendCancellableCoroutine { continuation ->
        val flight = Flight()
        val task = try {
            workers.submit {
                try {
                    val reply = request(request, onEvent, flight)
                    if (continuation.isActive) continuation.resume(reply)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e); null
        }
        continuation.invokeOnCancellation { flight.cancel(); task?.cancel(true) }
    }
    override fun execute(request: AiHttpRequest): AiHttpResponse = request(request, null, Flight())
    override fun stream(request: AiHttpRequest, onEvent: (String) -> Unit): AiHttpResponse = request(request, onEvent, Flight())

    private fun request(request: AiHttpRequest, callback: ((String) -> Unit)?, flight: Flight): AiHttpResponse {
        require((request.url == OpenRouterFreePolicy.MODELS_URL && request.method == "GET" && request.body == null) ||
            (request.url == OpenRouterFreePolicy.CHAT_URL && request.method == "POST" && request.body != null) ||
            (request.method == "GET" && request.body == null && (request.url == OpenRouterModelMetadata.USER_MODELS_URL ||
                request.url.startsWith(OpenRouterFreePolicy.MODELS_URL + "/") && request.url.endsWith("/endpoints") &&
                    OpenRouterModelMetadata.validId(request.url.removePrefix(OpenRouterFreePolicy.MODELS_URL + "/").removeSuffix("/endpoints")))))
        flight.check()
        val c = (URL(request.url).openConnection() as HttpURLConnection)
        flight.connection = c
        val timeout = deadlines.schedule({ flight.cancel(timeout = true) }, 90, TimeUnit.SECONDS)
        try {
            flight.check()
            c.apply {
                requestMethod = request.method; instanceFollowRedirects = false
                connectTimeout = 15000; readTimeout = 65000; useCaches = false
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
                doOutput = request.body != null
            }
            request.body?.let { body ->
                flight.check()
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            flight.check()
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            if (stream == null) return AiHttpResponse(code, "", c.getHeaderField("Retry-After"))
            val body = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                if (code == 200 && callback != null && c.contentType.orEmpty().startsWith("text/event-stream", true)) {
                    SseEvents.read(reader) { event -> flight.check(); callback(event) }; ""
                } else boundedText(reader, if (request.method == "GET") 4 * 1024 * 1024 else 256 * 1024, flight::check)
            }
            flight.check()
            return AiHttpResponse(code, body, c.getHeaderField("Retry-After"))
        } catch (e: Exception) {
            if (flight.expired) throw SocketTimeoutException("Request deadline exceeded")
            throw e
        } finally { timeout.cancel(false); c.disconnect(); flight.connection = null }
    }
    private fun boundedText(reader: Reader, limit: Int, check: () -> Unit): String {
        val text = StringBuilder(); val buf = CharArray(8192)
        while (true) {
            check()
            val n = reader.read(buf); if (n < 0) break
            require(text.length + n <= limit) { "Oversized HTTP reply" }; text.append(buf, 0, n)
        }
        return text.toString()
    }
    companion object {
        // Even repeated user cancellations cannot create unbounded threads or queued requests.
        private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            { runnable -> Thread(runnable, "OpenRouter-HTTPS").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "OpenRouter-deadline").apply { isDaemon = true } }
    }
}

object SseEvents {
    fun read(reader: Reader, callback: (String) -> Unit) {
        val line = StringBuilder(); val event = StringBuilder(); var chars = 0; var events = 0
        fun dispatch(): Boolean {
            if (event.isEmpty()) return false
            val text = event.toString().removeSuffix("\n"); event.setLength(0)
            require(++events <= 20000)
            callback(text)
            return text == "[DONE]"
        }
        fun consume(): Boolean {
            val value = line.toString().removeSuffix("\r"); line.setLength(0)
            if (value.isEmpty()) return dispatch()
            if (value.startsWith("data:")) {
                val data = value.substring(5).removePrefix(" ")
                require(event.length + data.length <= 65536)
                event.append(data).append('\n')
            }
            return false // Comments/keepalives/event names never enter model text.
        }
        while (true) {
            val ch = reader.read()
            if (ch < 0) { if (line.isNotEmpty()) consume(); dispatch(); return }
            require(++chars <= 2 * 1024 * 1024)
            if (ch.toChar() == '\n') { if (consume()) return }
            else { require(line.length < 65536); line.append(ch.toChar()) }
        }
    }
}
