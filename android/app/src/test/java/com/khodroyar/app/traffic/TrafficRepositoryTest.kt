package com.khodroyar.app.traffic

import com.khodroyar.app.ai.AiHttpResponse
import com.khodroyar.app.ai.AiHttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

class TrafficRepositoryTest {
    /** The working pre-5.5.7 reply shape: durations/distances, no route geometry. */
    private fun body(seconds: Double = 3000.0): String = JSONObject().put("routes", JSONArray().put(
        JSONObject().put("legs", JSONArray().put(JSONObject()
            .put("duration", JSONObject().put("value", seconds)).put("distance", JSONObject().put("value", 42000))))
    )).toString()

    private val expectedUrls = linkedMapOf(
        "shiraz" to "https://api.neshan.org/v4/direction?type=car&origin=32.31,51.56&destination=32.62,51.66",
        "zobahan" to "https://api.neshan.org/v4/direction?type=car&origin=32.3461,51.5211&destination=32.6539,51.666",
        "kharrazi" to "https://api.neshan.org/v4/direction?type=car&origin=32.615,51.575&destination=32.655,51.745",
        "keshvari" to "https://api.neshan.org/v4/direction?type=car&origin=32.6144146,51.7266836&destination=32.5910471,51.6675926",
        "meysami" to "https://api.neshan.org/v4/direction?type=car&origin=32.6235237,51.6172508&destination=32.6043362,51.6241549",
        "habibollahi" to "https://api.neshan.org/v4/direction?type=car&origin=32.6350839,51.6271786&destination=32.6235237,51.6172508",
    )

    @Test fun screenWorkerAndAgentShareSixRoutesAndTheOriginalThreeAreUnchanged() {
        assertEquals(expectedUrls.keys, TrafficRepository.CORRIDORS.map { it.id }.toSet())
        assertEquals(listOf(
            TrafficCorridor("shiraz", "اتوبان اصفهان–شیراز (مبارکه ← اصفهان)", 32.3100, 51.5600, 32.6200, 51.6600, 45.0 * 60),
            TrafficCorridor("zobahan", "اتوبان ذوب‌آهن (مبارکه ← اصفهان)", 32.3461, 51.5211, 32.6539, 51.6660, 50.0 * 60),
            TrafficCorridor("kharrazi", "اتوبان شهید خرازی (ابتدا ← انتها)", 32.6150, 51.5750, 32.6550, 51.7450, 25.0 * 60),
        ), TrafficRepository.CORRIDORS.take(3))
    }

    @Test fun explicitFetchUsesTheLegacyQueryForAllSixAndAcceptsLegsWithoutPolyline() = runBlocking {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val repo = TrafficRepository(AiHttpTransport { r ->
            urls.add(r.url); assertEquals("GET", r.method); assertEquals(mapOf("Api-Key" to "test-neshan-key"), r.headers)
            assertNull(r.body)
            assertTrue(r.url, r.url in expectedUrls.values)
            assertFalse(r.toString().contains("test-neshan-key"))
            // 5.5.7 rejected this otherwise usable reply for the added roads,
            // then cancelled the whole six-route snapshot. It must work for ALL.
            AiHttpResponse(200, body())
        })
        val snapshot = repo.fetch("test-neshan-key")
        assertEquals(6, urls.size)
        assertEquals(expectedUrls.values.toSet(), urls.toSet())
        assertEquals(expectedUrls.keys, snapshot.statuses.keys)
        assertTrue(snapshot.statuses.values.all { !it.detail.isNullOrBlank() })
    }

    @Test fun noNewWaypointAlternativeOrGeometryContractIsImposedOnAnyRoad() {
        for (route in TrafficRepository.CORRIDORS) {
            val url = TrafficRepository.directionUrl(route)
            assertEquals(expectedUrls.getValue(route.id), url)
            val uri = java.net.URI(url)
            assertEquals("https", uri.scheme); assertEquals("api.neshan.org", uri.host)
            assertEquals("/v4/direction", uri.path); assertNull(uri.rawFragment); assertNull(uri.userInfo)
            assertEquals(setOf("type", "origin", "destination"), uri.rawQuery.split('&').map { it.substringBefore('=') }.toSet())
            assertFalse(url.contains("waypoints")); assertFalse(url.contains("alternative"))
        }
    }

    @Test fun addedRoadsRetainTheirSourcedWholeRoadEndpointsAndDirectionLabels() {
        val roads = TrafficRepository.CORRIDORS.drop(3)
        assertEquals(listOf("keshvari", "meysami", "habibollahi"), roads.map { it.id })
        assertTrue(roads.all { it.coverageFa.isNotBlank() && it.referenceSec > 0 && it.originLat in 32.5..32.7 && it.destLat in 32.5..32.7 })
        assertEquals(32.6144146, roads[0].originLat, .00000001)
        assertEquals(51.6675926, roads[0].destLng, .00000001)
        assertEquals(32.6043362, roads[1].destLat, .00000001)
        assertEquals(32.6235237, roads[2].destLat, .00000001)
    }

    @Test fun unusedGeometryMetadataMayBeAbsentEmptyOrHaveAnotherShape() = runBlocking {
        // Synthetic optional metadata, not live Neshan output. Only the legacy
        // route leg values are consumed; arbitrary geometry is never measured.
        for (geometry in listOf<Any?>(null, "", "_p~iF~ps|U_ulLnnqC_mqNvxq`@", JSONObject().put("points", ""), JSONArray())) {
            val reply = JSONObject(body()).apply {
                if (geometry != null) getJSONArray("routes").getJSONObject(0).put("overview_polyline", geometry)
            }.toString()
            val snapshot = TrafficRepository(AiHttpTransport { AiHttpResponse(200, reply) }).fetch("test-key")
            assertEquals(expectedUrls.keys, snapshot.statuses.keys)
        }
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
    @Test fun multipleLegDurationsAndDistancesAreSummedAsBefore() {
        val reply = """{"routes":[{"legs":[{"duration":{"value":600},"distance":{"value":4200}},{"duration":{"value":900},"distance":{"value":6200}}]}]}"""
        val result = TrafficRepository.parse(reply, 1000.0)
        assertEquals(TrafficLevel.MODERATE, result.level)
        assertEquals("حدود 25 دقیقه · 10.4 کیلومتر", result.detail)
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
    @Test fun missingInvalidOrOversizedLegDataStillFailsInsteadOfInventingTraffic() {
        for (reply in listOf(
            """{"routes":[]}""", """{"routes":[{"legs":[]}]}""",
            """{"routes":[{"legs":[{"distance":{"value":10}}]}]}""",
            """{"routes":[{"legs":[{"duration":{"value":60},"distance":{"value":-1}}]}]}""",
            """{"routes":[{"legs":[{"duration":{"value":-1},"distance":{"value":10}}]}]}""",
            " ".repeat(128 * 1024 + 1),
        )) assertTrue(runCatching { TrafficRepository.parse(reply, 1000.0) }.isFailure)
    }
}
