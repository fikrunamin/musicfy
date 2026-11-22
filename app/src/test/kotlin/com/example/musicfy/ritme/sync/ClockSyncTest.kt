package com.example.musicfy.ritme.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClockSyncTest {

    @Test
    fun theSeedIsUsedUntilTheFirstPong() {
        val clock = ClockSync()
        assertNull(clock.offset)
        clock.seed(serverTime = 10_000, receivedAt = 400)
        assertEquals(9_600L, clock.offset)
        clock.addSample(t0 = 0, t1 = 100, serverTime = 1_050)
        assertEquals(1_000L, clock.offset)
    }

    @Test
    fun theOffsetComesFromTheSmallestRoundTrip() {
        val clock = ClockSync()
        clock.addSample(t0 = 0, t1 = 100, serverTime = 1_050) // rtt 100, offset 1000
        clock.addSample(t0 = 200, t1 = 220, serverTime = 1_215) // rtt 20, offset 1005
        clock.addSample(t0 = 300, t1 = 500, serverTime = 1_400) // rtt 200, offset 1000
        assertEquals(1_005L, clock.offset)
        assertEquals(20L, clock.rtt)
    }

    @Test
    fun onlyTheLastEightSamplesCount() {
        val clock = ClockSync()
        clock.addSample(t0 = 0, t1 = 1, serverTime = 7) // excellent but old: offset 7
        repeat(8) { i ->
            val t0 = 1_000L * (i + 1)
            clock.addSample(t0 = t0, t1 = t0 + 50, serverTime = t0 + 25 + 1_000)
        }
        assertEquals(1_000L, clock.offset)
        assertEquals(50L, clock.rtt)
    }

    @Test
    fun negativeRoundTripsAreIgnored() {
        val clock = ClockSync()
        clock.addSample(t0 = 500, t1 = 400, serverTime = 9_999)
        assertNull(clock.offset)
    }

    @Test
    fun serverNowAddsTheOffset() {
        val clock = ClockSync()
        assertEquals(1_234L, clock.serverNow(1_234))
        clock.addSample(t0 = 0, t1 = 100, serverTime = 1_050)
        assertEquals(3_000L, clock.serverNow(2_000))
        clock.reset()
        assertNull(clock.offset)
    }
}
