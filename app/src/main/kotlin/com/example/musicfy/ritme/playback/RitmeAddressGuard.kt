package com.example.musicfy.ritme.playback

import okhttp3.Interceptor
import java.io.IOException
import java.net.InetAddress

/**
 * Keeps a room's URL entries off this device's own network (spec 4.5): a member must not be able to
 * make other members' phones reach their router or LAN. MediaUrlRules already refuses local hosts
 * by name; this checks the address a connection actually reached, which also covers DNS rebinding,
 * IP literals (OkHttp does not ask Dns about those) and redirects.
 */
object RitmeAddressGuard {

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return false
        val b = address.address
        return when (b.size) {
            4 -> isPublicV4(b)
            16 -> isPublicV6(b)
            else -> false
        }
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun isPublicV4(b: ByteArray): Boolean {
        val a0 = u(b[0])
        val a1 = u(b[1])
        val local = a0 == 0 || a0 == 10 || a0 == 127 ||
            (a0 == 100 && a1 in 64..127) || // CGNAT
            (a0 == 169 && a1 == 254) ||
            (a0 == 172 && a1 in 16..31) ||
            (a0 == 192 && a1 == 168) ||
            (a0 == 192 && a1 == 0 && u(b[2]) == 0) ||
            (a0 == 198 && a1 in 18..19) ||
            a0 >= 224 // multicast and 240/4
        return !local
    }

    private fun isPublicV6(b: ByteArray): Boolean {
        val first = u(b[0])
        if (first and 0xFE == 0xFC) return false // fc00::/7
        if (first == 0xFE && (u(b[1]) and 0xC0) == 0xC0) return false // fec0::/10
        // ::ffff:a.b.c.d and NAT64 64:ff9b::/96 are judged by the IPv4 address inside them
        val mapped = (0..9).all { b[it] == 0.toByte() } && u(b[10]) == 0xFF && u(b[11]) == 0xFF
        val nat64 = u(b[0]) == 0x00 && u(b[1]) == 0x64 && u(b[2]) == 0xFF && u(b[3]) == 0x9B && (4..11).all { b[it] == 0.toByte() }
        if (mapped || nat64) return isPublicV4(b.copyOfRange(12, 16))
        return true
    }

    /** A network interceptor: runs once the connection is up, before the request goes out. */
    val interceptor = Interceptor { chain ->
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address != null && !isPublic(address)) throw IOException("Ritme: refused a local address")
        chain.proceed(chain.request())
    }
}
