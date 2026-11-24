package com.example.musicfy.ritme.browser

import kotlinx.coroutines.runBlocking
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
    fun aSubframeCannotClaimAYouTubeFrame() = runBlocking<Unit> {
        val model = RitmeBrowserModel({ u, _ -> error(u) }, { _, _ -> null }, this, clock = { 1L })
        model.pageStarted("https://example.com/film")
        model.onReport("""{"v":1,"page":"https://www.youtube-nocookie.com/embed/xyz"}""", mainFrame = false, origin = "https://ads.example")
        assertNull(model.findings.value.youTubeVideoId)
        assertEquals(0, model.offers())
        model.onReport("""{"v":1,"page":"https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"}""", mainFrame = false, origin = "https://www.youtube-nocookie.com")
        assertEquals("dQw4w9WgXcQ", model.findings.value.youTubeVideoId)
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
    fun manyOriginsCannotGrowTheTableWithoutBound() {
        val limiter = RateLimiter(maxKeys = 4)
        repeat(4) { assertTrue(limiter.allow("o$it", 0L)) }
        assertFalse(limiter.allow("o9", 10L))
        assertTrue(limiter.allow("o9", 2_000L))
    }
}
