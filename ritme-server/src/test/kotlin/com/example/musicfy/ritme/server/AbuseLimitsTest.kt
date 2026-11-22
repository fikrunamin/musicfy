package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbuseLimitsTest {
    private fun ApplicationTestBuilder.wsClient(): HttpClient = createClient { install(WebSockets) }

    @Test
    fun aSocketThatDoesNotJoinInTimeIsClosedEvenWhilePinging() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinDeadlineMs = 300))) }
        wsClient().ritme("1.1.1.1") {
            sendClient(ClientMessage.Ping(1))
            assertTrue(receiveServer() is ServerMessage.Pong)
            assertTrue(closedByServer())
        }
    }

    @Test
    fun aJoinedSocketOutlivesTheJoinDeadline() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinDeadlineMs = 300))) }
        wsClient().ritme("1.1.1.1") {
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
            client.ritme("2.2.2.2") {
                sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
                assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
            }
        }
        client.ritme("2.2.2.2") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
            assertTrue(closedByServer())
        }
        // Same /64 counts as the same client; another address does not.
        client.ritme("2001:db8:1:2::1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
        }
    }

    @Test
    fun ipv6ClientsShareTheirSlash64JoinBudget() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinsPerMinute = 1))) }
        val client = wsClient()
        client.ritme("2001:db8:1:2::1") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
        }
        client.ritme("2001:db8:1:2:ffff::9") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
        }
        client.ritme("2001:db8:1:3::1") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
        }
    }

    @Test
    fun connectionsBeyondThePerIpCapAreRefusedAndSlotsAreReleased() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(maxConnectionsPerIp = 2))) }
        val client = wsClient()
        client.ritme("3.3.3.3") {
            val first = this
            client.ritme("3.3.3.3") {
                client.ritme("3.3.3.3") {
                    assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
                    assertTrue(closedByServer())
                }
                client.ritme("4.4.4.4") {
                    sendClient(ClientMessage.Ping(1))
                    assertTrue(receiveServer() is ServerMessage.Pong)
                }
            }
            first.sendClient(ClientMessage.Ping(2))
            assertTrue(first.receiveServer() is ServerMessage.Pong)
        }
        // Both slots were released when the sockets ended.
        delay(200)
        client.ritme("3.3.3.3") {
            client.ritme("3.3.3.3") {
                sendClient(ClientMessage.Ping(3))
                assertTrue(receiveServer() is ServerMessage.Pong)
            }
        }
    }

    @Test
    fun connectionsBeyondTheGlobalCapAreRefused() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(maxConnections = 1))) }
        val client = wsClient()
        client.ritme("5.5.5.5") {
            client.ritme("6.6.6.6") {
                assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
            }
        }
    }

    @Test
    fun roomsBeyondTheGlobalCapAreRefused() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(maxRooms = 1))) }
        val client = wsClient()
        client.ritme("7.7.7.7") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
            client.ritme("7.7.7.8") {
                sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
                assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_FULL), receiveServer())
            }
        }
    }

    @Test
    fun anInboundFrameFloodClosesTheSocket() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(framesPerSecond = 5, frameBurst = 5))) }
        wsClient().ritme("8.8.8.8") {
            // Binary frames get no answer, so only the frame limiter can end this socket.
            repeat(20) { send(Frame.Binary(true, byteArrayOf(1))) }
            assertTrue(closedByServer())
        }
    }

    @Test
    fun aSteadyPingRateIsFine() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(framesPerSecond = 50, frameBurst = 5))) }
        wsClient().ritme("9.9.9.9") {
            repeat(10) {
                sendClient(ClientMessage.Ping(it.toLong()))
                assertEquals(it.toLong(), (receiveServer() as ServerMessage.Pong).clientTime)
                delay(25)
            }
        }
    }
}
