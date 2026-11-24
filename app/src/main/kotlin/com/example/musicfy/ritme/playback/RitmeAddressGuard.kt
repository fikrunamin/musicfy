package com.example.musicfy.ritme.playback

import okhttp3.Interceptor
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** A room URL led to a local address (or one that could not be checked); retrying cannot help. */
class RitmeAddressRefusedException(address: InetAddress?) : IOException("Ritme: refused a local address $address")

/**
 * Keeps a room's URL entries off this device's own network (spec 4.5): a member must not be able to
 * make other members' phones reach their router or LAN. MediaUrlRules already refuses local hosts
 * by name; this checks the address a socket is about to connect to, which also covers DNS
 * rebinding, IP literals (OkHttp does not ask Dns about those) and redirects.
 *
 * Two layers, both failing closed: [socketFactory] refuses before the TCP SYN (and so before any TLS
 * bytes), and [interceptor] checks the connected route again before a request goes out.
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

    /**
     * Plain sockets that refuse to connect to anything but a public address. OkHttp creates every
     * connection's raw socket here (redirect hops and every address of a name included) and layers
     * TLS over that socket for https, so a refused address sees neither a SYN nor a ClientHello.
     */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = GuardedSocket()
        override fun createSocket(host: String?, port: Int): Socket = connected(InetSocketAddress(host, port), null)
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            connected(InetSocketAddress(host, port), InetSocketAddress(localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = connected(InetSocketAddress(host, port), null)
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            connected(InetSocketAddress(address, port), InetSocketAddress(localAddress, localPort))
    }

    private fun connected(endpoint: InetSocketAddress, local: InetSocketAddress?): Socket {
        val socket = GuardedSocket()
        try {
            if (local != null) socket.bind(local)
            socket.connect(endpoint)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        return socket
    }

    /** Socket.connect(SocketAddress) goes through connect(SocketAddress, Int), so this is the one way out. */
    private class GuardedSocket : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address // null when unresolved
            if (address == null || !RitmeAddressGuard.isPublic(address)) throw RitmeAddressRefusedException(address)
            super.connect(endpoint, timeout)
        }
    }

    /** A network interceptor: runs once the connection is up, before the request goes out. A second check. */
    val interceptor = Interceptor { chain ->
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address == null || !isPublic(address)) throw RitmeAddressRefusedException(address)
        chain.proceed(chain.request())
    }

    /** Whether [error] (a PlaybackException, say) was caused by this guard: through its causes and suppressed exceptions. */
    fun isRefusal(error: Throwable?): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        fun found(t: Throwable?): Boolean =
            t != null && seen.add(t) && (t is RitmeAddressRefusedException || found(t.cause) || t.suppressed.any(::found))
        return found(error)
    }
}
