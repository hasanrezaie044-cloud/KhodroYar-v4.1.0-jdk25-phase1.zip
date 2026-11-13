package com.khodroyar.app.traffic

import com.khodroyar.app.ai.AiHttpResponse
import com.khodroyar.app.ai.AiHttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

class TrafficRepositoryTest {
    private fun body(seconds: Double = 3000.0, trace: List<TrafficPoint> = emptyList()): String {
        val route = JSONObject().put("legs", JSONArray().put(JSONObject()
            .put("duration", JSONObject().put("value", seconds)).put("distance", JSONObject().put("value", 42000))))
        if (trace.isNotEmpty()) route.put("overview_polyline", JSONObject().put("points", encode(trace)))
        return JSONObject().put("routes", JSONArray().put(route)).toString()
    }
    /** Synthetic geometry at public route checkpoints, NOT a live provider response. */
    private fun encode(points: List<TrafficPoint>): String = buildString {
        var lat = 0L; var lng = 0L
        fun delta(value: Long) {
            var v = if (value < 0) (value shl 1).inv() else value shl 1
            while (v >= 32) { append(((v and 31L).toInt() or 32).plus(63).toChar()); v = v shr 5 }
            append((v + 63).toInt().toChar())
        }
        for (p in points) {
            val y = (p.lat * 100000).roundToLong(); val x = (p.lng * 100000).roundToLong()
            delta(y - lat); delta(x - lng); lat = y; lng = x
        }
    }
    @Test fun threeConsumersShareSameFixedCorridorCatalogue() {
        assertEquals(setOf("shiraz", "zobahan", "kharrazi", "keshvari", "meysami", "habibollahi"), TrafficRepository.CORRIDORS.map { it.id }.toSet())
    }
    @Test fun explicitFetchUsesNeshanHeaderNotAiBearerOrPhoneLocation() = runBlocking {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val repo = TrafficRepository(AiHttpTransport { r ->
            urls.add(r.url); assertEquals("GET", r.method); assertEquals("test-neshan-key", r.headers["Api-Key"])
            assertNull(r.headers["Authorization"]); assertNull(r.body)
            assertTrue(r.url.startsWith("https://api.neshan.org/v4/direction?type=car"))
            assertFalse(r.toString().contains("test-neshan-key"))
            val corridor = TrafficRepository.CORRIDORS.single { r.url == TrafficRepository.directionUrl(it) }
            AiHttpResponse(200, body(trace = corridor.trace))
        })
        val snapshot = repo.fetch("test-neshan-key")
        assertEquals(6, urls.size); assertEquals(6, snapshot.statuses.size)
    }
    @Test fun missingKeyFailsBeforeTransport() = runBlocking {
        var calls = 0
        try { TrafficRepository(AiHttpTransport { calls++; error("unexpected") }).fetch(""); fail("must reject") } catch (_: IllegalArgumentException) {}
        assertEquals(0, calls)
    }
    @Test fun invalidHeaderCharactersAreRejectedBeforeTransport() = runBlocking {
        var calls = 0
        try { TrafficRepository(AiHttpTransport { calls++; error("unexpected") }).fetch("test\r\nInjected: yes"); fail("must reject") } catch (_: IllegalArgumentException) {}
        assertEquals(0, calls)
    }
    @Test fun thresholdComparisonIsAnEstimateNotInventedStreetLevelData() {
        assertEquals(TrafficLevel.SMOOTH, TrafficRepository.parse(body(1000.0), 1000.0).level)
        assertEquals(TrafficLevel.MODERATE, TrafficRepository.parse(body(1300.0), 1000.0).level)
        assertEquals(TrafficLevel.HEAVY, TrafficRepository.parse(body(1800.0), 1000.0).level)
    }
    @Test fun malformedProviderReplyCannotBecomeFreshTraffic() = runBlocking {
        try { TrafficRepository(AiHttpTransport { AiHttpResponse(200, "<html>secret-raw-page</html>") }).fetch("test-key"); fail("must reject") }
        catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("secret-raw-page")) }
    }
    @Test fun authErrorDoesNotExposeProviderBodyOrKey() = runBlocking {
        try { TrafficRepository(AiHttpTransport { AiHttpResponse(401, "sensitive-body-test-key") }).fetch("test-key"); fail("must reject") }
        catch (e: IllegalStateException) { assertFalse(e.message.orEmpty().contains("sensitive")); assertFalse(e.message.orEmpty().contains("test-key")) }
    }
    @Test(expected = IllegalArgumentException::class) fun zeroTravelTimeIsNotReportedAsSmooth() { TrafficRepository.parse(body(0.0), 1000.0) }
    @Test fun newFullLengthIsfahanRoadsHaveSourcedEndpointsAndOrderedEncodedWaypoints() {
        val roads = TrafficRepository.CORRIDORS.filter { it.trace.isNotEmpty() }
        assertEquals(setOf("keshvari", "meysami", "habibollahi"), roads.map { it.id }.toSet())
        for (c in roads) {
            assertTrue(c.coverageFa.contains("تمام مسیر"))
            assertEquals(TrafficPoint(c.originLat, c.originLng), c.trace.first())
            assertEquals(TrafficPoint(c.destLat, c.destLng), c.trace.last())
            assertTrue(c.trace.size >= 4)
            assertTrue(c.trace.all { it.lat in 32.5..32.7 && it.lng in 51.5..51.8 })
            val url = TrafficRepository.directionUrl(c)
            assertTrue(url.contains("alternative=false"))
            assertTrue(url.endsWith("waypoints=" + c.trace.drop(1).dropLast(1).joinToString("%7C") { it.query() }))
            assertFalse(url.contains("|"))
            assertEquals(TrafficLevel.SMOOTH, TrafficRepository.parse(body(c.referenceSec, c.trace), c.referenceSec, c.trace).level)
        }
        assertEquals(32.6144146, roads.single { it.id == "keshvari" }.originLat, .00000001)
        assertEquals(51.6675926, roads.single { it.id == "keshvari" }.destLng, .00000001)
        assertEquals(32.6043362, roads.single { it.id == "meysami" }.destLat, .00000001)
        assertEquals(32.6235237, roads.single { it.id == "habibollahi" }.destLat, .00000001)
    }
    @Test fun partialReversedMissingOrOffRoadGeometryCannotBeRelabelledAsTheWholeHighway() {
        for (c in TrafficRepository.CORRIDORS.filter { it.trace.isNotEmpty() }) {
            val wrong = listOf(c.trace.drop(1), c.trace.dropLast(1), c.trace.reversed(),
                c.trace.toMutableList().apply { this[2] = TrafficPoint(this[2].lat + .02, this[2].lng) })
            for ((index, points) in wrong.withIndex()) {
                assertTrue("${c.id}: invalid geometry $index", runCatching { TrafficRepository.parse(body(c.referenceSec, points), c.referenceSec, c.trace) }.isFailure)
            }
            assertTrue(runCatching { TrafficRepository.parse(body(c.referenceSec), c.referenceSec, c.trace) }.isFailure)
        }
    }
    @Test fun endpointsRequireTheWholeRoadButAllowSmallProviderSnapping() {
        for (c in TrafficRepository.CORRIDORS.filter { it.trace.isNotEmpty() }) {
            for (index in listOf(0, c.trace.lastIndex)) {
                val near = c.trace.toMutableList().apply { this[index] = TrafficPoint(this[index].lat + .0002, this[index].lng) }
                assertEquals(TrafficLevel.SMOOTH, TrafficRepository.parse(body(c.referenceSec, near), c.referenceSec, c.trace).level)
                val short = c.trace.toMutableList().apply { this[index] = TrafficPoint(this[index].lat + .0007, this[index].lng) }
                assertTrue("${c.id}: endpoint $index is over 50 m away", runCatching { TrafficRepository.parse(body(c.referenceSec, short), c.referenceSec, c.trace) }.isFailure)
            }
        }
    }
    @Test fun malformedOrHugePolylineIsBoundedAndDoesNotBecomeFreshTraffic() {
        val c = TrafficRepository.CORRIDORS.single { it.id == "meysami" }
        for (text in listOf("", "~~~~~~~", "\u0000", "?", "?".repeat(65537)))
            assertTrue(runCatching { TrafficRepository.verifyTrace(text, c.trace) }.isFailure)
    }

}
