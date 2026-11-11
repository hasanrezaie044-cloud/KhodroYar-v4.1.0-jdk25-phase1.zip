package com.khodroyar.app.traffic

import com.khodroyar.app.ai.AiHttpRequest
import com.khodroyar.app.ai.AiHttpTransport
import com.khodroyar.app.ai.UrlConnectionAiTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.Locale

data class TrafficCorridor(val id: String, val title: String, val originLat: Double, val originLng: Double,
    val destLat: Double, val destLng: Double, val referenceSec: Double)
enum class TrafficLevel { SMOOTH, MODERATE, HEAVY }
data class RouteStatus(val level: TrafficLevel, val labelFa: String, val detail: String? = null)
data class TrafficSnapshot(val checkedAt: Instant, val statuses: Map<String, RouteStatus>)

/** One source for tab, worker and agent. No screen scraping, hidden AI call or made-up live data. */
class TrafficRepository(private val transport: AiHttpTransport = UrlConnectionAiTransport()) {
    suspend fun fetch(key: String): TrafficSnapshot = coroutineScope {
        require(key.isNotBlank() && key.length <= 2048 && key.all { it.code in 33..126 }) { "کلید API نشان را در تب ترافیک تنظیم کنید؛ آن را در چت نفرستید." }
        val result = CORRIDORS.associate { c -> c.id to async { fetchRoute(key, c) } }.mapValues { it.value.await() }
        TrafficSnapshot(Instant.now(), result)
    }

    private suspend fun fetchRoute(key: String, c: TrafficCorridor): RouteStatus = withContext(Dispatchers.IO) {
        val url = "https://api.neshan.org/v4/direction?type=car&origin=${c.originLat},${c.originLng}&destination=${c.destLat},${c.destLng}"
        val response = transport.execute(AiHttpRequest(url, "GET", mapOf("Api-Key" to key)))
        check(response.status == 200) { when (response.status) {
            401, 403, 480 -> "کلید نشان نامعتبر است یا دسترسی مسیر ندارد."
            481 -> "سقف استفاده از سرویس نشان پر شده است."
            429, 482 -> "درخواست ترافیک زیاد است؛ کمی بعد تلاش کنید."
            else -> "دریافت اطلاعات ترافیک انجام نشد (HTTP ${response.status})."
        } }
        try {
            parse(response.body, c.referenceSec)
        } catch (_: Exception) {
            throw IllegalStateException("اطلاعات معتبر مسیر دریافت نشد؛ وضعیت ترافیک را حدس نمی‌زنم.")
        }
    }

    companion object {
        val CORRIDORS = listOf(
            TrafficCorridor("shiraz", "اتوبان اصفهان–شیراز (مبارکه ← اصفهان)", 32.3100, 51.5600, 32.6200, 51.6600, 45.0 * 60),
            TrafficCorridor("zobahan", "اتوبان ذوب‌آهن (مبارکه ← اصفهان)", 32.3461, 51.5211, 32.6539, 51.6660, 50.0 * 60),
            TrafficCorridor("kharrazi", "اتوبان شهید خرازی (ابتدا ← انتها)", 32.6150, 51.5750, 32.6550, 51.7450, 25.0 * 60),
        )
        fun parse(body: String, referenceSec: Double): RouteStatus {
            require(body.length <= 128 * 1024 && referenceSec > 0)
            val legs = JSONObject(body).getJSONArray("routes").getJSONObject(0).getJSONArray("legs")
            require(legs.length() in 1..100)
            var seconds = 0.0
            var meters = 0.0
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                seconds += leg.getJSONObject("duration").getDouble("value")
                meters += leg.getJSONObject("distance").getDouble("value")
            }
            require(seconds.isFinite() && seconds > 0 && meters.isFinite() && meters >= 0)
            val ratio = seconds / referenceSec
            val level = when { ratio < 1.25 -> TrafficLevel.SMOOTH; ratio < 1.7 -> TrafficLevel.MODERATE; else -> TrafficLevel.HEAVY }
            val label = when (level) { TrafficLevel.SMOOTH -> "روان"; TrafficLevel.MODERATE -> "نیمه‌سنگین"; TrafficLevel.HEAVY -> "سنگین" }
            return RouteStatus(level, label, "حدود ${(seconds / 60).toInt()} دقیقه · ${String.format(Locale.US, "%.1f", meters / 1000)} کیلومتر")
        }
    }
}
