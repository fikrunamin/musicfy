package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.RitmeJson
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.File

class VideoTest {
    private fun resource(name: String) = javaClass.getResource("/$name")!!.readText()
    private val video = resource("innertube-get-queue-video.json")
    private val live = resource("innertube-get-queue-live-recorded.json")
    private val wrapped = resource("innertube-get-queue-wrapped.json")
    private val empty = resource("innertube-get-queue-empty.json")
    private val searchJson = resource("innertube-search-minimal.json")
    private val id = "dQw4w9WgXcQ"
    private val liveId = "jfKfPfyJRdk"
    private val ugcQueue = resource("innertube-get-queue-ugc-recorded.json")
    private val ugcPlayer = resource("innertube-player-ugc-recorded.json")
    private val ugcId = "nPSvUczm8M0"
    private val ugcInfo = VideoInfo(
        ugcId, "nobody wants your AI app...", "Lama Dev", 627_000,
        "https://i.ytimg.com/vi_webp/nPSvUczm8M0/maxresdefault.webp", live = false,
    )

    /** A `player` answer whose videoDetails holds the video id, [title] (left out when null) and [extra] fields. */
    private fun details(videoId: String = ugcId, title: String? = "A video", extra: String = "") =
        """{"videoDetails":{"videoId":"$videoId"${title?.let { ",\"title\":\"$it\"" }.orEmpty()},"author":"A channel"$extra}}"""

    @Test
    fun parsesTitleChannelDurationAndTheLargestYoutubeThumbnail() {
        assertEquals(
            VideoInfo(id, "Never Gonna Give You Up", "Rick Astley", 214_000, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", live = false),
            InnerTubeVideoParser.parse(video, id),
        )
    }

    @Test
    fun aRecordedLiveStreamIsLiveDespiteItsElapsedLengthText() {
        val info = InnerTubeVideoParser.parse(live, liveId)!!
        assertTrue(info.title, info.title.startsWith("lofi hip hop radio"))
        assertEquals("Lofi Girl", info.channel)
        assertEquals(0L, info.durationMs)
        assertTrue(info.live)
        assertTrue("${info.thumbnailUrl}", info.thumbnailUrl!!.startsWith("https://i.ytimg.com/vi/$liveId/"))
    }

    @Test
    fun onlyAParsableLengthUnderTwelveHoursIsAVideo() {
        assertEquals(214_000L, InnerTubeVideoParser.lengthMs("3:34"))
        assertEquals(3_723_000L, InnerTubeVideoParser.lengthMs("1:02:03"))
        assertEquals(43_199_000L, InnerTubeVideoParser.lengthMs("11:59:59"))
        listOf(null, "", "0:00", "12:00:00", "99:59:59", "33,778:11:52", "LIVE", "3:4").forEach {
            assertNull("$it", InnerTubeVideoParser.lengthMs(it))
        }
    }

    @Test
    fun readsTheWrappedRendererAndDropsThumbnailsFromOtherHosts() {
        val info = InnerTubeVideoParser.parse(wrapped, id)!!
        assertEquals("Never Gonna Give You Up", info.title)
        assertEquals(213_000, info.durationMs)
        assertNull(info.thumbnailUrl)
    }

    @Test
    fun noRendererOrAnotherVideoMeansNotFound() {
        assertNull(InnerTubeVideoParser.parse(empty, id))
        assertNull(InnerTubeVideoParser.parse("{}", id))
        assertNull(InnerTubeVideoParser.parse(video, "aaaaaaaaaaa"))
    }

    @Test
    fun parsesARecordedResponse() {
        val info = InnerTubeVideoParser.parse(resource("innertube-get-queue-recorded.json"), id)!!
        assertTrue(info.title, info.title.contains("Never Gonna Give You Up"))
        assertTrue(info.channel, info.channel.contains("Rick Astley"))
        assertEquals(214_000L, info.durationMs)
        assertFalse(info.live)
        assertNotNull(info.thumbnailUrl)
    }

    @Test
    fun backendTurnsYoutubes404And400IntoAnEmptyAnswerAndOtherFailuresIntoErrors() = testApplication {
        routing {
            post("/missing") {
                call.respondText("""{"error":{"code":404,"status":"NOT_FOUND"}}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
            post("/bad") { call.respondText("""{"error":{"code":400}}""", ContentType.Application.Json, HttpStatusCode.BadRequest) }
            post("/down") { call.respondText("", status = HttpStatusCode.InternalServerError) }
            post("/ok") { call.respondText(video, ContentType.Application.Json) }
        }
        assertEquals("{}", InnerTubeVideoBackend(client, "/missing").fetch(id))
        assertEquals("{}", InnerTubeVideoBackend(client, "/bad").fetch(id))
        assertEquals(video, InnerTubeVideoBackend(client, "/ok").fetch(id))
        assertTrue(runCatching { InnerTubeVideoBackend(client, "/down").fetch(id) }.isFailure)
    }

    @Test
    fun anUnknownIdIsCachedAsMissingForFiveMinutes() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        // "{}" is what InnerTubeVideoBackend returns for YouTube's HTTP 404.
        val service = VideoInfoService({ calls++; "{}" }, clock)
        assertNull(service.lookup("AAAAAAAAAAA"))
        assertNull(service.lookup("AAAAAAAAAAA"))
        assertEquals(1, calls)
        clock.now += VideoInfoService.SHORT_TTL_MS
        assertNull(service.lookup("AAAAAAAAAAA"))
        assertEquals(2, calls)
    }

    @Test
    fun requestBodyAsksTheWebRemixClientForOneVideo() {
        val body = RitmeJson.parseToJsonElement(InnerTubeVideoBackend.requestBody(id)).jsonObject
        assertEquals(listOf(id), body.getValue("videoIds").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("WEB_REMIX", body.getValue("context").jsonObject.getValue("client").jsonObject.getValue("clientName").jsonPrimitive.content)
    }

    @Test
    fun videoIdsAreElevenUrlSafeCharacters() {
        assertTrue(VideoInfoService.isVideoId(id))
        assertTrue(VideoInfoService.isVideoId("aqz-KE-bpKQ"))
        listOf("", "abc", "dQw4w9WgXcQx", "dQw4w9WgXc!", "dQw4w9WgXc ").forEach { assertFalse(it, VideoInfoService.isVideoId(it)) }
    }

    @Test
    fun serviceKeepsVideosSixHoursAndLiveOrMissingOnesFiveMinutes() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val service = VideoInfoService({
            calls++
            when (it) {
                id -> video
                liveId -> live
                else -> empty
            }
        }, clock)
        service.lookup(id)
        service.lookup(liveId)
        assertNull(service.lookup("aaaaaaaaaaa"))
        assertEquals(3, calls)
        clock.now += VideoInfoService.SHORT_TTL_MS - 1
        listOf(id, liveId, "aaaaaaaaaaa").forEach { service.lookup(it) }
        assertEquals(3, calls)
        clock.now += 1
        listOf(id, liveId, "aaaaaaaaaaa").forEach { service.lookup(it) }
        assertEquals(5, calls)
        clock.now += VideoInfoService.TTL_MS - VideoInfoService.SHORT_TTL_MS
        service.lookup(id)
        assertEquals(6, calls)
    }

    @Test
    fun concurrentLookupsOfOneVideoShareOneUpstreamCall() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val service = VideoInfoService({ calls++; gate.await(); video }, FakeClock())
        val first = async { service.lookup(id) }
        val second = async { service.lookup(id) }
        yield()
        gate.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, calls)
    }

    private fun depsWith(
        budget: UpstreamBudget = UpstreamBudget(clock = SystemClock),
        player: VideoDetailsBackend? = null,
        backend: VideoBackend,
    ) = ServerDeps(
        config = ServerConfig(0, "https://ritme.test", File("build/no-web")),
        upstreamBudget = budget,
        search = SearchService({ searchJson }, SystemClock, budget = budget),
        video = VideoInfoService(backend, SystemClock, budget, player = player),
    )

    @Test
    fun endpointReturnsTheVideoAsJson() = testApplication {
        application { ritmeModule(depsWith { video }) }
        val response = client.get("/api/video?id=$id")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(InnerTubeVideoParser.parse(video, id), RitmeJson.decodeFromString(VideoInfo.serializer(), response.bodyAsText()))
    }

    @Test
    fun endpointValidatesTheId() = testApplication {
        application { ritmeModule(depsWith { video }) }
        listOf("/api/video", "/api/video?id=", "/api/video?id=abc", "/api/video?id=dQw4w9WgXcQx", "/api/video?id=dQw4w9WgXc%21")
            .forEach { assertEquals(it, HttpStatusCode.BadRequest, client.get(it).status) }
    }

    @Test
    fun endpointAnswers404WhenYoutubeReturnsNoVideo() = testApplication {
        var calls = 0
        application { ritmeModule(depsWith { calls++; "{}" }) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/video?id=AAAAAAAAAAA").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/video?id=AAAAAAAAAAA").status)
        assertEquals(1, calls) // the miss is cached
    }

    @Test
    fun endpointRateLimitsPerIpSeparatelyFromSearch() = testApplication {
        application { ritmeModule(depsWith { video }) }
        repeat(Limits.VIDEO_PER_MINUTE) {
            assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id") { header("X-Forwarded-For", "9.9.9.9") }.status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/api/video?id=$id") { header("X-Forwarded-For", "9.9.9.9") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=rick") { header("X-Forwarded-For", "9.9.9.9") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id") { header("X-Forwarded-For", "8.8.8.8") }.status)
    }

    @Test
    fun endpointSharesTheUpstreamBudgetWithSearch() = testApplication {
        application { ritmeModule(depsWith(budget = UpstreamBudget(perMinute = 2, clock = SystemClock)) { video }) }
        assertEquals(HttpStatusCode.OK, client.get("/api/search?q=a").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/video?id=aaaaaaaaaaa").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/search?q=b").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/video?id=$id").status) // cached answers cost nothing
    }

    @Test
    fun endpointReportsBackendFailureAsBadGateway() = testApplication {
        application { ritmeModule(depsWith { error("down") }) }
        assertEquals(HttpStatusCode.BadGateway, client.get("/api/video?id=$id").status)
    }

    // Non-music (UGC) videos: YouTube Music's get_queue answers an unplayable stub, the player
    // endpoint's videoDetails has the metadata.

    @Test
    fun getQueuesUnplayableStubForAUgcVideoIsNotAVideo() {
        assertNull(InnerTubeVideoParser.parse(ugcQueue, ugcId))
    }

    @Test
    fun parsesARecordedPlayerResponsesVideoDetails() {
        assertEquals(ugcInfo, InnerTubePlayerParser.parse(ugcPlayer, ugcId))
    }

    @Test
    fun serviceFallsBackToThePlayerForAUgcVideo() = runBlocking {
        var playerCalls = 0
        val service = VideoInfoService({ ugcQueue }, FakeClock(), player = { playerCalls++; ugcPlayer })
        assertEquals(ugcInfo, service.lookup(ugcId))
        assertEquals(ugcInfo, service.lookup(ugcId))
        assertEquals(1, playerCalls) // cached under the same key
    }

    @Test
    fun aMusicVideoIsServedByGetQueueAloneAndNeverAsksThePlayer() = runBlocking {
        var playerCalls = 0
        val service = VideoInfoService({ video }, FakeClock(), player = { playerCalls++; ugcPlayer })
        assertEquals(InnerTubeVideoParser.parse(video, id), service.lookup(id))
        assertEquals(0, playerCalls)
    }

    @Test
    fun aUgcVideoIsKeptSixHours() = runBlocking {
        val clock = FakeClock()
        var calls = 0
        val service = VideoInfoService({ calls++; ugcQueue }, clock, player = { ugcPlayer })
        service.lookup(ugcId)
        clock.now += VideoInfoService.TTL_MS - 1
        service.lookup(ugcId)
        assertEquals(1, calls)
        clock.now += 1
        service.lookup(ugcId)
        assertEquals(2, calls)
    }

    @Test
    fun whenBothMissNullIsCachedFiveMinutesAndCostsTwoCallsPerWindow() = runBlocking {
        val clock = FakeClock()
        var queueCalls = 0
        var playerCalls = 0
        // "{}" is what both backends return for YouTube's HTTP 404.
        val service = VideoInfoService({ queueCalls++; "{}" }, clock, player = { playerCalls++; "{}" })
        assertNull(service.lookup("AAAAAAAAAAA"))
        assertNull(service.lookup("AAAAAAAAAAA"))
        assertEquals(1 to 1, queueCalls to playerCalls)
        clock.now += VideoInfoService.SHORT_TTL_MS
        assertNull(service.lookup("AAAAAAAAAAA"))
        assertEquals(2 to 2, queueCalls to playerCalls)
    }

    @Test
    fun liveVideoDetailsAreLiveWithoutADuration() {
        listOf(
            ""","isLive":true,"isLiveContent":true,"lengthSeconds":"0"""",
            ""","isLive":true,"isLiveContent":true,"lengthSeconds":"627"""",
            ""","isLiveContent":true,"lengthSeconds":"0"""",
            ""","isLiveContent":true""",
            ""","lengthSeconds":"43200"""", // 12 h or longer: taken for live, like get_queue's lengths
            ""","lengthSeconds":"0"""",
            ""","lengthSeconds":"soon"""",
        ).forEach { extra ->
            val info = InnerTubePlayerParser.parse(details(extra = extra), ugcId)!!
            assertEquals(extra, 0L, info.durationMs)
            assertTrue(extra, info.live)
        }
    }

    @Test
    fun aFinishedLiveStreamWithALengthIsAVideo() {
        val info = InnerTubePlayerParser.parse(details(extra = ""","isLiveContent":true,"lengthSeconds":"3600""""), ugcId)!!
        assertEquals(VideoInfo(ugcId, "A video", "A channel", 3_600_000, null, live = false), info)
    }

    @Test
    fun playerThumbnailsFromOtherHostsAreDropped() {
        val extra = ""","lengthSeconds":"60","thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/vi/x/default.jpg"},{"url":"https://evil.example/x.jpg"}]}"""
        assertNull(InnerTubePlayerParser.parse(details(extra = extra), ugcId)!!.thumbnailUrl)
    }

    @Test
    fun playerDetailsOfAnotherVideoOrWithoutATitleMeanNotFound() {
        assertNull(InnerTubePlayerParser.parse(ugcPlayer, "aaaaaaaaaaa"))
        assertNull(InnerTubePlayerParser.parse(details(videoId = "aaaaaaaaaaa"), ugcId))
        assertNull(InnerTubePlayerParser.parse(details(title = null), ugcId))
        assertNull(InnerTubePlayerParser.parse(details(title = " "), ugcId))
        assertNull(InnerTubePlayerParser.parse("{}", ugcId))
        assertNull(InnerTubePlayerParser.parse("""{"playabilityStatus":{"status":"ERROR"}}""", ugcId))
    }

    @Test
    fun playerRequestAsksTheWebClientForOneVideo() {
        val body = RitmeJson.parseToJsonElement(InnerTubePlayerBackend.requestBody(ugcId)).jsonObject
        assertEquals(ugcId, body.getValue("videoId").jsonPrimitive.content)
        val client = body.getValue("context").jsonObject.getValue("client").jsonObject
        assertEquals("WEB", client.getValue("clientName").jsonPrimitive.content)
        assertEquals(InnerTubeSearchBackend.WEB_CLIENT_VERSION, client.getValue("clientVersion").jsonPrimitive.content)
    }

    @Test
    fun playerBackendTurnsYoutubes404And400IntoAnEmptyAnswerAndWarnsOn400WithoutTheId() = testApplication {
        routing {
            post("/missing") {
                call.respondText("""{"error":{"code":404,"status":"NOT_FOUND"}}""", ContentType.Application.Json, HttpStatusCode.NotFound)
            }
            post("/bad") { call.respondText("""{"error":{"code":400}}""", ContentType.Application.Json, HttpStatusCode.BadRequest) }
            post("/down") { call.respondText("", status = HttpStatusCode.InternalServerError) }
            post("/ok") {
                val client = call.request.headers["X-YouTube-Client-Name"]
                val body = call.receiveText()
                if (client == "1" && body.contains(ugcId)) call.respondText(ugcPlayer, ContentType.Application.Json)
                else call.respondText("", status = HttpStatusCode.BadRequest)
            }
        }
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(InnerTubePlayerBackend::class.java) as Logger
        logger.addAppender(appender)
        try {
            assertEquals("{}", InnerTubePlayerBackend(client, "/missing").fetch(ugcId))
            assertTrue(appender.list.isEmpty())
            assertEquals("{}", InnerTubePlayerBackend(client, "/bad").fetch(ugcId))
            assertEquals(1, appender.list.size)
            assertEquals(Level.WARN, appender.list[0].level)
            assertFalse(appender.list[0].formattedMessage.contains(ugcId))
            assertEquals(ugcPlayer, InnerTubePlayerBackend(client, "/ok").fetch(ugcId))
            assertTrue(runCatching { InnerTubePlayerBackend(client, "/down").fetch(ugcId) }.isFailure)
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun theFallbackSpendsASecondTokenOfTheSharedBudget() = runBlocking {
        val clock = FakeClock()
        val two = VideoInfoService({ ugcQueue }, clock, UpstreamBudget(perMinute = 2, clock = clock), player = { ugcPlayer })
        assertEquals(ugcInfo, two.lookup(ugcId))
        assertTrue(runCatching { two.lookup(id) }.exceptionOrNull() is UpstreamOverloadedException)

        var playerCalls = 0
        val one = VideoInfoService({ ugcQueue }, clock, UpstreamBudget(perMinute = 1, clock = clock), player = { playerCalls++; ugcPlayer })
        assertTrue(runCatching { one.lookup(ugcId) }.exceptionOrNull() is UpstreamOverloadedException)
        assertEquals(0, playerCalls)
    }

    @Test
    fun endpointReturnsAUgcVideoFromThePlayerFallback() = testApplication {
        application { ritmeModule(depsWith(player = { ugcPlayer }) { ugcQueue }) }
        val response = client.get("/api/video?id=$ugcId")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ugcInfo, RitmeJson.decodeFromString(VideoInfo.serializer(), response.bodyAsText()))
    }

    @Test
    fun endpointAnswers404WhenNeitherGetQueueNorThePlayerHasTheVideo() = testApplication {
        var calls = 0
        application { ritmeModule(depsWith(player = { calls++; "{}" }) { calls++; "{}" }) }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/video?id=AAAAAAAAAAA").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/video?id=AAAAAAAAAAA").status)
        assertEquals(2, calls) // one each; the miss is cached
    }
}
