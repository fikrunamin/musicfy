package com.example.musicfy.ritme.server

/** Token bucket. Not thread-safe: callers serialize access (room mutex, or synchronized). */
class RateLimiter(
    private val capacity: Int,
    private val refillPerSecond: Double,
    private val clock: Clock,
) {
    private var tokens = capacity.toDouble()
    private var last = clock.now()

    fun tryAcquire(): Boolean {
        if (!hasToken()) return false
        tokens -= 1.0
        return true
    }

    /** True when [tryAcquire] would succeed; spends nothing. */
    fun hasToken(): Boolean {
        val now = clock.now()
        tokens = minOf(capacity.toDouble(), tokens + (now - last) * refillPerSecond / 1_000.0)
        last = now
        return tokens >= 1.0
    }
}
