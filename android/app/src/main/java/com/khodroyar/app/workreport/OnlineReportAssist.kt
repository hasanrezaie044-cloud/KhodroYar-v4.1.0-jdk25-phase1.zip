package com.khodroyar.app.workreport

import java.util.Base64
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * OPTIONAL online assist for this screen only. It is never called by default,
 * never enabled by a build flag, and its HTTPS-only sender is injected for tests.
 * One POST per page happens EXCLUSIVELY after explicit consent that names the
 * destination host. A response merely PREFILLS the unchanged offline review
 * table as ordinary editable rows; it never writes service records, never
 * substitutes zero values and never bypasses strict comparison.
 */
object OnlineReportAssist {
    const val PREFS = "online-report-assist"
    const val MAX_PAGES_PER_SEND = 10
    const val MAX_BYTES_PER_PAGE = 1_200_000
    const val MAX_RESPONSE_BYTES = 2_000_000

    /** Never persists any image, word, name or amount. */
    class Sender {
        fun post(url: String, body: ByteArray): String {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 90_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                require(status in 200..299) { "سرویس پاسخ HTTP $status داد؛ بدون تغییر." }
                val bytes = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        require(out.size() + n <= MAX_RESPONSE_BYTES) { "پاسخ بیش از حد بزرگ است؛ نادیده گرفته شد." }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                return String(bytes, Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** The endpoint contract documented in docs/ONLINE-OCR-ASSIST.md. */
    data class PreparedPage(val pageId: String, val mime: String, val base64: String, val pixels: Int)

    data class ParsedPage(val pageId: String, val words: List<OcrWord>)

    fun hostOf(url: String): String? = runCatching { URI(url.trim()) }
        .getOrNull()?.takeIf { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null }?.host?.lowercase()

    fun preparePage(pageId: String, jpeg: ByteArray, pixels: Int): PreparedPage {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_BYTES_PER_PAGE) { "حجم هر صفحه باید حداکثر ${MAX_BYTES_PER_PAGE / 1000} کیلوبایت باشد." }
        return PreparedPage(pageId, "image/jpeg", Base64.getMimeEncoder().encodeToString(jpeg), pixels)
    }

    /** Bounded JPEG payload: downscale before quality, never silently drop pages. */
    fun jpeg(bitmap: android.graphics.Bitmap): ByteArray {
        var image = bitmap; var quality = 84
        repeat(7) {
            val out = java.io.ByteArrayOutputStream()
            image.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_BYTES_PER_PAGE) {
                if (image !== bitmap) image.recycle()
                return bytes
            }
            if (quality > 62) quality -= 11 else {
                val scaled = android.graphics.Bitmap.createScaledBitmap(image, (image.width * .8f).toInt().coerceAtLeast(600), (image.height * .8f).toInt().coerceAtLeast(800), true)
                if (image !== bitmap) image.recycle()
                image = scaled
            }
        }
        if (image !== bitmap) image.recycle()
        throw IllegalStateException("تصویر این صفحه پس از فشرده‌سازی هم بزرگ است؛ صفحهٔ کوچک‌تری انتخاب کنید.")
    }

    fun requestBody(pages: List<PreparedPage>): ByteArray {
        require(pages.isNotEmpty() && pages.size <= MAX_PAGES_PER_SEND)
        require(pages.map { it.pageId }.distinct().size == pages.size)
        val array = JSONArray()
        pages.forEach {
            array.put(JSONObject().put("id", it.pageId).put("mime", it.mime).put("pixels", it.pixels).put("image_base64", it.base64))
        }
        return JSONObject().put("version", 1).put("pages", array).toString().toByteArray(Charsets.UTF_8)
    }

    /** Numbers normalized; every word still becomes a fully EDITABLE report row. */
    fun parseResponse(body: String): List<ParsedPage> {
        require(body.length <= MAX_RESPONSE_BYTES)
        val root = JSONObject(body)
        val pages = root.getJSONArray("pages")
        require(pages.length() <= MAX_PAGES_PER_SEND)
        return (0 until pages.length()).map { index ->
            val page = pages.getJSONObject(index)
            val words = page.getJSONArray("words")
            require(words.length() <= 50000)
            ParsedPage(page.getString("id"), (0 until words.length()).mapNotNull { w ->
                val word = words.getJSONObject(w)
                val text = word.optString("text", "").take(120)
                val left = word.optDouble("left", Double.NaN).toFloat()
                val top = word.optDouble("top", Double.NaN).toFloat()
                val right = word.optDouble("right", Double.NaN).toFloat()
                val bottom = word.optDouble("bottom", Double.NaN).toFloat()
                val confidence = word.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 100f)
                if (text.isBlank() || listOf(left, top, right, bottom).any { !it.isFinite() || it !in 0f..1f } || left >= right || top >= bottom) null
                else OcrWord(text, left, top, right, bottom, confidence)
            })
        }
    }

    /**
     * Online words feed the SAME generic extractor used for ordinary documents.
     * No fixed-form grid trust, no zero-invention, and cells stay reviewable.
     * Provenance (offline/online) is returned separately for UI/diagnostics.
     */
    fun toOcrPages(parsed: List<ParsedPage>, order: List<Pair<String, Int>>): List<OcrPage> =
        order.mapIndexed { index, (id, _) ->
            val words = parsed.firstOrNull { it.pageId == id }?.words.orEmpty()
            OcrPage(id, index + 1, words, "")
        }

    fun mergeByPage(offline: List<OcrPage>, online: List<OcrPage>, pageCount: Int): Pair<List<OcrPage>, Map<String, String>> {
        val map = mutableMapOf<String, Pair<OcrPage, String>>()
        offline.forEach { map[it.id] = it to "offline" }
        online.forEach { map[it.id] = it to "online" }
        val ordered = orderedIds(offline, online, pageCount)
        return ordered.map { requireNotNull(map[it]?.first) } to ordered.mapNotNull { id -> map[id]?.second?.let { id to it } }.toMap()
    }

    internal fun orderedIds(offline: List<OcrPage>, online: List<OcrPage>, pageCount: Int): List<String> =
        (offline.map { it.id } + online.map { it.id }).distinct().also { require(it.size <= pageCount) }
}
