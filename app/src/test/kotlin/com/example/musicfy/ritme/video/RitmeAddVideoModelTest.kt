package com.example.musicfy.ritme.video

import com.example.musicfy.ritme.entries.RitmeEntries
import com.example.musicfy.ritme.protocol.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeAddVideoModelTest {

    private class FakeLookup(
        val infos: Map<String, VideoChoice> = emptyMap(),
        val results: List<VideoChoice> = emptyList(),
        val probed: Long? = null,
    ) : VideoLookup {
        val infoCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<String>()
        val probeCalls = mutableListOf<String>()

        override suspend fun info(videoId: String): VideoChoice? {
            infoCalls += videoId
            return infos[videoId]
        }

        override suspend fun search(query: String): List<VideoChoice> {
            searchCalls += query
            return results
        }

        override suspend fun probeDurationMs(url: String): Long? {
            probeCalls += url
            return probed
        }
    }

    private val rick = VideoChoice("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", 213, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", live = false)
    private val lofi = VideoChoice("jfKfPfyJRdk", "lofi hip hop radio", "Lofi Girl", null, "https://i.ytimg.com/vi/jfKfPfyJRdk/hq720_live.jpg", live = true)
    // A non-music upload (UGC): YouTube's general search finds it, get_queue does not.
    private val ugc = VideoChoice("nPSvUczm8M0", "nobody wants your AI app...", "Lama Dev", 627, "https://i.ytimg.com/vi/nPSvUczm8M0/hqdefault.jpg", live = false)

    private fun resolve(lookup: FakeLookup, text: String) = runBlocking { RitmeAddVideoModel(lookup).resolve(text) }

    @Test
    fun blankTextIsEmpty() {
        val lookup = FakeLookup()
        assertEquals(AddVideoState.Empty, resolve(lookup, "   "))
        assertTrue(lookup.searchCalls.isEmpty() && lookup.infoCalls.isEmpty())
    }

    @Test
    fun wordsAreSearchedTrimmed() {
        val lookup = FakeLookup(results = listOf(rick, lofi))
        assertEquals(AddVideoState.Results(listOf(rick, lofi)), resolve(lookup, "  never gonna  "))
        assertEquals(listOf("never gonna"), lookup.searchCalls)
    }

    @Test
    fun wordsNeverAskForInfo() {
        val lookup = FakeLookup(results = listOf(rick))
        resolve(lookup, "rick astley")
        assertTrue(lookup.infoCalls.isEmpty())
    }

    @Test
    fun aSearchWithNothingFoundIsAProblem() {
        assertEquals(AddVideoState.Problem(AddVideoProblem.NO_RESULTS), resolve(FakeLookup(), "zzzz qqqq"))
    }

    @Test
    fun aYouTubeLinkPreviewsTheVideo() {
        val lookup = FakeLookup(infos = mapOf(rick.videoId to rick))
        val expected = RitmeEntries.toVideoEntry(rick.videoId, rick.title, rick.channel, rick.thumbnailUrl, 213)!!
        assertEquals(AddVideoState.Preview(expected, editableTitle = false), resolve(lookup, "https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun aLinkIsNeverSearched() {
        val lookup = FakeLookup(infos = mapOf(rick.videoId to rick), results = listOf(lofi))
        resolve(lookup, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        resolve(lookup, "https://cdn.example.com/v.mp4")
        assertTrue(lookup.searchCalls.isEmpty())
    }

    @Test
    fun aMusicLinkBecomesAMusicEntry() {
        val lookup = FakeLookup(infos = mapOf(rick.videoId to rick))
        val state = resolve(lookup, "https://music.youtube.com/watch?v=dQw4w9WgXcQ") as AddVideoState.Preview
        assertEquals(MediaKind.YOUTUBE_MUSIC, state.entry.kind)
    }

    @Test
    fun aLiveVideoIsAddedAsLive() {
        val lookup = FakeLookup(infos = mapOf(lofi.videoId to lofi))
        val state = resolve(lookup, "https://www.youtube.com/watch?v=jfKfPfyJRdk") as AddVideoState.Preview
        assertEquals(0L, state.entry.durationMs)
    }

    @Test
    fun anUnknownVideoIsNotFoundAndNothingIsMadeUp() {
        assertEquals(AddVideoState.Problem(AddVideoProblem.NOT_FOUND), resolve(FakeLookup(), "https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun aVideoWithoutAVisibleTitleIsNotFound() {
        val blank = rick.copy(title = "​ ")
        assertEquals(
            AddVideoState.Problem(AddVideoProblem.NOT_FOUND),
            resolve(FakeLookup(infos = mapOf(rick.videoId to blank)), "https://youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun aFileLinkIsProbedForItsLengthAndItsTitleIsEditable() {
        val lookup = FakeLookup(probed = 90_000)
        val url = "https://cdn.example.com/films/My%20Movie.mp4"
        val expected = RitmeEntries.toUrlEntry(url, "My Movie.mp4", "cdn.example.com", 90_000)!!
        assertEquals(AddVideoState.Preview(expected, editableTitle = true), resolve(lookup, url))
    }

    @Test
    fun aFileWhoseLengthCannotBeReadIsARetryableProblemNeverLive() {
        // 0 would make it a live entry (Decision 13): joiners at 0:00, seeks ignored, "LIVE" on its row.
        for (url in listOf("https://cdn.example.com/v.webm", "https://cdn.example.com/v.mp4", "https://cdn.example.com/v.m4v", "https://cdn.example.com/v.mov")) {
            val lookup = FakeLookup(probed = null)
            assertEquals(url, AddVideoState.Problem(AddVideoProblem.LENGTH_UNREADABLE), resolve(lookup, url))
            assertEquals(listOf(url), lookup.probeCalls)
        }
    }

    @Test
    fun aStreamIsNeverProbed() {
        val lookup = FakeLookup(probed = 90_000)
        val state = resolve(lookup, "https://cdn.example.com/live/master.m3u8") as AddVideoState.Preview
        assertEquals(0L, state.entry.durationMs)
        assertTrue(lookup.probeCalls.isEmpty())
    }

    @Test
    fun aDirectLinkNeverAsksYouTube() {
        val lookup = FakeLookup()
        resolve(lookup, "https://cdn.example.com/v.mp4")
        assertTrue(lookup.infoCalls.isEmpty())
    }

    @Test
    fun aWebPageIsAProblem() {
        assertEquals(AddVideoState.Problem(AddVideoProblem.PAGE), resolve(FakeLookup(), "https://example.com/watch/123"))
    }

    @Test
    fun anUnsafeLinkIsAProblem() {
        assertEquals(AddVideoState.Problem(AddVideoProblem.UNSAFE), resolve(FakeLookup(), "http://192.168.1.1/v.mp4"))
    }

    @Test
    fun anotherSchemeIsUnrecognized() {
        assertEquals(AddVideoState.Problem(AddVideoProblem.UNRECOGNIZED), resolve(FakeLookup(), "ftp://example.com/v.mp4"))
    }

    @Test
    fun choosingAResultPreviewsIt() {
        val expected = RitmeEntries.toVideoEntry(rick.videoId, rick.title, rick.channel, rick.thumbnailUrl, 213)!!
        assertEquals(AddVideoState.Preview(expected, editableTitle = false), RitmeAddVideoModel(FakeLookup()).choose(rick))
    }

    @Test
    fun choosingAnUploadFromSearchNeedsNoInfoCall() {
        val lookup = FakeLookup()
        val state = RitmeAddVideoModel(lookup).choose(ugc) as AddVideoState.Preview
        assertEquals(MediaKind.YOUTUBE_VIDEO, state.entry.kind)
        assertEquals(627_000L, state.entry.durationMs)
        assertTrue(lookup.infoCalls.isEmpty())
    }

    @Test
    fun choosingAResultTheServerWouldRefuseIsNotFound() {
        assertEquals(
            AddVideoState.Problem(AddVideoProblem.NOT_FOUND),
            RitmeAddVideoModel(FakeLookup()).choose(rick.copy(videoId = "not a valid id!")),
        )
    }

    @Test
    fun anEditedTitleGoesIntoAUrlEntry() {
        val preview = resolve(FakeLookup(probed = 90_000), "https://cdn.example.com/v.mp4") as AddVideoState.Preview
        assertEquals("Holiday", RitmeAddVideoModel.entryOf(preview, "  Holiday ")?.title)
    }

    @Test
    fun aBlankedTitleFallsBackToTheDomain() {
        val preview = resolve(FakeLookup(probed = 90_000), "https://cdn.example.com/v.mp4") as AddVideoState.Preview
        assertEquals("cdn.example.com", RitmeAddVideoModel.entryOf(preview, "   ")?.title)
    }

    @Test
    fun aYouTubeEntryKeepsItsOwnTitle() {
        val preview = RitmeAddVideoModel(FakeLookup()).choose(rick) as AddVideoState.Preview
        assertEquals(rick.title, RitmeAddVideoModel.entryOf(preview, "something else")?.title)
    }

    @Test
    fun onlyAPreviewHasAnEntry() {
        assertNull(RitmeAddVideoModel.entryOf(AddVideoState.Loading, "x"))
    }
}
