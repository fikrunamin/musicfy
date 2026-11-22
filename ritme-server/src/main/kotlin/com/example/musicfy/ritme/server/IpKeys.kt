package com.example.musicfy.ritme.server

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The key rate limits and connection caps count against: an IPv4 address as is, an IPv6 address
 * by its /64 (one subscriber usually owns a whole /64). Never logged.
 */
fun ipKey(remoteHost: String): String {
    val host = remoteHost.trim().removePrefix("[").substringBefore(']').substringBefore('%')
    if (':' !in host) return host
    // Brackets force literal parsing: an unparsable value throws instead of triggering a DNS lookup.
    return when (val address = runCatching { InetAddress.getByName("[$host]") }.getOrNull()) {
        is Inet4Address -> address.hostAddress // IPv4-mapped IPv6
        is Inet6Address -> address.address.take(8).chunked(2).joinToString(":", postfix = "::/64") { (hi, lo) ->
            (((hi.toInt() and 0xff) shl 8) or (lo.toInt() and 0xff)).toString(16)
        }
        else -> host
    }
}
