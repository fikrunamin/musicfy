package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateCollectorTest {

    private val page = "https://example.com/film/1"
    private val hls = "https://cdn.example.com/v/master.m3u8"
    private val mp4 = "https://cdn.example.com/v/film.mp4"

    private fun collector() = CandidateCollector().also { it.startPage(page) }

    private fun video(url: String? = null, blob: Boolean = false, durationMs: Long? = null, area: Long = 0, playing: Boolean = false, live: Boolean = false) =
        PageVideo(listOfNotNull(url), blob, durationMs, live, 1280, 720, area, playing)

    private fun report(
        videos: List<PageVideo> = emptyList(),
        og: List<String> = emptyList(),
        ld: List<PageLdVideo> = emptyList(),
        mainFrame: Boolean = true,
        frameUrl: String? = page,
        eme: Boolean = false,
    ) = PageReport(frameUrl, mainFrame, "Film | Example", "Film", "Example TV", og, ld, videos, eme)

    @Test
    fun requestsBecomeCandidatesSegmentsOnlyMarkAStream() {
        val c = collector()
        assertTrue(c.onRequest(hls, "GET", emptyMap(), 1))
        assertTrue(c.onRequest(mp4, "GET", emptyMap(), 2))
        assertTrue(c.onRequest("https://cdn.example.com/v/seg1.ts", "GET", emptyMap(), 3))
        assertFalse(c.onRequest("https://cdn.example.com/v/seg2.ts", "GET", emptyMap(), 4))
        assertFalse(c.onRequest("https://cdn.example.com/logo.png", "GET", emptyMap(), 5))
        assertFalse(c.onRequest("https://pubads.g.doubleclick.net/ad.mp4", "GET", emptyMap(), 6))
        assertFalse(c.onRequest("http://10.0.0.2/v.mp4", "GET", emptyMap(), 7))
        val findings = c.findings()
        assertEquals(setOf(hls, mp4), findings.candidates.map { it.url }.toSet())
        assertTrue(findings.streamSeen)
        assertEquals(StreamType.HLS, findings.candidates.first { it.url == hls }.streamType)
    }

    @Test
    fun theSameVideoSeenTwiceIsOneCandidate() {
        val c = collector()
        c.onRequest("$mp4#t=3", "GET", emptyMap(), 1)
        assertTrue(c.onReport(report(listOf(video(mp4))), 2))
        val single = c.findings().candidates.single()
        assertEquals(mp4, single.url)
        assertEquals(setOf(FoundVia.NETWORK, FoundVia.PAGE_VIDEO), single.via)
        assertEquals(1L, single.firstSeenMs)
        assertEquals(2L, single.lastSeenMs)
    }

    @Test
    fun atMostFiftyPerPage() {
        val c = collector()
        repeat(60) { c.onRequest("https://cdn.example.com/v/$it.mp4", "GET", emptyMap(), it.toLong()) }
        assertEquals(CandidateCollector.MAX_CANDIDATES, c.findings().candidates.size)
    }

    @Test
    fun theLargestPlayingVideoComesFirstThenTheLongestThenTheNewest() {
        val c = collector()
        val small = "https://cdn.example.com/small.mp4"
        val long = "https://cdn.example.com/long.mp4"
        val newest = "https://cdn.example.com/newest.mp4"
        c.onRequest(newest, "GET", emptyMap(), 9)
        c.onReport(report(listOf(video(small, area = 10_000, playing = true), video(long, durationMs = 7_200_000, area = 900_000), video(mp4, area = 300_000, playing = true))), 5)
        assertEquals(listOf(mp4, small, long, newest), c.findings().candidates.map { it.url })
    }

    @Test
    fun aStreamPlayedThroughABlobGetsThatVideosFacts() {
        val c = collector()
        c.onRequest(hls, "GET", emptyMap(), 1)
        c.onReport(report(listOf(video(blob = true, area = 200_000, playing = true, live = true))), 2)
        val candidate = c.findings().candidates.single()
        assertTrue(candidate.playing)
        assertTrue(candidate.pageLive)
        assertEquals(200_000L, candidate.area)
        assertFalse(c.findings().blobOnly)
    }

    @Test
    fun onlyABlobMeansNothingCanBeShared() {
        val c = collector()
        c.onReport(report(listOf(video(blob = true, playing = true))), 1)
        assertTrue(c.findings().blobOnly)
    }

    @Test
    fun youTubePagesAndEmbedsOfferTheirVideo() {
        val c = CandidateCollector()
        c.startPage("https://m.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", c.findings().youTubeVideoId)
        assertTrue(c.urlChanged("https://m.youtube.com/"))
        assertNull(c.findings().youTubeVideoId)
        c.startPage(page)
        c.onReport(report(og = listOf("https://www.youtube.com/embed/jfKfPfyJRdk")), 1)
        assertEquals("jfKfPfyJRdk", c.findings().youTubeVideoId)
        c.startPage(page)
        c.onReport(report(mainFrame = false, frameUrl = "https://www.youtube-nocookie.com/embed/nPSvUczm8M0"), 1)
        assertEquals("nPSvUczm8M0", c.findings().youTubeVideoId)
        assertFalse(c.findings().blobOnly)
    }

    @Test
    fun openGraphAndJsonLdAddCandidatesWithLengthsAndNames() {
        val c = collector()
        c.onReport(report(og = listOf("https://cdn.example.com/og.mp4"), ld = listOf(PageLdVideo("https://cdn.example.com/ld.m3u8", 120_000, "Klip"))), 1)
        val byUrl = c.findings().candidates.associateBy { it.url }
        assertEquals(setOf(FoundVia.OPEN_GRAPH), byUrl.getValue("https://cdn.example.com/og.mp4").via)
        assertEquals(120_000L, byUrl.getValue("https://cdn.example.com/ld.m3u8").pageDurationMs)
        assertEquals("Klip", byUrl.getValue("https://cdn.example.com/ld.m3u8").name)
    }

    @Test
    fun theMainFrameNamesThePageAndDrmMarksIt() {
        val c = collector()
        c.onReport(report(mainFrame = false, frameUrl = "https://ads.example.net/frame").copy(title = "Ad"), 1)
        assertNull(c.findings().title)
        c.onReport(report(), 2)
        assertEquals("Film | Example", c.findings().title)
        assertEquals("Film", c.findings().ogTitle)
        assertEquals("Example TV", c.findings().siteName)
        assertFalse(c.findings().eme)
        assertTrue(c.onProtectedMedia())
        assertFalse(c.onProtectedMedia())
        assertTrue(c.findings().eme)
    }

    @Test
    fun aNewPageForgetsTheOldOne() {
        val c = collector()
        c.onRequest(hls, "GET", emptyMap(), 1)
        c.onProtectedMedia()
        c.startPage("https://example.com/other")
        assertEquals(PageFindings(pageUrl = "https://example.com/other"), c.findings())
    }

    @Test
    fun hiddenUrlsLeaveTheRanking() {
        val a = Candidate(hls, StreamType.HLS, setOf(FoundVia.NETWORK), 1, 1)
        val b = Candidate("https://cdn.example.com/v/720p/index.m3u8", StreamType.HLS, setOf(FoundVia.NETWORK), 2, 2)
        assertEquals(listOf(a), CandidateRanking.rank(listOf(a, b), hidden = setOf(b.url)))
    }

    @Test
    fun fragmentsAreStrippedFromEverySource() {
        val c = collector()
        assertTrue(c.onReport(report(videos = listOf(video("https://cdn.example.com/a.mp4#t=10")), og = listOf("https://cdn.example.com/b.mp4#x"), ld = listOf(PageLdVideo("https://cdn.example.com/c.m3u8#y", null, null))), 1))
        assertTrue(c.onRequest("https://cdn.example.com/d.mp4#z", "GET", emptyMap(), 2))
        assertEquals(
            setOf("https://cdn.example.com/a.mp4", "https://cdn.example.com/b.mp4", "https://cdn.example.com/c.m3u8", "https://cdn.example.com/d.mp4"),
            c.findings().candidates.map { it.url }.toSet(),
        )
    }

    @Test
    fun hostileReportsCannotSmuggleInPrivateOrCredentialedUrls() {
        val c = collector()
        val bad = listOf("http://192.168.1.1/a.mp4", "https://user:pw@cdn.example.com/a.mp4")
        c.onReport(report(videos = listOf(PageVideo(bad, false, null, false, 1, 1, 1, true)), og = bad, ld = bad.map { PageLdVideo(it, null, null) }), 1)
        assertTrue(c.findings().candidates.isEmpty())
    }

    @Test
    fun theFiftyCapHoldsAcrossFramesAndReports() {
        val c = collector()
        repeat(10) { frame ->
            val urls = (0 until 10).map { "https://cdn.example.com/f$frame/$it.mp4" }
            c.onReport(report(videos = listOf(PageVideo(urls.take(5), false, null, false, 1, 1, 1, false), PageVideo(urls.drop(5), false, null, false, 1, 1, 1, false)), mainFrame = frame == 0, frameUrl = "https://example.com/f$frame"), frame.toLong())
        }
        assertEquals(CandidateCollector.MAX_CANDIDATES, c.findings().candidates.size)
    }
}
