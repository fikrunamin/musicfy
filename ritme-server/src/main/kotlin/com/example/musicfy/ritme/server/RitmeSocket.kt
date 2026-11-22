package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeClientMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Never suspends on the network: messages go into a small per-client queue that its own coroutine
 * drains into the socket, so a slow client cannot hold the room lock. A client that falls
 * [SEND_BUFFER] messages behind is disconnected; it can resume with its token and get fresh state.
 */
class WebSocketPeer(private val session: DefaultWebSocketServerSession) : Peer {
    private val queue = Channel<ServerMessage>(capacity = SEND_BUFFER)
    private val pump = session.launch {
        for (message in queue) session.outgoing.send(Frame.Text(message.encode()))
    }.also { job -> job.invokeOnCompletion { queue.close() } }

    override suspend fun send(message: ServerMessage) {
        val result = queue.trySend(message)
        if (result.isClosed) throw ClosedSendChannelException("peer closed")
        if (result.isFailure) close()
    }

    override suspend fun close() {
        if (!queue.close()) return
        session.launch {
            runCatching {
                pump.join()
                session.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
            }
        }
    }

    companion object {
        const val SEND_BUFFER = 64
    }
}

/**
 * One connection: Ping works any time; the first other message must be Create or Join; after
 * that, commands go to the room. Dropping the socket starts the member's grace period.
 */
suspend fun DefaultWebSocketServerSession.ritmeSession(registry: RoomRegistry, clock: Clock) {
    val peer = WebSocketPeer(this)
    var session: JoinResult.Joined? = null
    try {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val message = runCatching { decodeClientMessage(frame.readText()) }.getOrNull()
            if (message == null) {
                peer.send(ServerMessage.Error(code = ErrorCode.INVALID))
                continue
            }
            if (message is ClientMessage.Ping) {
                peer.send(ServerMessage.Pong(message.clientTime, clock.now()))
                continue
            }
            val joined = session
            if (joined == null) {
                val result = when (message) {
                    is ClientMessage.Create -> registry.create(message, peer)
                    is ClientMessage.Join -> registry.join(message, peer)
                    else -> JoinResult.Rejected(ErrorCode.INVALID)
                }
                when (result) {
                    is JoinResult.Joined -> session = result
                    is JoinResult.Rejected -> {
                        // Not under any room lock: send directly so it is flushed before we return.
                        runCatching { outgoing.send(Frame.Text(ServerMessage.Error(code = result.code).encode())) }
                        close(CloseReason(CloseReason.Codes.NORMAL, "rejected"))
                        return
                    }
                }
                continue
            }
            when (message) {
                is ClientMessage.Send -> joined.room.command(joined.memberId, message.reqId, message.cmd)
                is ClientMessage.TrackEnded -> joined.room.trackEnded(joined.memberId, message.entryId)
                ClientMessage.Leave -> {
                    session = null
                    joined.room.leave(joined.memberId)
                    close(CloseReason(CloseReason.Codes.NORMAL, "left"))
                    return
                }
                is ClientMessage.Create, is ClientMessage.Join -> peer.send(ServerMessage.Error(code = ErrorCode.INVALID))
                is ClientMessage.Ping -> Unit
            }
        }
    } finally {
        val joined = session
        if (joined != null) {
            // The session may be cancelled here; the room still has to hear about it.
            withContext(NonCancellable) { joined.room.disconnected(joined.memberId, peer) }
        }
    }
}
