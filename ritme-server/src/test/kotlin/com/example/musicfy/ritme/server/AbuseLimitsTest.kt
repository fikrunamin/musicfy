package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
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
    fun failedJoinsAndCreatesAreRateLimitedPerIp() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = wsClient()
        repeat(Limits.JOIN_BURST) {
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
        client.ritme("2.2.2.2") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
        }
        // Another address has its own budget.
        client.ritme("2001:db8:1:2::1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Sari"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
        }
    }

    @Test
    fun successfulJoinsAndResumesAreFreeButFailuresAndCreatesAreCharged() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinBurst = 2))) }
        val client = wsClient()
        client.ritme("13.0.0.1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            val code = (receiveServer() as ServerMessage.Welcome).state.code
            var token: String? = null
            repeat(10) { // a flaky phone reconnecting from a shared address
                client.ritme("13.0.0.2") {
                    sendClient(ClientMessage.Join(PROTOCOL_VERSION, code, "Sari", token))
                    token = (receiveServer() as ServerMessage.Welcome).resumeToken
                }
            }
            repeat(2) {
                client.ritme("13.0.0.2") {
                    sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
                    assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
                }
            }
            client.ritme("13.0.0.2") { // bucket empty: even a valid code is refused (no guessing oracle)
                sendClient(ClientMessage.Join(PROTOCOL_VERSION, code, "Sari", token))
                assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
            }
            client.ritme("13.0.0.1") { // the creator's address spent one token on Create
                sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
                assertTrue(receiveServer() is ServerMessage.Welcome)
            }
            client.ritme("13.0.0.1") {
                sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
                assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
            }
        }
    }

    @Test
    fun ipv6ClientsShareTheirSlash64JoinBudget() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(joinBurst = 1))) }
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
            // Binary frames get no answer, so only the frame limiter can end this socket. The server
            // may close the socket mid-burst; a send on the cancelled channel then throws.
            for (i in 1..20) if (runCatching { send(Frame.Binary(true, byteArrayOf(1))) }.isFailure) break
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

    @Test
    fun noNewMembersWhileTheServerIsOverItsOutboundBudget() = testApplication {
        val stats = PeerStats(budget = 1_000)
        application { ritmeModule(testDeps(peerStats = stats)) }
        stats.add(1_001) // as if stuck clients held this much
        wsClient().ritme("12.0.0.1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            assertEquals(ServerMessage.Error(code = ErrorCode.RATE_LIMITED), receiveServer())
        }
        stats.add(-1_001)
        wsClient().ritme("12.0.0.2") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            assertTrue(receiveServer() is ServerMessage.Welcome)
        }
    }

    @Test
    fun membersWhoAckPromptlyAreNotClosedWhenSomeoneElseSpamsCommands() = testApplication {
        // Burst 6, almost no refill: without the Ack exemption the guest's Join + 6 Acks would be 7 frames.
        application { ritmeModule(testDeps(socketLimits = SocketLimits(framesPerSecond = 1, frameBurst = 6))) }
        val client = wsClient()
        client.ritme("14.0.0.1") {
            val host = this
            host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(NewEntry("dQw4w9WgXcQ", "Song", "Artist", null, 200_000))))
            val code = (host.receiveServer() as ServerMessage.Welcome).state.code
            client.ritme("14.0.0.2") {
                val guest = this
                guest.sendClient(ClientMessage.Join(PROTOCOL_VERSION, code, "Sari"))
                guest.receiveServer() // Welcome, acked
                host.receiveServer() // Sari joined, acked
                repeat(5) {
                    host.sendClient(ClientMessage.Send("s$it", Command.Seek(it * 1_000L)))
                    host.receiveServer()
                    assertEquals(it * 1_000L, (guest.receiveServer() as ServerMessage.State).state.playback.positionMs)
                }
                guest.sendClient(ClientMessage.Ping(5)) // still connected, still has budget
                assertEquals(5L, (guest.receiveServer() as ServerMessage.Pong).clientTime)
            }
        }
    }

    @Test
    fun acksForOtherVersionsStillCountAgainstTheFrameLimiter() = testApplication {
        application { ritmeModule(testDeps(socketLimits = SocketLimits(framesPerSecond = 1, frameBurst = 5))) }
        wsClient().ritme("15.0.0.1") {
            sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi"))
            receiveServer() // Welcome (its Ack is free)
            // The server may close the socket mid-burst; a send on the cancelled channel then throws.
            for (i in 1..10) if (runCatching { sendClient(ClientMessage.Ack(999)) }.isFailure) break
            assertTrue(closedByServer())
        }
    }
}
