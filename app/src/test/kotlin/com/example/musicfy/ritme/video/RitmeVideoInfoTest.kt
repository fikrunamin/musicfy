package com.example.musicfy.ritme.video

import com.music.innertube.models.SongItem
import com.music.innertube.models.response.GetQueueResponse
import com.music.innertube.models.response.PlayerResponse
import com.music.innertube.pages.NextPage
import com.music.innertube.pages.WebVideoItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Is YouTube Music's answer usable, else ask the WEB player" on recorded answers (2026-11-24,
 * trimmed; the same recordings as ritme-server's VideoTest): get_queue answers a non-music upload
 * (UGC) with an unplayable stub, the WEB client's player still has its videoDetails.
 */
class RitmeVideoInfoTest {

    // InnerTube's own decoder settings.
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/ritme/innertube/$name")) { name }.bufferedReader().use { it.readText() }

    /** What YouTube.queue makes of a get_queue answer. */
    private fun queue(name: String): List<SongItem> =
        json.decodeFromString<GetQueueResponse>(resource(name)).queueDatas
            .mapNotNull { it.content.playlistPanelVideoRenderer?.let(NextPage::fromPlaylistPanelVideoRenderer) }

    private fun player(name: String): PlayerResponse = json.decodeFromString<PlayerResponse>(resource(name))

    @Test
    fun aMusicVideoIsReadFromGetQueue() {
        assertEquals(
            VideoChoice("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 214, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", live = false),
            RitmeVideoInfo.fromQueue(queue("get-queue-video.json"), "dQw4w9WgXcQ"),
        )
    }

    @Test
    fun getQueuesStubForAnUploadIsNotUsable() {
        assertNull(RitmeVideoInfo.fromQueue(queue("get-queue-ugc-recorded.json"), "nPSvUczm8M0"))
    }

    @Test
    fun anotherVideosAnswerIsNotUsable() {
        assertNull(RitmeVideoInfo.fromQueue(queue("get-queue-video.json"), "nPSvUczm8M0"))
    }

    @Test
    fun theWebPlayersVideoDetailsDescribeTheUpload() {
        val choice = RitmeVideoInfo.fromPlayer(player("player-ugc-recorded.json"), "nPSvUczm8M0")!!
        assertEquals("nobody wants your AI app...", choice.title)
        assertEquals("Lama Dev", choice.channel)
        assertEquals(627, choice.durationSeconds)
        assertEquals(false, choice.live)
        assertEquals("https://i.ytimg.com/vi_webp/nPSvUczm8M0/maxresdefault.webp", choice.thumbnailUrl)
    }

    @Test
    fun aLiveStreamsAgeIsNotItsLength() {
        // lengthSeconds is the stream's age (121601512 s); isLive is absent for the WEB client
        val choice = RitmeVideoInfo.fromPlayer(player("player-live-recorded.json"), "jfKfPfyJRdk")!!
        assertNull(choice.durationSeconds)
        assertTrue(choice.live)
    }

    @Test
    fun thePlayerAnsweringForAnotherVideoIsNotUsable() {
        assertNull(RitmeVideoInfo.fromPlayer(player("player-ugc-recorded.json"), "dQw4w9WgXcQ"))
    }

    @Test
    fun noVideoDetailsIsNotUsable() {
        assertNull(RitmeVideoInfo.fromPlayer(json.decodeFromString<PlayerResponse>("""{"responseContext":{},"playabilityStatus":{"status":"ERROR"}}"""), "nPSvUczm8M0"))
    }

    @Test
    fun anUploadFallsBackToTheWebPlayer() = runBlocking {
        var playerCalls = 0
        val choice = RitmeVideoInfo.info(
            "nPSvUczm8M0",
            queue = { Result.success(queue("get-queue-ugc-recorded.json")) },
            player = { playerCalls++; Result.success(player("player-ugc-recorded.json")) },
        )
        assertEquals("nobody wants your AI app...", choice?.title)
        assertEquals(1, playerCalls)
    }

    @Test
    fun aFailedGetQueueFallsBackToTheWebPlayer() = runBlocking {
        val choice = RitmeVideoInfo.info(
            "nPSvUczm8M0",
            queue = { Result.failure(IllegalStateException("HTTP 500")) },
            player = { Result.success(player("player-ugc-recorded.json")) },
        )
        assertEquals("Lama Dev", choice?.channel)
    }

    @Test
    fun aUsableGetQueueNeverAsksThePlayer() = runBlocking {
        var playerCalls = 0
        RitmeVideoInfo.info(
            "dQw4w9WgXcQ",
            queue = { Result.success(queue("get-queue-video.json")) },
            player = { playerCalls++; Result.failure(IllegalStateException()) },
        )
        assertEquals(0, playerCalls)
    }

    @Test
    fun nothingWhenBothFail() = runBlocking {
        assertNull(
            RitmeVideoInfo.info(
                "nPSvUczm8M0",
                queue = { Result.success(queue("get-queue-ugc-recorded.json")) },
                player = { Result.failure(IllegalStateException("offline")) },
            ),
        )
    }

    @Test
    fun webSearchResultsAreTakenFieldByField() {
        val item = WebVideoItem("nPSvUczm8M0", "nobody wants your AI app...", "Lama Dev", 627, "https://i.ytimg.com/vi/nPSvUczm8M0/hqdefault.jpg", live = false)
        assertEquals(
            VideoChoice("nPSvUczm8M0", "nobody wants your AI app...", "Lama Dev", 627, "https://i.ytimg.com/vi/nPSvUczm8M0/hqdefault.jpg", live = false),
            RitmeVideoInfo.fromWeb(item),
        )
    }

    @Test
    fun theSearchFallsBackToYouTubeMusicWhenWebSearchFailsOrFindsNothing() = runBlocking {
        val song = queue("get-queue-video.json")
        val fromWeb = WebVideoItem("nPSvUczm8M0", "nobody wants your AI app...", "Lama Dev", 627, null, live = false)
        assertEquals(listOf("nPSvUczm8M0"), RitmeVideoInfo.search({ Result.success(listOf(fromWeb)) }, { Result.success(song) }).map { it.videoId })
        assertEquals(listOf("dQw4w9WgXcQ"), RitmeVideoInfo.search({ Result.success(emptyList()) }, { Result.success(song) }).map { it.videoId })
        assertEquals(listOf("dQw4w9WgXcQ"), RitmeVideoInfo.search({ Result.failure(IllegalStateException()) }, { Result.success(song) }).map { it.videoId })
        assertEquals(emptyList<VideoChoice>(), RitmeVideoInfo.search({ Result.failure(IllegalStateException()) }, { Result.failure(IllegalStateException()) }))
    }
}
