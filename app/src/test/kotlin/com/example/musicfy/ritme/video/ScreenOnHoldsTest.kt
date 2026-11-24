package com.example.musicfy.ritme.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenOnHoldsTest {

    private val view = Any()

    @Test
    fun theOnlyHoldTurnsItOnAndOff() {
        val holds = ScreenOnHolds()
        holds.acquire(view)
        assertTrue(holds.isHeld(view))
        assertTrue(holds.release(view))
        assertFalse(holds.isHeld(view))
    }

    @Test
    fun aHandoffThatStartsTheNewStageFirstKeepsTheScreenOn() {
        val holds = ScreenOnHolds()
        holds.acquire(view) // the cover's stage
        holds.acquire(view) // full screen's stage, before the cover's is disposed
        assertFalse(holds.release(view))
        assertTrue(holds.isHeld(view))
        assertTrue(holds.release(view))
    }

    @Test
    fun anExtraReleaseIsHarmless() {
        val holds = ScreenOnHolds()
        assertTrue(holds.release(view))
        holds.acquire(view)
        assertTrue(holds.isHeld(view))
    }

    @Test
    fun viewsAreCountedApart() {
        val holds = ScreenOnHolds()
        val other = Any()
        holds.acquire(view)
        holds.acquire(other)
        assertTrue(holds.release(view))
        assertTrue(holds.isHeld(other))
    }
}
