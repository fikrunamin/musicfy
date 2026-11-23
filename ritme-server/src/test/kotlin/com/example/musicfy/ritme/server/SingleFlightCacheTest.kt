package com.example.musicfy.ritme.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
