package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchTest {
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()
    private val minimal = resource("innertube-search-minimal.json")

    @Test
    fun parsesTitleArtistDurationAndLargestThumbnailAndSkipsItemsWithoutVideoId() {
        assertEquals(
            listOf(SearchResult("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 214_000, "https://large.example/t.jpg")),
            InnerTubeSearchParser.parse(minimal),
        )
    }

    @Test
    fun parsesDurations() {
        assertEquals(214_000, InnerTubeSearchParser.parseDuration("3:34"))
        assertEquals(3_723_000, InnerTubeSearchParser.parseDuration("1:02:03"))
    }

    @Test
    fun parsesARecordedResponse() {
        val results = InnerTubeSearchParser.parse(resource("innertube-search-recorded.json"))
        assertTrue("expected results", results.isNotEmpty())
        results.forEach {
            assertEquals(11, it.videoId.length)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.durationMs > 0)
        }
        assertTrue(results.first().artist.contains("Rick Astley"))
    }

    @Test
    fun requestBodyAsksForSongsFromTheWebRemixClient() {
        val body = RitmeJson.parseToJsonElement(InnerTubeSearchBackend.requestBody("abc")).jsonObject
        assertEquals("abc", body.getValue("query").jsonPrimitive.content)
        assertEquals(InnerTubeSearchBackend.SONGS_FILTER, body.getValue("params").jsonPrimitive.content)
        assertEquals("WEB_REMIX", body.getValue("context").jsonObject.getValue("client").jsonObject.getValue("clientName").jsonPrimitive.content)
    }

    @Test
    fun serviceCachesNormalizedQueriesUntilTheTtl() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val service = SearchService({ calls++; minimal }, clock, ttlMs = 1_000)
        service.search("Rick")
        service.search("  rick ")
        assertEquals(1, calls)
        clock.now += 1_000
        service.search("rick")
        assertEquals(2, calls)
    }

    @Test
    fun ipLimiterAllowsThirtyPerMinute() {
        val clock = FakeClock()
        val limiter = IpRateLimiter(clock)
        repeat(Limits.SEARCH_PER_MINUTE) { assertTrue(limiter.tryAcquire("1.2.3.4")) }
        assertEquals(false, limiter.tryAcquire("1.2.3.4"))
        assertTrue(limiter.tryAcquire("5.6.7.8"))
        clock.now += 2_000
        assertTrue(limiter.tryAcquire("1.2.3.4"))
    }

    @Test
    fun ipLimiterBucketsIpv6BySlash64() {
        val limiter = IpRateLimiter(FakeClock(), perMinute = 2)
        assertTrue(limiter.tryAcquire("2001:db8:1:2::1"))
        assertTrue(limiter.tryAcquire("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
        assertFalse(limiter.tryAcquire("2001:db8:1:2::ffff"))
        assertTrue(limiter.tryAcquire("2001:db8:1:3::1"))
    }

    @Test
    fun ipLimiterEvictsIdleAndLeastRecentlyUsedBucketsButNeverActiveOnes() {
        val clock = FakeClock()
        val limiter = IpRateLimiter(clock, perMinute = 2, idleMs = 120_000, maxTracked = 2)
        repeat(2) { assertTrue(limiter.tryAcquire("1.1.1.1")) }
        assertFalse(limiter.tryAcquire("1.1.1.1"))
        assertTrue(limiter.tryAcquire("2.2.2.2"))
        clock.now += 1
        assertFalse(limiter.tryAcquire("1.1.1.1")) // 1.1.1.1 is now the most recently used
        assertTrue(limiter.tryAcquire("3.3.3.3")) // over capacity: evicts 2.2.2.2, not 1.1.1.1
        assertEquals(2, limiter.tracked)
        assertFalse(limiter.tryAcquire("1.1.1.1")) // still exhausted, not reset
        // A flood of new addresses never resets a client that keeps calling.
        repeat(100) {
            limiter.tryAcquire("9.9.9.$it")
            assertFalse(limiter.tryAcquire("1.1.1.1"))
        }
        assertEquals(2, limiter.tracked)
        clock.now += 120_001
        assertTrue(limiter.tryAcquire("4.4.4.4"))
        assertEquals(1, limiter.tracked) // everything idle was dropped
    }

    @Test
    fun serviceStopsCallingUpstreamPastTheGlobalBudget() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val service = SearchService({ calls++; minimal }, clock, globalPerMinute = 2)
        service.search("a")
        service.search("b")
        assertThrows(SearchOverloadedException::class.java) { runBlocking { service.search("c") } }
        service.search("A") // cached answers cost nothing
        assertEquals(2, calls)
        clock.now += 30_000
        service.search("c")
        assertEquals(3, calls)
    }

    @Test
    fun concurrentIdenticalQueriesShareOneUpstreamCall() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val service = SearchService({ calls++; gate.await(); minimal }, FakeClock())
        val first = async { service.search("rick") }
        val second = async { service.search(" RICK ") }
        yield()
        gate.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    @Test
    fun aFailedSharedCallFailsItsWaitersAndIsRetriedNextTime() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val service = SearchService({ calls++; gate.await(); if (calls == 1) error("down") else minimal }, FakeClock())
        val first = async { runCatching { service.search("rick") } }
        val second = async { runCatching { service.search("rick") } }
        yield()
        gate.complete(Unit)
        assertTrue(first.await().isFailure && second.await().isFailure)
        assertEquals(1, service.search("rick").size)
        assertEquals(2, calls)
    }

    private fun depsWith(globalPerMinute: Int = Limits.SEARCH_GLOBAL_PER_MINUTE, backend: SearchBackend) = ServerDeps(
        config = ServerConfig(0, "https://ritme.test", File("build/no-web")),
        search = SearchService(backend, SystemClock, globalPerMinute = globalPerMinute),
    )

    @Test
    fun endpointAnswers503PastTheGlobalUpstreamBudget() = testApplication {
        application { ritmeModule(depsWith(globalPerMinute = 2) { minimal }) }
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=a").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=b").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/search?q=c").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=a").status)
    }

    @Test
    fun endpointReturnsJsonResults() = testApplication {
        application { ritmeModule(depsWith { minimal }) }
        val response = client.get("/api/search?q=rick")
        assertEquals(HttpStatusCode.OK, response.status)
        val results = RitmeJson.decodeFromString(ListSerializer(SearchResult.serializer()), response.bodyAsText())
        assertEquals("dQw4w9WgXcQ", results.single().videoId)
    }

    @Test
    fun endpointValidatesTheQuery() = testApplication {
        application { ritmeModule(depsWith { minimal }) }
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/search?q=%20").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/search?q=" + "a".repeat(Limits.SEARCH_MAX_QUERY_LENGTH + 1)).status)
    }

    @Test
    fun endpointRateLimitsPerForwardedIp() = testApplication {
        application { ritmeModule(depsWith { minimal }) }
        repeat(Limits.SEARCH_PER_MINUTE) {
            assertEquals(HttpStatusCode.OK, client.get("/api/search?q=q$it") { header("X-Forwarded-For", "9.9.9.9") }.status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/search?q=more") { header("X-Forwarded-For", "9.9.9.9") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=other") { header("X-Forwarded-For", "8.8.8.8") }.status)
    }

    @Test
    fun endpointReportsBackendFailureAsBadGateway() = testApplication {
        application { ritmeModule(depsWith { error("down") }) }
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/search?q=rick").status)
    }

    @Test
    fun ipLimiterBurstAndRefillAreSeparateAndPeekingIsFree() {
        val clock = FakeClock()
        val limiter = IpRateLimiter(clock, perMinute = 10, burst = 30)
        repeat(100) { assertTrue(limiter.hasToken("1.1.1.1")) }
        repeat(30) { assertTrue(limiter.tryAcquire("1.1.1.1")) }
        assertFalse(limiter.hasToken("1.1.1.1"))
        clock.now += 6_000 // 10 per minute
        assertTrue(limiter.tryAcquire("1.1.1.1"))
        assertFalse(limiter.tryAcquire("1.1.1.1"))
        clock.now += 150_000 // 2.5 min idle: still tracked, refilling (not reset early)
        repeat(25) { assertTrue(limiter.tryAcquire("1.1.1.1")) }
        assertFalse(limiter.tryAcquire("1.1.1.1"))
    }
}
