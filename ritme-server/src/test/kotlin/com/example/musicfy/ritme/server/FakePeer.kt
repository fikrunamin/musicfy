package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ServerMessage

class FakePeer : Peer {
    val received = mutableListOf<ServerMessage>()
    var closed = false
        private set

    override suspend fun send(message: ServerMessage) {
        received += message
    }

    override suspend fun close() {
        closed = true
    }

    inline fun <reified T : ServerMessage> last(): T = received.filterIsInstance<T>().last()
}
