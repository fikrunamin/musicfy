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

    @Test
    fun anOpposingDriftUnderFortyMsStopsTheCorrectionInsteadOfFlipping() {
        // slowing down, now 20..40 ms behind: back to 1.0, no new correction below the start threshold
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(9_970, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(9_961, speed = DriftCorrector.SLOWER))
        // speeding up, now 20..40 ms ahead: same
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_030, speed = DriftCorrector.FASTER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_039, speed = DriftCorrector.FASTER))
        // the same drift on the correction's own side keeps it running
        assertEquals(DriftAction.Ignore, evaluate(9_970, speed = DriftCorrector.FASTER))
        assertEquals(DriftAction.Ignore, evaluate(10_030, speed = DriftCorrector.SLOWER))
        // an opposing drift of 40 ms or more is a new correction
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_960, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(10_040, speed = DriftCorrector.FASTER))
    }

    @Test
    fun boundaries() {
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(10_040)) // exactly 40 starts
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_960))
        assertEquals(DriftAction.Ignore, evaluate(10_039))
        assertEquals(DriftAction.Ignore, evaluate(10_020, speed = DriftCorrector.SLOWER)) // exactly 20 continues
        assertEquals(DriftAction.Ignore, evaluate(9_980, speed = DriftCorrector.FASTER))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_019, speed = DriftCorrector.SLOWER))
        assertEquals(DriftAction.Seek(10_000), evaluate(11_001)) // 1001 seeks
        assertEquals(DriftAction.Seek(10_000), evaluate(8_999))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_000))
        // force lifts the seek rate limit, it never seeks a drift of 1000 ms or less
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(11_000, force = true))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_000, lastSeek = 9_900, force = true))
        assertEquals(DriftAction.Ignore, evaluate(10_030, force = true))
    }

    @Test
    fun aSpeedThatIsNotACorrectionNeverCountsAsOne() {
        // e.g. the user's own playback speed left on the player: under 40 ms it goes back to 1.0
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_030, speed = 1.25f))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.NORMAL), evaluate(10_000, speed = 0.8f))
        // and from 40 ms it is replaced by a real correction
        assertEquals(DriftAction.SetSpeed(DriftCorrector.SLOWER), evaluate(10_200, speed = 1.25f))
        assertEquals(DriftAction.SetSpeed(DriftCorrector.FASTER), evaluate(9_800, speed = 1.25f))
    }
}
