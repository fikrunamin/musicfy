package com.example.musicfy.ritme.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** The global upstream budget is spent: the endpoint answers 503. */
class UpstreamOverloadedException : RuntimeException("upstream budget exhausted")

/** The name search used before the budget was shared; kept so its callers and tests read the same. */
typealias SearchOverloadedException = UpstreamOverloadedException

/**
 * Uncached calls to YouTube's InnerTube from all endpoints together (search and video info):
 * at most [perMinute], refilled evenly. Thread-safe.
 */
class UpstreamBudget(perMinute: Int = Limits.SEARCH_GLOBAL_PER_MINUTE, clock: Clock) {
    private val limiter = RateLimiter(perMinute, perMinute / 60.0, clock)

    fun tryAcquire(): Boolean = synchronized(this) { limiter.tryAcquire() }
}

/**
 * LRU cache with single flight: concurrent misses for one key share one load, and every load
 * first spends a token of [budget] (none left: [UpstreamOverloadedException]); hits cost nothing.
 * A value lives [ttlMs] of itself, so callers can keep some answers shorter. A failed load is not
 * cached: its waiters fail too (never with the loader's own cancellation) and the next call tries
 * again. Null is a value like any other.
 */
class SingleFlightCache<K : Any, V>(
    private val clock: Clock,
    private val budget: UpstreamBudget,
    private val maxEntries: Int,
    private val ttlMs: (V) -> Long,
) {
    private class Cached<V>(val expiresAt: Long, val value: V)

    private val lock = Any()
    private val cache = object : LinkedHashMap<K, Cached<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Cached<V>>): Boolean = size > maxEntries
    }
    private val inFlight = HashMap<K, CompletableDeferred<V>>()

    val size: Int get() = synchronized(lock) { cache.size }

    suspend fun get(key: K, load: suspend () -> V): V {
        val call = CompletableDeferred<V>()
        val shared = synchronized(lock) {
            cache[key]?.takeIf { clock.now() < it.expiresAt }?.let { return it.value }
            inFlight[key] ?: run {
                if (!budget.tryAcquire()) throw UpstreamOverloadedException()
                inFlight[key] = call
                null
            }
        }
        if (shared != null) return shared.await()
        try {
            val value = load()
            synchronized(lock) {
                cache[key] = Cached(clock.now() + ttlMs(value), value)
                inFlight.remove(key)
            }
            call.complete(value)
            return value
        } catch (e: Throwable) {
            synchronized(lock) { inFlight.remove(key) }
            // Waiters get a plain failure (502), never this caller's cancellation.
            call.completeExceptionally(if (e is CancellationException) IllegalStateException("upstream call cancelled") else e)
            throw e
        }
    }
}
