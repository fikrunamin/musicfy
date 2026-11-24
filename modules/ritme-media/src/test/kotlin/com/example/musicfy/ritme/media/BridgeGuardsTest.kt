package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeGuardsTest {
    @Test
    fun aFrameUrlMustMatchTheRealSender() {
        val yt = "https://www.youtube-nocookie.com/embed/xyz"
        assertNull(SenderBinding.boundFrameUrl(yt, "https://ads.example"))
        assertEquals(yt, SenderBinding.boundFrameUrl(yt, "https://www.youtube-nocookie.com"))
        assertEquals(yt, SenderBinding.boundFrameUrl(yt, "https://WWW.youtube-nocookie.com:443"))
        assertNull(SenderBinding.boundFrameUrl(yt, "http://www.youtube-nocookie.com"))
        assertNull(SenderBinding.boundFrameUrl(yt, "https://www.youtube-nocookie.com:8443"))
        assertNull(SenderBinding.boundFrameUrl(yt, "null"))
        assertNull(SenderBinding.boundFrameUrl(null, "https://ads.example"))
    }

    @Test
    fun tenMessagesPerSecondPerOrigin() {
        val limiter = RateLimiter()
        repeat(10) { assertTrue(limiter.allow("https://a.example", 100L + it)) }
        assertFalse(limiter.allow("https://a.example", 500L))
        assertTrue(limiter.allow("https://b.example", 500L))
        assertTrue(limiter.allow("https://a.example", 1_100L))
    }

    @Test
    fun aFullTableEvictsTheLeastRecentKeyInsteadOfRefusingANewOrigin() {
        val limiter = RateLimiter(maxKeys = 4)
        repeat(4) { assertTrue(limiter.allow("o$it", it.toLong())) }
        assertTrue(limiter.allow("o9", 10L)) // none is stale: the oldest (o0) makes room
        assertEquals(4, limiter.size)
        // o0 starts over (evicted); the others kept their history
        assertTrue(limiter.allow("o0", 11L))
        assertEquals(4, limiter.size)
    }

    @Test
    fun staleKeysAreDroppedBeforeLiveOnes() {
        val limiter = RateLimiter(limit = 1, maxKeys = 2)
        assertTrue(limiter.allow("old", 0L))
        assertTrue(limiter.allow("busy", 1_500L))
        assertTrue(limiter.allow("new", 1_600L)) // "old" is stale, "busy" stays
        assertFalse(limiter.allow("busy", 1_700L))
        assertEquals(2, limiter.size)
    }
}
