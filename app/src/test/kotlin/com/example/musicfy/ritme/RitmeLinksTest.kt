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
        val servers = listOf("https://ritme.dubeli.id", "http://192.168.1.5:8080")
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("musicfy://ritme/K7M2QX", servers))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/k7m2qx", servers))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("Listen with me on Ritme: https://ritme.dubeli.id/r/K7M2QX", servers))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("http://192.168.1.5:8080/r/K7M2QX", servers))
        assertNull(RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/K7M2QXY", servers))
        assertNull(RitmeLinks.findRoomCode("https://music.youtube.com/watch?v=dQw4w9WgXcQ", servers))
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
    fun serverUrlsTheSocketCannotOpenAreRefused() {
        // java.net.URI accepts this, OkHttp (which opens the socket) does not
        assertNull(RitmeLinks.normalizeServerUrl("https://ritme.dubeli.id:70000"))
        // both accept an empty port, but it would end up in every share link
        assertNull(RitmeLinks.normalizeServerUrl("https://ritme.dubeli.id:"))
        assertNull(RitmeLinks.normalizeServerUrl("http://exa mple.com"))
        assertNull(RitmeLinks.normalizeServerUrl(""))
        assertEquals("https://example.com/sub", RitmeLinks.normalizeServerUrl("https://example.com/sub/"))
        assertEquals("http://[::1]:8080", RitmeLinks.normalizeServerUrl("http://[::1]:8080"))
    }

    @Test
    fun theStoredServerUrlIsBlankForTheDefaultAndNullWhenInvalid() {
        val default = "https://ritme.dubeli.id"
        assertEquals("", RitmeLinks.serverUrlToStore(" https://ritme.dubeli.id/ ", default))
        assertEquals("", RitmeLinks.serverUrlToStore("HTTPS://Ritme.Dubeli.id", default))
        assertEquals("http://10.0.2.2:8080", RitmeLinks.serverUrlToStore("http://10.0.2.2:8080/", default))
        assertNull(RitmeLinks.serverUrlToStore("ritme.dubeli.id", default))
        assertNull(RitmeLinks.serverUrlToStore("   ", default))
    }

    @Test
    fun webRoomLinksCountOnlyOnTheServersWeKnow() {
        val servers = listOf("https://ritme.dubeli.id", "http://192.168.1.5:8080")
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("musicfy://ritme/K7M2QX", servers))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/K7M2QX", servers))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("Listen with me on Ritme: https://RITME.dubeli.id/r/K7M2QX", servers))
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("http://192.168.1.5:8080/r/K7M2QX", servers))
        // a subreddit is not a room
        assertNull(RitmeLinks.findRoomCode("https://www.reddit.com/r/travel", servers))
        assertEquals("TRAVEL", RitmeLinks.findRoomCode("https://ritme.dubeli.id/r/travel", servers))
        // the first link that is a room wins
        assertEquals("K7M2QX", RitmeLinks.findRoomCode("https://reddit.com/r/travel and https://ritme.dubeli.id/r/K7M2QX", servers))
        assertNull(RitmeLinks.findRoomCode("musicfy://ritme/K7M2Q", servers))
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

    @Test
    fun sharedTextShrinksToItsFirstLink() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", RitmeLinks.sharedLinkText("Watch this! https://youtu.be/dQw4w9WgXcQ so good"))
        assertEquals("lofi beats", RitmeLinks.sharedLinkText("  lofi beats  "))
    }

    @Test
    fun sharedTextIsCappedAndTreatedAsPlainText() {
        val long = "https://example.com/" + "a".repeat(5000)
        assertEquals(2_048, RitmeLinks.sharedLinkText(long).length)
        assertEquals(2_048, RitmeLinks.sharedLinkText("x".repeat(9000)).length)
        assertEquals("", RitmeLinks.sharedLinkText("   "))
    }
}
