package com.example.musicfy.ritme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RitmeLinksTest {

    @Test
    fun codesAreUppercasedAndChecked() {
        assertEquals("K7M2QX", RitmeLinks.normalizeCode(" k7m2qx "))
        assertNull(RitmeLinks.normalizeCode("K7M2Q"))
        // 0, 1, I and O are not in the alphabet
        assertNull(RitmeLinks.normalizeCode("K7M2Q1"))
        assertNull(RitmeLinks.normalizeCode("K7M2QO"))
    }

    @Test
    fun roomCodesAreFoundInLinksAndSharedText() {
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("musicfy://ritme/K7M2QX"))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/k7m2qx"))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("Listen with me on Ritme: https://ritme.dubeli.id/r/K7M2QX"))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("http://192.168.1.5:8080/r/K7M2QX"))
        assertNull(RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/K7M2QXY"))
        assertNull(RitmeLinks.findRoomCode("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun serverUrlsAreNormalised() {
        assertEquals("https://ritme.dubeli.id", RitmeLinks.normalizeServerUrl(" https://ritme.dubeli.id/ "))
        assertEquals("http://10.0.2.2:8080", RitmeLinks.normalizeServerUrl("http://10.0.2.2:8080"))
        assertNull(RitmeLinks.normalizeServerUrl("ritme.dubeli.id"))
        assertNull(RitmeLinks.normalizeServerUrl("ftp://ritme.dubeli.id"))
        assertNull(RitmeLinks.normalizeServerUrl("https://"))
        assertNull(RitmeLinks.normalizeServerUrl("https://ritme.dubeli.id/?x=1"))
    }

    @Test
    fun socketAndShareLinksFollowTheServerUrl() {
        assertEquals("wss://ritme.dubeli.id/ritme", RitmeLinks.socketUrl("https://ritme.dubeli.id"))
        assertEquals("ws://10.0.2.2:8080/ritme", RitmeLinks.socketUrl("http://10.0.2.2:8080"))
        assertEquals("wss://example.com/sub/ritme", RitmeLinks.socketUrl("https://example.com/sub/"))
        assertEquals("https://ritme.dubeli.id/r/K7M2QX", RitmeLinks.shareLink("https://ritme.dubeli.id/", "K7M2QX"))
    }

    @Test
    fun displayNamesAreTrimmedAndBounded() {
        assertEquals("Budi", RitmeLinks.cleanDisplayName("  Budi "))
        assertNull(RitmeLinks.cleanDisplayName("   "))
        assertNull(RitmeLinks.cleanDisplayName("x".repeat(33)))
        assertEquals("x".repeat(32), RitmeLinks.cleanDisplayName("x".repeat(32)))
    }
}
