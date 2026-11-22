package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.NewEntry
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * Over real TCP (the in-memory test engine buffers without limit, so it cannot show backpressure):
 * a client that never reads must be cut off while the rest of the room keeps working.
 */
class SlowClientTest {
    /** A WebSocket client on a raw socket with a tiny receive window that never reads a byte. */
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

        override fun close() = socket.close()
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
}
