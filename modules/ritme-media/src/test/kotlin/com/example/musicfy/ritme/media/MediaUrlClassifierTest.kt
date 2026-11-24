package com.example.musicfy.ritme.media

import com.example.musicfy.ritme.protocol.StreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUrlClassifierTest {

    private val mediaHeaders = mapOf("Range" to "bytes=0-", "Accept-Encoding" to "identity;q=1, *;q=0")

    @Test
    fun extensionsSayWhatAUrlIs() {
        assertEquals(Sniffed.Media("https://cdn.example.com/v/master.m3u8?t=1", StreamType.HLS, false), MediaUrlClassifier.classify("https://cdn.example.com/v/master.m3u8?t=1#x"))
        assertEquals(Sniffed.Media("https://cdn.example.com/v/manifest.mpd", StreamType.DASH, false), MediaUrlClassifier.classify("https://cdn.example.com/v/manifest.mpd"))
        listOf("mp4", "m4v", "webm", "mov", "ogv", "mkv", "MP4").forEach {
            assertEquals(it, StreamType.FILE, (MediaUrlClassifier.classify("https://cdn.example.com/film.$it") as Sniffed.Media).streamType)
        }
    }

    @Test
    fun queryAndPathHintsCount() {
        assertEquals(StreamType.HLS, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/play?format=m3u8&id=1"))
        assertEquals(StreamType.HLS, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/play?type=hls"))
        assertEquals(StreamType.DASH, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/play?type=dash"))
        assertEquals(StreamType.FILE, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/get?mime=video%2Fmp4"))
        assertEquals(StreamType.UNKNOWN, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/v1/manifest?token=a"))
        assertEquals(StreamType.UNKNOWN, MediaUrlClassifier.streamTypeOf("https://cdn.example.com/hls/,480p,720p,.mp4.urlset/index"))
        assertNull(MediaUrlClassifier.streamTypeOf("https://example.com/manifest.json"))
        assertNull(MediaUrlClassifier.streamTypeOf("https://example.com/watch/123"))
    }

    @Test
    fun aMediaElementsRequestIsACandidateEvenWithoutAnExtension() {
        assertEquals(
            Sniffed.Media("https://cdn.example.com/stream/abc123", StreamType.UNKNOWN, mediaRequest = true),
            MediaUrlClassifier.classify("https://cdn.example.com/stream/abc123", "GET", mediaHeaders),
        )
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/stream/abc123"))
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/stream/abc123", "GET", mapOf("Range" to "bytes=0-")))
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/data.json", "GET", mediaHeaders))
        assertTrue(MediaUrlClassifier.isMediaElementRequest(mapOf("range" to "bytes=0-", "accept-encoding" to "identity;q=1,*;q=0")))
    }

    @Test
    fun segmentsOnlySayThePagePlaysAStream() {
        listOf("ts", "m4s", "aac", "key", "vtt").forEach {
            assertEquals(it, Sniffed.Segment, MediaUrlClassifier.classify("https://cdn.example.com/v/seg-001.$it"))
        }
    }

    @Test
    fun youTubeAdsLocalAndNonGetRequestsAreIgnored() {
        listOf(
            "https://rr1---sn-abc.googlevideo.com/videoplayback?mime=video%2Fmp4",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://imasdk.googleapis.com/js/core/ad.mp4",
            "https://s0.2mdn.net/videoplayback/ad.mp4",
            "https://ads.doubleclick.net/v.m3u8",
            "http://192.168.1.10/v.mp4",
            "https://user:pw@cdn.example.com/v.mp4",
            "blob:https://example.com/123",
        ).forEach { assertEquals(it, Sniffed.Ignored, MediaUrlClassifier.classify(it)) }
        assertEquals(Sniffed.Ignored, MediaUrlClassifier.classify("https://cdn.example.com/v.mp4", "POST"))
    }

    @Test
    fun adHostsMatchWholeLabelsOnly() {
        assertTrue(AdHosts.isAd("pubads.g.doubleclick.net"))
        assertTrue(AdHosts.isAd("doubleclick.net."))
        assertFalse(AdHosts.isAd("notdoubleclick.net"))
        assertFalse(AdHosts.isAd("doubleclick.net.example.com"))
    }

    @Test
    fun hostsFileNamesAndExtensions() {
        assertEquals("cdn.example.com", MediaUrlClassifier.hostOf("HTTPS://CDN.Example.com:8443/a.mp4"))
        assertEquals("[2001:db8::1]", MediaUrlClassifier.hostOf("https://[2001:db8::1]:443/a.mp4"))
        assertNull(MediaUrlClassifier.hostOf("ftp://example.com/a.mp4"))
        assertEquals("mp4", MediaUrlClassifier.extensionOf("https://cdn.example.com/a/b.MP4?x=1#t"))
        assertEquals("", MediaUrlClassifier.extensionOf("https://cdn.example.com/a/b"))
        assertEquals("My Film.mp4", MediaUrlClassifier.fileNameOf("https://cdn.example.com/films/My%20Film.mp4?x=1"))
        assertNull(MediaUrlClassifier.fileNameOf("https://cdn.example.com/"))
    }
}
