package com.example.musicfy.ritme.playback

import okhttp3.Interceptor
import okhttp3.Request
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

class RitmeAddressGuardTest {

    private fun v4(vararg b: Int) = InetAddress.getByAddress(b.map { it.toByte() }.toByteArray())

    // getByName parses literals without any DNS lookup
    private fun ip(literal: String) = InetAddress.getByName(literal)

    @Test
    fun publicAddressesPass() {
        assertTrue(RitmeAddressGuard.isPublic(v4(93, 184, 216, 34)))
        assertTrue(RitmeAddressGuard.isPublic(v4(8, 8, 8, 8)))
        assertTrue(RitmeAddressGuard.isPublic(ip("2606:4700::1111")))
        assertTrue(RitmeAddressGuard.isPublic(ip("64:ff9b::5db8:d822"))) // NAT64 of 93.184.216.34
    }

    @Test
    fun localAndPrivateAddressesAreRefused() {
        listOf(
            v4(127, 0, 0, 1), v4(10, 1, 2, 3), v4(172, 16, 0, 1), v4(172, 31, 255, 255), v4(192, 168, 1, 1),
            v4(169, 254, 169, 254), v4(100, 64, 0, 1), v4(0, 0, 0, 0), v4(224, 0, 0, 1), v4(198, 18, 0, 1), v4(192, 0, 0, 8),
            ip("::1"), ip("fe80::1"), ip("fd12:3456::1"), ip("fec0::1"), ip("::ffff:10.0.0.1"), ip("64:ff9b::a00:1"),
        ).forEach { assertFalse("$it", RitmeAddressGuard.isPublic(it)) }
    }

    /** The raw 16-byte ::ffff:a.b.c.d form; InetAddress.getByName would turn it into an Inet4Address. */
    private fun mapped(vararg v4: Int): InetAddress =
        Inet6Address.getByAddress(null, ByteArray(10) + byteArrayOf(-1, -1) + v4.map { it.toByte() }.toByteArray(), -1)

    @Test
    fun anIpv4MappedAddressIsJudgedByTheIpv4AddressInside() {
        listOf(mapped(10, 0, 0, 1), mapped(127, 0, 0, 1), mapped(192, 168, 1, 50)).forEach {
            assertTrue("$it", it is Inet6Address)
            assertFalse("$it", RitmeAddressGuard.isPublic(it))
        }
        val public = mapped(93, 184, 216, 34)
        assertTrue(public is Inet6Address)
        assertTrue(RitmeAddressGuard.isPublic(public))
    }

    /** A network-interceptor chain whose connection is unknown; proceeding would let the request out. */
    private fun chainWithoutConnection(): Interceptor.Chain =
        java.lang.reflect.Proxy.newProxyInstance(
            Interceptor.Chain::class.java.classLoader,
            arrayOf(Interceptor.Chain::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "connection" -> null
                "request" -> Request.Builder().url("https://cdn.example.com/v.mp4").build()
                "proceed" -> throw AssertionError("the request went out without a known address")
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Interceptor.Chain

    @Test
    fun theInterceptorRefusesWhenItCannotSeeTheAddress() {
        assertThrows(RitmeAddressRefusedException::class.java) {
            RitmeAddressGuard.interceptor.intercept(chainWithoutConnection())
        }
    }

    @Test
    fun aRefusalIsFoundAnywhereInTheCauseChain() {
        val refused = RitmeAddressRefusedException(InetAddress.getByName("192.168.1.50"))
        assertTrue(RitmeAddressGuard.isRefusal(refused))
        // ExoPlayer wraps it: PlaybackException <- HttpDataSourceException <- the refusal
        assertTrue(RitmeAddressGuard.isRefusal(RuntimeException("playback", IOException("open", refused))))
        // OkHttp reports one failed route and attaches the others as suppressed
        assertTrue(RitmeAddressGuard.isRefusal(IOException("first route").apply { addSuppressed(refused) }))
    }

    @Test
    fun otherFailuresAreNotRefusals() {
        assertFalse(RitmeAddressGuard.isRefusal(null))
        assertFalse(RitmeAddressGuard.isRefusal(java.net.ConnectException("Connection refused")))
        assertFalse(RitmeAddressGuard.isRefusal(RuntimeException("playback", IOException("Ritme: refused a local address"))))
        // a cause cycle ends
        val a = IOException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertFalse(RitmeAddressGuard.isRefusal(b))
    }
}
