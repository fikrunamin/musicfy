package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeClientMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.ProtocolViolationException
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Never suspends on the network: messages go into a small per-client queue that its own coroutine
 * drains into the socket, so a slow client cannot hold the room lock. A client that falls
 * [SEND_BUFFER] messages behind is cut off at once (its session is cancelled, so the room sees a
 * disconnect and starts the grace period); it can resume with its token and get fresh state.
 */
class WebSocketPeer(private val session: DefaultWebSocketServerSession) : Peer {
    private val queue = Channel<Outgoing>(capacity = SEND_BUFFER)
    private val pump = session.launch {
        try {
            for (message in queue) session.outgoing.send(Frame.Text(message.text))
        } catch (_: ClosedSendChannelException) {
            // The socket is already gone; nothing left to deliver.
        }
    }.also { job -> job.invokeOnCompletion { queue.close() } }

    override suspend fun send(message: Outgoing) {
        val result = queue.trySend(message)
        if (result.isClosed) throw ClosedSendChannelException("peer closed")
        if (result.isFailure) abort()
    }

    /** Delivers what is already queued (Kicked, RoomClosed, ...) and then closes the socket. */
    override suspend fun close() {
        if (!queue.close()) return
        session.launch {
            val closed = withTimeoutOrNull(CLOSE_FLUSH_MS) {
                pump.join()
                session.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
            }
            if (closed == null) session.cancel() // the client stopped reading: drop it
        }
    }

    private fun abort() {
        queue.close()
        pump.cancel()
        session.cancel()
    }

    companion object {
        const val SEND_BUFFER = 64
        const val CLOSE_FLUSH_MS = 5_000L
    }
}

/**
 * Refuses a socket before it gets a session: tells the client why, then closes. Used for the
 * connection caps (RATE_LIMITED) and for rejected Create/Join (the rejection's code).
 */
suspend fun DefaultWebSocketServerSession.refuse(code: ErrorCode, reason: CloseReason.Codes = CloseReason.Codes.NORMAL) {
    // Not under any room lock: send directly so it is flushed before we close.
    runCatching { outgoing.send(Frame.Text(ServerMessage.Error(code = code).encode())) }
    close(CloseReason(reason, "rejected"))
}

/** Abuse (flooding, idling before join): close without an answer. */
private suspend fun DefaultWebSocketServerSession.closeQuietly() {
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, ""))
}

/**
 * One connection: Ping works any time; the first other message must be Create or Join, and the
 * socket must have joined within [SocketLimits.joinDeadlineMs]; after that, commands go to the
 * room. Every inbound frame counts against a per-connection limiter. Dropping the socket starts
 * the member's grace period.
 */
suspend fun DefaultWebSocketServerSession.ritmeSession(
    registry: RoomRegistry,
    clock: Clock,
    guard: ConnectionGuard,
    remoteHost: String,
) {
    val limits = guard.limits
    val peer = WebSocketPeer(this)
    val frames = RateLimiter(limits.frameBurst, limits.framesPerSecond.toDouble(), clock)
    val joinDeadline = TimeSource.Monotonic.markNow() + limits.joinDeadlineMs.milliseconds
    var session: JoinResult.Joined? = null
    try {
        while (true) {
            val frame = if (session == null) {
                withTimeoutOrNull(-joinDeadline.elapsedNow()) { incoming.receive() }
                    ?: return closeQuietly()
            } else {
                incoming.receive()
            }
            if (!frames.tryAcquire()) return closeQuietly()
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
                    is ClientMessage.Create, is ClientMessage.Join -> when {
                        !guard.tryJoin(remoteHost) -> JoinResult.Rejected(ErrorCode.RATE_LIMITED)
                        message is ClientMessage.Create -> registry.create(message, peer)
                        else -> registry.join(message as ClientMessage.Join, peer)
                    }
                    else -> JoinResult.Rejected(ErrorCode.INVALID)
                }
                when (result) {
                    is JoinResult.Joined -> session = result
                    is JoinResult.Rejected -> return refuse(result.code)
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
    } catch (_: ClosedReceiveChannelException) {
        // The client went away or the server closed the socket: a normal end, never an ERROR log.
    } catch (_: ClosedSendChannelException) {
    } catch (_: FrameTooBigException) {
        // Ktor already closed the socket with TOO_BIG.
    } catch (_: ProtocolViolationException) {
    } catch (_: IOException) {
    } finally {
        val joined = session
        if (joined != null) {
            // The session may be cancelled here; the room still has to hear about it.
            withContext(NonCancellable) { joined.room.disconnected(joined.memberId, peer) }
        }
    }
}
