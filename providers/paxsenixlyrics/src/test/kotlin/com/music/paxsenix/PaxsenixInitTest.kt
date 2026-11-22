package com.music.paxsenix

import org.junit.Assert.assertEquals
import org.junit.Test

class PaxsenixInitTest {
    @Test
    fun userAgentCarriesTheVersionPassedToInit() {
        Paxsenix.init("8.0.0 build#1150")
        assertEquals("Musicfy/8.0.0 build#1150", Paxsenix.userAgent)
    }
}
