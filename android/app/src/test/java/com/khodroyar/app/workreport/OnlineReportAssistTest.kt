package com.khodroyar.app.workreport

import org.junit.Assert.*
import org.junit.Test

/**
 * The optional online assist is a narrow, auditable contract: HTTPS-only,
 * bounded payloads, normalized normalized boxes, and absolutely no value is
 * accepted without the offline review/comparison gates running afterwards.
 */
class OnlineReportAssistTest {
    @Test
    fun endpointMustBePlainHttps() {
        assertEquals("ocr.example.com", OnlineReportAssist.hostOf("https://ocr.example.com/recognize"))
        assertNull(OnlineReportAssist.hostOf("http://ocr.example.com"))
        assertNull(OnlineReportAssist.hostOf("https://user:pass@ocr.example.com"))
        assertNull(OnlineReportAssist.hostOf("not-a-url"))
        assertNull(OnlineReportAssist.hostOf(""))
        assertNull(OnlineReportAssist.hostOf("ftp://ocr.example.com"))
    }

    @Test
    fun requestBodyCarriesOnlyBoundedPages() {
        val page = OnlineReportAssist.preparePage("p1", byteArrayOf(1, 2, 3), 2_000_000)
        val body = String(OnlineReportAssist.requestBody(listOf(page)))
        assertTrue(body.contains("\"id\":\"p1\""))
        assertTrue(body.contains("\"mime\":\"image/jpeg\""))
        assertTrue(body.contains("\"pixels\":2000000"))
        assertTrue(OnlineReportAssist.requestBody((1..OnlineReportAssist.MAX_PAGES_PER_SEND).map {
            OnlineReportAssist.preparePage("p$it", byteArrayOf(1), 100)
        }).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            OnlineReportAssist.requestBody(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineReportAssist.requestBody(listOf(page, page))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineReportAssist.requestBody((1..OnlineReportAssist.MAX_PAGES_PER_SEND + 1).map {
                OnlineReportAssist.preparePage("p$it", byteArrayOf(1), 100)
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnlineReportAssist.preparePage("p-big", ByteArray(OnlineReportAssist.MAX_BYTES_PER_PAGE + 1), 100)
        }
    }

    @Test
    fun responseWordsAreNormalizedAndBounded() {
        val body = """{"pages":[{"id":"p1","words":[
            {"text":"۱۴۰۳/۰۴/۱۲","left":.70,"top":.30,"right":.90,"bottom":.34,"confidence":96},
            {"text":"55","left":.30,"top":.30,"right":.40,"bottom":.34,"confidence":150},
            {"text":"","left":1,"top":1,"right":2,"bottom":2},
            {"text":"خیابان","left":2.0,"top":.1,"right":2.4,"bottom":.2,"confidence":50}
        ]}]}""".trimIndent()
        val parsed = OnlineReportAssist.parseResponse(body)
        val words = parsed.single().words
        assertEquals(2, words.size)
        assertEquals("۱۴۰۳/۰۴/۱۲", words[0].text)
        assertEquals(100f, words[1].confidence, 0.001f) // clamped, never trusted beyond bounds
        assertThrows(IllegalArgumentException::class.java) {
            OnlineReportAssist.parseResponse("{\"pages\":[${(0..10).joinToString(",") { "{\"id\":\"p$it\",\"words\":[]}" }}]}")
        }
        assertThrows(org.json.JSONException::class.java) {
            OnlineReportAssist.parseResponse("")
        }
    }

    @Test
    fun orderingFollowsTheSelectedPages() {
        assertEquals(listOf("b", "a"), OnlineReportAssist.orderedIds(listOf(OcrPage("b", 1, emptyList(), "")), listOf(OcrPage("a", 1, emptyList(), "")), 10))
    }
}
