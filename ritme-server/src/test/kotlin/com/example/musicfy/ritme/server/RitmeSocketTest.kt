package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.Command
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.NewEntry
import com.example.musicfy.ritme.protocol.PROTOCOL_VERSION
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeServerMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RitmeSocketTest {
    private suspend fun DefaultClientWebSocketSession.sendClient(message: ClientMessage) =
        send(Frame.Text(message.encode()))

    private suspend fun DefaultClientWebSocketSession.receiveServer(): ServerMessage = withTimeout(5_000) {
        var frame = incoming.receive()
        while (frame !is Frame.Text) frame = incoming.receive()
        decodeServerMessage((frame as Frame.Text).readText())
    }

    @Test
    fun pingIsAnsweredBeforeJoining() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            sendClient(ClientMessage.Ping(42))
            assertEquals(42L, (receiveServer() as ServerMessage.Pong).clientTime)
        }
    }

    @Test
    fun joiningAnUnknownRoomIsRefused() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
        }
    }

    @Test
    fun aRefusalIsDeliveredBeforeTheSocketCloses() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            sendClient(ClientMessage.Join(PROTOCOL_VERSION, "ZZZZZZ", "Sari"))
            assertEquals(ServerMessage.Error(code = ErrorCode.ROOM_NOT_FOUND), receiveServer())
            val next = withTimeout(5_000) { incoming.receiveCatching() }
            assertTrue(next.isClosed || next.getOrNull() is Frame.Close)
        }
    }

    @Test
    fun garbageIsAnsweredWithInvalid() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            send(Frame.Text("""{"type":"Nope"}"""))
            assertEquals(ServerMessage.Error(code = ErrorCode.INVALID), receiveServer())
        }
    }

    @Test
    fun twoClientsShareOneRoom() = testApplication {
        application { ritmeModule(testDeps()) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/ritme") {
            val host = this
            host.sendClient(ClientMessage.Create(PROTOCOL_VERSION, "Budi", listOf(NewEntry("dQw4w9WgXcQ", "Song", "Artist", null, 200_000))))
            val welcome = host.receiveServer() as ServerMessage.Welcome

            client.webSocket("/ritme") {
                val guest = this
                guest.sendClient(ClientMessage.Join(PROTOCOL_VERSION, welcome.state.code, "Sari"))
                val guestWelcome = guest.receiveServer() as ServerMessage.Welcome
                assertEquals(listOf("Budi", "Sari"), guestWelcome.state.members.map { it.name })
                assertEquals(2, (host.receiveServer() as ServerMessage.State).state.members.size)

                guest.sendClient(ClientMessage.Send("r1", Command.Pause))
                val seenByGuest = guest.receiveServer() as ServerMessage.State
                val seenByHost = host.receiveServer() as ServerMessage.State
                assertFalse(seenByGuest.state.playback.isPlaying)
                assertEquals(seenByGuest.state.version, seenByHost.state.version)
            }

            val afterGuestLeft = host.receiveServer() as ServerMessage.State
            assertFalse(afterGuestLeft.state.members.single { it.name == "Sari" }.connected)
        }
    }
}
