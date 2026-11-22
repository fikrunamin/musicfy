package com.example.musicfy.ritme.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Abuse limits for `/ritme`; injectable so tests can use small values. */
data class SocketLimits(
    val joinDeadlineMs: Long = Limits.JOIN_DEADLINE_MS,
    val joinsPerMinute: Int = Limits.JOINS_PER_MINUTE,
    val maxConnectionsPerIp: Int = Limits.MAX_CONNECTIONS_PER_IP,
    val maxConnections: Int = Limits.MAX_CONNECTIONS,
    val maxRooms: Int = Limits.MAX_ROOMS,
    val framesPerSecond: Int = Limits.FRAMES_PER_SECOND,
    val frameBurst: Int = Limits.FRAME_BURST,
)

/** Concurrent-connection caps (per IP key and global) and the per-IP Create/Join attempt limiter. */
class ConnectionGuard(val limits: SocketLimits, clock: Clock) {
    private val perIp = ConcurrentHashMap<String, Int>()
    private val total = AtomicInteger()
    private val joins = IpRateLimiter(clock, limits.joinsPerMinute)

    val connections: Int get() = total.get()

    /** Reserves a slot for [remoteHost]; every true must be paired with one [release]. */
    fun tryOpen(remoteHost: String): Boolean {
        if (total.incrementAndGet() > limits.maxConnections) {
            total.decrementAndGet()
            return false
        }
        var opened = false
        perIp.compute(ipKey(remoteHost)) { _, n ->
            val count = n ?: 0
            if (count >= limits.maxConnectionsPerIp) {
                n
            } else {
                opened = true
                count + 1
            }
        }
        if (!opened) total.decrementAndGet()
        return opened
    }

    fun release(remoteHost: String) {
        perIp.computeIfPresent(ipKey(remoteHost)) { _, n -> if (n <= 1) null else n - 1 }
        total.decrementAndGet()
    }

    fun tryJoin(remoteHost: String): Boolean = joins.tryAcquire(remoteHost)
}
