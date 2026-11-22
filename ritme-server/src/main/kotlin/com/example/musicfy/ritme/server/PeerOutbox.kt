package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ServerMessage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one client has not been sent yet.
 *
 * Snapshots (Welcome, State) are flow-controlled: after one is taken for sending, the next waits
 * until the client acknowledges it ([ack] with exactly that version), and meanwhile a new State
 * replaces the pending one (latest wins, placed after everything queued so far). So a client has
 * at most one unacknowledged snapshot in flight and one pending. Other messages (Error, Pong,
 * Kicked, RoomClosed) are small and never wait. Bytes are bounded too: once [overBudget] (more
 * than [maxBytes] or [maxMessages] waiting) the caller drops the client.
 * Not thread-safe: the caller synchronizes.
 */
class PeerOutbox(
    private val maxBytes: Long = Limits.MAX_PENDING_BYTES_PER_PEER,
    private val maxMessages: Int = Limits.MAX_PENDING_MESSAGES_PER_PEER,
) {
    private val queue = ArrayDeque<Outgoing>()

    var pendingBytes: Long = 0
        private set

    val size: Int get() = queue.size

    /** A snapshot was sent and its Ack has not arrived yet. */
    var awaitingAck: Boolean = false
        private set

    /** Version of the last snapshot taken for sending. */
    var lastSentVersion: Long = -1
        private set

    val overBudget: Boolean get() = pendingBytes > maxBytes || queue.size > maxMessages

    /** True when [take] would return something. */
    val hasSendable: Boolean get() = queue.any { !awaitingAck || it.snapshotVersion == null }

    /** Queues [message]; returns the pending snapshot it replaced, if any. */
    fun add(message: Outgoing): Outgoing? {
        var replaced: Outgoing? = null
        if (message.message is ServerMessage.State) {
            val stale = queue.indexOfFirst { it.message is ServerMessage.State }
            if (stale >= 0) replaced = queue.removeAt(stale).also { pendingBytes -= it.size }
        }
        queue.addLast(message)
        pendingBytes += message.size
        return replaced
    }

    /** The next message that may be sent now (snapshots only when nothing is awaiting an Ack). */
    fun take(): Outgoing? {
        val index = queue.indexOfFirst { !awaitingAck || it.snapshotVersion == null }
        if (index < 0) return null
        val message = queue.removeAt(index)
        pendingBytes -= message.size
        message.snapshotVersion?.let {
            awaitingAck = true
            lastSentVersion = it
        }
        return message
    }

    /**
     * The client applied snapshot [version]. Only the version actually sent counts, so a client
     * cannot acknowledge blindly (e.g. Long.MAX_VALUE) without reading. Returns true if it was the
     * awaited Ack.
     */
    fun ack(version: Long): Boolean {
        if (!awaitingAck || version != lastSentVersion) return false
        awaitingAck = false
        return true
    }

    /** Empties the outbox; returns what it held. */
    fun clear(): List<Outgoing> = queue.toList().also {
        queue.clear()
        pendingBytes = 0
    }
}

/** Version of a snapshot message (Welcome, State); null for everything else. */
val Outgoing.snapshotVersion: Long?
    get() = when (val m = message) {
        is ServerMessage.State -> m.state.version
        is ServerMessage.Welcome -> m.state.version
        else -> null
    }

/**
 * Server-wide outbound buffering. Above [budget], clients that are behind are dropped and no new
 * members are let in until the backlog drains (stalled clients are dropped within SEND_STALL_MS).
 */
class PeerStats(val budget: Long = Limits.MAX_PENDING_BYTES_TOTAL) {
    /**
     * Bytes of every message still waiting in some client's outbox or being written; a broadcast
     * held by many clients is counted once (see [hold]/[drop]).
     */
    val pendingBytes = AtomicLong()
    val peakPendingBytes = AtomicLong()
    val aborted = AtomicInteger()

    val overBudget: Boolean get() = pendingBytes.get() > budget

    /** A client starts holding [message]. */
    fun hold(message: Outgoing) {
        if (message.retain()) add(message.size.toLong())
    }

    /** A client no longer holds [message] (written, replaced or dropped). */
    fun drop(message: Outgoing) {
        if (message.release()) add(-message.size.toLong())
    }

    fun add(delta: Long) {
        val now = pendingBytes.addAndGet(delta)
        peakPendingBytes.accumulateAndGet(now, ::maxOf)
    }
}
