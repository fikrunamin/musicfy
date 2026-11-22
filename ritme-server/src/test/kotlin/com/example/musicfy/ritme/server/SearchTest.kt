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
import org.junit.Assert.assertEquals
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

    private fun depsWith(backend: SearchBackend) = ServerDeps(
        config = ServerConfig(0, "https://ritme.test", File("build/no-web")),
        search = SearchService(backend, SystemClock),
    )

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
}
