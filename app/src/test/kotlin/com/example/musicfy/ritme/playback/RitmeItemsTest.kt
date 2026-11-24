package com.example.musicfy.ritme.playback

import androidx.media3.common.MimeTypes
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.StreamType
import com.example.musicfy.ritme.testing.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeItemsTest {

    private fun link(id: String, url: String, durationMs: Long = 0) =
        entry(id, kind = MediaKind.URL, videoId = "", durationMs = durationMs, url = url)

    @Test
    fun anHlsLinkWithoutALengthIsALiveHlsItem() {
        assertEquals(
            RitmeUrlSpec("RITME_URL:u1", "https://cdn.example.com/live/master.m3u8?token=1", MimeTypes.APPLICATION_M3U8, live = true),
            RitmeItems.urlSpec(link("u1", "https://cdn.example.com/live/master.m3u8?token=1")),
        )
    }

    @Test
    fun aDashManifestIsADashItem() {
        assertEquals(MimeTypes.APPLICATION_MPD, RitmeItems.urlSpec(link("u2", "https://cdn.example.com/v/Manifest.MPD"))?.mimeType)
    }

    @Test
    fun aFileWithALengthLeavesItsTypeToTheExtractors() {
        assertEquals(
            RitmeUrlSpec("RITME_URL:u3", "https://cdn.example.com/films/a.mp4", null, live = false),
            RitmeItems.urlSpec(link("u3", "https://cdn.example.com/films/a.mp4", durationMs = 5_400_000)),
        )
    }

    @Test
    fun youTubeEntriesAndUnsafeLinksHaveNoUrlSpec() {
        assertNull(RitmeItems.urlSpec(entry("a")))
        assertNull(RitmeItems.urlSpec(entry("v", kind = MediaKind.YOUTUBE_VIDEO)))
        assertNull(RitmeItems.urlSpec(link("u", "http://192.168.1.1/v.mp4")))
    }

    // A room entry's kind (YOUTUBE_MUSIC / YOUTUBE_VIDEO) says nothing about the library's song:
    // while in a room, or for a URL item, the library's isVideo flag is left alone.
    @Test
    fun roomItemsNeverRewriteTheLibrarysVideoFlag() {
        assertTrue(RitmeItems.syncsLibraryFlags("dQw4w9WgXcQ", roomActive = false))
        assertFalse(RitmeItems.syncsLibraryFlags("dQw4w9WgXcQ", roomActive = true))
        assertFalse(RitmeItems.syncsLibraryFlags("RITME_URL:u1", roomActive = false))
        assertFalse(RitmeItems.syncsLibraryFlags("RITME_URL:u1", roomActive = true))
    }

    @Test
    fun theStreamTypeSaysHowALinkWithoutAnExtensionPlays() {
        fun typed(type: StreamType?) = RitmeItems.urlSpec(link("u", "https://cdn.example.com/v/manifest?token=1").copy(streamType = type))!!
        assertEquals(MimeTypes.APPLICATION_M3U8, typed(StreamType.HLS).mimeType)
        assertEquals(MimeTypes.APPLICATION_MPD, typed(StreamType.DASH).mimeType)
        assertNull(typed(StreamType.FILE).mimeType)
        assertNull(typed(StreamType.UNKNOWN).mimeType)
        assertNull(typed(null).mimeType)
        // FILE wins over a misleading extension; UNKNOWN falls back to it
        val odd = link("o", "https://cdn.example.com/a.m3u8")
        assertNull(RitmeItems.urlSpec(odd.copy(streamType = StreamType.FILE))!!.mimeType)
        assertEquals(MimeTypes.APPLICATION_M3U8, RitmeItems.urlSpec(odd.copy(streamType = StreamType.UNKNOWN))!!.mimeType)
    }

    @Test
    fun onlyAnOriginReferrerIsKept() {
        fun referrer(value: String?) = RitmeItems.urlSpec(link("u", "https://cdn.example.com/a.mp4", 60_000).copy(referrer = value))!!.referrer
        assertEquals("https://example.com/", referrer("https://example.com/"))
        assertNull(referrer("https://example.com/watch/1"))
        assertNull(referrer("http://10.0.0.1/"))
        assertNull(referrer(null))
    }
}
