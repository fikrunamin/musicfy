package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlHintsTest {
    private val now = 1_700_000_000_000L

    @Test
    fun sensitiveParameterNamesAreFound() {
        assertEquals(
            listOf("token", "signature", "key-pair-id"),
            SensitiveParams.names("https://cdn.example.com/v.m3u8?token=a&Expires=1&Signature=x&Key-Pair-Id=k&quality=hd"),
        )
        assertEquals(listOf("session_id"), SensitiveParams.names("https://cdn.example.com/v.mp4?author=bob&session_id=1"))
        assertEquals(
            listOf("x-amz-signature", "x-amz-credential"),
            SensitiveParams.names("https://s3.example.com/v.mp4?X-Amz-Signature=a&X-Amz-Credential=b"),
        )
        assertEquals(listOf("token"), SensitiveParams.names("https://cdn.example.com/v.mp4?%74oken=1"))
        assertFalse(SensitiveParams.has("https://cdn.example.com/v.mp4#token=1"))
        assertFalse(SensitiveParams.has("https://cdn.example.com/v.mp4?quality=720"))
    }

    @Test
    fun expiryTimesBecomeMinutesFromNow() {
        assertEquals(LinkHints(10, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?expires=1700000600", now))
        assertEquals(LinkHints(10, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?Expires=1700000600000", now))
        assertEquals(LinkHints(20, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?hdnts=exp%3D1700001200~acl%3D%2F*~hmac%3Dab", now))
        // a small number is a lifetime, not a time
        assertEquals(LinkHints(null, false), ExpiryHints.of("https://cdn.example.com/v.m3u8?exp=3600", now))
        assertTrue(ExpiryHints.of("https://cdn.example.com/v.m3u8?exp=1699999000", now).expiresInMinutes!! <= 0)
    }

    @Test
    fun anIpParameterMeansTheLinkMayBeLockedToANetwork() {
        assertTrue(ExpiryHints.of("https://cdn.example.com/v.mp4?ip=203.0.113.9", now).ipLocked)
        assertFalse(ExpiryHints.of("https://cdn.example.com/v.mp4?ip=", now).ipLocked)
        assertEquals(LinkHints(null, false), ExpiryHints.of("https://cdn.example.com/v.mp4", now))
    }
}
