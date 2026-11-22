package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ServerMessage

class FakePeer : Peer {
    /** Exactly what the room handed over, pre-encoded text included. */
    val outgoing = mutableListOf<Outgoing>()
    val received: List<ServerMessage> get() = outgoing.map { it.message }
    var closed = false
        private set

    override suspend fun send(message: Outgoing) {
        outgoing += message
    }

    override suspend fun close() {
        closed = true
    }

    inline fun <reified T : ServerMessage> last(): T = received.filterIsInstance<T>().last()
}

class DeadPeer : Peer {
    override suspend fun send(message: Outgoing) = throw IllegalStateException("socket closed")

    override suspend fun close() = Unit
}
