package com.example.musicfy.ritme.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class DriftCorrectorTest {

    private fun evaluate(actual: Long, speed: Float = 1f, now: Long = 10_000, lastSeek: Long? = null, force: Boolean = false) =
        DriftCorrector.evaluate(expectedMs = 10_000, actualMs = actual, currentSpeed = speed, nowMs = now, lastSeekAtMs = lastSeek, force = force)

    @Test
    fun smallDriftIsIgnored() {
        assertEquals(DriftAction.Ignore, evaluate(10_030))
        assertEquals(DriftAction.Ignore, evaluate(9_961))
    }

    @Test
    fun beingAheadSlowsDownAndBeingBehindSpeedsUp() {
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(10_200))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_800))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(11_000)) // exactly 1000: still speed
    }

    @Test
    fun aCorrectionRunsUntilTheDriftIsUnderTwentyMs() {
        assertEquals(DriftAction.Ignore, evaluate(10_030, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_015, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(9_990, speed = DriftCorrector.FASTER))
    }

    @Test
    fun anOvershootFlipsTheCorrection() {
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_950, speed = DriftCorrector.SLOWER))
    }

    @Test
    fun bigDriftSeeksToTheExpectedPosition() {
        assertEquals(DriftAction.Seek(10_000), evaluate(11_500))
        assertEquals(DriftAction.Seek(10_000), evaluate(8_000))
    }

    @Test
    fun seeksAreRateLimitedUnlessForced() {
        assertEquals(DriftAction.Ignore, evaluate(11_500, now = 10_000, lastSeek = 8_000))
        assertEquals(DriftAction.Seek(10_000), evaluate(11_500, now = 11_000, lastSeek = 8_000))
        assertEquals(DriftAction.Seek(10_000), evaluate(11_500, now = 10_000, lastSeek = 9_900, force = true))
    }
}
