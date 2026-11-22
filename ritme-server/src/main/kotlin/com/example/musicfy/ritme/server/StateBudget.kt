package com.example.musicfy.ritme.server

import java.util.concurrent.atomic.AtomicLong

/**
 * Server-wide bytes of room snapshots at rest: every live room contributes the size of its last
 * encoded snapshot. Add (QUEUE_FULL) and Create (trimmed, or ROOM_FULL) stop at [max].
 */
class StateBudget(val max: Long = Limits.MAX_ROOM_STATE_BYTES_TOTAL) {
    private val used = AtomicLong()

    val usedBytes: Long get() = used.get()

    fun remaining(): Long = max - used.get()

    fun add(delta: Long) {
        used.addAndGet(delta)
    }
}
