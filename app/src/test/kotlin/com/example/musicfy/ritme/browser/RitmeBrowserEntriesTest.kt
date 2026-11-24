package com.example.musicfy.ritme.browser

import com.example.musicfy.ritme.media.Candidate
import com.example.musicfy.ritme.media.FoundVia
import com.example.musicfy.ritme.media.MediaFormat
import com.example.musicfy.ritme.media.PageFindings
import com.example.musicfy.ritme.media.ProbeVerdict
import com.example.musicfy.ritme.media.Refusal
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeBrowserEntriesTest {
    private val candidate = Candidate("https://cdn.example.com/films/My%20Film.mp4", StreamType.FILE, setOf(FoundVia.NETWORK), 0, 0)
    private val findings = PageFindings(
        pageUrl = "https://www.example.com/watch/1",
        candidates = listOf(candidate),
        title = "Watch: My Film | Example",
        ogTitle = "My Film",
        siteName = "Example TV",
    )
    private val ok = ProbeVerdict(candidate.url, StreamType.FILE, MediaFormat.MP4, durationMs = 90_000)

    @Test
    fun theTitleComesFromOgTitleThenThePageThenTheFileThenTheSite() {
        assertEquals("My Film", RitmeBrowserEntries.defaultTitle(candidate, findings))
        assertEquals("Watch: My Film | Example", RitmeBrowserEntries.defaultTitle(candidate, findings.copy(ogTitle = null)))
        assertEquals("My Film.mp4", RitmeBrowserEntries.defaultTitle(candidate, findings.copy(ogTitle = null, title = " ")))
        assertEquals("example.com", RitmeBrowserEntries.defaultTitle(candidate.copy(url = "https://cdn.example.com/"), findings.copy(ogTitle = null, title = null)))
        assertEquals("My Film.mp4", RitmeBrowserEntries.rowTitle(candidate, findings))
        assertEquals("Klip", RitmeBrowserEntries.rowTitle(candidate.copy(name = "Klip"), findings))
    }

    @Test
    fun theSourceIsTheSiteNameElseTheDomain() {
        assertEquals("Example TV", RitmeBrowserEntries.source(findings, candidate))
        assertEquals("example.com", RitmeBrowserEntries.source(findings.copy(siteName = null), candidate))
    }

    @Test
    fun theEntryCarriesWhatTheCheckFound() {
        assertEquals(
            NewEntry("", "My Film", "Example TV", null, 90_000, MediaKind.URL, candidate.url, StreamType.FILE, null),
            RitmeBrowserEntries.entryOf(candidate, ok, "My Film", "Example TV", findings.pageUrl),
        )
        val hls = candidate.copy(url = "https://cdn.example.com/live/index.m3u8", streamType = StreamType.HLS)
        val live = ProbeVerdict(hls.url, StreamType.HLS, MediaFormat.HLS, live = true, needsReferrer = true, webPlayable = false)
        val entry = RitmeBrowserEntries.entryOf(hls, live, "Live", "Example TV", "https://www.example.com/live")!!
        assertEquals(0L, entry.durationMs)
        assertEquals("https://www.example.com/", entry.referrer)
        assertEquals(StreamType.HLS, entry.streamType)
        assertNull(entry.thumbnailUrl)
    }

    @Test
    fun refusedOrUnsendableFindsHaveNoEntry() {
        listOf(Refusal.DRM, Refusal.NEEDS_LOGIN, Refusal.FAILED, Refusal.LENGTH_UNKNOWN).forEach {
            assertNull(it.name, RitmeBrowserEntries.entryOf(candidate, ok.copy(refusal = it), "T", "S", findings.pageUrl))
        }
        // needs a Referer, but the page's origin is not one that may be sent
        assertNull(RitmeBrowserEntries.entryOf(candidate, ok.copy(needsReferrer = true), "T", "S", "http://192.168.1.1/"))
        assertNull(RitmeBrowserEntries.entryOf(candidate, ok.copy(streamType = StreamType.UNKNOWN), "T", "S", findings.pageUrl)!!.streamType)
    }

    @Test
    fun notesWarnAboutKeysExpiryNetworkAndWhereItPlays() {
        val url = "https://cdn.example.com/v.m3u8?token=abc&expires=1700000600&ip=203.0.113.9"
        assertEquals(
            listOf(BrowserNote.SENSITIVE, BrowserNote.EXPIRES, BrowserNote.IP_LOCKED, BrowserNote.NOT_ON_WEB, BrowserNote.KEY_MAY_NEED_LOGIN),
            RitmeBrowserEntries.notes(url, ok.copy(webPlayable = false, aes128 = true), 1_700_000_000_000),
        )
        assertEquals(listOf(BrowserNote.EXPIRED), RitmeBrowserEntries.notes("https://cdn.example.com/v.mp4?exp=1600000000", ok, 1_700_000_000_000))
        assertEquals(listOf(BrowserNote.APP_ONLY), RitmeBrowserEntries.notes(candidate.url, ok.copy(needsReferrer = true, webPlayable = false), 0))
        assertEquals(emptyList<BrowserNote>(), RitmeBrowserEntries.notes(candidate.url, ok, 0))
    }

    @Test
    fun typeLabels() {
        assertEquals("HLS", RitmeBrowserEntries.typeLabel("https://x.example/a", StreamType.HLS))
        assertEquals("DASH", RitmeBrowserEntries.typeLabel("https://x.example/a", StreamType.DASH))
        assertEquals("MP4", RitmeBrowserEntries.typeLabel("https://x.example/a.mp4", StreamType.FILE))
        assertEquals("WebM", RitmeBrowserEntries.typeLabel("https://x.example/a.webm", StreamType.UNKNOWN))
        assertNull(RitmeBrowserEntries.typeLabel("https://x.example/stream", StreamType.UNKNOWN))
    }
}
