package com.example.musicfy.ritme.media

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAddressTest {

    private fun open(url: String) = AddressInput.Open(url)
    private fun refused(reason: AddressRefusal) = AddressInput.Refused(reason)

    @Test
    fun fullAddressesOpenAsTyped() {
        assertEquals(open("https://example.com/film?id=1#t=3"), BrowserAddress.resolve("  https://example.com/film?id=1#t=3 "))
        assertEquals(open("HTTP://Example.COM"), BrowserAddress.resolve("HTTP://Example.COM"))
    }

    @Test
    fun aBareDomainBecomesHttps() {
        assertEquals(open("https://example.com/video"), BrowserAddress.resolve("example.com/video"))
        assertEquals(open("https://example.com:8080/x"), BrowserAddress.resolve("example.com:8080/x"))
        assertEquals(open("https://[2001:db8::1]/v"), BrowserAddress.resolve("[2001:db8::1]/v"))
    }

    @Test
    fun wordsGoToDuckDuckGo() {
        assertEquals(open("https://duckduckgo.com/?q=lofi+hip+hop"), BrowserAddress.resolve("lofi hip hop"))
        assertEquals(open("https://duckduckgo.com/?q=lofi"), BrowserAddress.resolve("lofi"))
        assertEquals(open("https://duckduckgo.com/?q=3.5"), BrowserAddress.resolve("3.5"))
        assertEquals(open("https://duckduckgo.com/?q=c%2B%2B+%26+rust"), BrowserAddress.resolve("c++ & rust"))
    }

    @Test
    fun dangerousSchemesAreRefused() {
        listOf(
            "javascript:alert(1)", "JavaScript:void(0)", "file:///sdcard/a.mp4", "content://media/external/video/1",
            "intent://scan/#Intent;scheme=zxing;end", "data:text/html,<b>x</b>", "ftp://example.com/a", "about:blank",
        ).forEach { assertEquals(it, refused(AddressRefusal.SCHEME), BrowserAddress.resolve(it)) }
    }

    @Test
    fun localAndPrivateAddressesAreRefused() {
        listOf(
            "192.168.1.1", "10.0.0.1/admin", "localhost", "localhost:3000", "http://127.0.0.1:8080/",
            "router.local", "http://[::1]/", "http://2130706433/",
        ).forEach { assertEquals(it, refused(AddressRefusal.LOCAL), BrowserAddress.resolve(it)) }
    }

    @Test
    fun credentialsEmptyAndBrokenInputAreRefused() {
        assertEquals(refused(AddressRefusal.CREDENTIALS), BrowserAddress.resolve("https://user:pw@example.com/"))
        assertEquals(refused(AddressRefusal.CREDENTIALS), BrowserAddress.resolve("user:pw@example.com"))
        assertEquals(refused(AddressRefusal.EMPTY), BrowserAddress.resolve("   "))
        assertEquals(refused(AddressRefusal.MALFORMED), BrowserAddress.resolve("https://"))
    }

    @Test
    fun pageNavigations() {
        assertEquals(Navigation.ALLOW, BrowserAddress.navigation("https://example.com/next", hasGesture = false))
        assertEquals(Navigation.ALLOW, BrowserAddress.navigation("about:blank", hasGesture = false))
        assertEquals(Navigation.REFUSE_LOCAL, BrowserAddress.navigation("http://192.168.1.1/", hasGesture = true))
        assertEquals(Navigation.REFUSE_LOCAL, BrowserAddress.navigation("http://localhost:8080/", hasGesture = true))
        assertEquals(Navigation.ASK_EXTERNAL, BrowserAddress.navigation("intent://open#Intent;package=com.example;end", hasGesture = true))
        assertEquals(Navigation.ASK_EXTERNAL, BrowserAddress.navigation("market://details?id=com.example", hasGesture = true))
        assertEquals(Navigation.BLOCK, BrowserAddress.navigation("intent://open#Intent;end", hasGesture = false))
        listOf("javascript:alert(1)", "file:///etc/hosts", "content://x/y", "data:text/html,x").forEach {
            assertEquals(it, Navigation.BLOCK, BrowserAddress.navigation(it, hasGesture = true))
        }
    }
}
