package com.example.musicfy.ritme.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeBindingModelTest {
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
}
