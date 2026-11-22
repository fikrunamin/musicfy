package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeServerMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbuseLimitsTest {
    private suspend fun DefaultClientWebSocketSession.sendClient(message: ClientMessage) =
        send(Frame.Text(message.encode()))

    private suspend fun DefaultClientWebSocketSession.receiveServer(): ServerMessage = withTimeout(5_000) {
        var frame = incoming.receive()
        while (frame !is Frame.Text) frame = incoming.receive()
        decodeServerMessage((frame as Frame.Text).readText())
    }

    /** True once the server has closed the socket (a Close frame or the channel ending). */
    private suspend fun DefaultClientWebSocketSession.closedByServer(): Boolean = withTimeout(5_000) {
        while (true) {
            val next = incoming.receiveCatching()
            if (next.isClosed || next.getOrNull() is Frame.Close) return@withTimeout true
        }
        @Suppress("UNREACHABLE_CODE")
        false
    }

    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    private suspend fun HttpClient.ws(ip: String, block: suspend DefaultClientWebSocketSession.() -> Unit) =
        webSocket("/ritme", request = { header("X-Forwarded-For", ip) }, block = block)

    @Test
    fun aSocketThatDoesNotJoinInTimeIsClosedEvenWhilePinging() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinDeadlineMs = 300))) }
        wsClient().ws("1.1.1.1") {
            sendClient(ClientMessage.Ping(1))
            assertTrue(receiveServer() is ServerMessage.Pong)
            assertTrue(closedByServer())
        }
    }

    @Test
    fun aJoinedSocketOutlivesTheJoinDeadline() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinDeadlineMs = 300))) }
        wsClient().ws("1.1.1.1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
            delay(600)
            sendClient(ClientMessage.Ping(7))
            assertEquals(7L, (receiveServer() as ServerMessage.Pong).clientTime)
        }
    }

    @Test
    fun createAndJoinAttemptsAreRateLimitedPerIp() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = wsClient()
        repeat(Limits.JOINS_PER_MINUTE) {
            client.ws("2.2.2.2") {
                sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
                assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
            }
        }
        client.ws("2.2.2.2") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
            assertTrue(closedByServer())
        }
        // Same /64 counts as the same client; another address does not.
        client.ws("2001:db8:1:2::1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
        }
    }

    @Test
    fun ipv6ClientsShareTheirSlash64JoinBudget() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinsPerMinute = 1))) }
        val client = wsClient()
        client.ws("2001:db8:1:2::1") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
        }
        client.ws("2001:db8:1:2:ffff::9") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
        }
        client.ws("2001:db8:1:3::1") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
        }
    }

    @Test
    fun connectionsBeyondThePerIpCapAreRefusedAndSlotsAreReleased() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(maxConnectionsPerIp = 2))) }
        val client = wsClient()
        client.ws("3.3.3.3") {
            val first = this
            client.ws("3.3.3.3") {
                client.ws("3.3.3.3") {
                    assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
                    assertTrue(closedByServer())
                }
                client.ws("4.4.4.4") {
                    sendClient(ClientMessage.Ping(1))
                    assertTrue(receiveServer() is ServerMessage.Pong)
                }
            }
            first.sendClient(ClientMessage.Ping(2))
            assertTrue(first.receiveServer() is ServerMessage.Pong)
        }
        // Both slots were released when the sockets ended.
        delay(200)
        client.ws("3.3.3.3") {
            client.ws("3.3.3.3") {
                sendClient(ClientMessage.Ping(3))
                assertTrue(receiveServer() is ServerMessage.Pong)
            }
        }
    }

    @Test
    fun connectionsBeyondTheGlobalCapAreRefused() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(maxConnections = 1))) }
        val client = wsClient()
        client.ws("5.5.5.5") {
            client.ws("6.6.6.6") {
                assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
            }
        }
    }

    @Test
    fun roomsBeyondTheGlobalCapAreRefused() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(maxRooms = 1))) }
        val client = wsClient()
        client.ws("7.7.7.7") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
            client.ws("7.7.7.8") {
                sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
                assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_FULL), receiveServer())
            }
        }
    }

    @Test
    fun anInboundFrameFloodClosesTheSocket() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(framesPerSecond = 5, frameBurst = 5))) }
        wsClient().ws("8.8.8.8") {
            // Binary frames get no answer, so only the frame limiter can end this socket.
            repeat(20) { send(Frame.Binary(true, byteArrayOf(1))) }
            assertTrue(closedByServer())
        }
    }

    @Test
    fun aSteadyPingRateIsFine() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(framesPerSecond = 50, frameBurst = 5))) }
        wsClient().ws("9.9.9.9") {
            repeat(10) {
                sendClient(ClientMessage.Ping(it.toLong()))
                assertEquals(it.toLong(), (receiveServer() as ServerMessage.Pong).clientTime)
                delay(25)
            }
        }
    }
}
