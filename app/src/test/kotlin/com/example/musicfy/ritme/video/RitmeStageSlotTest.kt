package com.example.musicfy.ritme.video

import org.junit.Assert.assertEquals
import org.junit.Test

class RitmeStageSlotTest {

    @Test
    fun noTargetMeansNoStage() {
        for (open in listOf(false, true)) for (cover in listOf(false, true)) {
            assertEquals(RitmeStageSlot.NONE, RitmeStageSlot.of(hasTarget = false, fullscreenOpen = open, coverShown = cover))
        }
    }

    @Test
    fun theCoverHoldsTheStageWhileFullScreenIsClosed() {
        assertEquals(RitmeStageSlot.COVER, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = false, coverShown = true))
        assertEquals(RitmeStageSlot.NONE, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = false, coverShown = false))
    }

    @Test
    fun fullScreenTakesTheStageFromTheCover() {
        // one stage at a time: one follower, one texture, one screen-on hold
        assertEquals(RitmeStageSlot.FULLSCREEN, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = true, coverShown = true))
        assertEquals(RitmeStageSlot.FULLSCREEN, RitmeStageSlot.of(hasTarget = true, fullscreenOpen = true, coverShown = false))
    }
}
