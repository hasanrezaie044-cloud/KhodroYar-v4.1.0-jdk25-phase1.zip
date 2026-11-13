package com.khodroyar.app.workreport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FastOCR flow construction without any real network: scripted in-memory transport. */
class FastOcrClientTest {
    private class Script(vararg responses: Pair<Int, String>) : FastOcrClient.Transport {
        val requests = mutableListOf<FastOcrClient.Request>()
        private val queue = ArrayDeque(responses.toList())
        override fun execute(request: FastOcrClient.Request): FastOcrClient.Response {
            requests += request
            val (status, body) = queue.removeFirst()
            return FastOcrClient.Response(status, body.toByteArray(Charsets.UTF_8))
        }
    }

    private fun client(script: Script) = FastOcrClient(script) { /* no sleeping in tests */ }

    @Test fun fullFlowSendsKeyOnlyToApiHostAndNeverToPresignedUrls() = runBlocking {
        val script = Script(
            200 to """{"id":"doc_1","upload_url":"https://fastocr-prod.s3.amazonaws.com/x?signature=abc"}""",
            200 to "",
            200 to "",
            200 to """{"id":"doc_1","status":"completed","pages_processed":1,"pages_total":1,"is_truncated":false}""",
            200 to """{"format":"text","url":"https://fastocr-prod.s3.amazonaws.com/out?signature=def"}""",
            200 to "ردیف\n۱۴۰۴/۰۹/۱۰ ۱۲۳ ۱۲:۱۵",
        )
        val (text, status) = client(script).ocrPageJpeg("fok_live_test", "p1.jpg", ByteArray(3) { 7 })
        assertEquals("ردیف\n۱۴۰۴/۰۹/۱۰ ۱۲۳ ۱۲:۱۵", text)
        assertEquals("completed", status.status)
        val requests = script.requests
        val create = requests[0]
        assertEquals("POST", create.method)
        assertEquals("https://api.fastocr.org/v1/documents", create.url)
        assertEquals("Bearer fok_live_test", create.headers["Authorization"])
        assertFalse(create.headers["Idempotency-Key"].isNullOrBlank())
        val createBody = String(create.body!!)
        assertTrue(createBody.contains("\"size_bytes\"") && createBody.contains("\"sha256\"") && createBody.contains("p1.jpg"))
        val upload = requests[1]
        assertEquals("PUT", upload.method)
        assertTrue(upload.url.contains("s3.amazonaws.com"))
        assertFalse(upload.headers.containsKey("Authorization"))
        assertEquals("image/jpeg", upload.headers["Content-Type"])
        val start = requests[2]
        assertEquals("https://api.fastocr.org/v1/documents/doc_1/start", start.url)
        assertEquals("Bearer fok_live_test", start.headers["Authorization"])
        requests.forEach { req ->
            if (req.url.contains("s3.amazonaws.com")) assertFalse("key leaked to presigned URL", req.headers.containsKey("Authorization"))
            else assertEquals("Bearer fok_live_test", req.headers["Authorization"])
        }
    }

    @Test fun pollingWaitsThroughProcessingThenReturnsPartial() = runBlocking {
        val script = Script(
            200 to """{"id":"d","upload_url":"https://up.example/put"}""",
            200 to "",
            200 to "",
            200 to """{"status":"processing","pages_processed":0,"pages_total":1}""",
            200 to """{"status":"partial","pages_processed":1,"pages_total":2,"is_truncated":true}""",
            200 to """{"url":"https://down.example/get"}""",
            200 to "متن",
        )
        val (_, status) = client(script).ocrPageJpeg("fok_live_x", "p.jpg", byteArrayOf(1))
        assertEquals("partial", status.status)
        assertTrue(status.truncated)
        // upload and output download must not carry the key
        assertFalse(script.requests[1].headers.containsKey("Authorization"))
        assertFalse(script.requests[6].headers.containsKey("Authorization"))
    }

    @Test(expected = IllegalStateException::class)
    fun failedJobThrowsWithCode() = runBlocking {
        val script = Script(
            200 to """{"id":"d","upload_url":"https://up.example/put"}""",
            200 to "",
            200 to "",
            200 to """{"status":"failed","error":{"code":"content_too_large","message":"too big"}}""",
        )
        client(script).ocrPageJpeg("fok_live_x", "p.jpg", byteArrayOf(1))
        Unit
    }

    @Test(expected = IllegalStateException::class)
    fun unauthorizedExplainedFriendly() {
        client(Script(401 to """{"error":{"code":"unauthorized"}}""")).create("fok_bad", "a.pdf", byteArrayOf(1))
    }

    @Test(expected = IllegalStateException::class)
    fun quotaErrorsExplainedFriendly() {
        client(Script(402 to """{"error":{"code":"quota_exceeded"}}""")).create("fok_x", "a.pdf", byteArrayOf(1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankKeyRejected() = runBlocking {
        client(Script()).ocrPageJpeg(" ", "p.jpg", byteArrayOf(1))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedImageRejected() = runBlocking {
        client(Script()).ocrPageJpeg("fok_x", "p.jpg", ByteArray(FastOcrClient.MAX_IMAGE_BYTES + 1))
        Unit
    }

    @Test
    fun requestToStringNeverLeaksBodyOrAuthorization() {
        val request = FastOcrClient.Request("POST", "https://api.fastocr.org/v1/documents",
            mapOf("Authorization" to "Bearer fok_live_secret"), "PAYLOAD".toByteArray())
        val s = request.toString()
        assertFalse(s.contains("fok_live_secret"))
        assertFalse(s.contains("PAYLOAD"))
    }
}
