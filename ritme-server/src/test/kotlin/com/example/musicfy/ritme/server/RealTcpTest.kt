package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.MediaKind
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.RoomState
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Base64

/**
 * Over real TCP against Netty: the in-memory test engine buffers without limit and never holds a
 * half-closed connection, so it cannot show backpressure or clients that ignore Close frames.
 */
class RealTcpTest {
    /** A WebSocket client on a raw socket with a tiny receive window; it reads only when asked. */
    private class DeafClient(port: Int) : AutoCloseable {
        private val socket = Socket().apply {
            receiveBufferSize = 4_096
            connect(InetSocketAddress("127.0.0.1", port))
        }
        private val out = socket.getOutputStream()

        init {
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
            out.write(
                ("GET /ritme HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
            )
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            check(head.startsWith("HTTP/1.1 101")) { head }
        }

        fun send(message: ClientMessage) {
            val payload = message.encode().toByteArray()
            check(payload.size < 126)
            val mask = byteArrayOf(1, 2, 3, 4)
            out.write(byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()) + mask)
            out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
        }

        /** Reads one server frame (servers never mask) and returns its payload as text. */
        fun readText(): String {
            val input = socket.getInputStream()
            fun byte() = input.read().also { if (it < 0) throw IOException("closed") }
            byte()
            val len = when (val n = byte() and 0x7f) {
                126 -> (byte() shl 8) or byte()
                127 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or byte().toLong() }.toInt()
                else -> n
            }
            return String(input.readNBytes(len))
        }

        /** Reads (and discards) until the server closes the TCP connection; ms waited, or null on timeout. */
        fun millisUntilServerHangsUp(timeoutMs: Int): Long? {
            socket.soTimeout = timeoutMs
            val start = System.nanoTime()
            return try {
                val input = socket.getInputStream()
                while (input.read() != -1) Unit
                (System.nanoTime() - start) / 1_000_000
            } catch (_: SocketTimeoutException) {
                null
            } catch (_: IOException) {
                (System.nanoTime() - start) / 1_000_000 // reset by the server
            }
        }

        override fun close() = socket.close()
    }

    private fun <T> withServer(limits: SocketLimits, block: suspend (port: Int) -> T): T = runBlocking {
        val server = embeddedServer(Netty, port = 0) { ritmeModule(testDeps(socketLimits = limits)) }.start()
        try {
            block(server.engine.resolvedConnectors().first().port)
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun aClientThatIgnoresTheCloseFrameIsHungUpOnAndFreesItsSlot() = withServer(SocketLimits(joinDeadlineMs = 200, maxConnectionsPerIp = 1)) { port ->
        DeafClient(port).use { idle ->
            // Join deadline -> Close frame, never answered -> TCP closed after the handshake timeout.
            val waited = checkNotNull(idle.millisUntilServerHangsUp(timeoutMs = 10_000)) { "still connected after 10 s" }
            assertTrue("hung up after $waited ms", waited < 200 + CLOSE_HANDSHAKE_MS + 2_000)
        }
        // The per-IP slot (cap 1) is released right after the hang-up.
        delay(200)
        DeafClient(port).use { next ->
            next.send(ClientMessage.Ping(1))
            val reply = next.readText()
            assertTrue(reply, reply.contains("Pong")) // accepted, not RATE_LIMITED
        }
    }

    @Test
    fun overCapConnectionsThatIgnoreTheCloseFrameAreHungUpOnToo() = withServer(SocketLimits(maxConnectionsPerIp = 1)) { port ->
        DeafClient(port).use { first ->
            first.send(ClientMessage.Ping(1))
            check(first.readText().contains("Pong")) // its handler runs, so it holds the only slot
            DeafClient(port).use { refused ->
                check(refused.readText().contains("RATE_LIMITED"))
                val waited = checkNotNull(refused.millisUntilServerHangsUp(timeoutMs = 10_000)) { "still connected after 10 s" }
                assertTrue("hung up after $waited ms", waited < CLOSE_HANDSHAKE_MS + 2_000)
            }
        }
    }

    @Test
    fun aClientThatStopsReadingIsDisconnectedWhileTheRoomKeepsWorking() = runBlocking {
        // Generous frame limits so only the send queue can end the deaf socket.
        val deps = testDeps(socketLimits = SocketLimits(framesPerSecond = 1_000_000, frameBurst = 1_000_000))
        val server = embeddedServer(Netty, port = 0) { ritmeModule(deps) }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(WebSockets) }
        try {
            client.webSocket("ws://127.0.0.1:$port/ritme") {
                val host = this
                host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(NewEntry("dQw4w9WgXcQ", "Song", "Artist", null, 200_000))))
                val code = (host.receiveServer() as ServerMessage.Welcome).state.code
                DeafClient(port).use { deaf ->
                    deaf.send(ClientMessage.Join(PROTOCOL_VERSION, code, "Deaf"))
                    host.receiveServer() // Deaf joined
                    // Every Ping queues a Pong that is never read; the server stops writing, then gives up.
                    val flood = launch(Dispatchers.IO) {
                        try {
                            repeat(1_000_000) { deaf.send(ClientMessage.Ping(it.toLong())) }
                        } catch (_: IOException) {
                            // The server dropped us.
                        }
                    }
                    val dropped = withTimeout(30_000) {
                        var state = (host.receiveServer() as ServerMessage.State).state
                        while (state.members.single { it.name == "Deaf" }.connected) {
                            state = (host.receiveServer() as ServerMessage.State).state
                        }
                        state
                    }
                    assertFalse(dropped.members.single { it.name == "Deaf" }.connected)
                    host.sendClient(ClientMessage.Send("p", Command.Pause))
                    assertFalse((host.receiveServer() as ServerMessage.State).state.playback.isPlaying)
                    flood.cancel()
                }
            }
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }

    @Test
    fun silentPeersInARoomWithALargeStateAreDroppedAndBufferingStaysBounded() = runBlocking {
        val silent = 6
        val stats = PeerStats()
        val deps = testDeps(socketLimits = SocketLimits(sendStallMs = 1_000), peerStats = stats)
        val server = embeddedServer(Netty, port = 0) { ritmeModule(deps) }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(WebSockets) }
        // ~3.4 KB per entry: 110 of them make a ~370 KB snapshot.
        fun big(i: Int) = NewEntry(
            "", "x".repeat(300), "y".repeat(300), "https://cdn.example/" + "t".repeat(480), 600_000,
            MediaKind.URL, "https://cdn.example/$i/" + "u".repeat(2_000),
        )
        try {
            client.webSocket("ws://127.0.0.1:$port/ritme") {
                val host = this
                suspend fun nextState(): RoomState {
                    var message = host.receiveServer()
                    while (message !is ServerMessage.State) message = host.receiveServer()
                    return message.state
                }
                host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", List(55) { big(it) }))
                val code = (host.receiveServer() as ServerMessage.Welcome).state.code
                host.sendClient(ClientMessage.Send("add", Command.Add(List(55) { big(100 + it) })))
                val full = nextState()
                assertEquals(110, full.queue.size)

                val clients = List(silent) { i ->
                    DeafClient(port).also { it.send(ClientMessage.Join(PROTOCOL_VERSION, code, "S$i")) }
                }
                try {
                    // Keep snapshots coming while the silent members sit on theirs.
                    repeat(10) { host.sendClient(ClientMessage.Send("s$it", Command.Seek(it * 1_000L))) }
                    val settled = withTimeout(20_000) {
                        var state = nextState()
                        while (state.members.size < silent + 1 || state.members.any { it.name.startsWith("S") && it.connected }) {
                            state = nextState()
                        }
                        state
                    }
                    assertTrue(settled.members.filter { it.name.startsWith("S") }.none { it.connected })
                    assertTrue("aborted ${stats.aborted.get()}", stats.aborted.get() >= silent)
                    // Coalescing keeps each stuck client at about one snapshot plus its Welcome.
                    val perPeer = full.let { Outgoing(ServerMessage.State(it)).size } * 3L
                    assertTrue(
                        "peak ${stats.peakPendingBytes.get()} > ${perPeer * (silent + 1)}",
                        stats.peakPendingBytes.get() <= perPeer * (silent + 1),
                    )
                    host.sendClient(ClientMessage.Send("p", Command.Pause))
                    assertFalse(nextState().playback.isPlaying)
                    withTimeout(5_000) { while (stats.pendingBytes.get() > 0) delay(50) }
                } finally {
                    clients.forEach { it.close() }
                }
            }
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }
}
