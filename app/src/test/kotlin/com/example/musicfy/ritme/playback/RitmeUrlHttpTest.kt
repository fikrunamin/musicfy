package com.example.musicfy.ritme.playback

import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The client room URL entries play through (spec 4.5). A local server stands in for a member's
 * router: the client must not even open a TCP connection to it, let alone send a TLS ClientHello.
 */
class RitmeUrlHttpTest {

    private lateinit var server: ServerSocket

    @Before
    fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    }

    @After
    fun stopServer() {
        server.close()
    }

    // a bound limit, so a client that does connect fails the test instead of hanging on the silent server
    private fun client(): OkHttpClient = RitmeUrlHttp.client().newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

    private fun fetch(url: String): IOException = assertThrows(IOException::class.java) {
        client().newCall(Request.Builder().url(url).build()).execute().close()
    }

    /** The OS completes a TCP handshake into the backlog without accept(), so any connection shows up here. */
    private fun assertNoConnectionReachedTheServer() {
        server.soTimeout = 300
        assertThrows("a connection reached the local server", SocketTimeoutException::class.java) { server.accept().close() }
    }

    @Test
    fun aPlainRequestToALocalAddressIsRefusedBeforeConnecting() {
        val e = fetch("http://127.0.0.1:${server.localPort}/v.mp4")
        assertTrue("$e", RitmeAddressGuard.isRefusal(e))
        assertNoConnectionReachedTheServer()
    }

    @Test
    fun anHttpsRequestToALocalAddressSendsNoTlsBytes() {
        val e = fetch("https://127.0.0.1:${server.localPort}/v.m3u8")
        assertTrue("$e", RitmeAddressGuard.isRefusal(e))
        assertNoConnectionReachedTheServer()
    }

    @Test
    fun aNameThatResolvesToALocalAddressIsRefusedBeforeConnecting() {
        val e = fetch("http://localhost:${server.localPort}/v.mp4")
        assertTrue("$e", RitmeAddressGuard.isRefusal(e))
        assertNoConnectionReachedTheServer()
    }

    @Test
    fun theSocketFactoryRefusesLocalAndUnresolvedAddresses() {
        val local = InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort)
        RitmeAddressGuard.socketFactory.createSocket().use { socket ->
            assertThrows(RitmeAddressRefusedException::class.java) { socket.connect(local, 1_000) }
            assertFalse(socket.isConnected)
        }
        RitmeAddressGuard.socketFactory.createSocket().use { socket ->
            assertThrows(RitmeAddressRefusedException::class.java) { socket.connect(local) }
        }
        RitmeAddressGuard.socketFactory.createSocket().use { socket ->
            assertThrows(RitmeAddressRefusedException::class.java) {
                socket.connect(InetSocketAddress.createUnresolved("cdn.example.com", 443), 1_000)
            }
        }
        assertThrows(RitmeAddressRefusedException::class.java) {
            RitmeAddressGuard.socketFactory.createSocket("127.0.0.1", server.localPort).close()
        }
        assertNoConnectionReachedTheServer()
    }

    @Test
    fun theClientUsesNoProxyNoCookiesNoCacheAndTheGuardAsANetworkInterceptor() {
        val client = RitmeUrlHttp.client()
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertNull(client.cache)
        assertTrue(client.socketFactory === RitmeAddressGuard.socketFactory)
        assertTrue(RitmeAddressGuard.interceptor in client.networkInterceptors)
        // as an application interceptor it would run before any connection exists
        assertFalse(RitmeAddressGuard.interceptor in client.interceptors)
    }

    @Test
    fun theUserAgentIsReadForEveryRequest() {
        var ua = "first"
        val sent = mutableListOf<String?>()
        val client = RitmeUrlHttp.client { ua }.newBuilder()
            // stands in for the network: records what would be sent
            .addInterceptor { chain ->
                sent += chain.request().header("User-Agent")
                throw IOException("stop")
            }
            .build()
        fun call() = runCatching { client.newCall(Request.Builder().url("https://cdn.example.com/v.mp4").build()).execute() }
        call()
        ua = "second"
        call()
        assertEquals(listOf<String?>("first", "second"), sent)
    }
}
