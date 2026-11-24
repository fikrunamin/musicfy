package com.example.musicfy.ritme.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeVideoLinksTest {

    @Test
    fun youTubeLinks() {
        assertEquals(VideoLink.YouTube("dQw4w9WgXcQ", music = false), RitmeVideoLinks.classify(" https://youtu.be/dQw4w9WgXcQ?t=3 "))
        assertEquals(VideoLink.YouTube("dQw4w9WgXcQ", music = true), RitmeVideoLinks.classify("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun directMediaLinksLoseTheirFragment() {
        assertEquals(
            VideoLink.Direct("https://cdn.example.com/films/My%20Movie.mp4", "My Movie.mp4", "cdn.example.com", streaming = false),
            RitmeVideoLinks.classify("https://cdn.example.com/films/My%20Movie.mp4#t=10"),
        )
        assertEquals(
            VideoLink.Direct("https://www.example.com/live/master.m3u8?token=abc", "master.m3u8", "example.com", streaming = true),
            RitmeVideoLinks.classify("www.example.com/live/master.m3u8?token=abc"),
        )
        assertEquals(true, (RitmeVideoLinks.classify("https://cdn.example.com/v/manifest.mpd") as VideoLink.Direct).streaming)
    }

    @Test
    fun pagesUnsafeLinksAndOtherSchemesAreRefused() {
        assertEquals(VideoLink.Rejected(VideoLink.Problem.PAGE), RitmeVideoLinks.classify("https://example.com/watch/123"))
        assertEquals(VideoLink.Rejected(VideoLink.Problem.UNSAFE), RitmeVideoLinks.classify("http://192.168.1.1/v.mp4"))
        assertEquals(VideoLink.Rejected(VideoLink.Problem.UNSAFE), RitmeVideoLinks.classify("https://user:pw@cdn.example.com/v.mp4"))
        assertEquals(VideoLink.Rejected(VideoLink.Problem.UNRECOGNIZED), RitmeVideoLinks.classify("ftp://example.com/v.mp4"))
    }

    @Test
    fun wordsAreASearchNotALink() {
        assertNull(RitmeVideoLinks.classify("never gonna give you up"))
        assertNull(RitmeVideoLinks.classify("lofi"))
        assertNull(RitmeVideoLinks.classify("   "))
    }
}
