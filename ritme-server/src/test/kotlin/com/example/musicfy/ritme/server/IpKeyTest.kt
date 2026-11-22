package com.example.musicfy.ritme.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IpKeyTest {
    @Test
    fun ipv4IsKeptWhole() {
        assertEquals("1.2.3.4", ipKey("1.2.3.4"))
        assertEquals("1.2.3.4", ipKey(" 1.2.3.4 "))
    }

    @Test
    fun ipv6IsBucketedBySlash64() {
        assertEquals("2001:db8:1:2::/64", ipKey("2001:db8:1:2::1"))
        assertEquals(ipKey("2001:db8:1:2::1"), ipKey("2001:0db8:0001:0002:ffff:eeee:dddd:cccc"))
        assertEquals(ipKey("2001:db8:1:2::1"), ipKey("[2001:db8:1:2::5]"))
        assertEquals(ipKey("fe80::1"), ipKey("fe80::2%eth0"))
        assertNotEquals(ipKey("2001:db8:1:2::1"), ipKey("2001:db8:1:3::1"))
    }

    @Test
    fun ipv4MappedIpv6CountsAsTheIpv4Address() {
        assertEquals("1.2.3.4", ipKey("::ffff:1.2.3.4"))
    }

    @Test
    fun unparsableValuesAreUsedAsIsWithoutLookups() {
        assertEquals("not:an:address:zz", ipKey("not:an:address:zz"))
        assertEquals("localhost", ipKey("localhost"))
        assertTrue(ipKey("") == "")
    }
}
