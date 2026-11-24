package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MediaProberTest {

    private class FakeHttp(private val answer: (url: String, headers: Map<String, String>) -> ProbeResponse?) : ProbeHttp {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override fun get(url: String, headers: Map<String, String>, maxBytes: Int): ProbeResponse {
            calls += url to headers
            val full = answer(url, headers) ?: throw IOException("no answer")
            return ProbeResponse(full.status, full.contentType, full.body.copyOf(minOf(full.body.size, maxBytes)), full.finalUrl, full.allowOrigin)
        }
    }

    private fun text(url: String, body: String, type: String = "application/vnd.apple.mpegurl", allowOrigin: String? = null) =
        ProbeResponse(200, type, body.toByteArray(), url, allowOrigin)

    private fun bytes(url: String, body: ByteArray, type: String = "video/mp4") = ProbeResponse(200, type, body, url, null)

    private fun status(url: String, code: Int) = ProbeResponse(code, "text/html", "<!doctype html>".toByteArray(), url, null)

    private val web = "https://ritme.dubeli.id"
    private val mp4 = "https://cdn.example.com/films/a.mp4"
    private val master = "https://cdn.example.com/v/master.m3u8"
    private val variant = "https://cdn.example.com/v/360p/index.m3u8"
    private val masterText = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        360p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
        720p/index.m3u8
    """.trimIndent()
    private val vod = "#EXTM3U\n#EXTINF:10.0,\ns0.ts\n#EXTINF:10.0,\ns1.ts\n#EXTINF:4.5,\ns2.ts\n#EXT-X-ENDLIST\n"
    private val live = "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:120\n#EXTINF:6.0,\ns120.ts\n#EXTINF:6.0,\ns121.ts\n"
    private val staticMpd = """<?xml version="1.0"?><MPD type="static" mediaPresentationDuration="PT10M"><Period><Representation height="1080"/></Period></MPD>"""

    @Test
    fun anMp4AnswersWithItsLengthAndPlaysOnTheWeb() {
        val http = FakeHttp { url, _ -> bytes(url, mp4Head(0, 1000, 90_500)) }
        val verdict = MediaProber(http, web).probe(mp4, "https://example.com/film")
        assertEquals(ProbeVerdict(mp4, StreamType.FILE, MediaFormat.MP4, durationMs = 90_500), verdict)
        // as another member: the first 4 KiB, no cookie, no Referer
        assertEquals(mapOf("Range" to "bytes=0-4095"), http.calls.single().second)
    }

    @Test
    fun aMasterPlaylistIsCheckedThroughItsFirstVariant() {
        val http = FakeHttp { url, headers ->
            when (url) {
                master -> text(url, masterText, allowOrigin = if ("Origin" in headers) "*" else null)
                variant -> text(url, vod)
                else -> null
            }
        }
        val verdict = MediaProber(http, web).probe(master, null)
        assertEquals(StreamType.HLS, verdict.streamType)
        assertFalse(verdict.live)
        assertEquals(24_500L, verdict.durationMs)
        assertEquals(720, verdict.height)
        assertEquals(listOf(variant, "https://cdn.example.com/v/720p/index.m3u8"), verdict.variants)
        assertTrue(verdict.webPlayable)
        assertNull(verdict.refusal)
        assertEquals(web, http.calls.last().second["Origin"])
    }

    @Test
    fun aLiveStreamWithoutCorsCannotPlayOnTheWeb() {
        val http = FakeHttp { url, _ -> text(url, live) }
        val verdict = MediaProber(http, web).probe("https://cdn.example.com/live/index.m3u8", null)
        assertTrue(verdict.live)
        assertNull(verdict.durationMs)
        assertFalse(verdict.webPlayable)
        assertNull(verdict.refusal)
    }

    @Test
    fun drmIsRefused() {
        val sampleAes = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://k\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n#EXTINF:6,\ns.ts\n#EXT-X-ENDLIST"
        assertEquals(Refusal.DRM, MediaProber(FakeHttp { url, _ -> text(url, sampleAes) }, web).probe(master, null).refusal)
        val protectedMpd = staticMpd.replace("<Period>", "<Period><ContentProtection value=\"cenc\"/>")
        assertEquals(Refusal.DRM, MediaProber(FakeHttp { url, _ -> text(url, protectedMpd, "application/dash+xml") }, web).probe("https://cdn.example.com/v.mpd", null).refusal)
        assertEquals(Refusal.DRM, MediaProber(FakeHttp { url, _ -> bytes(url, mp4Head(0, 1000, 1000, extraBox = "pssh")) }, web).probe(mp4, null).refusal)
    }

    @Test
    fun dashIsNeverForTheWebAndDynamicIsLive() {
        val verdict = MediaProber(FakeHttp { url, _ -> text(url, staticMpd, "application/dash+xml") }, web).probe("https://cdn.example.com/v.mpd", null)
        assertEquals(ProbeVerdict("https://cdn.example.com/v.mpd", StreamType.DASH, MediaFormat.DASH, durationMs = 600_000, height = 1080, webPlayable = false), verdict)
        val dynamic = staticMpd.replace("type=\"static\"", "type=\"dynamic\"")
        assertTrue(MediaProber(FakeHttp { url, _ -> text(url, dynamic, "application/dash+xml") }, web).probe("https://cdn.example.com/v.mpd", null).live)
    }

    @Test
    fun aLinkThatOnlyWorksWithTheRefererIsAppOnly() {
        val http = FakeHttp { url, headers -> if (headers["Referer"] == "https://example.com/") bytes(url, mp4Head(0, 1000, 60_000)) else status(url, 403) }
        val verdict = MediaProber(http, web).probe(mp4, "https://example.com/film/123#t=1")
        assertTrue(verdict.needsReferrer)
        assertFalse(verdict.webPlayable)
        assertEquals(60_000L, verdict.durationMs)
        assertNull(verdict.refusal)
        assertEquals(listOf(null, "https://example.com/"), http.calls.map { it.second["Referer"] })
    }

    @Test
    fun forbiddenWithAndWithoutTheRefererNeedsALogin() {
        val verdict = MediaProber(FakeHttp { url, _ -> status(url, 403) }, web).probe(mp4, "https://example.com/film")
        assertEquals(Refusal.NEEDS_LOGIN, verdict.refusal)
    }

    @Test
    fun aPageOnThisNetworkIsNeverUsedAsAReferrer() {
        val http = FakeHttp { url, _ -> status(url, 401) }
        assertEquals(Refusal.NEEDS_LOGIN, MediaProber(http, web).probe(mp4, "http://192.168.1.1/films").refusal)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun noAnswerOrAnErrorIsAFailure() {
        assertEquals(Refusal.FAILED, MediaProber(FakeHttp { _, _ -> null }, web).probe(mp4, null).refusal)
        assertEquals(Refusal.FAILED, MediaProber(FakeHttp { url, _ -> status(url, 404) }, web).probe(mp4, null).refusal)
        // a master whose variant cannot be read says nothing about live or length
        val noVariant = FakeHttp { url, _ -> if (url == master) text(url, masterText) else null }
        assertEquals(Refusal.FAILED, MediaProber(noVariant, web).probe(master, null).refusal)
    }

    @Test
    fun aWebPageIsNotMedia() {
        val verdict = MediaProber(FakeHttp { url, _ -> text(url, "<!DOCTYPE html><title>Film</title>", "text/html") }, web).probe("https://example.com/embed/1", null)
        assertEquals(Refusal.NOT_MEDIA, verdict.refusal)
    }

    @Test
    fun variantsAreValidatedAndCapped() {
        val lines = StringBuilder("#EXTM3U\n")
        fun variant(path: String) { lines.append("#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=640x360\n$path\n") }
        variant("http://127.0.0.1/x.m3u8")
        variant("file:///etc/passwd.m3u8")
        for (i in 1..25) variant("v$i/index.m3u8")
        val http = FakeHttp { url, _ -> if (url == master) text(url, lines.toString()) else text(url, vod) }
        val verdict = MediaProber(http, web).probe(master, null)
        assertEquals(MediaProber.MAX_VARIANTS, verdict.variants.size)
        assertEquals("https://cdn.example.com/v/v1/index.m3u8", verdict.variants.first())
        assertTrue(verdict.variants.all { it.startsWith("https://cdn.example.com/") })
        assertNull(verdict.refusal)
    }
}
