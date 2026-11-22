package com.example.musicfy.ritme.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Abuse limits for `/ritme`; injectable so tests can use small values. */
data class SocketLimits(
    val joinDeadlineMs: Long = Limits.JOIN_DEADLINE_MS,
    val joinsPerMinute: Int = Limits.JOINS_PER_MINUTE,
    val joinBurst: Int = Limits.JOIN_BURST,
    val maxConnectionsPerIp: Int = Limits.MAX_CONNECTIONS_PER_IP,
    val maxConnections: Int = Limits.MAX_CONNECTIONS,
    val maxRooms: Int = Limits.MAX_ROOMS,
    val framesPerSecond: Int = Limits.FRAMES_PER_SECOND,
    val frameBurst: Int = Limits.FRAME_BURST,
    val sendStallMs: Long = Limits.SEND_STALL_MS,
)

/**
 * Concurrent-connection caps (per IP key and global) and the per-IP join limiter, which is charged
 * for every Create and for failed Joins only, so people sharing an address (home, campus, carrier
 * NAT) can join and resume freely while code guessing is still limited.
 */
class ConnectionGuard(val limits: SocketLimits, clock: Clock) {
    private val perIp = ConcurrentHashMap<String, Int>()
    private val total = AtomicInteger()
    private val joins = IpRateLimiter(clock, perMinute = limits.joinsPerMinute, burst = limits.joinBurst)

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

    /** Spends one token for a Create. */
    fun tryCreate(remoteHost: String): Boolean = joins.tryAcquire(remoteHost)

    /** A Join may be tried while the bucket is not empty; [chargeFailedJoin] spends a token if it fails. */
    fun mayJoin(remoteHost: String): Boolean = joins.hasToken(remoteHost)

    fun chargeFailedJoin(remoteHost: String) {
        joins.tryAcquire(remoteHost)
    }
}
