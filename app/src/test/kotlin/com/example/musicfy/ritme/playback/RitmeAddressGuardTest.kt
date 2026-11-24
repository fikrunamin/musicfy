package com.example.musicfy.ritme.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
