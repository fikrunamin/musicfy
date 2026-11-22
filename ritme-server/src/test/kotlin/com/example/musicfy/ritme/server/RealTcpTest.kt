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

        /** Reads one server frame (servers never mask): opcode and payload. */
        fun readFrame(): Pair<Int, ByteArray> {
            val input = socket.getInputStream()
            fun byte() = input.read().also { if (it < 0) throw IOException("closed") }
            val opcode = byte() and 0x0f
            val len = when (val n = byte() and 0x7f) {
                126 -> (byte() shl 8) or byte()
                127 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or byte().toLong() }.toInt()
                else -> n
            }
            return opcode to input.readNBytes(len)
        }

        /** Payload of the next text frame (skipping pings). */
        fun readText(): String {
            while (true) {
                val (opcode, payload) = readFrame()
                if (opcode == 1) return String(payload)
                if (opcode == 8) throw IOException("closed by server")
            }
        }

        /** Text payloads the server sent until it hung up (pings and the Close frame skipped). */
        fun readTextUntilClosed(timeoutMs: Int): List<String> {
            socket.soTimeout = timeoutMs
            val texts = mutableListOf<String>()
            try {
                while (true) {
                    val (opcode, payload) = readFrame()
                    if (opcode == 1) texts += String(payload)
                }
            } catch (_: IOException) {
                // EOF or reset: the server hung up (SocketTimeoutException is an IOException too).
            }
            return texts
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
        val deps = testDeps(socketLimits = SocketLimits(sendStallMs = 1_000, ackDeadlineMs = 1_000), peerStats = stats)
        val server = embeddedServer(Netty, port = 0) { ritmeModule(deps) }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(WebSockets) }
        // ~3.4 KB per entry: 70 of them make a ~240 KB snapshot (just under the 256 KB cap).
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
                host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", List(40) { big(it) }))
                val code = (host.receiveServer() as ServerMessage.Welcome).state.code
                host.sendClient(ClientMessage.Send("add", Command.Add(List(30) { big(100 + it) })))
                val full = nextState()
                assertEquals(70, full.queue.size)

                val clients = List(silent) { i ->
                    DeafClient(port).also { it.send(ClientMessage.Join(PROTOCOL_VERSION, code, "S$i")) }
                }
                try {
                    // Keep snapshots coming (~6/s) until every silent member has been dropped. (Once the
                    // kernel's socket buffers are full, the next snapshot stalls and the client goes.)
                    val settled = withTimeout(30_000) {
                        var state = nextState()
                        var k = 0
                        while (state.members.size < silent + 1 || state.members.any { it.name.startsWith("S") && it.connected }) {
                            host.sendClient(ClientMessage.Send("s$k", Command.Seek(k++ * 1_000L)))
                            delay(150)
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
                    withTimeout(5_000) { while (nextState().playback.isPlaying) Unit } // after earlier seeks
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

    @Test
    fun silentPeersThatNeverAckGetNoFurtherSnapshotsAndAreDroppedAfterTheStallWindow() = runBlocking {
        val silent = 8
        val stats = PeerStats()
        val deps = testDeps(socketLimits = SocketLimits(sendStallMs = 1_500, ackDeadlineMs = 1_500), peerStats = stats)
        val server = embeddedServer(Netty, port = 0) { ritmeModule(deps) }.start()
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(OkHttp) { install(WebSockets) }
        fun big(i: Int) = NewEntry(
            "", "x".repeat(300), "y".repeat(300), "https://cdn.example/" + "t".repeat(480), 600_000,
            MediaKind.URL, "https://cdn.example/$i/" + "u".repeat(2_000),
        )
        try {
            client.webSocket("ws://127.0.0.1:$port/ritme") {
                val host = this
                host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", List(40) { big(it) })) // ~135 KB
                val code = (host.receiveServer() as ServerMessage.Welcome).state.code
                val peers = List(silent) { i ->
                    DeafClient(port).also {
                        it.send(ClientMessage.Join(PROTOCOL_VERSION, code, "S$i"))
                        check(it.readText().contains("\"Welcome\"")) // read the Welcome, then go silent
                    }
                }
                try {
                    val start = System.nanoTime()
                    // Snapshots keep coming (~6/s); none may reach the silent peers.
                    withTimeout(15_000) {
                        var k = 0
                        while (stats.aborted.get() < silent) {
                            host.sendClient(ClientMessage.Send("s$k", Command.Seek(k++ * 1_000L)))
                            delay(150)
                            while (host.incoming.tryReceive().getOrNull() != null) Unit
                        }
                    }
                    val droppedAfterMs = (System.nanoTime() - start) / 1_000_000
                    assertTrue("dropped after $droppedAfterMs ms", droppedAfterMs >= 1_000)
                    peers.forEach { peer ->
                        val after = peer.readTextUntilClosed(timeoutMs = 5_000)
                        assertTrue("a silent peer got $after", after.none { it.contains("\"State\"") })
                    }
                } finally {
                    peers.forEach { it.close() }
                }
            }
        } finally {
            client.close()
            server.stop(0, 0)
        }
    }
}
