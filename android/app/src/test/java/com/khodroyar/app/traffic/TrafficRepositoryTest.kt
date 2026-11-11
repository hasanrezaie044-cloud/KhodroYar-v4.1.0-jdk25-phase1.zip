package com.khodroyar.app.traffic

import com.khodroyar.app.ai.AiHttpResponse
import com.khodroyar.app.ai.AiHttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class TrafficRepositoryTest {
    private fun body(seconds: Double = 3000.0) = """{"routes":[{"legs":[{"duration":{"value":$seconds},"distance":{"value":42000}}]}]}"""
    @Test fun threeConsumersShareSameFixedCorridorCatalogue() {
        assertEquals(setOf("shiraz", "zobahan", "kharrazi"), TrafficRepository.CORRIDORS.map { it.id }.toSet())
    }
    @Test fun explicitFetchUsesNeshanHeaderNotAiBearerOrPhoneLocation() = runBlocking {
        val urls = Collections.synchronizedList(mutableListOf<String>())
        val repo = TrafficRepository(AiHttpTransport { r ->
            urls.add(r.url); assertEquals("GET", r.method); assertEquals("test-neshan-key", r.headers["Api-Key"])
            assertNull(r.headers["Authorization"]); assertNull(r.body)
            assertTrue(r.url.startsWith("https://api.neshan.org/v4/direction?type=car"))
            assertFalse(r.toString().contains("test-neshan-key"))
            AiHttpResponse(200, body())
        })
        val snapshot = repo.fetch("test-neshan-key")
        assertEquals(3, urls.size); assertEquals(3, snapshot.statuses.size)
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
}
