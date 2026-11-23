package com.example.musicfy.ritme.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightCacheTest {
    @Test
    fun eachValueKeepsItsOwnTtl() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 10) {
            if (it == "short") 1_000 else 10_000
        }
        var loads = 0
        suspend fun get(key: String) = cache.get(key) { loads++; key }
        get("short")
        get("long")
        clock.now += 999
        get("short")
        get("long")
        assertEquals(2, loads)
        clock.now += 1
        get("short")
        get("long")
        assertEquals(3, loads)
        clock.now += 9_000
        get("long")
        assertEquals(4, loads)
    }

    @Test
    fun nullIsCachedLikeAnyValue() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String?>(clock, UpstreamBudget(clock = clock), maxEntries = 10) { 1_000 }
        var loads = 0
        assertNull(cache.get("x") { loads++; null })
        assertNull(cache.get("x") { loads++; null })
        assertEquals(1, loads)
    }

    @Test
    fun theLeastRecentlyUsedEntryGoesFirst() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 2) { 60_000 }
        var loads = 0
        suspend fun get(key: String) = cache.get(key) { loads++; key }
        get("a")
        get("b")
        get("a")
        get("c") // over capacity: evicts b, the least recently used
        assertEquals(2, cache.size)
        get("a")
        assertEquals(3, loads)
        get("b")
        assertEquals(4, loads)
    }

    @Test
    fun cachesSharingABudgetRunOutTogether() = runBlocking {
        val clock = FakeClock()
        val budget = UpstreamBudget(perMinute = 2, clock = clock)
        val first = SingleFlightCache<String, String>(clock, budget, maxEntries = 10) { 60_000 }
        val second = SingleFlightCache<String, String>(clock, budget, maxEntries = 10) { 60_000 }
        first.get("a") { "a" }
        second.get("b") { "b" }
        assertThrows(UpstreamOverloadedException::class.java) { runBlocking { first.get("c") { "c" } } }
        assertThrows(UpstreamOverloadedException::class.java) { runBlocking { second.get("d") { "d" } } }
        assertEquals("a", first.get("a") { error("must be cached") })
        clock.now += 30_000 // 2 per minute: one token back
        assertEquals("d", second.get("d") { "d" })
    }

    @Test
    fun concurrentMissesOfOneKeyShareOneLoad() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 10) { 60_000 }
        val gate = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val first = async { cache.get("k") { loads.incrementAndGet(); gate.await(); "v" } }
        val second = async { cache.get("k") { loads.incrementAndGet(); gate.await(); "v" } }
        yield()
        gate.complete(Unit)
        assertEquals("v", first.await())
        assertEquals("v", second.await())
        assertEquals(1, loads.get())
    }

    @Test
    fun aCancelledFirstCallerDoesNotFailTheOtherWaiters() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 10) { 60_000 }
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = async { cache.get("k") { started.complete(Unit); gate.await(); "v" } }
        started.await()
        val second = async { cache.get("k") { error("must share the first load") } }
        yield() // second is now waiting on the shared load
        first.cancelAndJoin() // e.g. the client that started the load disconnected
        gate.complete(Unit)
        assertEquals("v", second.await())
        assertEquals("v", cache.get("k") { error("must be cached") })
    }

    @Test
    fun aFailedLoadFailsItsWaitersAndIsNotCached() = runBlocking {
        val clock = FakeClock()
        val cache = SingleFlightCache<String, String>(clock, UpstreamBudget(clock = clock), maxEntries = 10) { 60_000 }
        val gate = CompletableDeferred<Unit>()
        val first = async { runCatching { cache.get("k") { gate.await(); error("down") } } }
        val second = async { runCatching { cache.get("k") { error("must share the first load") } } }
        yield()
        gate.complete(Unit)
        assertTrue(first.await().isFailure)
        assertTrue(second.await().isFailure)
        assertEquals(0, cache.size)
        assertEquals("v", cache.get("k") { "v" })
    }
}
