package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeServerMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout

suspend fun DefaultClientWebSocketSession.sendClient(message: ClientMessage) = send(Frame.Text(message.encode()))

/** Next server message; like a compliant client, acknowledges every Welcome/State unless [ack] is false. */
suspend fun DefaultClientWebSocketSession.receiveServer(ack: Boolean = true): ServerMessage = withTimeout(5_000) {
    var frame = incoming.receive()
    while (frame !is Frame.Text) frame = incoming.receive()
    val message = decodeServerMessage((frame as Frame.Text).readText())
    if (ack) {
        when (message) {
            is ServerMessage.Welcome -> sendClient(ClientMessage.Ack(message.state.version))
            is ServerMessage.State -> sendClient(ClientMessage.Ack(message.state.version))
            else -> Unit
        }
    }
    message
}

/** True once the server has closed the socket (a Close frame or the channel ending); fails after 5 s. */
suspend fun DefaultClientWebSocketSession.closedByServer(): Boolean = withTimeout(5_000) {
    var closed = false
    while (!closed) {
        val next = incoming.receiveCatching()
        closed = next.isClosed || next.getOrNull() is Frame.Close
    }
    closed
}

/** A /ritme socket whose client address (via X-Forwarded-For) is [ip]. */
suspend fun HttpClient.ritme(ip: String, block: suspend DefaultClientWebSocketSession.() -> Unit) =
    webSocket("/ritme", request = { header("X-Forwarded-For", ip) }, block = block)
