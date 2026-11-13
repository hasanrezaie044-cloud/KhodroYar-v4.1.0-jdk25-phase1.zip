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
import kotlin.math.cos

data class TrafficPoint(val lat: Double, val lng: Double) {
    init { require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) }
    fun query(): String = "$lat,$lng"
}
data class TrafficCorridor(val id: String, val title: String, val originLat: Double, val originLng: Double,
    val destLat: Double, val destLng: Double, val referenceSec: Double,
    val coverageFa: String = "", val trace: List<TrafficPoint> = emptyList())
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
        val url = directionUrl(c)
        val response = transport.execute(AiHttpRequest(url, "GET", mapOf("Api-Key" to key)))
        check(response.status == 200) { when (response.status) {
            401, 403, 480 -> "کلید نشان نامعتبر است یا دسترسی مسیر ندارد."
            481 -> "سقف استفاده از سرویس نشان پر شده است."
            429, 482 -> "درخواست ترافیک زیاد است؛ کمی بعد تلاش کنید."
            else -> "دریافت اطلاعات ترافیک انجام نشد (HTTP ${response.status})."
        } }
        try {
            parse(response.body, c.referenceSec, c.trace)
        } catch (_: Exception) {
            throw IllegalStateException("اطلاعات معتبر مسیر دریافت نشد؛ وضعیت ترافیک را حدس نمی‌زنم.")
        }
    }

    companion object {
        val CORRIDORS = listOf(
            TrafficCorridor("shiraz", "اتوبان اصفهان–شیراز (مبارکه ← اصفهان)", 32.3100, 51.5600, 32.6200, 51.6600, 45.0 * 60),
            TrafficCorridor("zobahan", "اتوبان ذوب‌آهن (مبارکه ← اصفهان)", 32.3461, 51.5211, 32.6539, 51.6660, 50.0 * 60),
            TrafficCorridor("kharrazi", "اتوبان شهید خرازی (ابتدا ← انتها)", 32.6150, 51.5750, 32.6550, 51.7450, 25.0 * 60),
            // Full named roads, one explicit driving direction, including their
            // terminal connection. Coordinates/OSM source IDs: docs/TRAFFIC-ROUTES.md.
            TrafficCorridor("keshvari", "اتوبان شهید کشوری", 32.6144146, 51.7266836, 32.5910471, 51.6675926, 6.4 * 60,
                "تمام مسیر: میدان قوچانی تا پل دفاع مقدس (شرق به غرب)", listOf(
                    TrafficPoint(32.6144146, 51.7266836), TrafficPoint(32.6122042, 51.7149717),
                    TrafficPoint(32.6092842, 51.7039828), TrafficPoint(32.6043855, 51.6970165),
                    TrafficPoint(32.5997138, 51.6901926), TrafficPoint(32.5921041, 51.6791494), TrafficPoint(32.5910471, 51.6675926))),
            TrafficCorridor("meysami", "بزرگراه شهید میثمی", 32.6235237, 51.6172508, 32.6043362, 51.6241549, 3.0 * 60,
                "تمام مسیر: حبیب‌اللهی تا اتصال آقارب‌پرست (شمال به جنوب)", listOf(
                    TrafficPoint(32.6235237, 51.6172508), TrafficPoint(32.6121279, 51.6170526),
                    TrafficPoint(32.6073957, 51.6169716), TrafficPoint(32.6043371, 51.6176353),
                    TrafficPoint(32.6033416, 51.6196563), TrafficPoint(32.6043059, 51.6224333), TrafficPoint(32.6043362, 51.6241549))),
            TrafficCorridor("habibollahi", "بزرگراه شهید حبیب‌اللهی", 32.6350839, 51.6271786, 32.6235237, 51.6172508, 2.0 * 60,
                "تمام مسیر: میرزا کوچک‌خان تا میثمی (شمال به جنوب)", listOf(
                    TrafficPoint(32.6350839, 51.6271786), TrafficPoint(32.6342561, 51.6248698),
                    TrafficPoint(32.6317355, 51.6209422), TrafficPoint(32.6288469, 51.6184780),
                    TrafficPoint(32.6268298, 51.6176188), TrafficPoint(32.6244179, 51.6173548), TrafficPoint(32.6235237, 51.6172508))),
        )

        fun directionUrl(c: TrafficCorridor): String {
            val waypoints = c.trace.drop(1).dropLast(1)
            return "https://api.neshan.org/v4/direction?type=car&origin=${c.originLat},${c.originLng}&destination=${c.destLat},${c.destLng}" +
                if (waypoints.isEmpty()) "" else "&alternative=false&waypoints=" + waypoints.joinToString("%7C") { it.query() }
        }

        fun parse(body: String, referenceSec: Double, requiredTrace: List<TrafficPoint> = emptyList()): RouteStatus {
            require(body.length <= 128 * 1024 && referenceSec > 0)
            val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
            // Do not label an arbitrary detour or a partial sub-route as a full
            // named highway. No geometry in the reply means no verified status.
            if (requiredTrace.isNotEmpty()) verifyTrace(route.getJSONObject("overview_polyline").getString("points"), requiredTrace)
            val legs = route.getJSONArray("legs")
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

        internal fun verifyTrace(polyline: String, trace: List<TrafficPoint>) {
            require(polyline.length in 1..65536 && trace.size in 2..32)
            val points = ArrayList<TrafficPoint>()
            var index = 0; var lat = 0L; var lng = 0L
            fun nextDelta(): Long {
                var value = 0L; var shift = 0
                while (true) {
                    require(index < polyline.length && shift <= 30)
                    val b = polyline[index++].code - 63
                    require(b in 0..63)
                    value = value or ((b and 31).toLong() shl shift)
                    if (b < 32) break
                    shift += 5
                }
                return if (value and 1L != 0L) (value shr 1).inv() else value shr 1
            }
            while (index < polyline.length) {
                require(points.size < 8192)
                lat += nextDelta(); lng += nextDelta()
                points += TrafficPoint(lat / 100000.0, lng / 100000.0)
            }
            require(points.size >= 2)
            val xScale = 111320.0 * cos(Math.toRadians(trace.first().lat))
            val yScale = 110540.0
            fun distance2(a: TrafficPoint, b: TrafficPoint): Double =
                ((a.lng - b.lng) * xScale).let { it * it } + ((a.lat - b.lat) * yScale).let { it * it }
            fun toPath2(p: TrafficPoint, path: List<TrafficPoint>): Double = path.zipWithNext().minOf { (a, b) ->
                val dx = (b.lng - a.lng) * xScale; val dy = (b.lat - a.lat) * yScale
                val length = dx * dx + dy * dy
                val t = if (length == 0.0) 0.0 else (((p.lng - a.lng) * xScale * dx + (p.lat - a.lat) * yScale * dy) / length).coerceIn(0.0, 1.0)
                distance2(p, TrafficPoint(a.lat + t * (b.lat - a.lat), a.lng + t * (b.lng - a.lng)))
            }
            // A simplified overview may deviate along a bend, but endpoints
            // must actually reach the named road's boundary. Using the wider
            // path tolerance here could accept a missing 100 m terminal tunnel.
            val endpointTolerance2 = 50.0 * 50.0
            val pathTolerance2 = 150.0 * 150.0 // Simplification tolerance, NOT live-traffic data.
            require(distance2(points.first(), trace.first()) <= endpointTolerance2 && distance2(points.last(), trace.last()) <= endpointTolerance2)
            require(trace.all { toPath2(it, points) <= pathTolerance2 } && points.all { toPath2(it, trace) <= pathTolerance2 })
        }

    }
}
