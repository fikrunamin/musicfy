package com.example.musicfy.ritme.server

import com.example.musicfy.ritme.protocol.ServerMessage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What one client has not been sent yet. Snapshots coalesce: a new [ServerMessage.State] replaces
 * a pending one (latest wins, placed after everything queued so far), so a slow reader holds at
 * most one snapshot. Bytes are bounded too: [add] returns false once the pending bytes exceed
 * [maxBytes] or [maxMessages] are waiting, and the caller drops the client.
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

    val overBudget: Boolean get() = pendingBytes > maxBytes || queue.size > maxMessages

    /** Queues [message]; returns the change in pending bytes (negative when a bigger snapshot was replaced). */
    fun add(message: Outgoing): Long {
        var delta = message.size.toLong()
        if (message.message is ServerMessage.State) {
            val stale = queue.indexOfFirst { it.message is ServerMessage.State }
            if (stale >= 0) delta -= queue.removeAt(stale).size
        }
        queue.addLast(message)
        pendingBytes += delta
        return delta
    }

    fun take(): Outgoing? = queue.removeFirstOrNull()?.also { pendingBytes -= it.size }

    /** Empties the outbox; returns the bytes it held. */
    fun clear(): Long = pendingBytes.also {
        queue.clear()
        pendingBytes = 0
    }
}

/**
 * Server-wide outbound buffering. Above [budget], clients that are behind are dropped and no new
 * members are let in until the backlog drains (stalled clients are dropped within SEND_STALL_MS).
 */
class PeerStats(val budget: Long = Limits.MAX_PENDING_BYTES_TOTAL) {
    /** Bytes waiting in every client's outbox plus the frames being written (shared bytes counted per client). */
    val pendingBytes = AtomicLong()
    val peakPendingBytes = AtomicLong()
    val aborted = AtomicInteger()

    val overBudget: Boolean get() = pendingBytes.get() > budget

    fun add(delta: Long) {
        val now = pendingBytes.addAndGet(delta)
        peakPendingBytes.accumulateAndGet(now, ::maxOf)
    }
}
