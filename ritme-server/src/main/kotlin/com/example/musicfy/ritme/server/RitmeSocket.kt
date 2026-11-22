package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ClientMessage
import com.example.musicfy.ritme.protocol.ErrorCode
import com.example.musicfy.ritme.protocol.ServerMessage
import com.example.musicfy.ritme.protocol.decodeClientMessage
import com.example.musicfy.ritme.protocol.encode
import io.ktor.server.plugins.origin
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
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Never suspends on the network: messages go into this client's [PeerOutbox] (snapshots wait for
 * the client's Ack and coalesce, bytes are bounded) and a pump coroutine hands them to Ktor one
 * frame at a time, so a slow client
 * cannot hold the room lock or grow memory. Every frame shares the broadcast's encoded bytes. The
 * client is cut off at once (session cancelled; the room sees a disconnect and starts the grace
 * period; it can resume with its token for fresh state) when its outbox goes over budget, when a
 * frame is not written or a snapshot not acknowledged within [stallMs], or when it is behind while
 * the server as a whole is over [PeerStats.budget].
 */
class WebSocketPeer(
    private val session: DefaultWebSocketServerSession,
    private val stats: PeerStats = PeerStats(),
    private val stallMs: Long = Limits.SEND_STALL_MS,
) : Peer {
    private val lock = Any()
    private val outbox = PeerOutbox()
    private var closed = false // no more messages accepted
    private var aborted = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var inFlight = 0 // bytes of the frame being written

    /** When the awaited snapshot Ack is overdue (null: not waiting for one). */
    private var ackDeadline: TimeMark? = null

    private val pump = session.launch {
        try {
            while (true) {
                val next = synchronized(lock) { outbox.take()?.also { inFlight = it.size } }
                if (next == null) {
                    val (done, deadline) = synchronized(lock) { (closed && !outbox.hasSendable) to ackDeadline }
                    if (done) break // closing: what is left is a snapshot nobody needs any more
                    if (deadline == null) {
                        wake.receiveCatching()
                    } else if (deadline.hasPassedNow()) {
                        abort() // no Ack within the stall window: treated like a stalled socket
                        break
                    } else {
                        withTimeoutOrNull(-deadline.elapsedNow()) { wake.receiveCatching() }
                    }
                    continue
                }
                if (next.snapshotVersion != null) synchronized(lock) { ackDeadline = TimeSource.Monotonic.markNow() + stallMs.milliseconds }
                // flush() returns once the frame is in Ktor's per-connection buffer (up to 1 MiB),
                // not on the wire; the Ack gating above is what keeps that buffer to about one
                // snapshot. stallMs measures the write into the local socket, which in production
                // is the connection to Caddy (its kernel buffer), not delivery to the end client. It
                // detects a stuck connection; it is not a client timeout and must not be tuned as one.
                val written = try {
                    withTimeoutOrNull(stallMs) {
                        session.outgoing.send(Frame.Text(true, next.bytes))
                        session.flush()
                    }
                } finally {
                    synchronized(lock) { inFlight = 0 }
                    stats.drop(next)
                }
                if (written == null) {
                    abort() // the client stopped reading
                    break
                }
            }
        } catch (_: ClosedSendChannelException) {
            // The socket is already gone; nothing left to deliver.
        } finally {
            synchronized(lock) {
                closed = true
                outbox.clear()
            }.forEach(stats::drop)
        }
    }

    /** The client applied snapshot [version]: the next pending snapshot may go out. */
    fun ack(version: Long) {
        val acked = synchronized(lock) {
            outbox.ack(version).also { if (it) ackDeadline = null }
        }
        if (acked) wake.trySend(Unit)
    }

    override suspend fun send(message: Outgoing) {
        val drop = synchronized(lock) {
            if (closed) throw ClosedSendChannelException("peer closed")
            val backlog = outbox.pendingBytes + inFlight
            stats.hold(message)
            outbox.add(message)?.let(stats::drop)
            // Over the server-wide budget, clients that are already behind go first.
            outbox.overBudget || (backlog > 0 && stats.overBudget)
        }
        if (drop) abort() else wake.trySend(Unit)
    }

    /** Delivers what is already queued (Kicked, RoomClosed, ...) and then closes the socket. */
    override suspend fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        wake.close()
        session.launch {
            withTimeoutOrNull(CLOSE_FLUSH_MS) {
                pump.join()
                session.close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
            }
            session.endConnection() // also if the client stopped reading or ignores the Close
        }
    }

    private fun abort() {
        val cleared = synchronized(lock) {
            if (aborted) return
            aborted = true
            closed = true
            outbox.clear()
        }
        cleared.forEach(stats::drop)
        stats.aborted.incrementAndGet()
        wake.close()
        pump.cancel()
        session.cancel()
    }

    companion object {
        const val CLOSE_FLUSH_MS = 5_000L
    }
}

/**
 * Makes sure the connection really ends. After the server sends Close, Ktor keeps the TCP
 * connection open until the client answers with its own Close (closeReason is already complete by
 * then, so it cannot tell), which an abusive client never does. So: discard whatever the client
 * still sends for up to [CLOSE_HANDSHAKE_MS] until its Close ends `incoming`, then cancel the
 * session, which closes the TCP connection in any case.
 */
suspend fun DefaultWebSocketServerSession.endConnection() {
    withContext(NonCancellable) {
        withTimeoutOrNull(CLOSE_HANDSHAKE_MS) {
            runCatching { for (frame in incoming) Unit }
        }
    }
    // The session's scope (not the coroutine running this): closes the TCP connection.
    cancel()
}

const val CLOSE_HANDSHAKE_MS = 3_000L
const val REFUSE_FLUSH_MS = 500L

/** Join failures that spend a join-limiter token (they are what code guessing produces). */
private val CHARGED_JOIN_FAILURES = setOf(ErrorCode.ROOM_NOT_FOUND, ErrorCode.ROOM_FULL, ErrorCode.UPGRADE_REQUIRED)

/**
 * `/ritme`: enforces the connection caps (a slot is held until the TCP connection has really
 * ended), then runs [ritmeSession].
 */
suspend fun DefaultWebSocketServerSession.ritmeConnection(
    registry: RoomRegistry,
    clock: Clock,
    guard: ConnectionGuard,
    stats: PeerStats,
) {
    val remoteHost = call.request.origin.remoteHost
    if (!guard.tryOpen(remoteHost)) {
        // Not counted against any cap, so it must not linger: a short flush, then hang up.
        withTimeoutOrNull(REFUSE_FLUSH_MS) {
            outgoing.send(Frame.Text(ServerMessage.Error(code = ErrorCode.RATE_LIMITED).encode()))
            outgoing.send(Frame.Close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "busy")))
            flush()
        }
        cancel()
        return
    }
    try {
        ritmeSession(registry, clock, guard, remoteHost, stats)
    } finally {
        try {
            endConnection()
        } finally {
            guard.release(remoteHost)
        }
    }
}

/** Refuses a Create/Join: tells the client why, then closes (the slot is held until it is gone). */
suspend fun DefaultWebSocketServerSession.refuse(code: ErrorCode, reason: CloseReason.Codes = CloseReason.Codes.NORMAL) {
    // Not under any room lock: send directly so it is flushed before we close.
    runCatching { outgoing.send(Frame.Text(ServerMessage.Error(code = code).encode())) }
    close(CloseReason(reason, "rejected"))
}

/**
 * Abuse (flooding, idling before join): no answer and no close handshake; the TCP connection is
 * dropped right after the Close frame, so a flood is not read any further.
 */
private fun DefaultWebSocketServerSession.closeQuietly() {
    // trySend: never wait on a full outgoing channel (the client may not be reading at all).
    outgoing.trySend(Frame.Close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "")))
    cancel()
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
    stats: PeerStats = PeerStats(),
) {
    val limits = guard.limits
    val peer = WebSocketPeer(this, stats, limits.sendStallMs)
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
            // Only decoding failures (SerializationException is an IllegalArgumentException); an
            // Error such as OutOfMemoryError must not be reported to the client as INVALID.
            val message = try {
                decodeClientMessage(frame.readText())
            } catch (_: IllegalArgumentException) {
                null
            }
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
                val result = when {
                    message !is ClientMessage.Create && message !is ClientMessage.Join -> JoinResult.Rejected(ErrorCode.INVALID)
                    // A Welcome can be up to a full snapshot: no new members while over budget.
                    stats.overBudget -> JoinResult.Rejected(ErrorCode.RATE_LIMITED)
                    message is ClientMessage.Create ->
                        if (guard.tryCreate(remoteHost)) registry.create(message, peer) else JoinResult.Rejected(ErrorCode.RATE_LIMITED)
                    !guard.mayJoin(remoteHost) -> JoinResult.Rejected(ErrorCode.RATE_LIMITED)
                    else -> registry.join(message as ClientMessage.Join, peer).also {
                        if (it is JoinResult.Rejected && it.code in CHARGED_JOIN_FAILURES) guard.chargeFailedJoin(remoteHost)
                    }
                }
                when (result) {
                    is JoinResult.Joined -> session = result
                    is JoinResult.Rejected -> return refuse(result.code)
                }
                continue
            }
            when (message) {
                is ClientMessage.Send -> joined.room.command(joined.memberId, peer, message.reqId, message.cmd)
                is ClientMessage.TrackEnded -> joined.room.trackEnded(joined.memberId, peer, message.entryId)
                ClientMessage.Leave -> {
                    session = null
                    joined.room.leave(joined.memberId, peer)
                    close(CloseReason(CloseReason.Codes.NORMAL, "left"))
                    return
                }
                is ClientMessage.Ack -> peer.ack(message.version)
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
